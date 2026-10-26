package dev.pocof4.camera.ai

import dev.pocof4.camera.core.AiScene
import dev.pocof4.camera.core.AiSceneIds

/**
 * Stability filter in front of the scene callback.
 *
 * Mirrors the two filters in com.android.camera.module.interceptor.camera.AiSceneMultipleASD:
 *  - `dataChanged()` compares the freshly parsed scene with the current one and rejects
 *    changes that arrive less than 300 ms after the previous change (line 68), so a
 *    flickering classifier cannot flip the UI;
 *  - when the currently reported scene is ID_CARD (38), any *other* scene is rejected
 *    for 3000 ms (line 61). That protects the ID-card tip/watermark from a single
 *    misclassified frame.
 *
 * On top of the stock rules this class requires the same id for [minStableCount]
 * consecutive analyses that also span at least [minStableMs] before reporting it.
 * The 20-frame face confirmation of the stock ASD interceptor is folded into this
 * generic stability rule (we analyse at most ~2.5 frames/second, so 20 frames would
 * be eight seconds of latency).
 *
 * The unit of "time" is whatever the caller passes to [offer]; the analyzer passes
 * SystemClock.elapsedRealtime(). Pure Kotlin: no android.* imports.
 */
class SceneDebouncer(
    private val minStableCount: Int = DEFAULT_STABLE_COUNT,
    private val minStableMs: Long = DEFAULT_STABLE_MS,
    private val idCardThrottleMs: Long = DEFAULT_ID_CARD_THROTTLE_MS
) {

    private var candidateId: Int? = null
    private var candidateSinceMs: Long = 0L
    private var candidateCount: Int = 0
    private var candidateVerdict: SceneVerdict? = null

    private var emittedId: Int? = null
    private var lastEmitMs: Long = 0L

    /**
     * Feeds one analysis result. Returns the scene to publish, or null when the
     * result is not stable yet, duplicates the published scene, or is blocked by the
     * ID-card throttle.
     */
    fun offer(verdict: SceneVerdict, nowMs: Long): AiScene? {
        if (candidateId != verdict.id) {
            candidateId = verdict.id
            candidateSinceMs = nowMs
            candidateCount = 1
        } else {
            candidateCount++
        }
        candidateVerdict = verdict

        if (candidateCount < minStableCount) return null
        if (nowMs - candidateSinceMs < minStableMs) return null
        if (emittedId == verdict.id) return null
        if (emittedId == AiSceneIds.ID_CARD && nowMs - lastEmitMs <= idCardThrottleMs) return null

        emittedId = verdict.id
        lastEmitMs = nowMs
        val current = candidateVerdict ?: verdict
        return AiScene(current.id, current.label, current.confidence)
    }

    /** Id that was last published, or null before the first publication. */
    fun lastEmittedId(): Int? = emittedId

    fun reset() {
        candidateId = null
        candidateSinceMs = 0L
        candidateCount = 0
        candidateVerdict = null
        emittedId = null
        lastEmitMs = 0L
    }

    companion object {
        const val DEFAULT_STABLE_COUNT = 2
        const val DEFAULT_STABLE_MS = 300L
        const val DEFAULT_ID_CARD_THROTTLE_MS = 3000L
    }
}
