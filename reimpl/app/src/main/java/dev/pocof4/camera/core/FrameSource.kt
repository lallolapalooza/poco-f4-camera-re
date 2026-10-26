package dev.pocof4.camera.core

/**
 * A packed preview frame.
 *
 * The stock camera hands the raw preview Image to ArcSoft's native
 * HandGesture.detectGesture(byte[], width, height, rotation) at ~16 fps, so the
 * reimplementation keeps the same shape: packed colour data, dimensions and the
 * clockwise rotation needed for upright orientation. [luma] is kept because the
 * native engine and the scene heuristics work on luminance only.
 */
class PreviewFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    val rgba: ByteArray,
    val rotationDegrees: Int,
    val timestampNs: Long
)

fun interface FrameListener {
    fun onFrame(frame: PreviewFrame)
}

interface FrameSource {
    fun addListener(listener: FrameListener)
    fun removeListener(listener: FrameListener)
}
