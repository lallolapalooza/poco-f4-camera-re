package dev.pocof4.camera.photo

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignmentCoreTest {

    private val width = 128
    private val height = 96

    @Test
    fun recoversInjectedTranslation() {
        val reference = texturedScene(width, height)
        val moving = translate(reference, width, height, 5, -3)

        val shift = AlignCore.estimateTranslation(
            reference, moving, width, height, maxShift = 12
        )

        assertEquals("dx", 5, shift.dx)
        assertEquals("dy", -3, shift.dy)
    }

    @Test
    fun recoversNegativeTranslationOnSecondPattern() {
        val reference = texturedScene(width, height)
        val moving = translate(reference, width, height, -4, 2)

        val shift = AlignCore.estimateTranslation(
            reference, moving, width, height, maxShift = 10, metric = AlignCore.Metric.SAD
        )

        assertEquals(-4, shift.dx)
        assertEquals(2, shift.dy)
    }

    @Test
    fun flatFramesReturnZeroShift() {
        val flat = IntArray(width * height) { gray(128) }
        val shift = AlignCore.estimateTranslation(flat, flat, width, height, maxShift = 16)
        assertEquals(Shift(0, 0), shift)
    }

    @Test
    fun identicalFramesReturnZeroShift() {
        val reference = texturedScene(width, height)
        val shift = AlignCore.estimateTranslation(reference, reference, width, height, maxShift = 16)
        assertEquals(Shift(0, 0), shift)
    }

    /** Deterministic, non-repeating high-frequency texture. */
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

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}
