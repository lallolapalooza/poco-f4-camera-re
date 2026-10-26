package dev.pocof4.camera.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinMaskTest {

    private val width = 100
    private val height = 100

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun pixels(fill: Int): IntArray = IntArray(width * height) { fill }

    private fun maskAt(mask: FloatArray, x: Int, y: Int) = mask[y * width + x]

    private fun face(cx: Float = 50f, cy: Float = 50f, rx: Float = 20f, ry: Float = 20f) =
        listOf(FaceOval(cx, cy, rx, ry))

    @Test
    fun skinToneInsideEllipseIsFullyMasked() {
        val mask = SkinMask.build(pixels(argb(200, 160, 140)), width, height, face())
        assertTrue(maskAt(mask, 50, 50) > 0.9f)
    }

    @Test
    fun nonSkinColourInsideEllipseIsRejected() {
        val blue = SkinMask.build(pixels(argb(30, 60, 200)), width, height, face())
        assertEquals(0f, maskAt(blue, 50, 50), 0f)

        val green = SkinMask.build(pixels(argb(20, 200, 30)), width, height, face())
        assertEquals(0f, maskAt(green, 50, 50), 0f)
    }

    @Test
    fun skinToneOutsideEllipseIsRejected() {
        val mask = SkinMask.build(pixels(argb(200, 160, 140)), width, height, face())
        assertEquals(0f, maskAt(mask, 0, 0), 0f)
        assertEquals(0f, maskAt(mask, 99, 99), 0f)
    }

    @Test
    fun ellipseEdgeFeathersToZero() {
        val mask = SkinMask.build(pixels(argb(200, 160, 140)), width, height, face())
        val centre = maskAt(mask, 50, 50)
        val nearEdge = maskAt(mask, 72, 50)
        val pastFeather = maskAt(mask, 79, 50)

        assertTrue("centre=$centre edge=$nearEdge", nearEdge in 0.5f..0.85f)
        assertTrue(nearEdge < centre)
        assertEquals(0f, pastFeather, 0f)
    }

    @Test
    fun noFacesProducesEmptyMask() {
        val mask = SkinMask.build(pixels(argb(200, 160, 140)), width, height, emptyList())
        assertEquals(width * height, mask.size)
        assertTrue(mask.all { it == 0f })
    }

    @Test
    fun multipleFacesTakeTheStrongestWeight() {
        val faces = listOf(
            FaceOval(20f, 20f, 5f, 5f),
            FaceOval(50f, 50f, 20f, 20f)
        )
        val mask = SkinMask.build(pixels(argb(200, 160, 140)), width, height, faces)
        assertTrue(maskAt(mask, 50, 50) > 0.9f)
        assertTrue(maskAt(mask, 20, 20) > 0.9f)
        assertEquals(0f, maskAt(mask, 90, 10), 0f)
    }

    @Test
    fun skinScoreIsZeroForGreyAndWhite() {
        assertEquals(0f, SkinMask.skinScore(argb(128, 128, 128)), 0f)
        assertEquals(0f, SkinMask.skinScore(argb(255, 255, 255)), 0f)
        assertEquals(0f, SkinMask.skinScore(argb(0, 0, 0)), 0f)
    }

    @Test
    fun skinScoreAcceptsTypicalSkinTones() {
        assertTrue(SkinMask.skinScore(argb(200, 160, 140)) > 0.8f)
        assertTrue(SkinMask.skinScore(argb(120, 80, 60)) > 0.5f)
    }

    @Test
    fun degenerateEllipsesAreIgnored() {
        val mask = SkinMask.build(
            pixels(argb(200, 160, 140)),
            width,
            height,
            listOf(FaceOval(50f, 50f, 0f, 20f))
        )
        assertTrue(mask.all { it == 0f })
    }
}
