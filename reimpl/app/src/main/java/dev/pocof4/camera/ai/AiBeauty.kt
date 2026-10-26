package dev.pocof4.camera.ai

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * ML Kit backed beauty post-processing for still captures.
 *
 * Stock behaviour: the beauty level chosen in the UI
 * (`pref_camera_face_beauty_advanced_key`, `pref_beautify_skin_smooth_ratio_key`,
 * `pref_beautify_whiten_ratio_key`, ...) is forwarded as vendor capture-request tags
 * and rendered by `libCameraEffectJNI.so` in the camera pipeline. The reimplementation
 * cannot call that library, so faces are detected after the fact with ML Kit and only
 * the visually dominant part of the effect chain is reproduced: soft-mask skin
 * smoothing plus slight brightening (see [BeautyProcessor]).
 *
 * [apply] is blocking (ML Kit Task await) and must be called off the main thread; the
 * app calls it from Dispatchers.Default. Returns a new bitmap and never mutates
 * [bitmap].
 */
object AiBeauty {

    private const val DETECT_TIMEOUT_MS = 2_500L
    private const val MIN_FACE_SIZE = 0.05f

    fun apply(bitmap: Bitmap, strength: Float): Bitmap {
        val amount = strength.coerceIn(0f, 1f)
        val source = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (amount <= 0f || source.width <= 0 || source.height <= 0) return source

        val faces = detectFaces(source)
        if (faces.isEmpty()) return source

        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val mask = SkinMask.build(pixels, width, height, faces)
        var anySkin = false
        for (value in mask) {
            if (value > 0f) {
                anySkin = true
                break
            }
        }
        if (!anySkin) return source

        val processed = BeautyProcessor.apply(pixels, width, height, mask, amount)
        return Bitmap.createBitmap(processed, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun detectFaces(bitmap: Bitmap): List<FaceOval> {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()
        )
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(
                detector.process(image),
                DETECT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            faces.mapNotNull { face ->
                val box = face.boundingBox
                val faceWidth = box.width().toFloat()
                val faceHeight = box.height().toFloat()
                if (faceWidth <= 0f || faceHeight <= 0f) {
                    null
                } else {
                    FaceOval(
                        centerX = box.exactCenterX(),
                        centerY = box.exactCenterY(),
                        radiusX = faceWidth / 2f,
                        radiusY = faceHeight / 2f
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { detector.close() }
        }
    }
}
