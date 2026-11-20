package com.example.core.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.core.processing.AspectCropUtils
import com.example.core.processing.ProcessedPhotoPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Architectural contract for media persistence using Android 10+ Scoped Storage (MediaStore).
 */
data class SavedMediaItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val timestamp: Long,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0
)

interface MediaStorageContract {
    suspend fun saveImage(bytes: ByteArray, filename: String): SavedMediaItem?
    suspend fun getLatestThumbnail(): Uri?
}

/**
 * Production implementation of [MediaStorageContract] and atomic photo persistence helper.
 * Ensures:
 * - Zero incomplete/orphan files in MediaStore (deletes pending entry on any I/O failure).
 * - Full EXIF orientation, dimension, camera capture tags, and optional GPS metadata preservation.
 */
class MediaStorePhotoSaver(private val context: Context) : MediaStorageContract {

    override suspend fun saveImage(bytes: ByteArray, filename: String): SavedMediaItem? =
        withContext(Dispatchers.IO) {
            savePhotoAtomically(
                context = context,
                outputBytes = bytes,
                sourceExifBytes = bytes,
                displayName = filename,
                mimeType = "image/jpeg",
                width = 0,
                height = 0,
                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                location = null
            )
        }

    override suspend fun getLatestThumbnail(): Uri? = withContext(Dispatchers.IO) {
        queryLatestGalleryMediaUri(context)
    }

