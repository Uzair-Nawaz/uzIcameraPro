package com.uzicamera.pro.ui.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.uzicamera.pro.camera.CameraViewModel

/**
 * Full-screen camera preview composable backed by [PreviewView].
 * Uses PERFORMANCE mode (TextureView) which avoids black screen on front camera video.
 * Re-binds the surface provider every time lens or capture mode changes.
 */
@Composable
fun CameraPreview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe state so we can react to lens/mode changes
    val state by viewModel.state.collectAsState()

    val previewView = remember {
        PreviewView(context).apply {
            // PERFORMANCE = TextureView — avoids black screen when use cases are rebound
            // especially important for front camera video mode
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Re-set surface provider and rebind camera whenever lens facing or capture mode changes.
    // Using lensFacing + captureMode as keys ensures preview is re-attached after each rebind.
    LaunchedEffect(previewView, state.lensFacing, state.captureMode) {
        viewModel.cameraController.surfaceProvider = previewView.surfaceProvider
        viewModel.bindCamera(lifecycleOwner)
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    viewModel.onZoomChanged(zoom)
                }
            }
    )
}
