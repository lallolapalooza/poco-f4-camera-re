package dev.pocof4.camera.ai

import kotlin.math.sqrt

/**
 * Axis-aligned ellipse approximating one ML Kit face box, in pixel coordinates.
 * The center is the box center; radii are half width and half height.
 */
data class FaceOval(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float
)

/**
 * Soft skin mask used by [AiBeauty].
 *
 * A pixel gets weight `skinScore * ellipseWeight`:
 *  - `skinScore` is 1 inside the classic YCbCr skin box and falls off over a narrow
 *    ramp at each boundary (Y 35..60 / 245..255, Cb 68..80 / 124..134,
 *    Cr 133..138 / 168..176). This rejects saturated backgrounds and deep shadows.
 *  - `ellipseWeight` is 1 inside the face ellipse and feathers to 0 by 1.4x the
 *    radius, so blending never ends on a hard ellipse edge.
 *
 * Only pixels inside at least one face ellipse are ever non-zero: smoothing must not
 * touch arms, clothes or background even when they happen to fall in the YCbCr box.
 *
 * Pure Kotlin: no android.* imports.
 */
object SkinMask {

    private const val FEATHER_INNER = 1.0f
    private const val FEATHER_OUTER = 1.4f

    fun build(
        pixels: IntArray,
        width: Int,
        height: Int,
        faces: List<FaceOval>
    ): FloatArray {
        val count = width * height
        val mask = FloatArray(count)
        if (width <= 0 || height <= 0 || pixels.size < count || faces.isEmpty()) return mask

        for (index in 0 until count) {
            val x = (index % width) + 0.5f
            val y = (index / width) + 0.5f
            var ellipse = 0f
            for (face in faces) {
                if (face.radiusX <= 0f || face.radiusY <= 0f) continue
                val dx = (x - face.centerX) / face.radiusX
                val dy = (y - face.centerY) / face.radiusY
                val distance = sqrt(dx * dx + dy * dy)
                val weight = ((FEATHER_OUTER - distance) / (FEATHER_OUTER - FEATHER_INNER))
                    .coerceIn(0f, 1f)
                if (weight > ellipse) ellipse = weight
                if (ellipse >= 1f) break
            }
            if (ellipse <= 0f) continue
            val skin = skinScore(pixels[index])
            if (skin > 0f) mask[index] = skin * ellipse
        }
        return mask
    }

    /** Product of soft Y/Cb/Cr membership functions; 0..1. */
    fun skinScore(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

        val yWeight = minOf(ramp(y, 35f, 60f), 1f - ramp(y, 245f, 255f))
        val cbWeight = trapezoid(cb, 68f, 80f, 124f, 134f)
        val crWeight = trapezoid(cr, 133f, 138f, 168f, 176f)
        if (yWeight <= 0f || cbWeight <= 0f || crWeight <= 0f) return 0f
        return yWeight * cbWeight * crWeight
    }

    private fun ramp(value: Float, from: Float, to: Float): Float =
        ((value - from) / (to - from)).coerceIn(0f, 1f)

    private fun trapezoid(value: Float, lowFrom: Float, lowTo: Float, highFrom: Float, highTo: Float): Float =
        minOf(ramp(value, lowFrom, lowTo), 1f - ramp(value, highFrom, highTo)).coerceAtLeast(0f)
}
