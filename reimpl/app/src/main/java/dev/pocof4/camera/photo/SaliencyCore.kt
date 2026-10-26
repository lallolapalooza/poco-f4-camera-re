package dev.pocof4.camera.photo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Saliency estimation + variable-radius background blur, pure Kotlin.
 *
 * Stock MIUI uses the vendor `libmialgo_saliency_jni.so` (see
 * [com.android.camera.saliencychecker.SaliencyChecker]); a spectral-residual
 * algorithm needs a real FFT, which the Kotlin/JVM stdlib does not provide.
 * This implementation uses the classic Itti/Koch-style centre-surround
 * (multi-scale difference-of-Gaussians) plus multi-radius local contrast. That
 * is a documented approximation, not a bit-exact port.
 *
 * Blur is a bokeh-like progressive stack: each level is the previous level
 * blurred by a disc-approximating 3-pass box kernel, and the radius is chosen
 * per pixel from the saliency mask. A true circular aperture kernel with
 * highlight bloom is not reproduced.
 */
object SaliencyCore {

    private const val MAX_MAP_PIXELS = 300_000
    private const val MAX_BLUR_LEVELS = 5
    private const val MASK_LOW = 0.18f
    private const val MASK_HIGH = 0.55f

    /** Normalised (0..1) saliency map at the full requested resolution. */
    fun map(argb: IntArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0) { "invalid image size" }
        val pixels = width * height
        require(argb.size >= pixels) { "pixels too small" }
        if (pixels <= MAX_MAP_PIXELS) return computeMap(argb, width, height)

        val scale = sqrt(MAX_MAP_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        val workWidth = max(1, (width * scale).roundToInt())
        val workHeight = max(1, (height * scale).roundToInt())
        val small = ImageMath.resizeArgb(argb, width, height, workWidth, workHeight)
        val smallMap = computeMap(small, workWidth, workHeight)
        return resampleMap(smallMap, workWidth, workHeight, width, height)
    }

    /**
     * Subject mask: normalised saliency, contrast-curved so only the dominant
     * subject survives, then feathered to avoid a hard cut-out edge.
     */
    fun subjectMask(argb: IntArray, width: Int, height: Int): FloatArray {
        val saliency = map(argb, width, height)
        for (i in saliency.indices) {
            saliency[i] = smoothstep(MASK_LOW, MASK_HIGH, saliency[i])
        }
        val feather = max(1, min(width, height) / 50)
        val mask = saliency.copyOf()
        val scratch = FloatArray(mask.size)
        ImageMath.blurFloatInPlace(mask, scratch, width, height, feather)
        return mask
    }

    /**
     * Progressive bokeh-like blur whose radius is driven by [mask].
     * Radius is 0 where mask = 1 (subject stays untouched) and grows towards
     * `strength * 5 %` of the short edge where mask = 0.
     */
    fun variableBlur(
        argb: IntArray,
        width: Int,
        height: Int,
        mask: FloatArray,
        strength: Float
    ): IntArray {
        val pixels = width * height
        require(mask.size >= pixels) { "mask too small" }
        val amount = strength.coerceIn(0f, 2f)
        if (amount <= 0.01f) return argb.copyOf()

        val maxRadius = max(1, (amount * 0.05f * min(width, height)).roundToInt())
        val levelCount = MAX_BLUR_LEVELS
        val radii = IntArray(levelCount) { k ->
            (maxRadius.toFloat() * k / (levelCount - 1)).roundToInt()
        }
        if (radii[levelCount - 1] <= 0) return argb.copyOf()

        // Extract planes once; every blur level reuses them.
        val red = IntArray(pixels)
        val green = IntArray(pixels)
        val blue = IntArray(pixels)
        for (i in 0 until pixels) {
            val c = argb[i]
            red[i] = (c ushr 16) and 0xFF
            green[i] = (c ushr 8) and 0xFF
            blue[i] = c and 0xFF
        }
        val scratch = IntArray(pixels)
        val stack = Array(levelCount) { IntArray(pixels) }
        argb.copyInto(stack[0])

        var previousRadius = 0
        for (level in 1 until levelCount) {
            val targetRadius = radii[level]
            val step = targetRadius - previousRadius
            previousRadius = targetRadius
            if (step > 0) {
                ImageMath.blurPlaneInPlace(red, scratch, width, height, step)
                ImageMath.blurPlaneInPlace(green, scratch, width, height, step)
                ImageMath.blurPlaneInPlace(blue, scratch, width, height, step)
            }
            val dst = stack[level]
            for (i in 0 until pixels) {
                dst[i] = (0xFF shl 24) or (red[i] shl 16) or (green[i] shl 8) or blue[i]
            }
        }

        val out = IntArray(pixels)
        val lastIndex = levelCount - 2
        for (i in 0 until pixels) {
            val subject = mask[i].coerceIn(0f, 1f)
            val position = (1f - subject) * (levelCount - 1)
            val lower = position.toInt().coerceAtMost(lastIndex)
            val fraction = (position - lower).coerceIn(0f, 1f)
            out[i] = ImageMath.lerpArgb(stack[lower][i], stack[lower + 1][i], fraction)
        }
        return out
    }

