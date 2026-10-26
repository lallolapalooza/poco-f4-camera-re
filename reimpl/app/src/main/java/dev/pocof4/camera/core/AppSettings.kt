package dev.pocof4.camera.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Toggles that mirror the stock preference keys documented in docs/.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocof4_camera", Context.MODE_PRIVATE)

    var palmShutter: Boolean
        get() = prefs.getBoolean(KEY_PALM, true)
        set(value) = prefs.edit().putBoolean(KEY_PALM, value).apply()

    var voiceShutter: Boolean
        get() = prefs.getBoolean(KEY_VOICE, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var aiScene: Boolean
        get() = prefs.getBoolean(KEY_AI_SCENE, true)
        set(value) = prefs.edit().putBoolean(KEY_AI_SCENE, value).apply()

    var aiBeauty: Boolean
        get() = prefs.getBoolean(KEY_AI_BEAUTY, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_BEAUTY, value).apply()

    var aiWatermark: Boolean
        get() = prefs.getBoolean(KEY_AI_WATERMARK, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_WATERMARK, value).apply()

    var hdrFusion: Boolean
        get() = prefs.getBoolean(KEY_HDR, true)
        set(value) = prefs.edit().putBoolean(KEY_HDR, value).apply()

    var superNight: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT, value).apply()

    var portraitBlur: Boolean
        get() = prefs.getBoolean(KEY_PORTRAIT, false)
        set(value) = prefs.edit().putBoolean(KEY_PORTRAIT, value).apply()

    companion object {
        const val KEY_PALM = "pref_hand_gesture"
        const val KEY_VOICE = "pref_speech_shutter"
        const val KEY_AI_SCENE = "pref_camera_ai_scene_mode_key"
        const val KEY_AI_BEAUTY = "pref_camera_face_beauty_advanced_key"
        const val KEY_AI_WATERMARK = "pref_ai_watermark"
        const val KEY_HDR = "pref_camera_hdr_key"
        const val KEY_NIGHT = "pref_camera_super_night_mode"
        const val KEY_PORTRAIT = "pref_camera_portrait_blur"
    }
}
