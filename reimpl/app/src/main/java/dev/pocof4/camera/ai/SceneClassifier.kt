package dev.pocof4.camera.ai

import dev.pocof4.camera.core.AiSceneIds
import java.util.Locale

/** One ML Kit image label: the raw label text and its confidence in 0..1. */
data class LabelScore(val name: String, val confidence: Float)

/**
 * Everything the pure classifier is allowed to look at.
 *
 * @param meanLuma mean Rec.601 luma of the upright frame, 0..255
 * @param topLuma mean luma of the upper third of the upright frame
 * @param bottomLuma mean luma of the lower third of the upright frame
 * @param faceWidthRatio widest ML Kit face box width divided by frame width
 * @param frontCamera true when the frame comes from the front sensor
 */
data class SceneInput(
    val labels: List<LabelScore>,
    val meanLuma: Float,
    val topLuma: Float,
    val bottomLuma: Float,
    val faceWidthRatio: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val frontCamera: Boolean = false
) {
    /** Top/bottom luminance ratio; used for the backlight heuristic. */
    val topBottomRatio: Float
        get() = topLuma / bottomLuma.coerceAtLeast(1f)

    val aspectRatio: Float
        get() = if (frameHeight <= 0) 0f else frameWidth.toFloat() / frameHeight

    /** Bright upper frame and dark lower frame: the classic backlit composition. */
    val isBacklit: Boolean
        get() = topBottomRatio >= SceneClassifier.BACKLIGHT_LUMA_RATIO
}

/**
 * Result of the heuristic classifier. [id] is one of [AiSceneIds]; id 0 means no
 * scene could be determined. [reason] is diagnostic only and is not shown to users.
 */
data class SceneVerdict(
    val id: Int,
    val label: String,
    val confidence: Float,
    val reason: String
)

/**
 * Heuristic replacement for the vendor AI scene detector.
 *
 * The stock app reads the `AI_SCENE_DETECTED` vendor tag from every capture result
 * (see com.android.camera.module.interceptor.camera.AiSceneMultipleASD). That tag is
 * produced inside the camera HAL, so no reimplementation can reproduce it. This
 * classifier instead combines bundled ML Kit image labels with luminance statistics
 * and face geometry, mapping them onto the same 46-id space.
 *
 * Rules are evaluated top-down; the first matching rule wins. See docs/03-ai-features.md
 * for the rationale and for the stock evidence behind each threshold.
 *
 * Pure Kotlin: no android.* imports.
 */
class SceneClassifier {

