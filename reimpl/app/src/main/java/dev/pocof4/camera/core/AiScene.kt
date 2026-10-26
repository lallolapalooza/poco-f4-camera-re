package dev.pocof4.camera.core

/**
 * Scene identifiers copied verbatim from
 * com.android.camera.constant.AiSceneModeConstant (MiuiCamera 5.1.001140.0).
 */
object AiSceneIds {
    const val UNKNOWN = 0
    const val DOC = 1
    const val FLOWER = 2
    const val FOOD = 3
    const val PPT = 4
    const val SKY = 5
    const val SUNRISE_SUNSET = 6
    const val CAT = 7
    const val DOG = 8
    const val GREEN_PLANTS = 9
    const val NIGHT = 10
    const val SNOW = 11
    const val SEA = 12
    const val AUTUMN = 13
    const val CANDLE_NIGHT = 14
    const val CAR = 15
    const val GRASS = 16
    const val MAPLE_LEAVES = 17
    const val SUCCULENT = 18
    const val BUILDING = 19
    const val CITY = 20
    const val CLOUD = 21
    const val OVERCAST = 22
    const val HUMAN = 25
    const val JEWELRY = 26
    const val BUDDHA = 27
    const val COW = 28
    const val CURRY = 29
    const val MOTORBIKE = 30
    const val TEMPLE = 31
    const val BEACH = 32
    const val DRIVING = 33
    const val TOWER = 34
    const val MOON = 35
    const val SIDEFACE = 36
    const val SCULPTURE = 37
    const val ID_CARD = 38
    const val MONKEY = 44
    const val COCONUT_TREE = 45
    const val TUTUK = 46
    const val UNSPECIFIED = -2
}

data class AiScene(
    val id: Int,
    val label: String,
    val confidence: Float = 1f
) {
    val isKnown: Boolean get() = id > 0
}
