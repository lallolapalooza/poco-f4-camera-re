package dev.pocof4.camera.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TemporalMergeCoreTest {

    private val width = 64
    private val height = 48

    @Test
    fun mergeReducesNoiseVarianceVersusSingleFrame() {
        val frames = List(5) { index ->
            noisyFrame(120, width, height, seed = 1000L + index, amplitude = 18)
        }
        val merged = TemporalMergeCore.merge(
            frames, width, height,
            TemporalMergeCore.Config(
                align = false,
                shadowGamma = 1f,
                outlierTolerance = 1000f
            )
        )

        val singleVariance = variance(frames[0], 8, 56, 8, 40)
        val mergedVariance = variance(merged, 8, 56, 8, 40)

        assertTrue("single frame variance should be non-trivial", singleVariance > 20.0)
        assertTrue(
            "merged variance $mergedVariance should be well below $singleVariance",
            mergedVariance < singleVariance * 0.5
        )
    }

    @Test
    fun outlierFrameIsRejectedByMedianGate() {
        val frames = MutableList(5) { index ->
            noisyFrame(100, width, height, seed = 2000L + index, amplitude = 4)
        }
        // Simulate a moving object / hot patch in exactly one frame.
        for (y in 10 until 30) {
            for (x in 20 until 40) {
                frames[2][y * width + x] = gray(210)
            }
        }

        val merged = TemporalMergeCore.merge(
            frames, width, height,
            TemporalMergeCore.Config(align = false, shadowGamma = 1f, outlierTolerance = 20f)
        )

        val centre = ImageMath.luma(merged[20 * width + 30])
        assertTrue("outlier patch must be rejected, got $centre", abs(centre - 100) <= 5)
        assertEquals(210, ImageMath.luma(frames[2][20 * width + 30]))
    }

    @Test
    fun shadowLiftBrightensDarkTones() {
        val frames = List(3) { IntArray(width * height) { gray(20) } }
        val merged = TemporalMergeCore.merge(
            frames, width, height,
            TemporalMergeCore.Config(align = false, shadowGamma = 1.35f)
        )
        val value = ImageMath.luma(merged[(height / 2) * width + width / 2])
        assertTrue("dark tones should be lifted, got $value", value in 32..48)
    }

    @Test
    fun alignedMergeRestoresTranslatedBurstFrames() {
        val reference = texturedScene(width, height)
        val frames = listOf(
            reference,
            translate(reference, width, height, 3, -2),
            translate(reference, width, height, -4, 1)
        )

        val merged = TemporalMergeCore.merge(
            frames, width, height,
            TemporalMergeCore.Config(
                align = true,
                maxShift = 10,
                shadowGamma = 1f,
                outlierTolerance = 8f
            )
        )

        assertEquals(width * height, merged.size)
        val error = meanAbsDifference(merged, reference, 12, width - 12, 12, height - 12)
        assertTrue("aligned merge should match the reference, mean error=$error", error < 6.0)
    }

    private fun noisyFrame(base: Int, w: Int, h: Int, seed: Long, amplitude: Int): IntArray {
        val random = Lcg(seed)
        return IntArray(w * h) { gray(base + random.next(amplitude)) }
    }

    private fun variance(argb: IntArray, left: Int, right: Int, top: Int, bottom: Int): Double {
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val v = ImageMath.luma(argb[y * width + x]).toDouble()
                sum += v
                sumSq += v * v
                count++
            }
        }
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    private fun meanAbsDifference(
        a: IntArray,
        b: IntArray,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): Double {
        var sum = 0L
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                sum += abs(ImageMath.luma(a[y * width + x]) - ImageMath.luma(b[y * width + x]))
                count++
            }
        }
        return sum.toDouble() / count
    }

    private fun texturedScene(w: Int, h: Int): IntArray {
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val hash = ((x * 37) xor (y * 53) xor ((x + 7) * (y + 3) * 11)) and 0xFF
            gray((hash * 3 + x * 5 + y * 7) and 0xFF)
        }
    }

    private fun translate(src: IntArray, w: Int, h: Int, sx: Int, sy: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sourceX = (x - sx).coerceIn(0, w - 1)
                val sourceY = (y - sy).coerceIn(0, h - 1)
                out[y * w + x] = src[sourceY * w + sourceX]
            }
        }
        return out
    }

    private fun gray(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    private class Lcg(seed: Long) {
        private var state = seed
        fun next(amplitude: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            val bits = (state ushr 33).toInt() and 0x7FFFFFFF
            return bits % (2 * amplitude + 1) - amplitude
        }
    }
}
