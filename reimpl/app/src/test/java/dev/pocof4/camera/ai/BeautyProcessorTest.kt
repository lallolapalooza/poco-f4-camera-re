package dev.pocof4.camera.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeautyProcessorTest {

    private val grey = 0xFF808080.toInt()

    private fun solid(width: Int, height: Int, colour: Int) = IntArray(width * height) { colour }

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    @Test
    fun zeroMaskLeavesPixelsUntouched() {
        val width = 8
        val height = 8
        val source = IntArray(width * height) { index -> 0xFF000000.toInt() or (index * 0x010101) }
        val mask = FloatArray(source.size)
        val output = BeautyProcessor.apply(source, width, height, mask, 1f)
        assertArrayEquals(source, output)
    }

    @Test
    fun strengthZeroLeavesPixelsUntouched() {
        val source = solid(8, 8, grey)
        val mask = FloatArray(source.size) { 1f }
        val output = BeautyProcessor.apply(source, 8, 8, mask, 0f)
        assertArrayEquals(source, output)
    }

    @Test
    fun flatSkinAreaIsBrightenedSlightly() {
        val width = 10
        val height = 10
        val source = solid(width, height, grey)
        val mask = FloatArray(source.size) { 1f }

        val output = BeautyProcessor.apply(source, width, height, mask, 1f)

        assertEquals(128 + BeautyProcessor.BRIGHTEN.toInt(), red(output[0]))
        assertEquals(128 + BeautyProcessor.BRIGHTEN.toInt(), green(output[0]))
        assertEquals(128 + BeautyProcessor.BRIGHTEN.toInt(), blue(output[0]))
        assertEquals(255, alpha(output[0]))
    }

    @Test
    fun strengthScalesBrightening() {
        val source = solid(4, 4, grey)
        val mask = FloatArray(source.size) { 1f }
        val half = BeautyProcessor.apply(source, 4, 4, mask, 0.5f)
        val full = BeautyProcessor.apply(source, 4, 4, mask, 1f)

        assertEquals(128 + 5, red(half[0]))
        assertEquals(128 + 10, red(full[0]))
    }

    @Test
    fun hardEdgesSurviveBlending() {
        val width = 100
        val height = 100
        val source = IntArray(width * height) { index ->
            val x = index % width
            if (x < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val mask = FloatArray(source.size) { 1f }

        val output = BeautyProcessor.apply(source, width, height, mask, 1f)

        val blackSideOfEdge = output[50 * width + 49]
        val whiteSideOfEdge = output[50 * width + 50]
        assertTrue("black edge pixel drifted: $blackSideOfEdge", red(blackSideOfEdge) <= 2)
        assertTrue("white edge pixel drifted: $whiteSideOfEdge", red(whiteSideOfEdge) >= 253)
    }

    @Test
    fun flatInteriorStillGetsSmoothedAndBrightened() {
        val width = 100
        val height = 100
        val source = solid(width, height, 0xFF404040.toInt())
        val mask = FloatArray(source.size) { 1f }
        val output = BeautyProcessor.apply(source, width, height, mask, 1f)
        assertEquals(64 + BeautyProcessor.BRIGHTEN.toInt(), red(output[50 * width + 50]))
    }

    @Test
    fun invalidStrengthIsClamped() {
        val source = solid(4, 4, grey)
        val mask = FloatArray(source.size) { 1f }
        val over = BeautyProcessor.apply(source, 4, 4, mask, 5f)
        val normal = BeautyProcessor.apply(source, 4, 4, mask, 1f)
        assertArrayEquals(normal, over)

        val negative = BeautyProcessor.apply(source, 4, 4, mask, -3f)
        assertArrayEquals(source, negative)
    }

    @Test
    fun alphaChannelIsPreservedForTransparentSkin() {
        val width = 4
        val height = 4
        val translucent = 0x80C8A08C.toInt()
        val source = solid(width, height, translucent)
        val mask = FloatArray(source.size) { 1f }
        val output = BeautyProcessor.apply(source, width, height, mask, 1f)
        assertEquals(0x80, alpha(output[0]))
    }
}
