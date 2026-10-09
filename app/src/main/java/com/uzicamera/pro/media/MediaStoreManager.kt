package com.uzicamera.pro.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Locale

/** Handles all MediaStore operations for saving photos and videos */
object MediaStoreManager {

    private const val PHOTO_COLLECTION_DIR = "Pictures/UziCameraPro"
    private const val VIDEO_COLLECTION_DIR = "Movies/UziCameraPro"
    private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

    private fun generateTimestamp(): String =
        SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())

    /**
     * Builds [ImageCapture.OutputFileOptions] targeting MediaStore for a JPEG photo.
     */
    fun getPhotoOutputOptions(context: Context): ImageCapture.OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${generateTimestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_COLLECTION_DIR)
            }
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
    }

    /**
     * Builds [MediaStoreOutputOptions] for CameraX VideoCapture targeting MediaStore.
     */
    fun getVideoOutputOptions(context: Context): MediaStoreOutputOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${generateTimestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_COLLECTION_DIR)
            }
        }
        return MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()
    }

    /**
     * Queries MediaStore for the latest image or video URI saved by this app.
     */
    fun queryLatestMediaUri(context: Context): Uri? {
        val all = queryAllMedia(context)
        if (all.isNotEmpty()) return all.first().uri

        // Fallback to latest image or video
        val imageUri = queryLatestFromCollection(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.DATE_ADDED,
            PHOTO_COLLECTION_DIR
        )
        val videoUri = queryLatestFromCollection(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.DATE_ADDED,
            VIDEO_COLLECTION_DIR
        )
        // Return whichever is newer
        if (imageUri == null) return videoUri
        if (videoUri == null) return imageUri

        val imgDate = getDateAdded(context, imageUri, MediaStore.Images.Media.DATE_ADDED)
        val vidDate = getDateAdded(context, videoUri, MediaStore.Video.Media.DATE_ADDED)
        return if (vidDate > imgDate) videoUri else imageUri
    }

    private fun queryLatestFromCollection(
        context: Context,
        collectionUri: Uri,
        dateColumn: String,
        relativePathDir: String
    ): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%$relativePathDir%")
        } else null
        val sortOrder = "$dateColumn DESC"

        context.contentResolver.query(
            collectionUri, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collectionUri, id.toString())
            }
        }
        return null
    }

    private fun getDateAdded(context: Context, uri: Uri, dateColumn: String): Long {
        context.contentResolver.query(
            uri, arrayOf(dateColumn), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return 0L
    }

    /**
     * Returns all photos and videos saved by this app, sorted newest first.
     * Each item is a [MediaItem] with uri + isVideo flag.
     */
    fun queryAllMedia(context: Context): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        // Query Photos
        queryAllFromCollection(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.DATE_ADDED,
            isVideo = false
        ).let { items.addAll(it) }

        // Query Videos
        queryAllFromCollection(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.DATE_ADDED,
            isVideo = true
        ).let { items.addAll(it) }

        // Sort newest first by dateAdded
        return items.sortedByDescending { it.dateAdded }
    }

    private fun queryAllFromCollection(
        context: Context,
        collectionUri: Uri,
        dateColumn: String,
        isVideo: Boolean
    ): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, dateColumn)

        val prefix = if (isVideo) "VID_%" else "IMG_%"
        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Pair(
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)",
                arrayOf("%UziCameraPro%", prefix)
            )
        } else {
            Pair(
                "(${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)",
                arrayOf("%UziCameraPro%", prefix)
            )
        }

        runCatching {
            context.contentResolver.query(
                collectionUri, projection, selection, selectionArgs, "$dateColumn DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateCol = cursor.getColumnIndexOrThrow(dateColumn)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val date = cursor.getLong(dateCol)
                    val uri = Uri.withAppendedPath(collectionUri, id.toString())
                    result.add(MediaItem(uri, isVideo, date))
                }
            }
        }

        // If specific folder query yielded nothing, fall back to querying latest media items from this collection
        if (result.isEmpty()) {
            runCatching {
                context.contentResolver.query(
                    collectionUri, projection, null, null, "$dateColumn DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dateCol = cursor.getColumnIndexOrThrow(dateColumn)
                    var count = 0
                    while (cursor.moveToNext() && count < 50) {
                        val id = cursor.getLong(idCol)
                        val date = cursor.getLong(dateCol)
                        val uri = Uri.withAppendedPath(collectionUri, id.toString())
                        result.add(MediaItem(uri, isVideo, date))
                        count++
                    }
                }
            }
        }

        return result
    }

    /**
     * Loads a thumbnail bitmap for an image or video safely without blocking or OOM.
     */
    fun loadThumbnail(context: Context, uri: Uri, isVideo: Boolean, size: Int = 300): android.graphics.Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    return@runCatching context.contentResolver.loadThumbnail(
                        uri, android.util.Size(size, size), null
                    )
                } catch (e: Exception) {
                    // Fall back to decoding below
                }
            }
            if (isVideo) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.frameAtTime
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                decodeSampledBitmap(context, uri, size, size)
            }
        }.getOrNull()
    }

    /**
     * Loads a high-quality full display bitmap safely scaled to max screen dimension to avoid OOM.
     */
    fun loadFullBitmap(context: Context, uri: Uri, isVideo: Boolean, maxDimension: Int = 1920): android.graphics.Bitmap? {
        return runCatching {
            if (isVideo) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.frameAtTime
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                decodeSampledBitmap(context, uri, maxDimension, maxDimension)
            }
        }.getOrNull()
    }

    private fun decodeSampledBitmap(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): android.graphics.Bitmap? {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        var inSampleSize = 1
        val (height, width) = options.outHeight to options.outWidth
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize.coerceAtLeast(1)
            this.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }

        return context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions)
        }
    }
}

/** A single photo or video item from MediaStore */
data class MediaItem(
    val uri: Uri,
    val isVideo: Boolean,
    val dateAdded: Long
)

