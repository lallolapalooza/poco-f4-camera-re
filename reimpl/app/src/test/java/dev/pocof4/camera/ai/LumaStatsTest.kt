package dev.pocof4.camera.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LumaStatsTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun rec601LumaMatchesPreviewPacking() {
        assertEquals(255, LumaStats.lumaOf(argb(255, 255, 255)))
        assertEquals(0, LumaStats.lumaOf(argb(0, 0, 0)))
        // 77*255 >> 8 == 76, 151*255 >> 8 == 150, 28*255 >> 8 == 27
        assertEquals(76, LumaStats.lumaOf(argb(255, 0, 0)))
        assertEquals(150, LumaStats.lumaOf(argb(0, 255, 0)))
        assertEquals(27, LumaStats.lumaOf(argb(0, 0, 255)))
    }

    @Test
    fun topAndBottomThirdsAreSeparate() {
        val pixels = intArrayOf(
            argb(255, 255, 255),
            argb(0, 0, 0),
            argb(128, 128, 128)
        )
        val stats = LumaStats.fromArgb(pixels, 1, 3)
        assertEquals(255f, stats.top, 0.5f)
        assertEquals(128f, stats.bottom, 0.5f)
        assertEquals((255f + 0f + 128f) / 3f, stats.mean, 0.5f)
    }

    @Test
    fun uniformFrameHasEqualTopBottomAndMean() {
        val width = 7
        val height = 9
        val pixels = IntArray(width * height) { argb(64, 64, 64) }
        val stats = LumaStats.fromArgb(pixels, width, height)
        assertEquals(64f, stats.mean, 0.01f)
        assertEquals(64f, stats.top, 0.01f)
        assertEquals(64f, stats.bottom, 0.01f)
    }

    @Test
    fun emptyInputIsZero() {
        val stats = LumaStats.fromArgb(IntArray(0), 0, 0)
        assertEquals(0f, stats.mean, 0f)
        assertEquals(0f, stats.top, 0f)
        assertEquals(0f, stats.bottom, 0f)
    }
}
