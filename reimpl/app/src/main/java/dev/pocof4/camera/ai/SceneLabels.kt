package dev.pocof4.camera.ai

import dev.pocof4.camera.core.AiSceneIds

/**
 * English display names for the AI scene ids.
 *
 * The strings are copied from apktool_out/res/values/strings.xml (`ai_scene_mode_*`),
 * which is also the order used by the stock `ai_scene_mode` string array
 * (apktool_out/res/values/arrays.xml, entries starting at line 168). Two ids in that
 * array have no constant in `AiSceneModeConstant`: index 23 (Backlight) and index 24
 * (Silhouette); they are intentionally not represented here.
 *
 * Note the stock quirk that id 33 (AI_SCENE_MODE_DRIVING) is labelled "Underwater"
 * (strings.xml `ai_scene_mode_driving`); the label is kept verbatim.
 *
 * Pure Kotlin: no android.* imports, safe for JVM unit tests.
 */
object SceneLabels {

    fun of(id: Int): String = when (id) {
        AiSceneIds.UNKNOWN -> "Unknown"
        AiSceneIds.DOC -> "Document"
        AiSceneIds.FLOWER -> "Flowers"
        AiSceneIds.FOOD -> "Food"
        AiSceneIds.PPT -> "Presentation"
        AiSceneIds.SKY -> "Sky"
        AiSceneIds.SUNRISE_SUNSET -> "Sunrises and sunsets"
        AiSceneIds.CAT -> "Cats"
        AiSceneIds.DOG -> "Dogs"
        AiSceneIds.GREEN_PLANTS -> "Plants"
        AiSceneIds.NIGHT -> "Night"
        AiSceneIds.SNOW -> "Snow"
        AiSceneIds.SEA -> "Seaside"
        AiSceneIds.AUTUMN -> "Autumn"
        AiSceneIds.CANDLE_NIGHT -> "Candlelight"
        AiSceneIds.CAR -> "Cars"
        AiSceneIds.GRASS -> "Grass"
        AiSceneIds.MAPLE_LEAVES -> "Leaves"
        AiSceneIds.SUCCULENT -> "Succulent plants"
        AiSceneIds.BUILDING -> "Architecture"
        AiSceneIds.CITY -> "City"
        AiSceneIds.CLOUD -> "Clouds"
        AiSceneIds.OVERCAST -> "Overcast"
        AiSceneIds.HUMAN -> "Person"
        AiSceneIds.JEWELRY -> "Jewelry"
        AiSceneIds.BUDDHA -> "Statue"
        AiSceneIds.COW -> "Cows"
        AiSceneIds.CURRY -> "Curry"
        AiSceneIds.MOTORBIKE -> "Motorcycles"
        AiSceneIds.TEMPLE -> "Temple"
        AiSceneIds.BEACH -> "Beach"
        AiSceneIds.DRIVING -> "Underwater"
        AiSceneIds.TOWER -> "Tower"
        AiSceneIds.MOON -> "Moon"
        AiSceneIds.SIDEFACE -> "Group photo"
        AiSceneIds.SCULPTURE -> "Sculpture"
        AiSceneIds.ID_CARD -> "ID"
        AiSceneIds.MONKEY -> "Monkeys"
        AiSceneIds.COCONUT_TREE -> "Palm trees"
        AiSceneIds.TUTUK -> "Three-wheeler"
        else -> "Unknown"
    }
}
