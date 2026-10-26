package dev.pocof4.camera.photo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SaliencyCoreTest {

    private val width = 160
    private val height = 120
    private val subjectLeft = 50
    private val subjectTop = 30
    private val subjectRight = 110
    private val subjectBottom = 90

    /** High-contrast checker subject on a low-contrast checker background. */
    private fun syntheticScene(): IntArray {
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val even = ((x / 2) + (y / 2)) % 2 == 0
            val inSubject = x in subjectLeft until subjectRight && y in subjectTop until subjectBottom
            val value = if (inSubject) {
                if (even) 188 else 68
            } else {
                if (even) 64 else 56
            }
            gray(value)
        }
    }

    @Test
    fun saliencyMapLocalizesSyntheticSubject() {
        val scene = syntheticScene()
        val map = SaliencyCore.map(scene, width, height)

        val inside = meanMap(map, subjectLeft + 6, subjectRight - 6, subjectTop + 6, subjectBottom - 6)
        val outside = meanMap(map, 4, subjectLeft - 14, 4, height - 4)

        assertTrue("inside saliency too low: $inside", inside > 0.45)
        assertTrue("background saliency too high: $outside", outside < 0.2)
        assertTrue("subject must dominate background ($inside vs $outside)", inside > outside * 5.0)

        val mask = SaliencyCore.subjectMask(scene, width, height)
        val insideMask = meanMap(mask, subjectLeft + 10, subjectRight - 10, subjectTop + 10, subjectBottom - 10)
        val outsideMask = meanMap(mask, 4, subjectLeft - 14, 4, height - 4)
        assertTrue("subject mask should be saturated: $insideMask", insideMask > 0.9)
        assertTrue("background mask should be near zero: $outsideMask", outsideMask < 0.1)

        var maxIndex = 0
        for (i in map.indices) if (map[i] > map[maxIndex]) maxIndex = i
        val maxX = maxIndex % width
        val maxY = maxIndex / width
        assertTrue("saliency peak outside subject: ($maxX,$maxY)", maxX in subjectLeft until subjectRight)
        assertTrue("saliency peak outside subject: ($maxX,$maxY)", maxY in subjectTop until subjectBottom)

        for (value in map) assertTrue("map must be normalised, got $value", value in 0f..1f)
    }

    @Test
    fun renderKeepsSubjectSharpAndBlursBackground() {
        val scene = syntheticScene()
        val rendered = SaliencyCore.render(scene, width, height, strength = 1f)

        assertEquals(width * height, rendered.size)

        // Inset well beyond the ~2 px mask feather.
        val subjectBefore = gradientEnergy(scene, subjectLeft + 10, subjectRight - 10, subjectTop + 10, subjectBottom - 10)
        val subjectAfter = gradientEnergy(rendered, subjectLeft + 10, subjectRight - 10, subjectTop + 10, subjectBottom - 10)
        assertTrue(
            "subject must stay sharp: before=$subjectBefore after=$subjectAfter",
            subjectAfter >= subjectBefore * 0.85
        )

        val backgroundBefore = gradientEnergy(scene, 4, subjectLeft - 14, 10, height - 10)
        val backgroundAfter = gradientEnergy(rendered, 4, subjectLeft - 14, 10, height - 10)
        assertTrue("background texture should be present before blur", backgroundBefore > 1000.0)
        assertTrue(
            "background must be blurred: before=$backgroundBefore after=$backgroundAfter",
            backgroundAfter < backgroundBefore * 0.5
        )
    }

    @Test
    fun zeroStrengthIsIdentity() {
        val scene = syntheticScene()
        val rendered = SaliencyCore.render(scene, width, height, 0f)
        assertArrayEquals(scene, rendered)
    }

    private fun meanMap(map: FloatArray, left: Int, right: Int, top: Int, bottom: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                sum += map[y * width + x]
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun gradientEnergy(argb: IntArray, left: Int, right: Int, top: Int, bottom: Int): Double {
        var sum = 0.0
        for (y in top until bottom) {
            for (x in left until right) {
                val centre = ImageMath.luma(argb[y * width + x])
                if (x + 1 < width) sum += abs(centre - ImageMath.luma(argb[y * width + x + 1]))
                if (y + 1 < height) sum += abs(centre - ImageMath.luma(argb[(y + 1) * width + x]))
            }
        }
        return sum
    }

    private fun gray(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }
}
