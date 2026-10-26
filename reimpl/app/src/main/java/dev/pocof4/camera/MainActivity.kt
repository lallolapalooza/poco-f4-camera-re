package dev.pocof4.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.pocof4.camera.ai.AiBeauty
import dev.pocof4.camera.ai.AiSceneAnalyzer
import dev.pocof4.camera.ai.WatermarkRenderer
import dev.pocof4.camera.camera.CameraEngine
import dev.pocof4.camera.camera.PhotoSaver
import dev.pocof4.camera.core.AiScene
import dev.pocof4.camera.core.AppSettings
import dev.pocof4.camera.core.CaptureReason
import dev.pocof4.camera.databinding.ActivityMainBinding
import dev.pocof4.camera.gesture.PalmGestureDetector
import dev.pocof4.camera.photo.HdrFusion
import dev.pocof4.camera.photo.NightFusion
import dev.pocof4.camera.photo.SaliencyBlur
import dev.pocof4.camera.voice.VoiceShutterController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings

    private var engine: CameraEngine? = null
    private var palmDetector: PalmGestureDetector? = null
    private var voiceShutter: VoiceShutterController? = null
    private var sceneAnalyzer: AiSceneAnalyzer? = null

    private val captureLock = AtomicBoolean(false)
    private var countdownJob: Job? = null

    @Volatile
    private var latestScene: AiScene? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            setStatus("Camera permission is required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        binding.shutterButton.setOnClickListener { capture(CaptureReason.BUTTON) }
        binding.settingsButton.setOnClickListener { showSettings() }
        updateHint()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun startCamera() {
        if (engine != null) return
        val cameraEngine = CameraEngine(this, this, binding.previewView)
        engine = cameraEngine
        cameraEngine.onError = { message -> setStatus(message) }
        cameraEngine.onReady = {
            runOnUiThread {
                buildFeatures()
                setStatus("Ready")
            }
        }
        cameraEngine.start()
    }

    private fun buildFeatures() {
        val cameraEngine = engine ?: return
        palmDetector = PalmGestureDetector(this, cameraEngine) { onPalmDetected() }
        voiceShutter = VoiceShutterController(this) { phrase -> onVoiceKeyword(phrase) }
        sceneAnalyzer = AiSceneAnalyzer(this, cameraEngine) { scene ->
            latestScene = scene
            runOnUiThread { renderScene(scene) }
        }
        applyFeatureState()
    }

    private fun applyFeatureState() {
        if (settings.palmShutter) palmDetector?.start() else palmDetector?.stop()
        if (settings.voiceShutter && hasMicPermission()) {
            voiceShutter?.start()
        } else {
            voiceShutter?.stop()
        }
        if (settings.aiScene) sceneAnalyzer?.start() else sceneAnalyzer?.stop()
        if (!settings.aiScene) runOnUiThread { binding.sceneChip.visibility = android.view.View.GONE }
        updateHint()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateHint() {
        val hints = buildList {
            if (settings.palmShutter) add(getString(R.string.palm_tip))
            if (settings.voiceShutter && hasMicPermission()) add(getString(R.string.voice_tip))
        }
        binding.featureHint.text = hints.joinToString("  •  ")
    }

    private fun onPalmDetected() {
        if (countdownJob?.isActive == true) return
        countdownJob = lifecycleScope.launch {
            for (second in 3 downTo 1) {
                setStatus("Palm shutter: timer $second s")
                delay(1000)
            }
            capture(CaptureReason.PALM)
        }
    }

    private fun onVoiceKeyword(phrase: String) {
        setStatus("Voice shutter: \"$phrase\"")
        capture(CaptureReason.VOICE)
    }

    private fun capture(reason: CaptureReason) {
        if (!captureLock.compareAndSet(false, true)) {
            setStatus("Capture already in progress")
            return
        }
        lifecycleScope.launch {
            try {
                setStatus("Capturing (${reason.name.lowercase()})…")
                val frames = when {
                    settings.superNight -> captureFrames(5, null)
                    settings.hdrFusion -> captureFrames(3, intArrayOf(-2, 0, 2))
                    else -> captureFrames(1, null)
                }
                if (frames.isEmpty()) {
                    setStatus("Capture failed")
                    return@launch
                }
                val processed = withContext(Dispatchers.Default) {
                    var output = when {
                        settings.superNight && frames.size >= 2 -> NightFusion.merge(frames)
                        settings.hdrFusion && frames.size >= 2 -> HdrFusion.fuse(frames)
                        else -> frames.first()
                    }
                    if (settings.portraitBlur) output = SaliencyBlur.render(output, 0.7f)
                    if (settings.aiBeauty) output = AiBeauty.apply(output, 0.6f)
                    if (settings.aiWatermark) {
                        output = WatermarkRenderer.render(
                            output,
                            latestScene,
                            System.currentTimeMillis()
                        )
                    }
                    output
                }
                val uri = PhotoSaver.save(this@MainActivity, processed, "PocoF4RE_")
                val mode = when {
                    settings.superNight -> "Super Night"
                    settings.hdrFusion -> "HDR"
                    else -> "Photo"
                }
                setStatus(
                    if (uri != null) {
                        "$mode saved ${processed.width}x${processed.height} " +
                            "(${reason.name.lowercase()})"
                    } else {
                        "Save failed"
                    }
                )
            } finally {
                captureLock.set(false)
            }
        }
    }

    private suspend fun captureFrames(count: Int, evs: IntArray?): List<Bitmap> =
        suspendCancellableCoroutine { continuation ->
            val cameraEngine = engine
            if (cameraEngine == null) {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            cameraEngine.captureBurst(count, evs) { frames ->
                if (continuation.isActive) continuation.resume(frames)
            }
        }

    private fun renderScene(scene: AiScene) {
        if (!scene.isKnown) {
            binding.sceneChip.visibility = android.view.View.GONE
            return
        }
        binding.sceneChip.visibility = android.view.View.VISIBLE
        binding.sceneChip.text = "%s  (%d)".format(scene.label, scene.id)
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }

    private fun showSettings() {
        val labels = arrayOf(
            "Palm shutter",
            "Voice shutter",
            "AI scene detection",
            "AI beauty",
            "AI watermark",
            "HDR fusion",
            "Super Night (MFNR)",
            "Portrait blur (saliency)"
        )
        val checked = booleanArrayOf(
            settings.palmShutter,
            settings.voiceShutter,
            settings.aiScene,
            settings.aiBeauty,
            settings.aiWatermark,
            settings.hdrFusion,
            settings.superNight,
            settings.portraitBlur
        )
        AlertDialog.Builder(this)
            .setTitle("Features")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                settings.palmShutter = checked[0]
                settings.voiceShutter = checked[1]
                settings.aiScene = checked[2]
                settings.aiBeauty = checked[3]
                settings.aiWatermark = checked[4]
                settings.hdrFusion = checked[5]
                settings.superNight = checked[6]
                settings.portraitBlur = checked[7]
                applyFeatureState()
                Toast.makeText(this, "Features updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        palmDetector?.stop()
        voiceShutter?.stop()
        sceneAnalyzer?.stop()
        engine?.stop()
        super.onDestroy()
    }
}
