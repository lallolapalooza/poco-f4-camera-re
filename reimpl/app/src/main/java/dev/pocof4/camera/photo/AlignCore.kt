package dev.pocof4.camera.photo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Integer translation of a moving frame relative to the reference frame. */
data class Shift(val dx: Int, val dy: Int)

/**
 * Hierarchical (Gaussian-pyramid) translation estimator for burst / HDR frames.
 *
 * The moving frame is sampled as `moving(x + dx, y + dy)`, so a scene feature
 * which moved right by 3 px yields `Shift(3, 0)`.
 *
 * * Coarsest pyramid level: exhaustive search of the full (clamped) radius.
 * * Every finer level: local search of +/-2 px around twice the previous estimate.
 * * Cost: mean absolute difference on luma (SAD) or on the luma gradient.
 *   Gradient cost is used for HDR brackets, where frames have different
 *   exposure; SAD is used for same-exposure night bursts.
 * * Integer output only. Subpixel refinement would need 4 bilinear samples per
 *   pixel per level for a residual well below the noise floor of hand-held
 *   bursts, and the fusion stages below tolerate 1 px error, so it is omitted.
 */
object AlignCore {

    enum class Metric { SAD, GRADIENT }

    private const val MAX_PYRAMID_LEVELS = 5
    private const val TARGET_SAMPLES = 96
    private const val REFINEMENT_RADIUS = 2
    private const val CONFIDENCE_RATIO = 0.97

    /**
     * @param reference packed ARGB_8888 pixels of the reference frame.
     * @param moving packed ARGB_8888 pixels of the frame to register.
     */
    fun estimateTranslation(
        reference: IntArray,
        moving: IntArray,
        width: Int,
        height: Int,
        maxShift: Int = 24,
        metric: Metric = Metric.GRADIENT
    ): Shift {
        require(width > 0 && height > 0) { "invalid image size" }
        val pixels = width * height
        require(reference.size >= pixels && moving.size >= pixels) { "plane too small" }
        if (width < 8 || height < 8) return Shift(0, 0)

        val limit = min(maxShift, max(1, min(width, height) / 4))
        if (limit <= 0) return Shift(0, 0)

        val refPlanes = ArrayList<IntArray>()
        val movPlanes = ArrayList<IntArray>()
        val levelWidths = ArrayList<Int>()
        val levelHeights = ArrayList<Int>()
        val levelRadii = ArrayList<Int>()

        var lumaRef = ImageMath.lumaPlane(reference, width, height)
        var lumaMov = ImageMath.lumaPlane(moving, width, height)
        var lw = width
        var lh = height
        var levelShift = limit

        while (true) {
            refPlanes.add(metricPlane(lumaRef, lw, lh, metric))
            movPlanes.add(metricPlane(lumaMov, lw, lh, metric))
            levelWidths.add(lw)
            levelHeights.add(lh)
            levelRadii.add(levelShift)
            if (levelShift < 2 || lw < 48 || lh < 48 || refPlanes.size >= MAX_PYRAMID_LEVELS) break
            lumaRef = ImageMath.downsampleLuma(lumaRef, lw, lh)
            lumaMov = ImageMath.downsampleLuma(lumaMov, lw, lh)
            lw = max(1, lw / 2)
            lh = max(1, lh / 2)
            levelShift = (levelShift + 1) / 2
        }

        val top = refPlanes.size - 1
        var shiftX = 0
        var shiftY = 0

        for (level in top downTo 0) {
            val widthAtLevel = levelWidths[level]
            val heightAtLevel = levelHeights[level]
            val w = refPlanes[level]
            val m = movPlanes[level]
            val maxAbs = levelRadii[level]
            val radius = if (level == top) maxAbs else REFINEMENT_RADIUS
            val startX = if (level == top) 0 else shiftX * 2
            val startY = if (level == top) 0 else shiftY * 2
            val step = max(1, min(widthAtLevel, heightAtLevel) / TARGET_SAMPLES)

            var bestX = startX.coerceIn(-maxAbs, maxAbs)
            var bestY = startY.coerceIn(-maxAbs, maxAbs)
            var bestScore = score(w, m, widthAtLevel, heightAtLevel, bestX, bestY, step)

            for (dy in -radius..radius) {
                val cy = startY + dy
                if (cy < -maxAbs || cy > maxAbs) continue
                for (dx in -radius..radius) {
                    val cx = startX + dx
                    if (cx < -maxAbs || cx > maxAbs) continue
                    if (cx == bestX && cy == bestY) continue
                    val candidate = score(w, m, widthAtLevel, heightAtLevel, cx, cy, step)
                    if (candidate < bestScore) {
                        bestScore = candidate
                        bestX = cx
                        bestY = cy
                    }
                }
            }
            shiftX = bestX
            shiftY = bestY
        }

        // Reject noise-only "matches": a real misalignment must improve the cost
        // by at least 3 % over the zero-shift hypothesis. Flat/noisy frames then
        // reliably return Shift(0, 0) instead of a random couple of pixels.
        if (shiftX != 0 || shiftY != 0) {
            val step = max(1, min(width, height) / TARGET_SAMPLES)
            val zeroCost = score(refPlanes[0], movPlanes[0], width, height, 0, 0, step)
            val bestCost = score(refPlanes[0], movPlanes[0], width, height, shiftX, shiftY, step)
            if (zeroCost == Long.MAX_VALUE ||
                bestCost.toDouble() > zeroCost.toDouble() * CONFIDENCE_RATIO
            ) {
                return Shift(0, 0)
            }
        }
        return Shift(shiftX, shiftY)
    }

    private fun metricPlane(luma: IntArray, width: Int, height: Int, metric: Metric): IntArray {
        return when (metric) {
            Metric.SAD -> luma
            Metric.GRADIENT -> ImageMath.gradientPlane(luma, width, height)
        }
    }

    private fun score(
        reference: IntArray,
        moving: IntArray,
        width: Int,
        height: Int,
        dx: Int,
        dy: Int,
        step: Int
    ): Long {
        var sum = 0L
        var count = 0L
        var y = 2
        while (y < height - 2) {
            val ry = y + dy
            if (ry in 0 until height) {
                val refRow = y * width
                val movRow = ry * width
                var x = 2
                while (x < width - 2) {
                    val rx = x + dx
                    if (rx in 0 until width) {
                        sum += abs(reference[refRow + x] - moving[movRow + rx]).toLong()
                        count++
                    }
                    x += step
                }
            }
            y += step
        }
        if (count == 0L) return Long.MAX_VALUE
        return (sum * 1024L) / count
    }
}
