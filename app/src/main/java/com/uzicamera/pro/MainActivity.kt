package com.uzicamera.pro

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.uzicamera.pro.ui.camera.CameraScreen
import com.uzicamera.pro.ui.theme.UziCameraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Go edge-to-edge so preview fills the screen
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            UziCameraTheme {
                PermissionGate {
                    CameraScreen()
                }
            }
        }
    }
}

/** Required permissions based on API level */
private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
    }
}.toTypedArray()

/**
 * Handles runtime permission checking and requesting.
 * Shows the camera UI only once all required permissions are granted.
 */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val permissions = remember { requiredPermissions() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var allGranted by remember {
        mutableStateOf(
            permissions.all {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    it
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var showRationale by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        allGranted = results.values.all { it }
        showRationale = !allGranted
    }

    LaunchedEffect(Unit) {
        if (!allGranted) {
            launcher.launch(permissions)
        }
    }

    if (allGranted) {
        content()
    } else {
        PermissionDeniedScreen(
            showRationale = showRationale,
            onRequestAgain = { launcher.launch(permissions) }
        )
    }
}

@Composable
private fun PermissionDeniedScreen(
    showRationale: Boolean,
    onRequestAgain: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flash_off),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = "Camera Permission Required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (showRationale)
                    "uzI Camera Pro needs access to your camera and microphone to capture photos and videos. Please grant the permissions to continue."
                else
                    "Requesting camera and microphone access…",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            if (showRationale) {
                Button(
                    onClick = onRequestAgain,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permissions", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
