package dev.pocof4.camera.ai

import dev.pocof4.camera.core.AiSceneIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClassifierTest {

    private val classifier = SceneClassifier()

    private fun input(
        labels: List<Pair<String, Float>> = emptyList(),
        meanLuma: Float = 130f,
        topLuma: Float = meanLuma,
        bottomLuma: Float = meanLuma,
        faceWidthRatio: Float = 0f,
        width: Int = 1920,
        height: Int = 1080,
        frontCamera: Boolean = false
    ) = SceneInput(
        labels = labels.map { LabelScore(it.first, it.second) },
        meanLuma = meanLuma,
        topLuma = topLuma,
        bottomLuma = bottomLuma,
        faceWidthRatio = faceWidthRatio,
        frameWidth = width,
        frameHeight = height,
        frontCamera = frontCamera
    )

    @Test
    fun moonLabelMapsToMoon() {
        val result = classifier.classify(input(labels = listOf("Moon" to 0.8f), meanLuma = 65f))
        assertEquals(AiSceneIds.MOON, result.id)
        assertEquals("Moon", result.label)
    }

    @Test
    fun lowMoonConfidenceDoesNotWin() {
        val result = classifier.classify(input(labels = listOf("Moon" to 0.3f), meanLuma = 120f))
        assertNotEquals(AiSceneIds.MOON, result.id)
    }

    @Test
    fun candleOnlyInDarkFrame() {
        val dark = classifier.classify(
            input(labels = listOf("Candle" to 0.8f), meanLuma = 40f)
        )
        assertEquals(AiSceneIds.CANDLE_NIGHT, dark.id)

        val bright = classifier.classify(
            input(labels = listOf("Candle" to 0.8f), meanLuma = 160f)
        )
        assertNotEquals(AiSceneIds.CANDLE_NIGHT, bright.id)
    }

    @Test
    fun strongBrightDocumentBecomesIdCard() {
        val result = classifier.classify(
            input(labels = listOf("Document" to 0.8f), meanLuma = 200f, width = 1600, height = 1000)
        )
        assertEquals(AiSceneIds.ID_CARD, result.id)
    }

    @Test
    fun documentStaysDocumentWhenNotCardShaped() {
        val result = classifier.classify(
            input(labels = listOf("Document" to 0.8f), meanLuma = 200f, width = 1000, height = 1400)
        )
        assertEquals(AiSceneIds.DOC, result.id)
    }

    @Test
    fun presentationWinsOverDocument() {
        val result = classifier.classify(
            input(
                labels = listOf("Presentation" to 0.7f, "Document" to 0.9f),
                meanLuma = 180f,
                width = 1600,
                height = 900
            )
        )
        assertEquals(AiSceneIds.PPT, result.id)
    }

    @Test
    fun foodWinsOverSky() {
        val result = classifier.classify(
            input(labels = listOf("Food" to 0.7f, "Sky" to 0.95f), meanLuma = 150f)
        )
        assertEquals(AiSceneIds.FOOD, result.id)
    }

    @Test
    fun darkFrameIsNightWithoutLabels() {
        val result = classifier.classify(input(meanLuma = 30f))
        assertEquals(AiSceneIds.NIGHT, result.id)
    }

    @Test
    fun nightLabelNeedsDimFrame() {
        val dim = classifier.classify(input(labels = listOf("Night" to 0.7f), meanLuma = 70f))
        assertEquals(AiSceneIds.NIGHT, dim.id)

        val bright = classifier.classify(input(labels = listOf("Night" to 0.7f), meanLuma = 140f))
        assertNotEquals(AiSceneIds.NIGHT, bright.id)
    }

    @Test
    fun skyLabelMapsToSky() {
        val result = classifier.classify(input(labels = listOf("Sky" to 0.9f), meanLuma = 190f))
        assertEquals(AiSceneIds.SKY, result.id)
    }

    @Test
    fun plantAndFlowerUseSpecificIds() {
        val plant = classifier.classify(input(labels = listOf("Plant" to 0.8f), meanLuma = 120f))
        assertEquals(AiSceneIds.GREEN_PLANTS, plant.id)

        val flower = classifier.classify(input(labels = listOf("Flower" to 0.8f), meanLuma = 120f))
        assertEquals(AiSceneIds.FLOWER, flower.id)
    }

    @Test
    fun animalLabelsMapToAnimalIds() {
        val cat = classifier.classify(input(labels = listOf("Cat" to 0.9f), meanLuma = 120f))
        assertEquals(AiSceneIds.CAT, cat.id)

        val dog = classifier.classify(input(labels = listOf("Dog" to 0.9f), meanLuma = 120f))
        assertEquals(AiSceneIds.DOG, dog.id)
    }

    @Test
    fun lowConfidenceLabelIsRejected() {
        val result = classifier.classify(input(labels = listOf("Food" to 0.3f), meanLuma = 150f))
        assertEquals(AiSceneIds.UNKNOWN, result.id)
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun wideFaceIsPerson() {
        val result = classifier.classify(input(meanLuma = 120f, faceWidthRatio = 0.2f))
        assertEquals(AiSceneIds.HUMAN, result.id)
    }

    @Test
    fun narrowFaceIsNotPerson() {
        val result = classifier.classify(input(meanLuma = 120f, faceWidthRatio = 0.05f))
        assertEquals(AiSceneIds.UNKNOWN, result.id)
    }

    @Test
    fun humanThresholdIsSevenPointFivePercent() {
        val atThreshold = classifier.classify(
            input(meanLuma = 120f, faceWidthRatio = SceneClassifier.HUMAN_FACE_WIDTH_RATIO)
        )
        assertEquals(AiSceneIds.HUMAN, atThreshold.id)

        val below = classifier.classify(
            input(meanLuma = 120f, faceWidthRatio = SceneClassifier.HUMAN_FACE_WIDTH_RATIO - 0.001f)
        )
        assertEquals(AiSceneIds.UNKNOWN, below.id)
    }

    @Test
    fun frontCameraSuppressesPerson() {
        val result = classifier.classify(
            input(meanLuma = 120f, faceWidthRatio = 0.3f, frontCamera = true)
        )
        assertEquals(AiSceneIds.UNKNOWN, result.id)
    }

    @Test
    fun backlitFrameNeverReportsReservedIds() {
        val result = classifier.classify(
            input(meanLuma = 100f, topLuma = 210f, bottomLuma = 40f)
        )
        assertTrue(result.id == AiSceneIds.UNKNOWN)
        assertNotEquals(23, result.id)
        assertNotEquals(24, result.id)
        assertTrue(result.reason.contains("backlit"))
    }

    @Test
    fun backlitFrameWithWideFaceIsPerson() {
        val result = classifier.classify(
            input(meanLuma = 100f, topLuma = 210f, bottomLuma = 40f, faceWidthRatio = 0.2f)
        )
        assertEquals(AiSceneIds.HUMAN, result.id)
    }

    @Test
    fun derivedRatios() {
        val scene = SceneInput(
            labels = emptyList(),
            meanLuma = 100f,
            topLuma = 150f,
            bottomLuma = 50f,
            faceWidthRatio = 0f,
            frameWidth = 1600,
            frameHeight = 1000
        )
        assertEquals(3f, scene.topBottomRatio, 0.0001f)
        assertEquals(1.6f, scene.aspectRatio, 0.0001f)
        assertTrue(scene.isBacklit)
    }

    @Test
    fun labelNamesAreNormalized() {
        val result = classifier.classify(
            input(labels = listOf("  FULL   MOON " to 0.7f), meanLuma = 80f)
        )
        assertEquals(AiSceneIds.MOON, result.id)
    }
}
