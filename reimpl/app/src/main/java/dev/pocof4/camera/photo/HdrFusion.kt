package dev.pocof4.camera.photo

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HDR exposure fusion wrapper (stock: MIUI HDR / MFNR shot types 5/8/16 and the
 * HDR checker; see docs/04-computational-photography.md).
 *
 * Input frames may have different sizes; they are centre-cropped/scaled to the
 * smallest common size. Processing is capped at [MAX_FUSION_PIXELS] because the
 * Laplacian pyramid needs roughly
 * `frames * levels * 1.33 * pixels * 4 bytes` plus the weight pyramids; the
 * result is scaled back to the target size.
 */
object HdrFusion {

    private const val MIN_FRAMES = 2
    private const val MAX_FRAMES = 5

    /** Peak JVM heap for the pyramid blend at this size is roughly 150 MB. */
    private const val MAX_FUSION_PIXELS = 3_000_000

    fun fuse(frames: List<Bitmap>): Bitmap {
        require(frames.size in MIN_FRAMES..MAX_FRAMES) {
            "HDR fusion needs $MIN_FRAMES..$MAX_FRAMES frames, got ${frames.size}"
        }
        val targetWidth = frames.minOf { it.width }
        val targetHeight = frames.minOf { it.height }
        require(targetWidth > 0 && targetHeight > 0) { "empty frame" }

        val targetPixels = targetWidth.toLong() * targetHeight.toLong()
        var workWidth = targetWidth
        var workHeight = targetHeight
        if (targetPixels > MAX_FUSION_PIXELS) {
            val scale = sqrt(MAX_FUSION_PIXELS.toDouble() / targetPixels.toDouble())
            workWidth = max(1, (targetWidth * scale).roundToInt())
            workHeight = max(1, (targetHeight * scale).roundToInt())
        }

        val planes = ArrayList<IntArray>(frames.size)
        for (frame in frames) planes.add(BitmapPixels.toPixels(frame, workWidth, workHeight))
        val fused = ExposureFusionCore.fuse(planes, workWidth, workHeight)

        val workBitmap = BitmapPixels.fromPixels(fused, workWidth, workHeight)
        if (workWidth == targetWidth && workHeight == targetHeight) return workBitmap

        val scaled = Bitmap.createScaledBitmap(workBitmap, targetWidth, targetHeight, true)
        if (scaled !== workBitmap) workBitmap.recycle()
        return scaled
    }
}
