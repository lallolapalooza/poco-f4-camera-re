package dev.pocof4.camera.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.pocof4.camera.core.AiScene
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scene-aware photo watermark, the reimplementation's stand-in for the stock
 * AI-watermark pipeline.
 *
 * Stock wiring (com.android.camera.aiwatermark):
 *  - `ASDHandler` receives scene ids from `AIWatermarkDetect`, converts them through
 *    `ASDEngine.spots2ASDKey(id)` and picks a matching `WatermarkItem` from the list
 *    returned by `ASDWatermark.getForAI()` (watermark type 7);
 *  - when no item matches, the scene is treated as `ASD_NEGATIVE` ("negative") and the
 *    generic watermark path is used;
 *  - the selected item supplies a PNG overlay designed by Xiaomi; the actual artwork
 *    is not present in the dump in a redistributable form.
 *
 * The reimplementation therefore renders text: an "AI SCENE <label>" badge for known
 * scenes, the capture time and a device line, bottom-left, white with a soft shadow.
 * Layout scales with image width and always stays inside the image margins.
 * Returns a new bitmap; [bitmap] is not modified.
 */
object WatermarkRenderer {

    private const val DEVICE_LINE = "POCO F4"
    private const val REFERENCE_WIDTH = 1080f
    private const val MARGIN_RATIO = 0.035f
    private const val BADGE_TEXT_COLOR = 0xF2FFFFFF.toInt()
    private const val BADGE_BACKGROUND = 0x40000000
    private const val SHADOW_COLOR = 0x99000000.toInt()

    private val DATE_FORMAT = "yyyy.MM.dd  HH:mm"

    fun render(bitmap: Bitmap, scene: AiScene?, timestampMs: Long): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = output.width
        val height = output.height
        if (width <= 0 || height <= 0) return output

        val canvas = Canvas(output)
        val scale = (width / REFERENCE_WIDTH).coerceIn(0.55f, 2.5f)
        val margin = (width * MARGIN_RATIO).coerceAtLeast(12f * scale)
        val available = width - 2f * margin

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BADGE_TEXT_COLOR
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(3f * scale, 0f, 1.5f * scale, SHADOW_COLOR)
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BADGE_TEXT_COLOR
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(3f * scale, 0f, 1.5f * scale, SHADOW_COLOR)
        }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BADGE_BACKGROUND
        }

        // Draw from the bottom up so the block hugs the bottom-left corner.
        var baseline = height - margin - 2f * scale
        textPaint.textSize = 22f * scale
        canvas.drawText(DEVICE_LINE, margin, baseline, textPaint)

        baseline -= 24f * scale
        textPaint.textSize = 20f * scale
        val stamp = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date(timestampMs))
        canvas.drawText(stamp, margin, baseline, textPaint)

        val known = scene?.takeIf { it.isKnown }
        if (known != null) {
            baseline -= 18f * scale
            badgePaint.textSize = 30f * scale
            val text = "AI SCENE  ${known.label.uppercase(Locale.US)}"
            val measured = badgePaint.measureText(text)
            if (measured > available && measured > 0f) {
                badgePaint.textSize = (badgePaint.textSize * available / measured)
                    .coerceAtLeast(9f * scale)
            }
            val textWidth = badgePaint.measureText(text)
            val paddingH = 10f * scale
            val metrics = badgePaint.fontMetrics
            val rect = RectF(
                margin - paddingH,
                baseline + metrics.ascent - 6f * scale,
                margin + textWidth + paddingH,
                baseline + metrics.descent + 6f * scale
            )
            canvas.drawRoundRect(rect, 6f * scale, 6f * scale, backgroundPaint)
            canvas.drawText(text, margin, baseline, badgePaint)
        }
        return output
    }
}
