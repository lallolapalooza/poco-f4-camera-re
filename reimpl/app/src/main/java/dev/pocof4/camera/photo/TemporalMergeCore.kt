package dev.pocof4.camera.photo

import kotlin.math.abs
import kotlin.math.pow

/**
 * Robust temporal merge for same-exposure night bursts.
 *
 * Pipeline: optional translation alignment against frame 0, per-channel
 * median, then a mean of samples within `outlierTolerance` of that median
 * (moving objects / hot pixels are rejected), then a shadow-lift gamma.
 *
 * The median is computed by insertion sort on a preallocated sample buffer;
 * no allocation happens inside the pixel loop.
 */
object TemporalMergeCore {

    data class Config(
        val align: Boolean = true,
        val maxShift: Int = 24,
        val shadowGamma: Float = 1.35f,
        val outlierTolerance: Float = 20f
    )

    fun merge(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        config: Config = Config()
    ): IntArray {
        require(frames.size >= 2) { "temporal merge needs at least 2 frames" }
        require(width > 0 && height > 0) { "invalid image size" }
        val pixels = width * height
        frames.forEach { require(it.size >= pixels) { "frame too small" } }

        val frameCount = frames.size
        val dx = IntArray(frameCount)
        val dy = IntArray(frameCount)
        if (config.align && config.maxShift > 0) {
            // Frame 0 is the reference; stock ArcSoft/MTK night pipelines also
            // designate one input (normally EV0) as the geometric reference.
            for (k in 1 until frameCount) {
                val shift = AlignCore.estimateTranslation(
                    frames[0], frames[k], width, height,
                    config.maxShift, AlignCore.Metric.SAD
                )
                dx[k] = shift.dx
                dy[k] = shift.dy
            }
        }

        val gammaLut = buildGammaLut(config.shadowGamma)
        val tolerance = config.outlierTolerance.coerceAtLeast(1f)
        val samples = IntArray(frameCount)
        val out = IntArray(pixels) { 0xFF shl 24 }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val target = y * width + x
                var packed = 0xFF shl 24
                for (channel in 0..2) {
                    val shift = 16 - channel * 8
                    for (k in 0 until frameCount) {
                        val sx = (x + dx[k]).coerceIn(0, width - 1)
                        val sy = (y + dy[k]).coerceIn(0, height - 1)
                        samples[k] = (frames[k][sy * width + sx] ushr shift) and 0xFF
                    }
                    val median = medianInPlace(samples, frameCount)
                    var sum = 0
                    var count = 0
                    for (k in 0 until frameCount) {
                        if (abs(samples[k] - median) <= tolerance) {
                            sum += samples[k]
                            count++
                        }
                    }
                    val merged = if (count == 0) median else sum / count
                    val lifted = gammaLut[merged.coerceIn(0, 255)]
                    packed = packed or (lifted shl shift)
                }
                out[target] = packed
            }
        }
        return out
    }

    private fun medianInPlace(values: IntArray, count: Int): Int {
        for (i in 1 until count) {
            val value = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > value) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = value
        }
        return if (count % 2 == 1) {
            values[count / 2]
        } else {
            (values[count / 2 - 1] + values[count / 2]) / 2
        }
    }

    private fun buildGammaLut(gamma: Float): IntArray {
        val g = gamma.coerceIn(0.5f, 3f)
        val lut = IntArray(256)
        if (abs(g - 1f) < 1e-4f) {
            for (v in 0..255) lut[v] = v
            return lut
        }
        val exponent = 1f / g
        for (v in 0..255) {
            val lifted = (v / 255f).pow(exponent)
            lut[v] = (lifted * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }
}