    fun classify(input: SceneInput): SceneVerdict {
        val scores = Scores(input.labels)

        // 1. Moon. The stock app only accepts moon on devices that advertise support
        //    (AiSceneMultipleASD logs "detected moon mode on unsupported device").
        //    We have no such capability, so a confident label is the only evidence.
        val moon = scores.best(MOON_LABELS)
        if (moon >= CONF_MOON) return verdict(AiSceneIds.MOON, moon, "moon label")

        // 2. Candlelight is a dark scene dominated by a small bright source.
        val candle = scores.best(CANDLE_LABELS)
        if (candle >= CONF_SPECIFIC && input.meanLuma < DARK_LUMA) {
            return verdict(AiSceneIds.CANDLE_NIGHT, candle, "candle label in dark frame")
        }

        // 3. ID card. ML Kit rarely emits an identity-document label for the bundled
        //    model, so fall back to a bright, card-shaped document with a very strong
        //    document score.
        val idCard = scores.best(ID_CARD_LABELS)
        val document = scores.best(DOC_LABELS)
        if (idCard >= CONF_MOON) {
            return verdict(AiSceneIds.ID_CARD, idCard, "identity-document label")
        }
        if (document >= CONF_STRONG_DOC &&
            scores.best(PPT_LABELS) < CONF_MOON &&
            input.aspectRatio in ID_CARD_ASPECT_MIN..ID_CARD_ASPECT_MAX &&
            input.meanLuma >= ID_CARD_MIN_LUMA
        ) {
            return verdict(AiSceneIds.ID_CARD, document, "bright card-shaped document")
        }

        // 4. Presentation (screen / projector / whiteboard).
        val ppt = scores.best(PPT_LABELS)
        if (ppt >= CONF_SPECIFIC) return verdict(AiSceneIds.PPT, ppt, "presentation label")

        // 5. Flat document / paper / text.
        if (document >= CONF_SPECIFIC) return verdict(AiSceneIds.DOC, document, "document label")

        // 6. Food, with curry as the more specific id.
        val curry = scores.best(CURRY_LABELS)
        if (curry >= CONF_SPECIFIC) return verdict(AiSceneIds.CURRY, curry, "curry label")
        val food = scores.best(FOOD_LABELS)
        if (food >= CONF_FOOD) return verdict(AiSceneIds.FOOD, food, "food label")

        // 7. Golden hour.
        val sunset = scores.best(SUNSET_LABELS)
        if (sunset >= CONF_SPECIFIC) {
            return verdict(AiSceneIds.SUNRISE_SUNSET, sunset, "sunrise/sunset label")
        }

        // 8. Night: either a dark frame overall, or a night label in a dim frame.
        //    Luma is measured after rotation, so the top/bottom split stays upright.
        val nightLabel = scores.best(NIGHT_LABELS)
        if (nightLabel >= CONF_MOON && input.meanLuma < DIM_LUMA) {
            return verdict(AiSceneIds.NIGHT, nightLabel, "night label in dim frame")
        }
        if (input.meanLuma < NIGHT_LUMA) {
            return verdict(AiSceneIds.NIGHT, 1f, "mean luma ${input.meanLuma}")
        }

        // 9. Weather / water.
        val snow = scores.best(SNOW_LABELS)
        if (snow >= CONF_SPECIFIC) return verdict(AiSceneIds.SNOW, snow, "snow label")
        val sea = scores.best(SEA_LABELS)
        if (sea >= CONF_SPECIFIC) return verdict(AiSceneIds.SEA, sea, "sea label")
        val beach = scores.best(BEACH_LABELS)
        if (beach >= CONF_SPECIFIC) return verdict(AiSceneIds.BEACH, beach, "beach label")
        val sky = scores.best(SKY_LABELS)
        if (sky >= CONF_FOOD) return verdict(AiSceneIds.SKY, sky, "sky label")
        val cloud = scores.best(CLOUD_LABELS)
        if (cloud >= CONF_SPECIFIC) return verdict(AiSceneIds.CLOUD, cloud, "cloud label")
        val overcast = scores.best(OVERCAST_LABELS)
        if (overcast >= CONF_SPECIFIC) {
            return verdict(AiSceneIds.OVERCAST, overcast, "overcast label")
        }

        // 10. Plants.
        val flower = scores.best(FLOWER_LABELS)
        if (flower >= CONF_FOOD) return verdict(AiSceneIds.FLOWER, flower, "flower label")
        val succulent = scores.best(SUCCULENT_LABELS)
        if (succulent >= CONF_FOOD) {
            return verdict(AiSceneIds.SUCCULENT, succulent, "succulent label")
        }
        val grass = scores.best(GRASS_LABELS)
        if (grass >= CONF_SPECIFIC) return verdict(AiSceneIds.GRASS, grass, "grass label")
        val leaves = scores.best(LEAF_LABELS)
        if (leaves >= CONF_FOOD) return verdict(AiSceneIds.MAPLE_LEAVES, leaves, "leaf label")
        val plants = scores.best(PLANT_LABELS)
        if (plants >= CONF_FOOD) {
            return verdict(AiSceneIds.GREEN_PLANTS, plants, "plant label")
        }

        // 11. Animals.
        val cat = scores.best(CAT_LABELS)
        if (cat >= CONF_SPECIFIC) return verdict(AiSceneIds.CAT, cat, "cat label")
        val dog = scores.best(DOG_LABELS)
        if (dog >= CONF_SPECIFIC) return verdict(AiSceneIds.DOG, dog, "dog label")
        val cow = scores.best(COW_LABELS)
        if (cow >= CONF_SPECIFIC) return verdict(AiSceneIds.COW, cow, "cow label")
        val monkey = scores.best(MONKEY_LABELS)
        if (monkey >= CONF_SPECIFIC) return verdict(AiSceneIds.MONKEY, monkey, "monkey label")

        // 12. Architecture and vehicles.
        val building = scores.best(BUILDING_LABELS)
        if (building >= CONF_SPECIFIC) {
            return verdict(AiSceneIds.BUILDING, building, "building label")
        }
        val city = scores.best(CITY_LABELS)
        if (city >= CONF_FOOD) return verdict(AiSceneIds.CITY, city, "city label")
        val tower = scores.best(TOWER_LABELS)
        if (tower >= CONF_SPECIFIC) return verdict(AiSceneIds.TOWER, tower, "tower label")
        val temple = scores.best(TEMPLE_LABELS)
        if (temple >= CONF_SPECIFIC) return verdict(AiSceneIds.TEMPLE, temple, "temple label")
        val statue = scores.best(STATUE_LABELS)
        if (statue >= CONF_SPECIFIC) return verdict(AiSceneIds.BUDDHA, statue, "statue label")
        val sculpture = scores.best(SCULPTURE_LABELS)
        if (sculpture >= CONF_SPECIFIC) {
            return verdict(AiSceneIds.SCULPTURE, sculpture, "sculpture label")
        }
        val car = scores.best(CAR_LABELS)
        if (car >= CONF_SPECIFIC) return verdict(AiSceneIds.CAR, car, "car label")
        val motorbike = scores.best(MOTORBIKE_LABELS)
        if (motorbike >= CONF_SPECIFIC) {
            return verdict(AiSceneIds.MOTORBIKE, motorbike, "motorbike label")
        }

        // 13. Human. Mirrors AiSceneMultipleASD: a face counts only when its box is
        //     wider than ~7.5% of the frame, and front-camera frames never report
        //     HUMAN. Stock uses 300 px / (activeWidth / cropWidth), which reduces to
        //     a fixed ratio because crop and active region scale together.
        if (!input.frontCamera && input.faceWidthRatio >= HUMAN_FACE_WIDTH_RATIO) {
            return verdict(AiSceneIds.HUMAN, 1f, "face width ratio ${input.faceWidthRatio}")
        }

        // 14. Backlit frames have no id in AiSceneModeConstant. Stock strings keep a
        //     "Backlight" slot at array index 23, but no constant exists, so we never
        //     emit 23 or 24. A wide face has already been reported as HUMAN above;
        //     anything else stays UNKNOWN.
        val reason = when {
            input.isBacklit -> "backlit frame without a reportable id"
            input.frontCamera -> "front camera suppresses HUMAN"
            else -> "no rule matched"
        }
        return verdict(AiSceneIds.UNKNOWN, 0f, reason)
    }

