package dev.pocof4.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import dev.pocof4.camera.core.FrameListener
import dev.pocof4.camera.core.FrameSource
import dev.pocof4.camera.core.PreviewFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * CameraX host for the reimplementation.
 *
 * Two details are deliberately modelled on the stock app:
 *  - analysis is throttled to ~16 fps (HandGestureDecoder.getDecodeInterval() == 62 ms);
 *  - exposure bracketing is requested through Camera2Interop, because the stock
 *    HDR/SuperNight pipelines run on bracketed RAW/YUV frames.
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) : FrameSource {

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<FrameListener>()

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    @Volatile
    private var lastFrameNs = 0L

    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                preview = previewUseCase
                val capture = buildImageCapture(0)
                imageCapture = capture
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    buildAnalysis(),
                    capture
                )
                onReady?.invoke()
            } catch (t: Throwable) {
                onError?.invoke(t.message ?: "Unable to start camera")
            }
        }, mainExecutor)
    }

    private fun buildAnalysis(): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { proxy -> dispatch(proxy) }
            }

    private fun buildImageCapture(exposureCompensation: Int): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                exposureCompensation
            )
        return builder.build()
    }

    private fun dispatch(image: ImageProxy) {
        try {
            val now = image.imageInfo.timestamp
            if (now - lastFrameNs < FRAME_INTERVAL_NS) return
            lastFrameNs = now
            val frame = image.toPreviewFrame() ?: return
            for (listener in listeners) {
                try {
                    listener.onFrame(frame)
                } catch (_: Throwable) {
                    // A misbehaving listener must not kill the analysis pipeline.
                }
            }
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toPreviewFrame(): PreviewFrame? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = width
        val height = height
        if (width <= 0 || height <= 0 || pixelStride < 4) return null
        val rgba = ByteArray(width * height * 4)
        val luma = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val base = y.toLong() * rowStride
            if (base >= buffer.limit()) break
            buffer.position(base.toInt())
            val count = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, count)
            var src = 0
            var dst = y * width
            var lumaDst = y * width
            for (x in 0 until width) {
                val r = row[src].toInt() and 0xFF
                val g = row[src + 1].toInt() and 0xFF
                val b = row[src + 2].toInt() and 0xFF
                val a = row[src + 3]
                val out = dst * 4
                rgba[out] = r.toByte()
                rgba[out + 1] = g.toByte()
                rgba[out + 2] = b.toByte()
                rgba[out + 3] = a
                luma[lumaDst] = ((r * 77 + g * 151 + b * 28) shr 8).toByte()
                src += pixelStride
                dst++
                lumaDst++
            }
        }
        return PreviewFrame(
            width,
            height,
            luma,
            rgba,
            imageInfo.rotationDegrees,
            imageInfo.timestamp
        )
    }

    /**
     * Captures [count] JPEGs. When [exposureCompensations] is non-null the camera
     * use cases are rebound for each step so real AE bracketing is applied.
     */
    fun captureBurst(
        count: Int,
        exposureCompensations: IntArray? = null,
        onDone: (List<Bitmap>) -> Unit
    ) {
        nextCapture(0, count, exposureCompensations, ArrayList(count), onDone)
    }

    private fun nextCapture(
        index: Int,
        count: Int,
        evs: IntArray?,
        results: MutableList<Bitmap>,
        onDone: (List<Bitmap>) -> Unit
    ) {
        if (index >= count) {
            onDone(results)
            return
        }
        if (evs != null) {
            rebindForExposure(evs.getOrElse(index) { 0 })
            // CameraX needs a moment after a rebind before the new
            // ImageCapture use case accepts a request.
            Handler(Looper.getMainLooper()).postDelayed({
                takeShot(index, count, evs, results, onDone)
            }, REBIND_SETTLE_MS)
        } else {
            takeShot(index, count, evs, results, onDone)
        }
    }

    private fun takeShot(
        index: Int,
        count: Int,
        evs: IntArray?,
        results: MutableList<Bitmap>,
        onDone: (List<Bitmap>) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onDone(results)
            return
        }
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = image.decodeCapturedJpeg()
                    if (bitmap != null) results.add(bitmap)
                } finally {
                    image.close()
                }
                nextCapture(index + 1, count, evs, results, onDone)
            }

            override fun onError(exception: ImageCaptureException) {
                onDone(results)
            }
        })
    }

    @Synchronized
    private fun rebindForExposure(ev: Int) {
        val cameraProvider = provider ?: return
        val previewUseCase = preview ?: return
        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                buildAnalysis(),
                buildImageCapture(ev).also { imageCapture = it }
            )
        } catch (t: Throwable) {
            onError?.invoke("Exposure rebind failed: ${t.message}")
        }
    }

    /**
     * Decodes the JPEG returned by [takePicture], applying EXIF/rotation.
     *
     * Deliberately NOT named `toBitmap`: [ImageProxy] already declares a default
     * `toBitmap()` method, and a member always wins over an extension, so an
     * extension with that name is silently never called.
     */
    private fun ImageProxy.decodeCapturedJpeg(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArrayOutputStream(buffer.remaining())
        val copy = buffer.duplicate()
        copy.rewind()
        val chunk = ByteArray(16 * 1024)
        while (copy.hasRemaining()) {
            val n = minOf(copy.remaining(), chunk.size)
            copy.get(chunk, 0, n)
            bytes.write(chunk, 0, n)
        }
        val data = bytes.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val exifOriented = applyExifOrientation(data, bitmap)
        if (exifOriented === bitmap) {
            // Some camera HALs (and the emulator) emit a normal EXIF orientation
            // while still requiring the ImageProxy rotation to reach upright.
            val rotation = imageInfo.rotationDegrees
            if (rotation % 360 != 0) {
                bitmap = rotateBitmap(bitmap, rotation)
            }
        } else {
            bitmap = exifOriented
        }
        // The activity is locked to portrait, so a landscape buffer cannot match the
        // preview. Some HALs (and the emulator) report rotation 0 for a landscape
        // sensor frame; rotate it to portrait instead of saving a sideways photo.
        if (bitmap.width > bitmap.height) {
            bitmap = rotateBitmap(bitmap, 90)
        }
        Log.i(TAG, "captured jpeg ${data.size}B rotation=${imageInfo.rotationDegrees} " +
            "out=${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    /**
     * CameraX records the sensor orientation in the JPEG EXIF block instead of
     * rotating pixels. BitmapFactory ignores it, which produced sideways photos
     * and a wrongly oriented watermark; apply it once here.
     */
    private fun applyExifOrientation(jpeg: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun stop() {
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        analysisExecutor.shutdown()
    }

    override fun addListener(listener: FrameListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun removeListener(listener: FrameListener) {
        listeners.remove(listener)
    }

    companion object {
        /** 16 fps, matching HandGestureDecoder.getDecodeInterval(). */
        private const val FRAME_INTERVAL_NS = 62_000_000L

        /** Settle time after rebinding use cases for a bracketed exposure. */
        private const val REBIND_SETTLE_MS = 180L

        private const val TAG = "CameraEngine"
    }
}
