package dev.pocof4.camera.ai

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Skin smoothing and brightening for [AiBeauty].
 *
 * The stock app pushes a full beauty parameter set (smooth, whiten, slim face, eye
 * enlargement, ...) into the vendor effect chain `libCameraEffectJNI.so`
 * (`BeautyConstant.BEAUTY_VENDOR_TAG_MAP`). That library is not usable outside the
 * stock camera service, so the reimplementation performs only the two effects that
 * dominate a default beauty level: skin smoothing and a slight brightening.
 *
 * Smoothing is a box-blur ("guided box") approximation of a bilateral filter. The
 * blend weight at each pixel is `mask * strength * edgeStop`, where
 * `edgeStop = 1 / (1 + (|luma - blurredLuma| / EDGE_SIGMA)^2)`. Flat skin is smoothed
 * fully; a strong luminance discontinuity (nostrils, lips, glasses, hair boundary)
 * keeps its original detail. Non-skin pixels are copied verbatim.
 *
 * Pure Kotlin: no android.* imports.
 */
object BeautyProcessor {

    /** Luma difference at which the edge stop halves the blend. */
    const val EDGE_SIGMA = 12f

    /** Maximum added luma at strength 1 and mask 1. */
    const val BRIGHTEN = 10f

    const val MIN_RADIUS = 2
    const val MAX_RADIUS = 8
    const val RADIUS_DIVISOR = 120

    /**
     * Returns a new ARGB array. [mask], [pixels] and the returned array share the
     * same row-major layout. [strength] is clamped to 0..1.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: FloatArray,
        strength: Float
    ): IntArray {
        val count = width * height
        if (width <= 0 || height <= 0 || pixels.size < count) return pixels.copyOf()
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f || mask.size < count) return pixels.copyOf()

        val radius = (minOf(width, height) / RADIUS_DIVISOR).coerceIn(MIN_RADIUS, MAX_RADIUS)

        val red = IntArray(count)
        val green = IntArray(count)
        val blue = IntArray(count)
        val luma = IntArray(count)
        for (i in 0 until count) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            red[i] = r
            green[i] = g
            blue[i] = b
            luma[i] = (r * 77 + g * 151 + b * 28) shr 8
        }

        val smoothRed = BoxBlur.blur(red, width, height, radius)
        val smoothGreen = BoxBlur.blur(green, width, height, radius)
        val smoothBlue = BoxBlur.blur(blue, width, height, radius)
        val smoothLuma = BoxBlur.blur(luma, width, height, radius)

        val output = IntArray(count)
        for (i in 0 until count) {
            val weight = mask[i]
            if (weight <= 0f) {
                output[i] = pixels[i]
                continue
            }
            val edge = abs(luma[i] - smoothLuma[i]).toFloat()
            val edgeStop = 1f / (1f + (edge / EDGE_SIGMA) * (edge / EDGE_SIGMA))
            val alpha = (weight.coerceIn(0f, 1f) * amount * edgeStop).coerceIn(0f, 1f)
            val boost = BRIGHTEN * amount * weight.coerceIn(0f, 1f) * edgeStop

            val r = blend(red[i], smoothRed[i], alpha, boost)
            val g = blend(green[i], smoothGreen[i], alpha, boost)
            val b = blend(blue[i], smoothBlue[i], alpha, boost)
            output[i] = (pixels[i] and ALPHA_MASK) or (r shl 16) or (g shl 8) or b
        }
        return output
    }

    private fun blend(source: Int, smooth: Int, alpha: Float, boost: Float): Int =
        (source + (smooth - source) * alpha + boost).roundToInt().coerceIn(0, 255)

    private const val ALPHA_MASK = -0x1000000 // 0xFF000000
}