    private fun verdict(id: Int, confidence: Float, reason: String) =
        SceneVerdict(id, SceneLabels.of(id), confidence.coerceIn(0f, 1f), reason)

    private class Scores(labels: List<LabelScore>) {
        private val map = HashMap<String, Float>(labels.size * 2)

        init {
            for (label in labels) {
                val value = label.confidence
                if (!value.isFinite() || value <= 0f) continue
                val key = normalize(label.name)
                if (key.isEmpty()) continue
                val clamped = value.coerceAtMost(1f)
                val previous = map[key]
                if (previous == null || clamped > previous) map[key] = clamped
            }
        }

        fun best(names: Array<String>): Float {
            var best = 0f
            for (name in names) {
                val value = map[name] ?: continue
                if (value > best) best = value
            }
            return best
        }
    }

    companion object {
        /** Mean luma below this is treated as night (Rec.601, 0..255). */
        const val NIGHT_LUMA = 45f

        /** Dim-frame gate for a "night" image label. */
        const val DIM_LUMA = 90f

        /** Dark-frame gate for candlelight. */
        const val DARK_LUMA = 90f

        /** Stock human rule: face box wider than ~7.5% of the frame. */
        const val HUMAN_FACE_WIDTH_RATIO = 0.075f

        /** Stock backlight ratio is not public; 2.2 keeps false positives down. */
        const val BACKLIGHT_LUMA_RATIO = 2.2f

        /** Bright, card-shaped documents are promoted to ID_CARD. */
        const val ID_CARD_ASPECT_MIN = 1.25f
        const val ID_CARD_ASPECT_MAX = 1.85f
        const val ID_CARD_MIN_LUMA = 150f

        private const val CONF_MOON = 0.5f
        private const val CONF_FOOD = 0.55f
        private const val CONF_SPECIFIC = 0.6f
        private const val CONF_STRONG_DOC = 0.75f

        private val MOON_LABELS = arrayOf("moon", "full moon", "lunar eclipse", "crescent moon")
        private val CANDLE_LABELS = arrayOf("candle", "candlelight", "flame", "fire")
        private val ID_CARD_LABELS = arrayOf(
            "identity document", "identity card", "id card", "passport", "driver's license",
            "business card", "credit card"
        )
        private val DOC_LABELS = arrayOf(
            "document", "paper", "text", "handwriting", "book", "newspaper", "menu",
            "white paper", "page"
        )
        private val PPT_LABELS = arrayOf(
            "presentation", "projector", "whiteboard", "computer monitor", "television",
            "display device", "screenshot"
        )
        private val FOOD_LABELS = arrayOf(
            "food", "dish", "cuisine", "meal", "fast food", "dessert", "cake", "bread",
            "pizza", "fruit", "vegetable", "noodle", "sushi", "salad", "coffee", "drink",
            "breakfast", "buffet"
        )
        private val CURRY_LABELS = arrayOf("curry", "stew", "soup")
        private val SUNSET_LABELS = arrayOf("sunset", "sunrise", "dusk", "dawn", "afterglow")
        private val NIGHT_LABELS = arrayOf("night", "night sky", "darkness", "evening")
        private val SNOW_LABELS = arrayOf("snow", "winter", "blizzard", "ice")
        private val SEA_LABELS = arrayOf("sea", "ocean", "coast", "shore", "wave", "water")
        private val BEACH_LABELS = arrayOf("beach", "sand", "seaside")
        private val SKY_LABELS = arrayOf("sky", "blue sky", "horizon")
        private val CLOUD_LABELS = arrayOf("cloud", "clouds")
        private val OVERCAST_LABELS = arrayOf("overcast", "rain", "fog", "storm", "grey sky")
        private val FLOWER_LABELS = arrayOf("flower", "blossom", "rose", "petal")
        private val SUCCULENT_LABELS = arrayOf("succulent", "cactus", "aloe")
        private val GRASS_LABELS = arrayOf("grass", "lawn", "meadow", "field")
        private val LEAF_LABELS = arrayOf(
            "leaf", "leaves", "autumn leaf", "maple", "foliage"
        )
        private val PLANT_LABELS = arrayOf(
            "plant", "tree", "vegetation", "houseplant", "green plant", "shrub", "garden"
        )
        private val CAT_LABELS = arrayOf("cat", "kitten")
        private val DOG_LABELS = arrayOf("dog", "puppy", "canine")
        private val COW_LABELS = arrayOf("cow", "cattle", "bull")
        private val MONKEY_LABELS = arrayOf("monkey", "ape", "primate")
        private val BUILDING_LABELS = arrayOf(
            "building", "skyscraper", "architectural structure", "house", "facade"
        )
        private val CITY_LABELS = arrayOf("city", "skyline", "town", "urban area")
        private val TOWER_LABELS = arrayOf("tower", "clock tower", "lighthouse")
        private val TEMPLE_LABELS = arrayOf("temple", "shrine", "pagoda", "monastery")
        private val STATUE_LABELS = arrayOf("statue", "buddha", "sculpture of a person")
        private val SCULPTURE_LABELS = arrayOf("sculpture", "carving")
        private val CAR_LABELS = arrayOf("car", "sedan", "automobile", "sports car", "suv")
        private val MOTORBIKE_LABELS = arrayOf("motorcycle", "motorbike", "scooter", "moped")
    }
}

private val LABEL_WHITESPACE = Regex("\\s+")

/** Lower-cases, trims and collapses whitespace so ML Kit labels match the tables. */
private fun normalize(label: String): String =
    label.trim().lowercase(Locale.ROOT).replace('_', ' ').replace(LABEL_WHITESPACE, " ")
