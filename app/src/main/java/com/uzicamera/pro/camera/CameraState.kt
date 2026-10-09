package com.uzicamera.pro.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture

/** Represents the current state of camera controls and recording */
data class CameraState(
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val flashMode: Int = ImageCapture.FLASH_MODE_AUTO,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val isRecording: Boolean = false,
    val recordingElapsedSeconds: Int = 0,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val hasFlash: Boolean = false,
    val isScreenFlashActive: Boolean = false,
    val latestMediaUri: android.net.Uri? = null,
    val isCameraReady: Boolean = false,
    val errorMessage: String? = null,
)

enum class CaptureMode { PHOTO, VIDEO }

enum class FlashMode {
    AUTO, ON, OFF;

    fun toImageCaptureFlashMode(): Int = when (this) {
        AUTO -> ImageCapture.FLASH_MODE_AUTO
        ON   -> ImageCapture.FLASH_MODE_ON
        OFF  -> ImageCapture.FLASH_MODE_OFF
    }

    fun next(): FlashMode = when (this) {
        AUTO -> ON
        ON   -> OFF
        OFF  -> AUTO
    }

    companion object {
        fun fromImageCaptureFlashMode(mode: Int): FlashMode = when (mode) {
            ImageCapture.FLASH_MODE_ON  -> ON
            ImageCapture.FLASH_MODE_OFF -> OFF
            else                        -> AUTO
        }
    }
}
