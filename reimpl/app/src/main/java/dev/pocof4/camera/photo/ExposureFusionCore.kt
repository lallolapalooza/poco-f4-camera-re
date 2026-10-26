package dev.pocof4.camera.photo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mertens exposure fusion on packed ARGB planes.
 *
 * Per-pixel weights combine (Mertens et al., "Exposure Fusion", 2007):
 *  - contrast: |Laplacian(luma)|,
 *  - saturation: standard deviation of R,G,B,
 *  - well-exposedness: product of per-channel Gaussians centred on 0.5 (sigma 0.2).
 *
 * The weights and the images are blended in a Laplacian pyramid (default 3
 * levels, configurable 1..4) with per-level weight renormalisation, then the
 * collapsed result passes through a soft-shoulder tone curve so highlights roll
 * off instead of clipping at 255.
 *
 * The core is deliberately bounded at 5 input frames and works in place on
 * caller-owned IntArrays; only per-level plane buffers are allocated.
 */
object ExposureFusionCore {

    private const val MIN_FRAMES = 2
    private const val MAX_FRAMES = 5
    private const val MAX_LEVELS = 4
    private const val SIGMA = 0.2f
    private const val TWO_SIGMA_SQ = 2f * SIGMA * SIGMA
    private const val WEIGHT_EPS = 1e-4f

