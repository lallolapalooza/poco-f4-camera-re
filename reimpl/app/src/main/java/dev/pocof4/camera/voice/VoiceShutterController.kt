package dev.pocof4.camera.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.pocof4.camera.core.Feature
import java.util.Locale

/**
 * Always-listening voice shutter built on [SpeechRecognizer].
 *
 * Stock MiuiCamera uses the vendor wake-word engine
 * (`com.xiaomi.asr.engine.MultiWakeupEngine`, backed by
 * `libmulti-wakeup-engine.so`) and converts a phrase detection into the
 * `com.android.camera.action.SPEECH_SHUTTER` broadcast, which the camera module
 * turns into `onShutterButtonClick(MODE_SPEECH_SHUTTER = 110)`. The public
 * Android SDK exposes neither that engine nor an always-on keyword spotter, so
 * this controller continuously restarts a [SpeechRecognizer] and runs each
 * recognition hypothesis through [VoiceKeywordMatcher].
 *
 * Threading contract:
 *  - [start] and [stop] may be called from any thread. Recognizer creation,
 *    `startListening` and `destroy` are marshalled onto the main looper.
 *  - [onKeyword] is invoked on the main thread (SpeechRecognizer delivers its
 *    callbacks there). The callback must be cheap or hand work to another
 *    dispatcher; it must not block the main thread.
 *
 * Failure behaviour:
 *  - Missing `RECORD_AUDIO` or no installed recognition service makes the
 *    controller *inert*: [isInert] becomes true, nothing is created, no crash.
 *  - Recognition is restarted after every final result / error, with a 300 ms
 *    base delay and exponential backoff capped at 3 s.
 */
class VoiceShutterController(
    private val context: Context,
    private val onKeyword: (String) -> Unit
) : Feature {

    override val name: String = "voice-shutter"

    /**
     * True when the last [start] could not create a working recognizer
     * (permission denied, no recognition service, or the service reported
     * `ERROR_INSUFFICIENT_PERMISSIONS`). The controller then does nothing until
     * [start] is called again.
     */
    @Volatile
    var isInert: Boolean = false
        private set

    /** True between a successful [start] and the matching [stop]. */
    @Volatile
    var isRunning: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val locale: Locale = Locale.getDefault()
    private val matcher = VoiceKeywordMatcher(locale.language)

    private var recognizer: SpeechRecognizer? = null
    private var consecutiveErrors = 0

    private val restartRunnable = Runnable { startListening() }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isRunning) return
            consecutiveErrors = 0
            matcher.beginUtterance()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            if (!isRunning) return
            processHypotheses(results)
            consecutiveErrors = 0
            scheduleRestart(BASE_RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isRunning) return
            // Only the first (highest-confidence) hypothesis is inspected; using
            // the N-best list would let a low-confidence alternative trigger the
            // shutter for speech the user did not actually say.
            processHypotheses(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun start() {
        if (isMainThread()) {
            startInternal()
        } else {
            mainHandler.post { startInternal() }
        }
    }

    override fun stop() {
        isRunning = false
        mainHandler.removeCallbacks(restartRunnable)
        matcher.reset()
        // destroy() must run on the main thread; force=false keeps a recognizer
        // alive if a new start() superseded this stop() in the meantime.
        if (isMainThread()) {
            releaseRecognizer(force = false)
        } else {
            mainHandler.post { releaseRecognizer(force = false) }
        }
    }

    private fun startInternal() {
        if (isRunning) return
        mainHandler.removeCallbacks(restartRunnable)

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            isInert = true
            Log.w(TAG, "RECORD_AUDIO not granted; voice shutter inert")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            isInert = true
            Log.w(TAG, "No speech recognition service; voice shutter inert")
            return
        }

        isInert = false
        releaseRecognizer(force = true)
        val created = createRecognizer()
        if (created == null) return

        recognizer = created
        consecutiveErrors = 0
        isRunning = true
        Log.d(TAG, "started, language=${locale.toLanguageTag()}")
        startListening()
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(recognitionListener)
        }
    } catch (e: RuntimeException) {
        Log.e(TAG, "createSpeechRecognizer failed", e)
        isInert = true
        isRunning = false
        null
    }

    private fun startListening() {
        if (!isRunning) return
        val current = recognizer ?: return
        // A new recognition session is a new utterance: re-arm the matcher.
        matcher.beginUtterance()
        try {
            current.startListening(buildRecognizerIntent())
        } catch (e: SecurityException) {
            // Permission was revoked after start() checked it.
            Log.w(TAG, "startListening denied RECORD_AUDIO; voice shutter inert", e)
            isInert = true
            isRunning = false
            mainHandler.removeCallbacks(restartRunnable)
            releaseRecognizer(force = true)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startListening failed", e)
            scheduleRestart(backoffDelay())
        }
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Hint only. Some providers ignore it; we never require a network.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun processHypotheses(bundle: Bundle?) {
        val hypotheses = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val best = hypotheses.firstOrNull() ?: return
        val keyword = matcher.accept(best) ?: return
        dispatchKeyword(keyword)
    }

    private fun dispatchKeyword(keyword: String) {
        if (isMainThread()) {
            onKeyword(keyword)
        } else {
            mainHandler.post { onKeyword(keyword) }
        }
    }

    private fun handleError(error: Int) {
        if (!isRunning) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Ordinary silence; the next session starts quickly.
                consecutiveErrors = 0
                scheduleRestart(BASE_RESTART_DELAY_MS)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                Log.w(TAG, "ERROR_INSUFFICIENT_PERMISSIONS; voice shutter inert")
                isInert = true
                isRunning = false
                mainHandler.removeCallbacks(restartRunnable)
                releaseRecognizer(force = true)
            }

            else -> {
                // Busy / client / audio / server / network: back off and, after
                // repeated failures, replace the recognizer instance entirely.
                Log.w(TAG, "recognition error $error")
                if (consecutiveErrors >= RECREATE_AFTER_ERRORS) {
                    releaseRecognizer(force = true)
                    recognizer = createRecognizer()
                    consecutiveErrors = 0
                    if (recognizer == null) return
                }
                scheduleRestart(backoffDelay())
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isRunning) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun backoffDelay(): Long {
        consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(MAX_BACKOFF_STEPS)
        return (BASE_RESTART_DELAY_MS shl (consecutiveErrors - 1))
            .coerceAtMost(MAX_RESTART_DELAY_MS)
    }

    /** Must run on the main thread. */
    private fun releaseRecognizer(force: Boolean) {
        if (!force && isRunning) return
        val current = recognizer ?: return
        recognizer = null
        try {
            current.cancel()
        } catch (_: RuntimeException) {
            // Already stopped or destroyed.
        }
        try {
            current.destroy()
        } catch (_: RuntimeException) {
            // Nothing else to release.
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    companion object {
        const val TAG = "VoiceShutter"

        /** Delay before restarting recognition after a normal result / silence. */
        const val BASE_RESTART_DELAY_MS = 300L

        /** Upper bound for the exponential error backoff. */
        const val MAX_RESTART_DELAY_MS = 3_000L

        private const val MAX_BACKOFF_STEPS = 4
        private const val RECREATE_AFTER_ERRORS = 3
    }
}
