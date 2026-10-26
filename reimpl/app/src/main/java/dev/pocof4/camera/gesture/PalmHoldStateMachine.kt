package dev.pocof4.camera.gesture

/**
 * Pure-Kotlin hold / cooldown state machine behind the palm shutter.
 *
 * The stock camera asks ArcSoft's native hand engine for a decoded rectangle on
 * every preview frame at ~16 fps (`HandGestureDecoder.DETECTION_FRAMES_PER_SECOND`)
 * and fires `CameraAction.onShutterButtonClick(100)` once a hand has survived the
 * `HandGestureDecoder` continuous-interval gate. This class reproduces the
 * debounce logic without Android dependencies so it can be unit-tested on the JVM.
 *
 * Semantics:
 *  - an accepted palm ([OPEN_PALM] with `confidence >= minConfidence`) starts a
 *    hold window of [holdDurationNs];
 *  - the window is measured with frame timestamps, never wall-clock sleeps;
 *  - a non-palm frame, a below-threshold palm, or a gap of more than
 *    [maxPalmGapNs] between two palm frames restarts the hold;
 *  - the first palm frame whose age reaches [holdDurationNs] emits [Event.TRIGGERED]
 *    exactly once and arms a [cooldownNs] cooldown (stock 48 frames at 16 fps
 *    ≈ 3 s, `HandGestureDecoder.java:231`);
 *  - frames older than or equal to the newest processed timestamp are [Event.STALE]
 *    and never change state (camera timestamps are monotonic; this makes the
 *    detector robust to duplicate or jittered deliveries).
 */
class PalmHoldStateMachine(
    private val holdDurationNs: Long = DEFAULT_HOLD_NANOS,
    private val cooldownNs: Long = DEFAULT_COOLDOWN_NANOS,
    private val maxPalmGapNs: Long = DEFAULT_MAX_PALM_GAP_NANOS,
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    private val palmGestureName: String = OPEN_PALM
) {

    init {
        require(holdDurationNs > 0L) { "holdDurationNs must be > 0" }
        require(cooldownNs >= 0L) { "cooldownNs must be >= 0" }
        require(maxPalmGapNs > 0L) { "maxPalmGapNs must be > 0" }
    }

    /** Result of feeding one frame into [onDetection]. */
    enum class Event {
        /** No palm for long enough / a non-palm frame; nothing is pending. */
        NONE,

        /** An accepted palm started (or restarted after a gap) the hold window. */
        HOLD_STARTED,

        /** An accepted palm is inside the hold window; keep presenting it. */
        HOLDING,

        /** The palm was held for [holdDurationNs]: fire the shutter exactly once. */
        TRIGGERED,

        /** A trigger already happened and [cooldownNs] has not elapsed yet. */
        COOLDOWN,

        /** Duplicate or backwards timestamp: ignored, state untouched. */
        STALE
    }

    /** Newest timestamp accepted by the machine, or `null` before the first frame. */
    var lastTimestampNs: Long? = null
        private set

    /** Timestamp of the first accepted palm in the current hold window. */
    var holdStartNs: Long? = null
        private set

    /** Timestamp at which the current cooldown expires, or `null` if not cooling down. */
    var cooldownUntilNs: Long? = null
        private set

    val isHolding: Boolean
        get() = holdStartNs != null

    fun isCoolingDownAt(timestampNs: Long): Boolean {
        val until = cooldownUntilNs ?: return false
        return timestampNs < until
    }

    /**
     * Feeds one detection result.
     *
     * @param timestampNs frame timestamp in nanoseconds (`ImageProxy.getImageInfo().getTimestamp()`)
     * @param gestureName MediaPipe canned gesture name, e.g. [OPEN_PALM], or `null`
     * @param confidence classifier score of [gestureName]
     */
    fun onDetection(timestampNs: Long, gestureName: String?, confidence: Float): Event {
        val newest = lastTimestampNs
        if (newest != null && timestampNs <= newest) {
            // Duplicate or jittered frame: never advance/restart state on stale data.
            return Event.STALE
        }
        lastTimestampNs = timestampNs

        val coolingUntil = cooldownUntilNs
        if (coolingUntil != null) {
            if (timestampNs < coolingUntil) {
                // Refire suppression: while cooling down, palms cannot accumulate hold time.
                holdStartNs = null
                lastPalmFrameNs = null
                return Event.COOLDOWN
            }
            cooldownUntilNs = null
        }

        val isPalm = gestureName == palmGestureName && confidence >= minConfidence
        if (!isPalm) {
            // Any non-palm (or low-confidence) frame breaks the hold: palm must be re-presented.
            holdStartNs = null
            lastPalmFrameNs = null
            return Event.NONE
        }

        val previousPalm = lastPalmFrameNs
        val start = holdStartNs
        if (start == null || previousPalm == null || timestampNs - previousPalm > maxPalmGapNs) {
            // First accepted palm, or the stream stalled: the window starts here.
            holdStartNs = timestampNs
            lastPalmFrameNs = timestampNs
            return Event.HOLD_STARTED
        }

        lastPalmFrameNs = timestampNs
        if (timestampNs - start >= holdDurationNs) {
            holdStartNs = null
            lastPalmFrameNs = null
            cooldownUntilNs = timestampNs + cooldownNs
            return Event.TRIGGERED
        }
        return Event.HOLDING
    }

    /** Clears every timer; called when the feature starts/stops or the camera resets. */
    fun reset() {
        lastTimestampNs = null
        holdStartNs = null
        lastPalmFrameNs = null
        cooldownUntilNs = null
    }

    private var lastPalmFrameNs: Long? = null

    companion object {
        /** MediaPipe canned gesture label emitted for a flat, open hand. */
        const val OPEN_PALM = "Open_Palm"

        /** 600 ms, inside the 500-800 ms window specified for the reimplementation. */
        const val DEFAULT_HOLD_NANOS = 600_000_000L

        /**
         * 48 frames at 16 fps ≈ 3.0 s. Stock sets `mContinuousInterval = 48` after
         * triggering (`HandGestureDecoder.java:231`) and the decoder runs at 62 ms
         * per frame (`HandGestureDecoder.java:148-150`).
         */
        const val DEFAULT_COOLDOWN_NANOS = 3_000_000_000L

        /** At 16 fps a normal inter-frame gap is 62 ms; 500 ms means the stream stalled. */
        const val DEFAULT_MAX_PALM_GAP_NANOS = 500_000_000L

        /** Minimum MediaPipe Open_Palm score. */
        const val DEFAULT_MIN_CONFIDENCE = 0.6f
    }
}
