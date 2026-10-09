package com.uzicamera.pro.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uzicamera.pro.camera.CaptureMode
import com.uzicamera.pro.camera.CameraViewModel
import com.uzicamera.pro.ui.gallery.GalleryScreen

/**
 * Root camera screen composable.
 * Lays out: full-screen preview → top controls overlay → bottom controls overlay.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(modelClass = CameraViewModel::class.java)
) {
    val state by viewModel.state.collectAsState()
    var isGalleryOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera Preview (fills screen) ─────────────────────────────────────
        CameraPreview(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )

        // ── Error message ────────────────────────────────────────────────────
        state.errorMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp)
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── Recording indicator (top-center when recording) ───────────────────
        if (state.isRecording) {
            RecordingIndicator(
                elapsedSeconds = state.recordingElapsedSeconds,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
            )
        }

        // ── Top Controls (zoom badge on top & flash) ──────────────────────────
        TopControls(
            state = state,
            onFlashToggle = { viewModel.cycleFlash() },
            onResetZoom = { viewModel.setZoomRatioDirect(1f) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        )

        // ── Mode Selector (Photo/Video) ───────────────────────────────────────
        ModeSelector(
            currentMode = state.captureMode,
            onModeChange = { viewModel.setCaptureMode(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        )

        // ── Bottom Controls (shutter, flip, gallery) ──────────────────────────
        BottomControls(
            state = state,
            onShutter = {
                when (state.captureMode) {
                    CaptureMode.PHOTO -> viewModel.takePhoto()
                    CaptureMode.VIDEO -> {
                        if (state.isRecording) viewModel.stopRecording()
                        else viewModel.startRecording()
                    }
                }
            },
            onFlipCamera = { viewModel.flipCamera() },
            onGalleryOpen = { isGalleryOpen = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )

        // ── In-App Gallery Overlay ───────────────────────────────────────────
        if (isGalleryOpen) {
            GalleryScreen(
                initialUri = state.latestMediaUri,
                onClose = {
                    isGalleryOpen = false
                    viewModel.refreshLatestMedia()
                }
            )
        }
    }
}
