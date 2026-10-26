package dev.pocof4.camera.core

/**
 * Mirrors com.android.camera.constant.TriggerConstant from the stock app:
 * MODE_SHUTTER_BUTTON=10, MODE_HAND_GESTURE=100, MODE_SPEECH_SHUTTER=110.
 */
enum class CaptureReason(val stockTriggerMode: Int) {
    BUTTON(10),
    PALM(100),
    VOICE(110)
}
