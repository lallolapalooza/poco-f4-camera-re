package dev.pocof4.camera.gesture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import dev.pocof4.camera.camera.toBitmap
import dev.pocof4.camera.core.Feature
import dev.pocof4.camera.core.FrameListener
import dev.pocof4.camera.core.FrameSource
import dev.pocof4.camera.core.PreviewFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Snapshot of the last palm-detection results, for the status line / log. */
data class PalmGestureStats(
    val active: Boolean = false,
    val recognizerReady: Boolean = false,
    val lastGesture: String? = null,
    val lastScore: Float = 0f,
    val lastEvent: String? = null,
    val triggers: Int = 0,
    val lastTriggerTimestampNs: Long = 0L,
    val lastError: String? = null
)

/**
 * MediaPipe-based replacement for the stock ArcSoft palm shutter.
 *
 * Stock pipeline (`decompiled2/sources/com/android/zxing/decoders/HandGestureDecoder.java`):
 * preview frames are throttled to 16 fps (`getDecodeInterval() == 62 ms`) and handed to the
 * native `HandGesture.detectGesture(byte[], w, h, rotation)` engine; a positive detection arms
 * a continuous-interval gate and calls `CameraAction.onShutterButtonClick(100)`, which the timer
 * manager turns into a 3 s countdown before the capture.
 *
 * This class keeps the same frame contract: frames arrive from [FrameSource] on the camera
 * analysis executor (already throttled to 16 fps), are converted to a Bitmap once and classified
 * with the bundled `assets/gesture_recognizer.task` in MediaPipe IMAGE mode. Work is posted to a
 * private single-thread executor so the analysis thread never waits on inference; when inference
 * is still busy the newest frame is dropped, mirroring `STRATEGY_KEEP_ONLY_LATEST`.
 *
 * The only shutter side effect is [onTrigger]; everything else is exposed through [stats].
 */
class PalmGestureDetector(
    context: Context,
    private val frameSource: FrameSource,
    private val onTrigger: () -> Unit
) : FrameListener, Feature {

    private val appContext: Context = context.applicationContext
    private val machine = PalmHoldStateMachine()

    /** Serialises recognizer creation, inference and release; never used by the analysis thread. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    /** One in-flight detection at a time; later frames are dropped. */
    private val frameInFlight = AtomicBoolean(false)

    @Volatile
    private var running = false

    @Volatile
    private var recognizer: GestureRecognizer? = null

    /** Set after a failed model load; retried on the next [start]. */
    @Volatile
    private var recognizerUnavailable = false

    @Volatile
    var stats: PalmGestureStats = PalmGestureStats()
        private set

    override val name: String = "Palm gesture shutter"

    override fun start() {
        if (running) return
        running = true
        recognizerUnavailable = false
        machine.reset()
        frameInFlight.set(false)
        stats = stats.copy(active = true, lastError = null)
        frameSource.addListener(this)
        worker.execute { ensureRecognizer() }
    }

    override fun stop() {
        if (!running) return
        running = false
        frameSource.removeListener(this)
        machine.reset()
        frameInFlight.set(false)
        stats = stats.copy(active = false)
        // Release off the caller thread; queued behind any inference currently running.
        worker.execute { releaseRecognizer() }
    }

    override fun onFrame(frame: PreviewFrame) {
        if (!running) return
        if (!frameInFlight.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (running) {
                    detect(frame)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Palm detection failed", t)
                stats = stats.copy(lastError = t.message ?: t.javaClass.simpleName)
            } finally {
                frameInFlight.set(false)
            }
        }
    }

    private fun detect(frame: PreviewFrame) {
        val detector = ensureRecognizer() ?: return
        val bitmap: Bitmap = try {
            frame.toBitmap(applyRotation = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to convert preview frame", t)
            stats = stats.copy(lastError = "frame conversion: ${t.message}")
            return
        }

        val mpImage = try {
            BitmapImageBuilder(bitmap).build()
        } catch (t: Throwable) {
            bitmap.recycle()
            Log.w(TAG, "Unable to build MPImage", t)
            stats = stats.copy(lastError = "MPImage: ${t.message}")
            return
        }

        val result = try {
            detector.recognize(
                mpImage,
                ImageProcessingOptions.builder()
                    .setRotationDegrees(frame.rotationDegrees)
                    .build()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Gesture inference failed", t)
            stats = stats.copy(lastError = "inference: ${t.message}")
            null
        } finally {
            try {
                mpImage.close()
            } catch (_: Throwable) {
            }
            bitmap.recycle()
        }
        if (result == null) return

        val category = result.gestures().firstOrNull()?.firstOrNull()
        val gestureName = category?.categoryName()
        val score = category?.score() ?: 0f
        val landmarks = result.landmarks().firstOrNull()

        // The classifier is the primary decision; landmarks only veto a mislabelled closed hand.
        val poseLooksOpen = landmarks == null || PalmPoseHeuristics.looksLikeOpenPalm(
            landmarks.map { PalmPoseHeuristics.Point(it.x(), it.y(), it.z()) }
        )
        val acceptedName = if (poseLooksOpen) gestureName else null

        val event = machine.onDetection(frame.timestampNs, acceptedName, score)
        stats = stats.copy(
            lastGesture = gestureName,
            lastScore = score,
            lastEvent = event.name
        )

        if (event == PalmHoldStateMachine.Event.TRIGGERED) {
            if (!running) return
            stats = stats.copy(
                triggers = stats.triggers + 1,
                lastTriggerTimestampNs = frame.timestampNs
            )
            Log.i(TAG, "Palm held for ${PalmHoldStateMachine.DEFAULT_HOLD_NANOS / 1_000_000} ms, firing shutter")
            try {
                onTrigger()
            } catch (t: Throwable) {
                // A consumer failure must not kill the detector thread.
                Log.w(TAG, "Palm trigger callback threw", t)
            }
        }
    }

    private fun ensureRecognizer(): GestureRecognizer? {
        recognizer?.let { return it }
        if (recognizerUnavailable) return null
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(MIN_HAND_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_HAND_CONFIDENCE)
                .setMinTrackingConfidence(MIN_HAND_CONFIDENCE)
                .build()
            GestureRecognizer.createFromOptions(appContext, options).also {
                recognizer = it
                stats = stats.copy(recognizerReady = true, lastError = null)
                Log.i(TAG, "MediaPipe gesture recognizer ready")
            }
        } catch (t: Throwable) {
            recognizerUnavailable = true
            stats = stats.copy(
                recognizerReady = false,
                lastError = "recognizer unavailable: ${t.message}"
            )
            Log.e(TAG, "Cannot create MediaPipe gesture recognizer; palm shutter is inert", t)
            null
        }
    }

    private fun releaseRecognizer() {
        val detector = recognizer
        recognizer = null
        stats = stats.copy(recognizerReady = false)
        if (detector != null) {
            try {
                detector.close()
                Log.i(TAG, "MediaPipe gesture recognizer released")
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing gesture recognizer", t)
            }
        }
    }

    companion object {
        private const val TAG = "PalmGestureDetector"
        private const val THREAD_NAME = "palm-gesture"

        /** Model bundled in `reimpl/app/src/main/assets/gesture_recognizer.task`. */
        const val MODEL_ASSET = "gesture_recognizer.task"

        /** MediaPipe defaults are 0.5; keep them so the canned classifier stays responsive. */
        private const val MIN_HAND_CONFIDENCE = 0.5f
    }
}
