package dev.pocof4.camera.ai

import dev.pocof4.camera.core.AiSceneIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SceneDebouncerTest {

    private fun verdict(id: Int, confidence: Float = 1f) =
        SceneVerdict(id, SceneLabels.of(id), confidence, "test")

    @Test
    fun firstSceneNeedsTwoSamples() {
        val debouncer = SceneDebouncer()
        assertNull(debouncer.offer(verdict(AiSceneIds.DOC), 0L))
        val emitted = debouncer.offer(verdict(AiSceneIds.DOC), 350L)
        assertNotNull(emitted)
        assertEquals(AiSceneIds.DOC, emitted?.id)
    }

    @Test
    fun twoSamplesInsideThreeHundredMillisAreNotEnough() {
        val debouncer = SceneDebouncer()
        assertNull(debouncer.offer(verdict(AiSceneIds.FOOD), 0L))
        assertNull(debouncer.offer(verdict(AiSceneIds.FOOD), 200L))
        val emitted = debouncer.offer(verdict(AiSceneIds.FOOD), 350L)
        assertNotNull(emitted)
        assertEquals(AiSceneIds.FOOD, emitted?.id)
    }

    @Test
    fun repeatedSceneIsSuppressed() {
        val debouncer = SceneDebouncer()
        debouncer.offer(verdict(AiSceneIds.SKY), 0L)
        assertNotNull(debouncer.offer(verdict(AiSceneIds.SKY), 400L))
        assertNull(debouncer.offer(verdict(AiSceneIds.SKY), 800L))
        assertNull(debouncer.offer(verdict(AiSceneIds.SKY), 1200L))
        assertEquals(AiSceneIds.SKY, debouncer.lastEmittedId())
    }

    @Test
    fun flickeringScenesNeverEmit() {
        val debouncer = SceneDebouncer()
        val ids = intArrayOf(
            AiSceneIds.FOOD, AiSceneIds.DOC, AiSceneIds.FOOD, AiSceneIds.DOC,
            AiSceneIds.FOOD, AiSceneIds.DOC
        )
        var time = 0L
        for (id in ids) {
            assertNull(debouncer.offer(verdict(id), time))
            time += 400L
        }
        assertNull(debouncer.lastEmittedId())
    }

    @Test
    fun sceneChangeAfterStableSceneEmits() {
        val debouncer = SceneDebouncer()
        debouncer.offer(verdict(AiSceneIds.FOOD), 0L)
        assertNotNull(debouncer.offer(verdict(AiSceneIds.FOOD), 400L))
        assertNull(debouncer.offer(verdict(AiSceneIds.SKY), 800L))
        val emitted = debouncer.offer(verdict(AiSceneIds.SKY), 1200L)
        assertNotNull(emitted)
        assertEquals(AiSceneIds.SKY, emitted?.id)
    }

    @Test
    fun leavingIdCardIsThrottledForThreeSeconds() {
        val debouncer = SceneDebouncer()
        debouncer.offer(verdict(AiSceneIds.ID_CARD), 0L)
        val idCard = debouncer.offer(verdict(AiSceneIds.ID_CARD), 400L)
        assertNotNull(idCard)
        assertEquals(AiSceneIds.ID_CARD, idCard?.id)

        // A different scene becomes stable quickly, but the stock 3 s guard holds it.
        assertNull(debouncer.offer(verdict(AiSceneIds.FOOD), 800L))
        assertNull(debouncer.offer(verdict(AiSceneIds.FOOD), 1200L))
        assertNull(debouncer.offer(verdict(AiSceneIds.FOOD), 3300L))

        val emitted = debouncer.offer(verdict(AiSceneIds.FOOD), 3401L)
        assertNotNull(emitted)
        assertEquals(AiSceneIds.FOOD, emitted?.id)
    }

    @Test
    fun enteringIdCardIsNotDelayed() {
        val debouncer = SceneDebouncer()
        debouncer.offer(verdict(AiSceneIds.DOC), 0L)
        assertNotNull(debouncer.offer(verdict(AiSceneIds.DOC), 400L))
        assertNull(debouncer.offer(verdict(AiSceneIds.ID_CARD), 800L))
        val emitted = debouncer.offer(verdict(AiSceneIds.ID_CARD), 1200L)
        assertNotNull(emitted)
        assertEquals(AiSceneIds.ID_CARD, emitted?.id)
    }

    @Test
    fun resetDropsCandidateAndPublishedScene() {
        val debouncer = SceneDebouncer()
        debouncer.offer(verdict(AiSceneIds.DOG), 0L)
        assertNotNull(debouncer.offer(verdict(AiSceneIds.DOG), 400L))

        debouncer.reset()
        assertNull(debouncer.lastEmittedId())
        assertNull(debouncer.offer(verdict(AiSceneIds.DOG), 800L))
        assertNotNull(debouncer.offer(verdict(AiSceneIds.DOG), 1200L))
    }

    @Test
    fun unknownSceneIsDebouncedLikeAnyOther() {
        val debouncer = SceneDebouncer()
        assertNull(debouncer.offer(verdict(AiSceneIds.UNKNOWN), 0L))
        val emitted = debouncer.offer(verdict(AiSceneIds.UNKNOWN), 400L)
        assertNotNull(emitted)
        assertEquals(AiSceneIds.UNKNOWN, emitted?.id)
    }
}
