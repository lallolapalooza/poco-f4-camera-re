package dev.pocof4.camera.photo

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Saliency-driven background blur wrapper ("portrait bokeh" without a depth
 * sensor). Stock MIUI uses the vendor mialgo saliency library for AF-region
 * refinement plus a separate depth/bokeh pipeline; see
 * docs/04-computational-photography.md for the exact split.
 *
 * Saliency and blur run at most [MAX_PROCESS_PIXELS]. Up to
 * [MAX_FULL_COMPOSITE_PIXELS] the mask/blur are upscaled and composited against
 * the original pixels, so the subject keeps full-resolution detail. Beyond that
 * the whole result is upscaled instead to avoid a third full-resolution buffer.
 */
object SaliencyBlur {

    private const val MAX_PROCESS_PIXELS = 2_000_000
    private const val MAX_FULL_COMPOSITE_PIXELS = 12_000_000

    fun render(bitmap: Bitmap, strength: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0) { "empty bitmap" }
        if (strength <= 0.01f) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val pixels = width.toLong() * height.toLong()
        if (pixels <= MAX_PROCESS_PIXELS) {
            val source = readPixels(bitmap, width, height)
            val out = SaliencyCore.render(source, width, height, strength)
            return BitmapPixels.fromPixels(out, width, height)
        }

        val scale = sqrt(MAX_PROCESS_PIXELS.toDouble() / pixels.toDouble())
        val workWidth = max(1, (width * scale).roundToInt())
        val workHeight = max(1, (height * scale).roundToInt())

        if (pixels <= MAX_FULL_COMPOSITE_PIXELS) {
            val source = readPixels(bitmap, width, height)
            val small = ImageMath.resizeArgb(source, width, height, workWidth, workHeight)
            val smallMask = SaliencyCore.subjectMask(small, workWidth, workHeight)
            val smallBlurred = SaliencyCore.variableBlur(
                small, workWidth, workHeight, smallMask, strength
            )
            val blurredFull = ImageMath.resizeArgb(
                smallBlurred, workWidth, workHeight, width, height
            )
            val maskFull = SaliencyCore.resampleMap(
                smallMask, workWidth, workHeight, width, height
            )
            for (i in blurredFull.indices) {
                blurredFull[i] = ImageMath.lerpArgb(blurredFull[i], source[i], maskFull[i])
            }
            return BitmapPixels.fromPixels(blurredFull, width, height)
        }

        val small = BitmapPixels.toPixels(bitmap, workWidth, workHeight)
        val smallMask = SaliencyCore.subjectMask(small, workWidth, workHeight)
        val smallBlurred = SaliencyCore.variableBlur(
            small, workWidth, workHeight, smallMask, strength
        )
        val upscaled = ImageMath.resizeArgb(smallBlurred, workWidth, workHeight, width, height)
        return BitmapPixels.fromPixels(upscaled, width, height)
    }

    private fun readPixels(bitmap: Bitmap, width: Int, height: Int): IntArray {
        return BitmapPixels.toPixels(bitmap, width, height)
    }
}
