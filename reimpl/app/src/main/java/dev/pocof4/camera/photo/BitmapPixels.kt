package dev.pocof4.camera.photo

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max

/**
 * Bitmap <-> IntArray bridge shared by the computational-photography wrappers.
 *
 * Scaling uses centre-crop + fill: the bitmap is scaled so that it covers the
 * requested box, then the centred region is cropped. This keeps the optical
 * centre aligned for frames whose sensors produced slightly different sizes,
 * at the cost of a small field-of-view crop. Caller-owned bitmaps are never
 * recycled; only bitmaps created here are. HARDWARE bitmaps are copied to
 * ARGB_8888 first because `getPixels` is not supported on them.
 */
internal object BitmapPixels {

    fun toPixels(bitmap: Bitmap, dstWidth: Int, dstHeight: Int): IntArray {
        require(dstWidth > 0 && dstHeight > 0) { "invalid target size" }
        val working = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        try {
            if (working.width == dstWidth && working.height == dstHeight) {
                val out = IntArray(dstWidth * dstHeight)
                working.getPixels(out, 0, dstWidth, 0, 0, dstWidth, dstHeight)
                return out
            }

            val scale = max(
                dstWidth.toFloat() / working.width,
                dstHeight.toFloat() / working.height
            )
            val scaledWidth = max(dstWidth, ceil(working.width * scale).toInt())
            val scaledHeight = max(dstHeight, ceil(working.height * scale).toInt())

            val scaled = if (scaledWidth == working.width && scaledHeight == working.height) {
                working
            } else {
                Bitmap.createScaledBitmap(working, scaledWidth, scaledHeight, true)
            }
            val cropped = if (scaled.width == dstWidth && scaled.height == dstHeight) {
                scaled
            } else {
                val x = (scaled.width - dstWidth) / 2
                val y = (scaled.height - dstHeight) / 2
                Bitmap.createBitmap(scaled, x, y, dstWidth, dstHeight)
            }
            try {
                val out = IntArray(dstWidth * dstHeight)
                cropped.getPixels(out, 0, dstWidth, 0, 0, dstWidth, dstHeight)
                return out
            } finally {
                if (cropped !== scaled && cropped !== working) cropped.recycle()
                if (scaled !== working) scaled.recycle()
            }
        } finally {
            if (working !== bitmap) working.recycle()
        }
    }

    fun fromPixels(pixels: IntArray, width: Int, height: Int): Bitmap {
        require(pixels.size >= width * height) { "pixels too small" }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
