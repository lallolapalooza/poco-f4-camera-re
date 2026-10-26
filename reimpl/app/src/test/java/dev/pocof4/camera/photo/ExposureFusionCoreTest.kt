package dev.pocof4.camera.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ExposureFusionCoreTest {

    private val width = 96
    private val height = 64

    /**
     * Scene: dark left half, bright right half, both carrying a fine checker.
     * The dark bracket clips the left half to 0; the bright bracket clips the
     * right half to 255. Neither single frame contains both shadows and
     * highlights, so the fused result must recover detail from both halves.
     */
    @Test
    fun fusesDarkMediumBrightFramesWithoutClipping() {
        val scene = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = if (x < width / 2) 0.07f else 0.93f
                val checker = if (((x / 2) + (y / 2)) % 2 == 0) 0.035f else -0.035f
                scene[y * width + x] = (base + checker).coerceIn(0f, 1f)
            }
        }

        val dark = IntArray(width * height) {
            val v = (scene[it] - 0.30f).coerceIn(0f, 1f)
            gray((v * 255f).toInt())
        }
        val medium = IntArray(width * height) {
            gray((scene[it] * 255f).toInt())
        }
        val bright = IntArray(width * height) {
            val v = (scene[it] * 0.55f + 0.55f).coerceIn(0f, 1f)
            gray((v * 255f).toInt())
        }

        val fused = ExposureFusionCore.fuse(listOf(dark, medium, bright), width, height)

        val leftStdDarkFrame = lumaStd(dark, 6, width / 2 - 4, 6, height - 6)
        val rightStdBrightFrame = lumaStd(bright, width / 2 + 4, width - 6, 6, height - 6)
        assertEquals("dark bracket must clip the shadows", 0.0, leftStdDarkFrame, 1e-6)
        assertEquals("bright bracket must clip the highlights", 0.0, rightStdBrightFrame, 1e-6)

        val leftStd = lumaStd(fused, 6, width / 2 - 4, 6, height - 6)
        val rightStd = lumaStd(fused, width / 2 + 4, width - 6, 6, height - 6)
        assertTrue("dark half keeps texture, std=$leftStd", leftStd > 1.0)
        assertTrue("bright half keeps texture, std=$rightStd", rightStd > 1.0)

        // No hard clipping in either recovered half (seam excluded).
        var clipped = 0
        for (y in 6 until height - 6) {
            for (x in 6 until width - 6) {
                if (x in (width / 2 - 4)..(width / 2 + 4)) continue
                val v = ImageMath.luma(fused[y * width + x])
                if (v == 0 || v == 255) clipped++
            }
        }
        assertEquals("fused output must not clip", 0, clipped)

        val mean = lumaMean(fused, 6, width - 6, 6, height - 6)
        assertTrue("fused mean should be mid-range, was $mean", mean in 100.0..190.0)
    }

    @Test
    fun acceptsTwoFramesAndRejectsOne() {
        val a = IntArray(width * height) { gray(60) }
        val b = IntArray(width * height) { gray(200) }
        val fused = ExposureFusionCore.fuse(listOf(a, b), width, height, levels = 2)
        assertEquals(width * height, fused.size)
        val mean = lumaMean(fused, 0, width, 0, height)
        assertTrue(mean in 60.0..200.0)

        var threw = false
        try {
            ExposureFusionCore.fuse(listOf(a), width, height)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("one frame must be rejected", threw)

        val five = List(5) { index ->
            IntArray(width * height) { gray(40 + index * 40) }
        }
        val fusedFive = ExposureFusionCore.fuse(five, width, height)
        assertEquals(width * height, fusedFive.size)
    }

    @Test
    fun toneCurveIsMonotonicAndCompressesHighlights() {
        var previous = -1f
        for (i in 0..1000) {
            val input = i / 100f
            val output = ExposureFusionCore.toneCurve(input)
            assertTrue("monotonic at $input", output >= previous)
            assertTrue("bounded at $input", output in 0f..1f)
            previous = output
        }
        assertTrue("1.0 must not hard clip", ExposureFusionCore.toneCurve(1f) < 0.99f)
        assertTrue("2.0 must stay bounded", ExposureFusionCore.toneCurve(2f) < 1f)
        assertEquals(0.0, ExposureFusionCore.toneCurve(0f).toDouble(), 1e-6)
    }

    private fun lumaStd(
        argb: IntArray,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): Double {
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
        return sqrt((sumSq / count) - mean * mean)
    }

    private fun lumaMean(
        argb: IntArray,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): Double {
        var sum = 0L
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                sum += ImageMath.luma(argb[y * width + x])
                count++
            }
        }
        return sum.toDouble() / count
    }

    private fun gray(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }
}
