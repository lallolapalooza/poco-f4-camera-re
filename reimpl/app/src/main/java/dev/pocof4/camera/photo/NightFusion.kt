package dev.pocof4.camera.photo

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Multi-frame night merge wrapper (stock: ArcSoft Super Night, module 173,
 * shot types 5/8/16; see docs/04-computational-photography.md).
 *
 * Frames are aligned to the first frame, robustly averaged and shadow-lifted.
 * Because the merge keeps only the input IntArrays and the output, the cap is
 * higher than for HDR: peak heap is about `(frames + 1) * pixels * 4 bytes`.
 */
object NightFusion {

    /** 12 MP matches the binned still size used by stock Super Night. */
    private const val MAX_NIGHT_PIXELS = 12_000_000

    /**
     * Total budget for the copied input planes. A 5-frame burst is capped at
     * 6 MP per frame, a 3-frame burst at 10 MP, a 2-frame burst at 12 MP.
     */
    private const val TOTAL_PIXEL_BUDGET = 30_000_000L

    fun merge(frames: List<Bitmap>): Bitmap {
        require(frames.size >= 2) { "night merge needs at least 2 frames" }
        val targetWidth = frames.minOf { it.width }
        val targetHeight = frames.minOf { it.height }
        require(targetWidth > 0 && targetHeight > 0) { "empty frame" }

        val perFrameCap = min(
            MAX_NIGHT_PIXELS.toLong(),
            TOTAL_PIXEL_BUDGET / frames.size
        )
        val targetPixels = targetWidth.toLong() * targetHeight.toLong()
        var workWidth = targetWidth
        var workHeight = targetHeight
        if (targetPixels > perFrameCap) {
            val scale = sqrt(perFrameCap.toDouble() / targetPixels.toDouble())
            workWidth = max(1, (targetWidth * scale).roundToInt())
            workHeight = max(1, (targetHeight * scale).roundToInt())
        }

        val planes = ArrayList<IntArray>(frames.size)
        for (frame in frames) planes.add(BitmapPixels.toPixels(frame, workWidth, workHeight))
        val merged = TemporalMergeCore.merge(planes, workWidth, workHeight)

        val workBitmap = BitmapPixels.fromPixels(merged, workWidth, workHeight)
        if (workWidth == targetWidth && workHeight == targetHeight) return workBitmap

        val scaled = Bitmap.createScaledBitmap(workBitmap, targetWidth, targetHeight, true)
        if (scaled !== workBitmap) workBitmap.recycle()
        return scaled
    }
}
