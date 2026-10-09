package com.uzicamera.pro.camera

import android.app.Application
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uzicamera.pro.media.MediaStoreManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel that owns [CameraController] and exposes [CameraState] as a [StateFlow].
 * The composables observe this state to render the camera UI.
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    val cameraController = CameraController(application)

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var recordingTimerJob: Job? = null

    // ─── Camera binding ───────────────────────────────────────────────────────

    /**
     * Called after the surface provider is set on [cameraController].
     * Re-binds all use cases for current lens / mode.
     */
    fun bindCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        val s = _state.value
        cameraController.bindCamera(
            lifecycleOwner = lifecycleOwner,
            lensFacing = s.lensFacing,
            captureMode = s.captureMode,
            flashMode = s.flashMode,
            onReady = { hasFlash ->
                val zoom = cameraController.getZoomState()
                _state.update {
                    it.copy(
                        isCameraReady = true,
                        hasFlash = hasFlash,
                        errorMessage = null,
                        zoomRatio = zoom?.zoomRatio ?: it.zoomRatio,
                        minZoom = zoom?.minZoomRatio ?: it.minZoom,
                        maxZoom = zoom?.maxZoomRatio ?: it.maxZoom
                    )
                }
                refreshLatestMedia()
            },
            onError = { msg ->
                _state.update { it.copy(errorMessage = msg) }
            }
        )
    }

    // ─── Mode & lens ─────────────────────────────────────────────────────────

    fun setCaptureMode(mode: CaptureMode) {
        if (_state.value.isRecording) return
        _state.update { it.copy(captureMode = mode) }
        // CameraPreview's LaunchedEffect(lensFacing, captureMode) handles rebind automatically
    }

    fun flipCamera() {
        if (_state.value.isRecording) return
        val newFacing = if (_state.value.lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        _state.update { it.copy(lensFacing = newFacing) }
        // CameraPreview's LaunchedEffect(lensFacing, captureMode) handles rebind automatically
    }

    // ─── Flash ───────────────────────────────────────────────────────────────

    fun cycleFlash() {
        val current = FlashMode.fromImageCaptureFlashMode(_state.value.flashMode)
        val next = current.next()
        val newMode = next.toImageCaptureFlashMode()
        cameraController.setFlashMode(newMode)
        _state.update { it.copy(flashMode = newMode) }
    }

    // ─── Zoom ────────────────────────────────────────────────────────────────

    fun onZoomChanged(scaleFactor: Float) {
        val zoomState = cameraController.getZoomState() ?: return
        val newRatio = CameraUtils.clamp(
            _state.value.zoomRatio * scaleFactor,
            zoomState.minZoomRatio,
            zoomState.maxZoomRatio
        )
        cameraController.setZoomRatio(newRatio)
        _state.update {
            it.copy(
                zoomRatio = newRatio,
                minZoom = zoomState.minZoomRatio,
                maxZoom = zoomState.maxZoomRatio
            )
        }
    }

    fun setZoomRatioDirect(ratio: Float) {
        val zoomState = cameraController.getZoomState() ?: return
        val clamped = CameraUtils.clamp(ratio, zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cameraController.setZoomRatio(clamped)
        _state.update { it.copy(zoomRatio = clamped) }
    }

    // ─── Photo capture ───────────────────────────────────────────────────────

    fun takePhoto() {
        if (!_state.value.isCameraReady || _state.value.captureMode != CaptureMode.PHOTO) return
        cameraController.takePhoto(
            onSuccess = { uri ->
                _state.update { it.copy(latestMediaUri = uri) }
            },
            onError = { msg ->
                _state.update { it.copy(errorMessage = msg) }
            }
        )
    }

    // ─── Video recording ─────────────────────────────────────────────────────

    fun startRecording() {
        if (!_state.value.isCameraReady || _state.value.captureMode != CaptureMode.VIDEO) return
        cameraController.startRecording { event ->
            when (event) {
                is RecordingEvent.Started -> {
                    _state.update { it.copy(isRecording = true, recordingElapsedSeconds = 0) }
                    startRecordingTimer()
                }
                is RecordingEvent.Status -> {
                    // Timer job handles elapsed seconds — no-op here
                }
                is RecordingEvent.Saved -> {
                    stopRecordingTimer()
                    _state.update { it.copy(isRecording = false, latestMediaUri = event.uri, recordingElapsedSeconds = 0) }
                }
                is RecordingEvent.Error -> {
                    stopRecordingTimer()
                    _state.update { it.copy(isRecording = false, errorMessage = event.message, recordingElapsedSeconds = 0) }
                }
            }
        }
    }

    fun stopRecording() {
        cameraController.stopRecording()
        stopRecordingTimer()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _state.update { it.copy(recordingElapsedSeconds = it.recordingElapsedSeconds + 1) }
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    // ─── Gallery ─────────────────────────────────────────────────────────────

    fun refreshLatestMedia() {
        viewModelScope.launch {
            val uri = MediaStoreManager.queryLatestMediaUri(getApplication())
            _state.update { it.copy(latestMediaUri = uri) }
        }
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        cameraController.shutdown()
    }
}
