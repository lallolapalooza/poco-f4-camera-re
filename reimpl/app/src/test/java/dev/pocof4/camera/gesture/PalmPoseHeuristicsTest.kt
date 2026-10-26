package dev.pocof4.camera.gesture

import dev.pocof4.camera.gesture.PalmPoseHeuristics.Point
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmPoseHeuristicsTest {

    /**
     * Builds 21 MediaPipe hand landmarks. Finger tips are [extension] above the
     * PIP joints (larger y is lower in image space); the wrist is below the palm.
     */
    private fun hand(extension: Float, palmWidth: Float = 0.24f): List<Point> {
        val points = MutableList(21) { Point(0.5f, 0.5f) }
        points[PalmPoseHeuristics.WRIST] = Point(0.5f, 0.9f)
        points[PalmPoseHeuristics.INDEX_MCP] = Point(0.5f - palmWidth / 2f, 0.7f)
        points[PalmPoseHeuristics.PINKY_MCP] = Point(0.5f + palmWidth / 2f, 0.7f)

        val chains = listOf(
            // mcp, pip, tip
            Triple(5, PalmPoseHeuristics.INDEX_PIP, PalmPoseHeuristics.INDEX_TIP),
            Triple(9, PalmPoseHeuristics.MIDDLE_PIP, PalmPoseHeuristics.MIDDLE_TIP),
            Triple(13, PalmPoseHeuristics.RING_PIP, PalmPoseHeuristics.RING_TIP),
            Triple(17, PalmPoseHeuristics.PINKY_PIP, PalmPoseHeuristics.PINKY_TIP)
        )
        for ((i, chain) in chains.withIndex()) {
            val x = when (i) {
                0 -> 0.5f - palmWidth / 2f
                1 -> 0.5f - palmWidth / 6f
                2 -> 0.5f + palmWidth / 6f
                else -> 0.5f + palmWidth / 2f
            }
            points[chain.first] = Point(x, 0.7f)
            points[chain.second] = Point(x, 0.55f)
            points[chain.third] = Point(x, 0.55f - extension)
        }
        return points
    }

    @Test
    fun openPalmLandmarksAreAccepted() {
        assertTrue(PalmPoseHeuristics.looksLikeOpenPalm(hand(extension = 0.25f)))
    }

    @Test
    fun fistLandmarksAreRejected() {
        // Tips level with (or below) the PIP joints: no finger is extended.
        assertFalse(PalmPoseHeuristics.looksLikeOpenPalm(hand(extension = 0f)))
        assertFalse(PalmPoseHeuristics.looksLikeOpenPalm(hand(extension = -0.05f)))
    }

    @Test
    fun twoExtendedFingersAreRejected() {
        val points = hand(extension = 0.25f).toMutableList()
        // Fold ring and pinky: tips sit at the PIP joints.
        points[PalmPoseHeuristics.RING_TIP] = points[PalmPoseHeuristics.RING_PIP]
        points[PalmPoseHeuristics.PINKY_TIP] = points[PalmPoseHeuristics.PINKY_PIP]
        assertFalse(PalmPoseHeuristics.looksLikeOpenPalm(points))
    }

    @Test
    fun threeExtendedFingersAreAccepted() {
        val points = hand(extension = 0.25f).toMutableList()
        points[PalmPoseHeuristics.PINKY_TIP] = points[PalmPoseHeuristics.PINKY_PIP]
        assertTrue(PalmPoseHeuristics.looksLikeOpenPalm(points))
    }

    @Test
    fun degenerateNarrowPalmIsRejected() {
        assertFalse(PalmPoseHeuristics.looksLikeOpenPalm(hand(extension = 0.25f, palmWidth = 0.01f)))
    }

    @Test
    fun malformedLandmarksAreRejected() {
        assertFalse(PalmPoseHeuristics.looksLikeOpenPalm(emptyList()))
        assertFalse(PalmPoseHeuristics.looksLikeOpenPalm(hand(extension = 0.25f).take(20)))
    }
}