    companion object {
        private data class LatestMediaCandidate(
            val uri: Uri,
            val timestampSeconds: Long
        )

        /**
         * Queries MediaStore for the most recently saved photo or video accessible to the app.
         * Safely returns null if the gallery is empty, no media is accessible, or permission is not granted.
         */
        fun queryLatestGalleryMediaUri(context: Context): Uri? {
            val latestImage = queryLatestFromCollection(
                context = context,
                collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
            val latestVideo = queryLatestFromCollection(
                context = context,
                collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )

            return when {
                latestImage == null -> latestVideo?.uri
                latestVideo == null -> latestImage.uri
                latestVideo.timestampSeconds > latestImage.timestampSeconds -> latestVideo.uri
                else -> latestImage.uri
            }
        }

        private fun queryLatestFromCollection(
            context: Context,
            collectionUri: Uri
        ): LatestMediaCandidate? {
            return try {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.MediaColumns.IS_PENDING} = 0"
                } else {
                    null
                }
                val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"

                context.contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (idCol < 0) return null
                    val id = cursor.getLong(idCol)
                    val dateAddedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L
                    val dateModified = if (dateModifiedCol >= 0) cursor.getLong(dateModifiedCol) else 0L
                    val timestamp = if (dateAdded > 0L) dateAdded else dateModified
                    LatestMediaCandidate(
                        uri = ContentUris.withAppendedId(collectionUri, id),
                        timestampSeconds = timestamp
                    )
                }
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Atomically writes a processed photo (JPEG or DNG) into MediaStore, enriches EXIF metadata,
         * and deletes the pending MediaStore row if any error occurs so incomplete files never persist.
         */
        fun savePhotoAtomically(
            context: Context,
            outputBytes: ByteArray,
            sourceExifBytes: ByteArray?,
            displayName: String,
            mimeType: String,
            width: Int,
            height: Int,
            exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
            location: Location? = null,
            timestampMillis: Long = System.currentTimeMillis(),
            isDirectCameraJpeg: Boolean = false
        ): SavedMediaItem? {
            if (outputBytes.isEmpty()) return null

            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (width > 0 && height > 0) {
                    put(MediaStore.MediaColumns.WIDTH, width)
                    put(MediaStore.MediaColumns.HEIGHT, height)
                }
                put(MediaStore.Images.Media.DATE_TAKEN, timestampMillis)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = try {
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (_: Exception) {
                null
            } ?: return null

            var writeSucceeded = false
            try {
                val isJpeg = mimeType.equals("image/jpeg", ignoreCase = true)
                val needsOrientationOrGpsPatch = exifOrientation != ExifInterface.ORIENTATION_NORMAL || location != null

                if (isDirectCameraJpeg && isJpeg) {
                    // Zero-recompression direct camera JPEG path:
                    // Write camera JPEG bytes directly to MediaStore with zero Bitmap decode and zero temp-file copy
                    // when no orientation/GPS patch is needed; otherwise update EXIF in-place on the target descriptor.
                    if (!needsOrientationOrGpsPatch) {
                        val stream = contentResolver.openOutputStream(uri) ?: return null
                        stream.use { out ->
                            out.write(outputBytes)
                            out.flush()
                        }
                    } else {
                        var patchedInPlace = false
                        val stream = contentResolver.openOutputStream(uri) ?: return null
                        stream.use { out ->
                            out.write(outputBytes)
                            out.flush()
                        }
                        try {
                            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                                val targetExif = ExifInterface(pfd.fileDescriptor)
                                targetExif.setAttribute(
                                    ExifInterface.TAG_ORIENTATION,
                                    exifOrientation.toString()
                                )
                                if (location != null &&
                                    location.latitude.isFinite() &&
                                    location.longitude.isFinite() &&
                                    location.latitude in -90.0..90.0 &&
                                    location.longitude in -180.0..180.0
                                ) {
                                    targetExif.setAttribute(
                                        ExifInterface.TAG_GPS_LATITUDE,
                                        AspectCropUtils.formatDecimalDegreesToExifDms(location.latitude)
                                    )
                                    targetExif.setAttribute(
                                        ExifInterface.TAG_GPS_LATITUDE_REF,
                                        if (location.latitude >= 0.0) "N" else "S"
                                    )
                                    targetExif.setAttribute(
                                        ExifInterface.TAG_GPS_LONGITUDE,
                                        AspectCropUtils.formatDecimalDegreesToExifDms(location.longitude)
                                    )
                                    targetExif.setAttribute(
                                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                                        if (location.longitude >= 0.0) "E" else "W"
                                    )
                                }
                                targetExif.saveAttributes()
                                patchedInPlace = true
                            }
                        } catch (_: Throwable) {
                            patchedInPlace = false
                        }

                        if (!patchedInPlace) {
                            val fallbackBytes = enrichViaTempFile(
                                context = context,
                                outputBytes = outputBytes,
                                sourceExifBytes = sourceExifBytes,
                                width = width,
                                height = height,
                                exifOrientation = exifOrientation,
                                location = location,
                                timestampMillis = timestampMillis
                            )
                            val rewriteStream = contentResolver.openOutputStream(uri, "wt")
                                ?: contentResolver.openOutputStream(uri)
                                ?: return null
                            rewriteStream.use { out ->
                                out.write(fallbackBytes)
                                out.flush()
                            }
                        }
                    }
                } else {
                    val bytesToWrite = if (isJpeg && width > 0 && height > 0) {
                        enrichViaTempFile(
                            context = context,
                            outputBytes = outputBytes,
                            sourceExifBytes = sourceExifBytes,
                            width = width,
                            height = height,
                            exifOrientation = exifOrientation,
                            location = location,
                            timestampMillis = timestampMillis
                        )
                    } else {
                        outputBytes
                    }

                    val stream = contentResolver.openOutputStream(uri) ?: return null
                    stream.use { out ->
                        out.write(bytesToWrite)
                        out.flush()
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publishValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, publishValues, null, null)
                }

                writeSucceeded = true
                return SavedMediaItem(
                    uri = uri,
                    displayName = displayName,
                    mimeType = mimeType,
                    timestamp = timestampMillis,
                    sizeBytes = outputBytes.size.toLong(),
                    width = width,
                    height = height
                )
            } catch (_: Exception) {
                return null
            } finally {
                if (!writeSucceeded) {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {}
                }
            }
        }

        private fun enrichViaTempFile(
            context: Context,
            outputBytes: ByteArray,
            sourceExifBytes: ByteArray?,
            width: Int,
            height: Int,
            exifOrientation: Int,
            location: Location?,
            timestampMillis: Long
        ): ByteArray {
            var tempExifFile: java.io.File? = null
            return try {
                tempExifFile = java.io.File.createTempFile("exif_enrich_", ".jpg", context.cacheDir)
                tempExifFile.writeBytes(outputBytes)
                val targetExif = ExifInterface(tempExifFile.absolutePath)
                AspectCropUtils.copyAndEnrichExifMetadata(
                    sourceJpegBytes = sourceExifBytes ?: outputBytes,
                    targetExif = targetExif,
                    finalWidth = width,
                    finalHeight = height,
                    exifOrientation = exifOrientation,
                    location = location,
                    timestampMillis = timestampMillis
                )
                tempExifFile.readBytes()
            } catch (_: Exception) {
                outputBytes
            } finally {
                try {
                    tempExifFile?.delete()
                } catch (_: Exception) {}
            }
        }
    }
}