    fun fuse(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        levels: Int = 3
    ): IntArray {
        require(frames.size in MIN_FRAMES..MAX_FRAMES) {
            "exposure fusion needs $MIN_FRAMES..$MAX_FRAMES frames"
        }
        require(width > 0 && height > 0) { "invalid image size" }
        val pixels = width * height
        frames.forEach { require(it.size >= pixels) { "frame too small" } }

        val frameCount = frames.size
        val levelCount = computeLevelCount(width, height, levels)
        val levelWidths = IntArray(levelCount + 1)
        val levelHeights = IntArray(levelCount + 1)
        levelWidths[0] = width
        levelHeights[0] = height
        for (k in 1..levelCount) {
            levelWidths[k] = max(1, levelWidths[k - 1] / 2)
            levelHeights[k] = max(1, levelHeights[k - 1] / 2)
        }

        // 1. Raw weights for every frame, then cross-frame normalisation.
        val scratchLuma = FloatArray(pixels)
        val weightPyramids = Array(frameCount) { Array(levelCount + 1) { FloatArray(0) } }
        val rawWeights = Array(frameCount) { FloatArray(pixels) }
        for (f in 0 until frameCount) {
            fillLuma(frames[f], scratchLuma, pixels)
            fillWeights(frames[f], scratchLuma, rawWeights[f], width, height)
        }
        for (i in 0 until pixels) {
            var sum = 0f
            for (f in 0 until frameCount) sum += rawWeights[f][i]
            if (sum > WEIGHT_EPS) {
                val inv = 1f / sum
                for (f in 0 until frameCount) rawWeights[f][i] *= inv
            } else {
                val uniform = 1f / frameCount
                for (f in 0 until frameCount) rawWeights[f][i] = uniform
            }
        }
        for (f in 0 until frameCount) {
            weightPyramids[f][0] = rawWeights[f]
            for (k in 1..levelCount) {
                weightPyramids[f][k] = ImageMath.downsampleFloat(
                    weightPyramids[f][k - 1], levelWidths[k - 1], levelHeights[k - 1]
                )
            }
        }
        // Weight normalisation is repeated per level because Gaussian blurring
        // and subsampling do not preserve a sum of one.
        for (k in 0..levelCount) {
            val size = levelWidths[k] * levelHeights[k]
            for (i in 0 until size) {
                var sum = 0f
                for (f in 0 until frameCount) sum += weightPyramids[f][k][i]
                if (sum > WEIGHT_EPS) {
                    val inv = 1f / sum
                    for (f in 0 until frameCount) weightPyramids[f][k][i] *= inv
                } else {
                    val uniform = 1f / frameCount
                    for (f in 0 until frameCount) weightPyramids[f][k][i] = uniform
                }
            }
        }

        // 2. Blend each colour channel separately to keep peak memory bounded.
        val out = IntArray(pixels) { 0xFF shl 24 }
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            val bitMask = 0xFF shl shift
            val gaussians = Array(frameCount) { Array(levelCount + 1) { FloatArray(0) } }
            for (f in 0 until frameCount) {
                gaussians[f][0] = ImageMath.channelPlane(frames[f], width, height, shift)
                for (k in 1..levelCount) {
                    gaussians[f][k] = ImageMath.downsampleFloat(
                        gaussians[f][k - 1], levelWidths[k - 1], levelHeights[k - 1]
                    )
                }
            }

            val fusedLaps = Array(levelCount + 1) {
                FloatArray(levelWidths[it] * levelHeights[it])
            }
            val top = levelCount
            val topSize = levelWidths[top] * levelHeights[top]
            for (f in 0 until frameCount) {
                val image = gaussians[f][top]
                val weight = weightPyramids[f][top]
                val target = fusedLaps[top]
                for (i in 0 until topSize) target[i] += image[i] * weight[i]
            }
            for (k in top - 1 downTo 0) {
                val cw = levelWidths[k]
                val ch = levelHeights[k]
                val pw = levelWidths[k + 1]
                val ph = levelHeights[k + 1]
                val size = cw * ch
                val target = fusedLaps[k]
                for (f in 0 until frameCount) {
                    val image = gaussians[f][k]
                    val up = ImageMath.upsampleFloat(gaussians[f][k + 1], pw, ph, cw, ch)
                    val weight = weightPyramids[f][k]
                    for (i in 0 until size) {
                        target[i] += (image[i] - up[i]) * weight[i]
                    }
                }
            }

            var fused = fusedLaps[top]
            for (k in top - 1 downTo 0) {
                val cw = levelWidths[k]
                val ch = levelHeights[k]
                val up = ImageMath.upsampleFloat(
                    fused, levelWidths[k + 1], levelHeights[k + 1], cw, ch
                )
                val lap = fusedLaps[k]
                for (i in 0 until cw * ch) up[i] += lap[i]
                fused = up
            }

            for (i in 0 until pixels) {
                val value = (toneCurve(fused[i]) * 255f + 0.5f).roundToInt().coerceIn(0, 255)
                out[i] = (out[i] and bitMask.inv()) or (value shl shift)
            }
        }
        return out
    }

    /**
     * Soft-shoulder tone curve. Linear up to 0.9, then exponential compression
     * towards 1.0. Monotonic and continuous; it never hard-clips a highlight
     * that the pyramid blend allowed above 1.0.
     */
    fun toneCurve(value: Float): Float {
        if (value <= 0f) return 0f
        val x = if (value > 4f) 4f else value
        val mapped = if (x <= 0.9f) x else 0.9f + 0.1f * (1f - exp(-(x - 0.9f) * 8f))
        return if (mapped > 1f) 1f else mapped
    }

    private fun computeLevelCount(width: Int, height: Int, requested: Int): Int {
        var level = 0
        var w = width
        var h = height
        val limit = requested.coerceIn(1, MAX_LEVELS)
        while (level < limit && w >= 4 && h >= 4) {
            w /= 2
            h /= 2
            level++
        }
        return if (level == 0) 1 else level
    }

    private fun fillLuma(frame: IntArray, out: FloatArray, pixels: Int) {
        val inv = 1f / 255f
        for (i in 0 until pixels) out[i] = ImageMath.luma(frame[i]) * inv
    }

    private fun fillWeights(
        frame: IntArray,
        luma: FloatArray,
        out: FloatArray,
        width: Int,
        height: Int
    ) {
        for (y in 0 until height) {
            val up = if (y > 0) y - 1 else 0
            val down = if (y < height - 1) y + 1 else height - 1
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val left = if (x > 0) i - 1 else i
                val right = if (x < width - 1) i + 1 else i
                val laplacian = abs(
                    4f * luma[i] - luma[left] - luma[right] -
                        luma[up * width + x] - luma[down * width + x]
                )
                val contrast = min(1f, laplacian * 3f)

                val argb = frame[i]
                val r = ((argb ushr 16) and 0xFF) / 255f
                val g = ((argb ushr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                val mean = (r + g + b) / 3f
                val dr = r - mean
                val dg = g - mean
                val db = b - mean
                val saturation = min(1f, sqrt((dr * dr + dg * dg + db * db) / 3f) * 3f)

                val dr2 = r - 0.5f
                val dg2 = g - 0.5f
                val db2 = b - 0.5f
                val exposure = exp(-(dr2 * dr2 + dg2 * dg2 + db2 * db2) / TWO_SIGMA_SQ)

                // Weight is the product of the three Mertens terms; the small
                // offsets keep completely clipped pixels from zeroing a frame.
                out[i] = (0.01f + contrast) * (0.01f + saturation) * (WEIGHT_EPS + exposure)
            }
        }
    }
}