    /**
     * Full pipeline at the given resolution: saliency mask, variable blur,
     * then a re-composite so pixels classified as subject are the original
     * pixels rather than the level-0 blur stack entry.
     */
    fun render(argb: IntArray, width: Int, height: Int, strength: Float): IntArray {
        if (strength <= 0.01f) return argb.copyOf()
        val mask = subjectMask(argb, width, height)
        val blurred = variableBlur(argb, width, height, mask, strength)
        val out = IntArray(width * height)
        for (i in out.indices) {
            out[i] = ImageMath.lerpArgb(blurred[i], argb[i], mask[i])
        }
        return out
    }

    /** Bilinear resampling for masks computed at a reduced working size. */
    fun resampleMap(
        map: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): FloatArray = ImageMath.upsampleFloat(map, srcWidth, srcHeight, dstWidth, dstHeight)

    private fun computeMap(argb: IntArray, width: Int, height: Int): FloatArray {
        val pixels = width * height
        val luma = FloatArray(pixels)
        val inv = 1f / 255f
        for (i in 0 until pixels) luma[i] = ImageMath.luma(argb[i]) * inv

        // Gaussian pyramid, at most 5 levels, never smaller than 8 px.
        val pyramid = ArrayList<FloatArray>()
        val widths = ArrayList<Int>()
        val heights = ArrayList<Int>()
        pyramid.add(luma)
        widths.add(width)
        heights.add(height)
        while (pyramid.size < 5 && widths[pyramid.size - 1] >= 8 && heights[pyramid.size - 1] >= 8) {
            val last = pyramid.size - 1
            pyramid.add(ImageMath.downsampleFloat(pyramid[last], widths[last], heights[last]))
            widths.add(max(1, widths[last] / 2))
            heights.add(max(1, heights[last] / 2))
        }

        val accum = FloatArray(pixels)

        // Centre-surround: |centre - surround| for scales c in {0,1},
        // surround s in {c+2, c+3}. Each difference map is upsampled to full res.
        val centreCount = min(2, pyramid.size - 2)
        for (c in 0 until centreCount) {
            val centre = pyramid[c]
            val cw = widths[c]
            val ch = heights[c]
            for (s in (c + 2)..min(c + 3, pyramid.size - 1)) {
                val surround = ImageMath.upsampleFloat(
                    pyramid[s], widths[s], heights[s], cw, ch
                )
                val diff = FloatArray(cw * ch)
                for (i in diff.indices) diff[i] = abs(centre[i] - surround[i])
                val full = ImageMath.upsampleFloat(diff, cw, ch, width, height)
                for (i in 0 until pixels) accum[i] += full[i]
            }
        }

        // Local contrast at two radii: fills textured subject interiors, which a
        // pure edge/DoG map would leave hollow.
        val blurSmall = ImageMath.blurFloat(luma, width, height, 2)
        val blurLarge = ImageMath.blurFloat(luma, width, height, 5)
        for (i in 0 until pixels) {
            accum[i] += 2f * abs(luma[i] - blurSmall[i]) + 1.5f * abs(luma[i] - blurLarge[i])
        }

        normalizeP98(accum)

        // Pool saliency a little so the object interior is retained even when
        // only its border is textured. Background stays far below the mask
        // threshold because pooling never exceeds 55 % of the pooled value.
        val poolRadius = max(1, min(width, height) / 16)
        val pooled = ImageMath.blurFloat(accum, width, height, poolRadius)
        for (i in 0 until pixels) {
            val candidate = pooled[i] * 0.55f
            if (candidate > accum[i]) accum[i] = candidate
        }
        val smooth = ImageMath.blurFloat(accum, width, height, 1)
        normalizeP98(smooth)
        return smooth
    }

    private fun normalizeP98(values: FloatArray) {
        val sorted = values.copyOf()
        sorted.sort()
        val index = ((sorted.size - 1) * 0.98f).toInt().coerceIn(0, sorted.size - 1)
        val scale = sorted[index]
        if (scale <= 1e-6f) {
            values.fill(0f)
            return
        }
        val inv = 1f / scale
        for (i in values.indices) values[i] = (values[i] * inv).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
