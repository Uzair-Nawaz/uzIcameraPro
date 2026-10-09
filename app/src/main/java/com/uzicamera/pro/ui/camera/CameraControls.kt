package com.uzicamera.pro.ui.camera

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzicamera.pro.R
import com.uzicamera.pro.camera.CameraState
import com.uzicamera.pro.camera.CaptureMode
import com.uzicamera.pro.camera.CameraUtils
import com.uzicamera.pro.camera.FlashMode

// ─── Colors ──────────────────────────────────────────────────────────────────
val controlBg = Color.Black.copy(alpha = 0.45f)
val shutterWhite = Color.White
val recordRed = Color(0xFFFF3B30)

// ─── Top Controls (zoom indicator & flash) ────────────────────────────────────

@Composable
fun TopControls(
    state: CameraState,
    onFlashToggle: () -> Unit,
    onResetZoom: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Zoom indicator badge on top (e.g. 1x, 1.2x, 8x)
        ZoomBadge(
            zoomRatio = state.zoomRatio,
            onClick = onResetZoom
        )

        // Flash button on top right
        if (state.hasFlash) {
            FlashButton(flashMode = state.flashMode, onToggle = onFlashToggle)
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
fun ZoomBadge(
    zoomRatio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val text = if (zoomRatio % 1.0f == 0.0f) {
        "%.0fx".format(zoomRatio)
    } else {
        "%.1fx".format(zoomRatio)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(controlBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun FlashButton(flashMode: Int, onToggle: () -> Unit) {
    val mode = FlashMode.fromImageCaptureFlashMode(flashMode)
    val iconRes = when (mode) {
        FlashMode.AUTO -> R.drawable.ic_flash_auto
        FlashMode.ON   -> R.drawable.ic_flash_on
        FlashMode.OFF  -> R.drawable.ic_flash_off
    }
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .size(48.dp)
            .background(controlBg, CircleShape)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = "Flash: ${mode.name}",
            tint = Color.Unspecified,
            modifier = Modifier.size(28.dp)
        )
    }
}

// ─── Mode Selector (Photo / Video) ────────────────────────────────────────────

@Composable
fun ModeSelector(
    currentMode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(controlBg, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CaptureMode.values().forEach { mode ->
            val selected = mode == currentMode
            val bgColor by animateColorAsState(
                if (selected) Color.White else Color.Transparent
            )
            val textColor by animateColorAsState(
                if (selected) Color.Black else Color.White
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bgColor)
                    .clickable { onModeChange(mode) }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = mode.name,
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ─── Bottom Controls (shutter, flip, gallery) ─────────────────────────────────

@Composable
fun BottomControls(
    state: CameraState,
    onShutter: () -> Unit,
    onFlipCamera: () -> Unit,
    onGalleryOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gallery thumbnail
        GalleryThumbnail(
            uri = state.latestMediaUri,
            onClick = onGalleryOpen,
            modifier = Modifier.size(56.dp)
        )

        // Shutter button
        ShutterButton(
            mode = state.captureMode,
            isRecording = state.isRecording,
            onClick = onShutter
        )

        // Camera flip button
        IconButton(
            onClick = onFlipCamera,
            modifier = Modifier
                .size(56.dp)
                .background(controlBg, CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flip_camera),
                contentDescription = "Switch camera",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun ShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    val innerColor by animateColorAsState(
        targetValue = if (mode == CaptureMode.VIDEO && isRecording) recordRed else shutterWhite
    )
    val innerShape = if (mode == CaptureMode.VIDEO && isRecording)
        RoundedCornerShape(8.dp) else CircleShape

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(if (mode == CaptureMode.VIDEO && isRecording) 30.dp else 64.dp)
                .clip(innerShape)
                .background(innerColor)
        )
    }
}

@Composable
fun GalleryThumbnail(
    uri: Uri?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(controlBg)
            .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            val painter = rememberUriPainter(uri)
            Image(
                painter = painter,
                contentDescription = "Latest media",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_gallery),
                contentDescription = "Gallery",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

// ─── Recording indicator ─────────────────────────────────────────────────────

@Composable
fun RecordingIndicator(
    elapsedSeconds: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Row(
        modifier = modifier
            .background(controlBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(recordRed.copy(alpha = alpha))
        )
        Text(
            text = "REC  ${CameraUtils.formatDuration(elapsedSeconds)}",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─── Helper: URI → Painter via ContentResolver ────────────────────────────────

@Composable
fun rememberUriPainter(uri: Uri): Painter {
    val context = LocalContext.current
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.uzicamera.pro.media.MediaStoreManager.loadThumbnail(context, uri, isVideo = false, size = 150)
                ?: com.uzicamera.pro.media.MediaStoreManager.loadThumbnail(context, uri, isVideo = true, size = 150)
        }
    }
    val bmp = bitmapState.value
    return if (bmp != null) {
        BitmapPainter(bmp.asImageBitmap())
    } else {
        painterResource(R.drawable.ic_gallery)
    }
}
