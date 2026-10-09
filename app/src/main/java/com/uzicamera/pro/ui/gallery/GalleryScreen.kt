package com.uzicamera.pro.ui.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzicamera.pro.R
import com.uzicamera.pro.media.MediaItem
import com.uzicamera.pro.media.MediaStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app media gallery and viewer.
 * Supports direct full-screen preview of latest media, swipe/arrow navigation,
 * pinch-to-zoom for photos, video playback launcher, and grid overview mode.
 */
@Composable
fun GalleryScreen(
    initialUri: Uri? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val mediaList = remember { mutableStateListOf<MediaItem>() }
    var isLoading by remember { mutableStateOf(true) }
    var currentIndex by remember { mutableStateOf(0) }
    // If opened with an image, default to full-screen viewer; otherwise show grid
    var isFullScreen by remember { mutableStateOf(initialUri != null) }

    // Load media asynchronously on IO dispatcher
    LaunchedEffect(initialUri) {
        isLoading = true
        val items = withContext(Dispatchers.IO) {
            val queried = MediaStoreManager.queryAllMedia(context).toMutableList()
            if (initialUri != null && queried.none { it.uri == initialUri }) {
                queried.add(0, MediaItem(initialUri, isVideo = false, dateAdded = System.currentTimeMillis() / 1000))
            }
            queried
        }
        mediaList.clear()
        mediaList.addAll(items)
        if (initialUri != null) {
            val idx = items.indexOfFirst { it.uri == initialUri }
            if (idx >= 0) currentIndex = idx
        }
        isLoading = false
    }

    // Intercept back button
    BackHandler {
        if (isFullScreen && mediaList.isNotEmpty()) {
            isFullScreen = false
        } else {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLoading && mediaList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (mediaList.isEmpty()) {
            EmptyGalleryView(onClose = onClose)
        } else if (isFullScreen) {
            val safeIndex = currentIndex.coerceIn(0, mediaList.lastIndex)
            val currentItem = mediaList[safeIndex]

            FullScreenViewer(
                item = currentItem,
                currentIndex = safeIndex,
                totalCount = mediaList.size,
                onPrevious = {
                    if (safeIndex > 0) currentIndex = safeIndex - 1
                },
                onNext = {
                    if (safeIndex < mediaList.lastIndex) currentIndex = safeIndex + 1
                },
                onSwitchToGrid = { isFullScreen = false },
                onClose = onClose
            )
        } else {
            GalleryGrid(
                items = mediaList,
                onItemClick = { index ->
                    currentIndex = index
                    isFullScreen = true
                },
                onClose = onClose
            )
        }
    }
}

// ─── Full-screen viewer ───────────────────────────────────────────────────────

@Composable
private fun FullScreenViewer(
    item: MediaItem,
    currentIndex: Int,
    totalCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSwitchToGrid: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // Zoom and pan state
    var scale by remember(item.uri) { mutableStateOf(1f) }
    var offsetX by remember(item.uri) { mutableStateOf(0f) }
    var offsetY by remember(item.uri) { mutableStateOf(0f) }

    // Load full-resolution display bitmap asynchronously on IO dispatcher
    val bitmapState = produceState<Bitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) {
            MediaStoreManager.loadFullBitmap(context, item.uri, item.isVideo, maxDimension = 1920)
        }
    }
    val bitmap = bitmapState.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Image / Video display area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.uri) {
                    if (!item.isVideo) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Media item",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                }
            }

            // Big play button overlay for videos
            if (item.isVideo) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(item.uri, "video/*")
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            runCatching { context.startActivity(intent) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "▶",
                        color = Color.White,
                        fontSize = 32.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        // Previous button (left)
        if (currentIndex > 0 && scale <= 1.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
            ) {
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Next button (right)
        if (currentIndex < totalCount - 1 && scale <= 1.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
            ) {
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close / Back button
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Counter (e.g. 1 / 10)
            Text(
                text = "${currentIndex + 1} / $totalCount",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            // Right actions: Grid view and Share
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Share button
                IconButton(
                    onClick = {
                        val mime = if (item.isVideo) "video/*" else "image/*"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, item.uri)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = "Share",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Grid view button
                IconButton(onClick = onSwitchToGrid) {
                    Icon(
                        painter = painterResource(R.drawable.ic_grid),
                        contentDescription = "All Photos",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// ─── Grid view ────────────────────────────────────────────────────────────────

@Composable
private fun GalleryGrid(
    items: List<MediaItem>,
    onItemClick: (Int) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Back to camera",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "Gallery (${items.size})",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(items) { index, item ->
                GridThumbnailTile(item = item, onClick = { onItemClick(index) })
            }
        }
    }
}

@Composable
private fun GridThumbnailTile(item: MediaItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) {
            MediaStoreManager.loadThumbnail(context, item.uri, item.isVideo, size = 250)
        }
    }
    val bmp = bitmapState.value

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF222222))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_gallery),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(28.dp)
            )
        }

        // Video badge
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyGalleryView(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = "Gallery",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No photos or videos yet",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 16.sp
            )
        }
    }
}
