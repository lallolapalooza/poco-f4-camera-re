package dev.pocof4.camera.gesture

/**
 * Cheap landmark sanity check applied on top of the MediaPipe canned gesture
 * classifier. The classifier already labels the gesture; this guard rejects the
 * rare case where `Open_Palm` is emitted for a hand whose four long fingers are
 * visibly folded, which would otherwise start the shutter countdown.
 *
 * Only normalized image coordinates are used, so the check is rotation-invariant
 * (as long as the detector passes the frame orientation to MediaPipe). It does
 * not attempt a true "palm is facing the camera" test: normalized 2-D landmarks
 * cannot distinguish a palm from the back of a hand reliably. [INFERENCE]
 *
 * Pure Kotlin: no Android types, unit-testable on the JVM.
 */
object PalmPoseHeuristics {

    /** A MediaPipe normalized hand landmark (`x`, `y` in [0, 1] image space). */
    data class Point(val x: Float, val y: Float, val z: Float = 0f)

    // MediaPipe hand landmark indices (21-point model).
    const val WRIST = 0
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_TIP = 12
    const val RING_PIP = 14
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_TIP = 20

    private val FINGER_CHAINS = arrayOf(
        INDEX_PIP to INDEX_TIP,
        MIDDLE_PIP to MIDDLE_TIP,
        RING_PIP to RING_TIP,
        PINKY_PIP to PINKY_TIP
    )

    /** A fingertip counts as extended when it is at least 20 % farther from the wrist than its PIP. */
    private const val EXTENSION_RATIO = 1.2f

    /** Minimum normalized distance between the index and pinky knuckles. */
    private const val MIN_PALM_WIDTH = 0.05f

    /**
     * Returns `true` when at least three of the four long fingers are extended and
     * the palm is wide enough to be a real hand rather than degenerate landmarks.
     * Malformed input (fewer than 21 landmarks) is rejected.
     */
    fun looksLikeOpenPalm(landmarks: List<Point>): Boolean {
        if (landmarks.size < 21) return false
        val wrist = landmarks[WRIST]
        var extended = 0
        for ((pipIndex, tipIndex) in FINGER_CHAINS) {
            val pip = landmarks[pipIndex]
            val tip = landmarks[tipIndex]
            if (distance(wrist, tip) >= distance(wrist, pip) * EXTENSION_RATIO) {
                extended++
            }
        }
        val palmWidth = distance(landmarks[INDEX_MCP], landmarks[PINKY_MCP])
        return extended >= 3 && palmWidth >= MIN_PALM_WIDTH
    }

    private fun distance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
