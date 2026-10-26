package dev.pocof4.camera.ai

/**
 * Luminance statistics over one upright ARGB frame.
 *
 * Uses the Rec.601 integer luma weights already used by the preview packing in
 * CameraEngine (`(77*R + 151*G + 28*B) >> 8`). "Top" and "bottom" are the first and
 * last third of the image rows, which gives the classifier a cheap backlight signal.
 *
 * Pure Kotlin: no android.* imports.
 */
object LumaStats {

    data class Stats(val mean: Float, val top: Float, val bottom: Float)

    fun fromArgb(pixels: IntArray, width: Int, height: Int): Stats {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return Stats(0f, 0f, 0f)
        }
        val topEnd = (height / 3).coerceAtLeast(1)
        val bottomStart = (height * 2 / 3).coerceAtMost(height - 1)
        var total = 0L
        var topTotal = 0L
        var topCount = 0
        var bottomTotal = 0L
        var bottomCount = 0
        var index = 0
        for (y in 0 until height) {
            val inTop = y < topEnd
            val inBottom = y >= bottomStart
            for (x in 0 until width) {
                val luma = lumaOf(pixels[index])
                index++
                total += luma
                if (inTop) {
                    topTotal += luma
                    topCount++
                }
                if (inBottom) {
                    bottomTotal += luma
                    bottomCount++
                }
            }
        }
        val count = width * height
        return Stats(
            mean = total.toFloat() / count,
            top = if (topCount == 0) 0f else topTotal.toFloat() / topCount,
            bottom = if (bottomCount == 0) 0f else bottomTotal.toFloat() / bottomCount
        )
    }

    fun lumaOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }
}
