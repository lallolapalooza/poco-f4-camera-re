package dev.pocof4.camera.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoxBlurTest {

    @Test
    fun uniformPlaneIsUnchanged() {
        val width = 4
        val height = 4
        val source = IntArray(width * height) { 100 }
        val blurred = BoxBlur.blur(source, width, height, 2)
        assertArrayEquals(source, blurred)
    }

    @Test
    fun zeroRadiusReturnsCopy() {
        val source = intArrayOf(0, 10, 20, 30)
        val blurred = BoxBlur.blur(source, 2, 2, 0)
        assertArrayEquals(source, blurred)
        assertTrueNotSame(source, blurred)
    }

    @Test
    fun impulseSpreadsToThreeByThreeNeighbourhood() {
        val width = 5
        val height = 5
        val source = IntArray(width * height)
        source[2 * width + 2] = 100
        val blurred = BoxBlur.blur(source, width, height, 1)

        assertEquals(11, blurred[2 * width + 2])
        assertEquals(11, blurred[1 * width + 1])
        assertEquals(11, blurred[1 * width + 2])
        assertEquals(11, blurred[3 * width + 3])
        assertEquals(0, blurred[0])
        assertEquals(0, blurred[4 * width + 4])
    }

    @Test
    fun windowIsClippedAtBorders() {
        val width = 3
        val height = 3
        val source = intArrayOf(
            0, 0, 0,
            0, 90, 0,
            0, 0, 0
        )
        val blurred = BoxBlur.blur(source, width, height, 1)
        // Center sees the full 3x3 window: 90/9 = 10.
        assertEquals(10, blurred[4])
        // Corners see a 2x2 window that still contains the centre: 90/4 = 22.5 -> 23.
        assertEquals(23, blurred[0])
        assertEquals(23, blurred[2])
        assertEquals(23, blurred[6])
        assertEquals(23, blurred[8])
        // Edge midpoints see a 2x3 window: 90/6 = 15.
        assertEquals(15, blurred[1])
        assertEquals(15, blurred[3])
        assertEquals(15, blurred[5])
        assertEquals(15, blurred[7])
    }

    @Test
    fun invalidInputIsReturnedAsCopy() {
        val source = intArrayOf(1, 2, 3)
        val blurred = BoxBlur.blur(source, 0, 4, 1)
        assertArrayEquals(source, blurred)
    }

    private fun assertTrueNotSame(first: IntArray, second: IntArray) {
        org.junit.Assert.assertNotSame(first, second)
    }
}
