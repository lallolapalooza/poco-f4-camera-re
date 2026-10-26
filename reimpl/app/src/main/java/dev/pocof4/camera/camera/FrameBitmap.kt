package dev.pocof4.camera.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import dev.pocof4.camera.core.PreviewFrame

/**
 * Packs the RGBA bytes into an ARGB_8888 bitmap.
 *
 * Pass `applyRotation = false` when the consumer takes a rotation hint (MediaPipe
 * ImageProcessingOptions, ML Kit InputImage) so the frame is not rotated twice.
 */
fun PreviewFrame.toBitmap(applyRotation: Boolean = true): Bitmap {
    val pixels = IntArray(width * height)
    var source = 0
    for (index in pixels.indices) {
        val r = rgba[source].toInt() and 0xFF
        val g = rgba[source + 1].toInt() and 0xFF
        val b = rgba[source + 2].toInt() and 0xFF
        pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        source += 4
    }
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    return if (applyRotation && rotationDegrees != 0) {
        rotateBitmap(bitmap, rotationDegrees)
    } else {
        bitmap
    }
}

fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return source
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source) source.recycle()
    return rotated
}
