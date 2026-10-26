package dev.pocof4.camera.gesture

import dev.pocof4.camera.gesture.PalmHoldStateMachine.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmHoldStateMachineTest {

    private val frameNs = 62_000_000L          // 16 fps, HandGestureDecoder.getDecodeInterval()
    private val holdNs = 600_000_000L
    private val cooldownNs = 3_000_000_000L

    private fun machine(
        hold: Long = holdNs,
        cooldown: Long = cooldownNs,
        maxGap: Long = 2_000_000_000L,
        minConfidence: Float = 0.6f
    ) = PalmHoldStateMachine(hold, cooldown, maxGap, minConfidence)

    @Test
    fun singlePalmFrameDoesNotTrigger() {
        val m = machine()
        var triggers = 0

        if (m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.95f) == Event.TRIGGERED) triggers++
        if (m.onDetection(frameNs, "Closed_Fist", 0.99f) == Event.TRIGGERED) triggers++
        if (m.onDetection(2 * frameNs, null, 0f) == Event.TRIGGERED) triggers++

        assertEquals(0, triggers)
        assertFalse(m.isHolding)
    }

    @Test
    fun sustainedPalmFiresExactlyOnce() {
        val m = machine()
        val events = ArrayList<Event>()
        var t = 0L
        while (t <= 1_000_000_000L) {
            events += m.onDetection(t, PalmHoldStateMachine.OPEN_PALM, 0.9f)
            t += frameNs
        }

        assertEquals(1, events.count { it == Event.TRIGGERED })
        // Trigger happens at the first frame at or after 600 ms (0 + 10 * 62 ms = 620 ms).
        assertEquals(10, events.indexOf(Event.TRIGGERED))
        assertFalse(m.isHolding)
    }

    @Test
    fun triggerAtExactHoldBoundary() {
        val m = machine(hold = 600_000_000L)
        m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f)

        assertEquals(Event.HOLDING, m.onDetection(599_999_999L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.TRIGGERED, m.onDetection(600_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(600_000_000L + cooldownNs, m.cooldownUntilNs)
    }

    @Test
    fun cooldownSuppressesImmediateRefire() {
        val m = machine()
        // Drive a palm until the first trigger at 620 ms.
        var t = 0L
        var triggeredAt = -1L
        while (t <= 700_000_000L) {
            if (m.onDetection(t, PalmHoldStateMachine.OPEN_PALM, 0.9f) == Event.TRIGGERED) {
                triggeredAt = t
            }
            t += frameNs
        }
        assertEquals(620_000_000L, triggeredAt)

        // Palm still in front of the camera right after the trigger: no second shot.
        assertEquals(Event.COOLDOWN, m.onDetection(744_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.99f))
        assertEquals(Event.COOLDOWN, m.onDetection(1_000_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.99f))
        assertFalse(m.isHolding)
        assertTrue(m.isCoolingDownAt(3_000_000_000L))

        // At the end of the cooldown a fresh hold window is required: no instant refire.
        val cooldownEnd = triggeredAt + cooldownNs
        assertEquals(
            Event.HOLD_STARTED,
            m.onDetection(cooldownEnd, PalmHoldStateMachine.OPEN_PALM, 0.99f)
        )
        assertEquals(
            Event.HOLDING,
            m.onDetection(cooldownEnd + holdNs - 1, PalmHoldStateMachine.OPEN_PALM, 0.99f)
        )
        assertEquals(
            Event.TRIGGERED,
            m.onDetection(cooldownEnd + holdNs, PalmHoldStateMachine.OPEN_PALM, 0.99f)
        )
    }

    @Test
    fun nonPalmFramesResetHoldAndRequireAFreshWindow() {
        val m = machine()

        assertEquals(Event.HOLD_STARTED, m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.HOLDING, m.onDetection(frameNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.NONE, m.onDetection(2 * frameNs, "Closed_Fist", 0.99f))
        assertFalse(m.isHolding)

        assertEquals(Event.HOLD_STARTED, m.onDetection(3 * frameNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.HOLDING, m.onDetection(3 * frameNs + holdNs - 1, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.TRIGGERED, m.onDetection(3 * frameNs + holdNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))
    }

    @Test
    fun gapLongerThanMaxPalmGapRestartsHold() {
        val m = machine(maxGap = 500_000_000L)

        assertEquals(Event.HOLD_STARTED, m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.HOLDING, m.onDetection(frameNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))

        // 700 ms since the last palm frame: the stream stalled, so the window restarts.
        assertEquals(Event.HOLD_STARTED, m.onDetection(700_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.HOLDING, m.onDetection(762_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.HOLDING, m.onDetection(800_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        // A 500 ms delivery gap is still within maxPalmGap, and the restarted window is old enough.
        assertEquals(Event.TRIGGERED, m.onDetection(1_300_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
    }

    @Test
    fun gapEqualToMaxPalmGapKeepsHold() {
        val m = machine(maxGap = 500_000_000L)
        m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f)

        // Exactly maxGap is not a stall (comparison is strict >).
        assertEquals(Event.HOLDING, m.onDetection(500_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.TRIGGERED, m.onDetection(600_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
    }

    @Test
    fun nonPalmGesturesNeverFire() {
        val m = machine()
        val labels = listOf(
            "Closed_Fist", "Victory", "Thumb_Up", "Thumb_Down", "Pointing_Up",
            "ILoveYou", "None", "", null
        )
        val events = ArrayList<Event>()
        var t = 0L
        var index = 0
        while (t <= 5_000_000_000L) {
            events += m.onDetection(t, labels[index % labels.size], 0.99f)
            index++
            t += frameNs
        }

        assertEquals(0, events.count { it == Event.TRIGGERED })
        assertFalse(m.isHolding)
        assertFalse(m.isCoolingDownAt(t))
    }

    @Test
    fun palmBelowConfidenceThresholdIsIgnored() {
        val m = machine(minConfidence = 0.6f)
        val events = ArrayList<Event>()
        var t = 0L
        while (t <= 2_000_000_000L) {
            events += m.onDetection(t, PalmHoldStateMachine.OPEN_PALM, 0.59f)
            t += frameNs
        }

        assertEquals(0, events.count { it == Event.TRIGGERED })
        assertTrue(events.all { it == Event.NONE })
        assertFalse(m.isHolding)
    }

    @Test
    fun duplicateAndJitteredTimestampsAreStaleAndDoNotDisturbHold() {
        val m = machine()

        assertEquals(Event.HOLD_STARTED, m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.STALE, m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.STALE, m.onDetection(-62_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(0L, m.lastTimestampNs)

        assertEquals(Event.HOLDING, m.onDetection(frameNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.HOLDING, m.onDetection(2 * frameNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        // A backwards jitter mid-hold is ignored, not treated as a palm gap.
        assertEquals(Event.STALE, m.onDetection(frameNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.HOLDING, m.onDetection(3 * frameNs, PalmHoldStateMachine.OPEN_PALM, 0.9f))

        var t = 4 * frameNs
        var triggers = 0
        while (t <= 1_000_000_000L) {
            if (m.onDetection(t, PalmHoldStateMachine.OPEN_PALM, 0.9f) == Event.TRIGGERED) triggers++
            t += frameNs
        }
        assertEquals(1, triggers)

        // Duplicate delivery of the trigger timestamp must not fire a second time.
        assertEquals(Event.STALE, m.onDetection(620_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.99f))
        assertEquals(1, triggers)
    }

    @Test
    fun resetClearsHoldAndCooldown() {
        val m = machine()
        m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f)
        assertEquals(Event.TRIGGERED, m.onDetection(600_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertTrue(m.isCoolingDownAt(1_000_000_000L))

        m.reset()

        assertNull(m.lastTimestampNs)
        assertNull(m.holdStartNs)
        assertNull(m.cooldownUntilNs)
        assertFalse(m.isCoolingDownAt(1_000_000_000L))
        assertEquals(Event.HOLD_STARTED, m.onDetection(1L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
    }

    @Test
    fun customWindowsAreHonoured() {
        val m = machine(hold = 100_000_000L, cooldown = 200_000_000L, maxGap = 500_000_000L)
        assertEquals(Event.HOLD_STARTED, m.onDetection(0L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertEquals(Event.TRIGGERED, m.onDetection(100_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
        assertTrue(m.isCoolingDownAt(299_999_999L))
        assertFalse(m.isCoolingDownAt(300_000_000L))
        assertEquals(Event.HOLD_STARTED, m.onDetection(300_000_000L, PalmHoldStateMachine.OPEN_PALM, 0.9f))
    }
}
