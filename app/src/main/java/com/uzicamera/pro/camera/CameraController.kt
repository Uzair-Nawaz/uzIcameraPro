package com.uzicamera.pro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.uzicamera.pro.media.MediaStoreManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "UziCameraController"

/**
 * Manages CameraX lifecycle, use cases (Preview, ImageCapture, VideoCapture),
 * and exposes camera operations. Not a ViewModel — used from CameraViewModel.
 */
class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Track current state for flash decisions during capture
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var currentFlashMode: Int = ImageCapture.FLASH_MODE_AUTO

    // Public surface provider — set by CameraPreview composable
    var surfaceProvider: Preview.SurfaceProvider? = null

    /** Returns true if a front camera is available on this device */
    fun hasFrontCamera(): Boolean = runCatching {
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }.getOrDefault(false)

    /** Returns true if the selected camera has a flash unit */
    fun hasFlashUnit(): Boolean = camera?.cameraInfo?.hasFlashUnit() ?: false

    /**
     * Binds all CameraX use cases for the given [lensFacing] and [captureMode].
     * Must be called from the main thread (or dispatched to it).
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int,
        captureMode: CaptureMode,
        flashMode: Int,
        onReady: (hasFlash: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                val preview = Preview.Builder().build().also { p ->
                    surfaceProvider?.let { p.setSurfaceProvider(it) }
                }

                imageCapture = ImageCapture.Builder()
                    .setFlashMode(flashMode)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HIGHEST,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    ).build()
                videoCapture = VideoCapture.withOutput(recorder)

                provider.unbindAll()

                camera = when (captureMode) {
                    CaptureMode.PHOTO -> provider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageCapture
                    )
                    CaptureMode.VIDEO -> provider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, videoCapture
                    )
                }

                val hasHardwareFlash = runCatching { camera?.cameraInfo?.hasFlashUnit() ?: false }.getOrDefault(false)
                val hasFlash = hasHardwareFlash || (lensFacing == CameraSelector.LENS_FACING_FRONT)
                // Save state for use during capture
                currentLensFacing = lensFacing
                currentFlashMode = flashMode
                if (hasHardwareFlash) {
                    runCatching {
                        // For AUTO mode, torch stays OFF — it fires only at capture time
                        camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
                    }
                }

                // Front camera exposure boost — front sensors are smaller and darker by default.
                // Boost to ~60% of max positive EV so preview and video look bright and natural.
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    runCatching {
                        val exposureState = camera?.cameraInfo?.exposureState
                        if (exposureState?.isExposureCompensationSupported == true) {
                            val maxEv = exposureState.exposureCompensationRange.upper
                            // Use 60% of max positive index, minimum index of 2
                            val targetEv = (maxEv * 0.6f).toInt().coerceAtLeast(2).coerceAtMost(maxEv)
                            Log.d(TAG, "Front camera EV boost: $targetEv / $maxEv")
                            camera?.cameraControl?.setExposureCompensationIndex(targetEv)
                        }
                    }
                }

                onReady(hasFlash)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                onError("Camera initialization failed: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Updates the flash mode on the current ImageCapture use case and controls torch */
    fun setFlashMode(flashMode: Int) {
        currentFlashMode = flashMode
        imageCapture?.flashMode = flashMode
        val hasHardwareFlash = runCatching { camera?.cameraInfo?.hasFlashUnit() ?: false }.getOrDefault(false)
        if (hasHardwareFlash) {
            runCatching {
                // For AUTO mode, torch stays OFF between captures — fires only at capture time
                camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
            }
        }
    }

    /** Sets zoom ratio on the current camera (clamped to supported range) */
    fun setZoomRatio(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /** Returns current min/max zoom range (linear zoom) */
    fun getZoomState(): androidx.camera.core.ZoomState? =
        camera?.cameraInfo?.zoomState?.value

    /**
     * Takes a photo and saves it to MediaStore.
     * Calls [onSuccess] with the saved URI, or [onError] on failure.
     *
     * Front camera + AUTO: briefly enables torch before capture, disables after.
     * No screen flash / blank screen — camera preview stays visible at all times.
     */
    fun takePhoto(
        onSuccess: (android.net.Uri?) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError("Camera not ready")
            return
        }

        val isAutoFlash = currentFlashMode == ImageCapture.FLASH_MODE_AUTO
        val hasHardwareFlash = runCatching { camera?.cameraInfo?.hasFlashUnit() ?: false }.getOrDefault(false)

        // AUTO flash (any camera): turn torch ON just before capture → capture → torch OFF
        // This gives a real blink effect: flash fires while shutter button is pressed, stops after.
        // Flash ON: torch already on continuously — no extra step needed.
        // Flash OFF: torch stays off.
        val needAutoFlashBlink = isAutoFlash && hasHardwareFlash

        if (needAutoFlashBlink) {
            runCatching { camera?.cameraControl?.enableTorch(true) }
        }

        val outputOptions = MediaStoreManager.getPhotoOutputOptions(context)
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Torch OFF after photo saved — AUTO blink complete
                    if (needAutoFlashBlink) {
                        runCatching { camera?.cameraControl?.enableTorch(false) }
                    }
                    Log.d(TAG, "Photo saved: ${output.savedUri}")
                    onSuccess(output.savedUri)
                }
                override fun onError(exception: ImageCaptureException) {
                    // Always restore torch on error too
                    if (needAutoFlashBlink) {
                        runCatching { camera?.cameraControl?.enableTorch(false) }
                    }
                    Log.e(TAG, "Photo capture failed", exception)
                    onError("Photo failed: ${exception.localizedMessage}")
                }
            }
        )
    }

    /**
     * Starts video recording to MediaStore.
     * [onRecordingEvent] receives [RecordingEvent] updates.
     *
     * Flash AUTO or ON (any camera): torch ON when recording starts, OFF when recording stops.
     * Camera preview stays visible at all times — no blank/white screen.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(onRecordingEvent: (RecordingEvent) -> Unit) {
        val capture = videoCapture ?: run {
            onRecordingEvent(RecordingEvent.Error("Camera not ready for video"))
            return
        }

        val hasHardwareFlash = runCatching { camera?.cameraInfo?.hasFlashUnit() ?: false }.getOrDefault(false)
        // Any camera video: torch ON if flash is AUTO or ON — stays on for entire recording
        val enableTorchForVideo = hasHardwareFlash &&
            (currentFlashMode == ImageCapture.FLASH_MODE_ON || currentFlashMode == ImageCapture.FLASH_MODE_AUTO)

        if (enableTorchForVideo) {
            runCatching { camera?.cameraControl?.enableTorch(true) }
        }

        val outputOptions = MediaStoreManager.getVideoOutputOptions(context)
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start  -> onRecordingEvent(RecordingEvent.Started)
                    is VideoRecordEvent.Status -> onRecordingEvent(
                        RecordingEvent.Status(event.recordingStats.recordedDurationNanos)
                    )
                    is VideoRecordEvent.Finalize -> {
                        // Torch OFF when recording ends for AUTO (blink done), stays ON if flash is ON
                        if (enableTorchForVideo && currentFlashMode != ImageCapture.FLASH_MODE_ON) {
                            runCatching { camera?.cameraControl?.enableTorch(false) }
                        }
                        if (event.hasError()) {
                            onRecordingEvent(RecordingEvent.Error("Recording error: ${event.error}"))
                        } else {
                            onRecordingEvent(RecordingEvent.Saved(event.outputResults.outputUri))
                        }
                    }
                    else -> Unit
                }
            }
    }

    /** Stops an active video recording */
    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    /** Unbinds all use cases and shuts down the camera executor */
    fun shutdown() {
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}

/** Sealed events emitted during video recording lifecycle */
sealed class RecordingEvent {
    object Started : RecordingEvent()
    data class Status(val durationNanos: Long) : RecordingEvent()
    data class Saved(val uri: android.net.Uri) : RecordingEvent()
    data class Error(val message: String) : RecordingEvent()
}
