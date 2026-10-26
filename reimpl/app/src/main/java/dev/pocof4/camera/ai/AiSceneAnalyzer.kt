package dev.pocof4.camera.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dev.pocof4.camera.camera.toBitmap
import dev.pocof4.camera.core.AiScene
import dev.pocof4.camera.core.Feature
import dev.pocof4.camera.core.FrameListener
import dev.pocof4.camera.core.FrameSource
import dev.pocof4.camera.core.PreviewFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI scene detection for the reimplementation.
 *
 * Stock path (com.android.camera.module.interceptor.camera.AiSceneMultipleASD):
 * every capture result exposes the vendor tag `AI_SCENE_DETECTED`, which the HAL fills
 * with one of the ids in `AiSceneModeConstant`. The interceptor combines it with a
 * face-width rule (HUMAN), a 20-frame face confirmation, a 300 ms change throttle and
 * a 3 s ID-card throttle, then calls `Camera2Module.mAiSceneMgr.consumeAiSceneResult`.
 *
 * The vendor tag does not exist on a CameraX/legacy HAL, so this class derives the
 * scene from the preview frames themselves:
 *  - bundled ML Kit [ImageLabeling] produces label confidences;
 *  - bundled ML Kit [FaceDetection] produces face boxes;
 *  - [LumaStats] produces mean/top/bottom luminance for night and backlight;
 *  - [SceneClassifier] maps that tuple into the stock id space;
 *  - [SceneDebouncer] applies the stock stability/throttle rules.
 *
 * Detection runs on a dedicated single thread; [onFrame] only performs a throttled
 * hand-off, so the camera analysis thread is never blocked. Frames are analysed at
 * most once every [MIN_ANALYSIS_INTERVAL_MS]; while ML Kit is busy further frames are
 * dropped rather than queued.
 */
class AiSceneAnalyzer(
    context: Context,
    private val frameSource: FrameSource,
    private val onScene: (AiScene) -> Unit
) : FrameListener, Feature {

    override val name: String = "AI Scene Detection"

    /**
     * Must be set by the host when the bound camera is the front sensor. Stock
     * suppresses HUMAN in that case (AiSceneMultipleASD.acceptResult line 127).
     */
    @Volatile
    var isFrontCamera: Boolean = false

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ai-scene-analyzer").apply { isDaemon = true }
    }
    private val classifier = SceneClassifier()
    private val debouncer = SceneDebouncer()
    private val pending = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    @Volatile
    private var started = false

    @Volatile
    private var lastAnalysisMs = 0L

    @Volatile
    private var labeler: ImageLabeler? = null

    @Volatile
    private var faceDetector: FaceDetector? = null

    // The application context is kept for parity with the stock feature wiring; the
    // ML Kit clients are context-free in the bundled artifacts.
    private val appContext = context.applicationContext

    override fun start() {
        if (started) return
        started = true
        lastAnalysisMs = SystemClock.elapsedRealtime() - MIN_ANALYSIS_INTERVAL_MS
        debouncer.reset()
        pending.set(false)
        generation.incrementAndGet()
        labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MLKIT_CONFIDENCE_THRESHOLD)
                .build()
        )
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()
        )
        frameSource.addListener(this)
        Log.d(TAG, "AI scene analyzer started (app=${appContext.packageName})")
    }

    override fun stop() {
        if (!started) return
        started = false
        frameSource.removeListener(this)
        generation.incrementAndGet()
        pending.set(false)
        debouncer.reset()
        val oldLabeler = labeler
        val oldDetector = faceDetector
        labeler = null
        faceDetector = null
        executor.execute {
            runCatching { oldLabeler?.close() }
            runCatching { oldDetector?.close() }
        }
    }

    override fun onFrame(frame: PreviewFrame) {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisMs < MIN_ANALYSIS_INTERVAL_MS) return
        if (!pending.compareAndSet(false, true)) return
        lastAnalysisMs = now
        val token = generation.get()
        try {
            executor.execute { analyse(frame, token) }
        } catch (t: Throwable) {
            pending.set(false)
            Log.w(TAG, "Unable to schedule scene analysis", t)
        }
    }

    private fun analyse(frame: PreviewFrame, token: Int) {
        if (!started) {
            release(token)
            return
        }
        val activeLabeler = labeler
        val activeDetector = faceDetector
        if (activeLabeler == null || activeDetector == null) {
            release(token)
            return
        }
        val bitmap: Bitmap = try {
            frame.toBitmap()
        } catch (t: Throwable) {
            Log.w(TAG, "Preview frame conversion failed", t)
            release(token)
            return
        }

        var handedOff = false
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faceTask: Task<List<Face>> = activeDetector.process(image)
            val labelTask: Task<List<ImageLabel>> = activeLabeler.process(image)
            val all: List<Task<*>> = listOf(faceTask, labelTask)
            Tasks.whenAllComplete(all).addOnCompleteListener(executor) {
                try {
                    if (started && generation.get() == token) {
                        val faces = if (faceTask.isSuccessful) faceTask.result else emptyList()
                        val labels =
                            if (labelTask.isSuccessful) labelTask.result else emptyList()
                        emit(bitmap, faces, labels)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Scene analysis failed", t)
                } finally {
                    bitmap.recycle()
                    release(token)
                }
            }
            handedOff = true
        } catch (t: Throwable) {
            Log.w(TAG, "ML Kit request failed", t)
        } finally {
            if (!handedOff) {
                bitmap.recycle()
                release(token)
            }
        }
    }

    private fun emit(bitmap: Bitmap, faces: List<Face>, labels: List<ImageLabel>) {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val stats = LumaStats.fromArgb(pixels, width, height)

        var faceWidthRatio = 0f
        for (face in faces) {
            val box = face.boundingBox
            if (box.width() <= 0) continue
            val ratio = box.width().toFloat() / width
            if (ratio > faceWidthRatio) faceWidthRatio = ratio
        }

        val input = SceneInput(
            labels = labels.map { LabelScore(it.text, it.confidence) },
            meanLuma = stats.mean,
            topLuma = stats.top,
            bottomLuma = stats.bottom,
            faceWidthRatio = faceWidthRatio,
            frameWidth = width,
            frameHeight = height,
            frontCamera = isFrontCamera
        )
        val verdict = classifier.classify(input)
        val scene = debouncer.offer(verdict, SystemClock.elapsedRealtime()) ?: return
        Log.d(TAG, "scene id=${scene.id} label=${scene.label} reason=${verdict.reason}")
        try {
            onScene(scene)
        } catch (t: Throwable) {
            Log.w(TAG, "Scene callback failed", t)
        }
    }

    private fun release(token: Int) {
        if (generation.get() == token) pending.set(false)
    }

    companion object {
        private const val TAG = "AiSceneAnalyzer"

        /** ~2.5 analyses per second; the debouncer needs 300 ms between observations. */
        const val MIN_ANALYSIS_INTERVAL_MS = 400L

        /** Low threshold: the classifier applies its own per-label thresholds. */
        private const val MLKIT_CONFIDENCE_THRESHOLD = 0.4f

        /** ML Kit minimum face size is a fraction of the image's shorter side. */
        private const val MIN_FACE_SIZE = 0.05f
    }
}
