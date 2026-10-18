package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.location.Location
import android.media.ExifInterface
import com.example.core.preferences.AspectRatioMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Result of processing a captured JPEG payload for a target aspect ratio, orientation, and mirror state.
 */
data class ProcessedPhotoPayload(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val wasRecompressed: Boolean,
    val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL
)

/**
 * Utility for calculating aspect-ratio crop bounds, cropping/orienting captured image bitmaps,
 * avoiding unnecessary JPEG recompression when native dimensions already match, and preserving EXIF metadata.
 */
object AspectCropUtils {

    private val PRESERVED_EXIF_TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        "PhotographicSensitivity",
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_COLOR_SPACE,
        "LensMake",
        "LensModel",
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE
    )

    /**
     * Calculates the center-crop rectangle within a source frame of [srcWidth] x [srcHeight]
     * for the given [aspectRatioMode].
     */
    fun calculateCropRect(
        srcWidth: Int,
        srcHeight: Int,
        aspectRatioMode: AspectRatioMode
    ): Rect {
        if (srcWidth <= 0 || srcHeight <= 0) return Rect(0, 0, 0, 0)

        val isPortrait = srcHeight >= srcWidth
        val targetRatio = when (aspectRatioMode) {
            AspectRatioMode.RATIO_1_1 -> 1.0f
            AspectRatioMode.RATIO_4_3 -> if (isPortrait) 3f / 4f else 4f / 3f
            AspectRatioMode.RATIO_9_16 -> if (isPortrait) 9f / 16f else 16f / 9f
        }

        val currentRatio = srcWidth.toFloat() / srcHeight.toFloat()

        val cropWidth: Int
        val cropHeight: Int

        if (currentRatio > targetRatio) {
            // Source is wider than target ratio: constrain by height
            cropHeight = srcHeight
            cropWidth = (srcHeight * targetRatio).roundToInt().coerceAtMost(srcWidth)
        } else {
            // Source is taller than target ratio: constrain by width
            cropWidth = srcWidth
            cropHeight = (srcWidth / targetRatio).roundToInt().coerceAtMost(srcHeight)
        }

        val left = ((srcWidth - cropWidth) / 2).coerceAtLeast(0)
        val top = ((srcHeight - cropHeight) / 2).coerceAtLeast(0)

        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    /**
     * Rotates [sourceBitmap] by [rotationDegrees] if non-zero, then crops it
     * to match the target [aspectRatioMode].
     */
    fun cropBitmap(
        sourceBitmap: Bitmap,
        aspectRatioMode: AspectRatioMode,
        rotationDegrees: Int = 0
    ): Bitmap {
        return cropAndOrientBitmap(
            sourceBitmap = sourceBitmap,
            aspectRatioMode = aspectRatioMode,
            rotationDegrees = rotationDegrees,
            mirrorHorizontally = false
        )
    }

    /**
     * Rotates [sourceBitmap] by [rotationDegrees], optionally applies horizontal mirroring
     * (for front selfie camera when mirror setting is active), and crops to [aspectRatioMode].
     */
    fun cropAndOrientBitmap(
        sourceBitmap: Bitmap,
        aspectRatioMode: AspectRatioMode,
        rotationDegrees: Int = 0,
        mirrorHorizontally: Boolean = false
    ): Bitmap {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        if (srcW <= 0 || srcH <= 0) return sourceBitmap

        val normRot = ((rotationDegrees % 360) + 360) % 360
        if (normRot == 0 || normRot == 90 || normRot == 180 || normRot == 270) {
            val orientedW = if (normRot == 90 || normRot == 270) srcH else srcW
            val orientedH = if (normRot == 90 || normRot == 270) srcW else srcH

            val cropRect = calculateCropRect(
                srcWidth = orientedW,
                srcHeight = orientedH,
                aspectRatioMode = aspectRatioMode
            )
            val outW = cropRect.width().coerceAtLeast(1)
            val outH = cropRect.height().coerceAtLeast(1)

            if (normRot == 0 && !mirrorHorizontally && outW == srcW && outH == srcH) {
                return sourceBitmap
            }

            val srcPixels = IntArray(srcW * srcH)
            sourceBitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
            val outPixels = IntArray(outW * outH)
            val cropLeft = cropRect.left
            val cropTop = cropRect.top

            for (oy in 0 until outH) {
                val uy = cropTop + oy
                val outRow = oy * outW
                for (ox in 0 until outW) {
                    val ux = cropLeft + if (mirrorHorizontally) (outW - 1 - ox) else ox
                    val sx: Int
                    val sy: Int
                    when (normRot) {
                        90 -> {
                            sx = uy.coerceIn(0, srcW - 1)
                            sy = (srcH - 1 - ux).coerceIn(0, srcH - 1)
                        }
                        180 -> {
                            sx = (srcW - 1 - ux).coerceIn(0, srcW - 1)
                            sy = (srcH - 1 - uy).coerceIn(0, srcH - 1)
                        }
                        270 -> {
                            sx = (srcW - 1 - uy).coerceIn(0, srcW - 1)
                            sy = ux.coerceIn(0, srcH - 1)
                        }
                        else -> {
                            sx = ux.coerceIn(0, srcW - 1)
                            sy = uy.coerceIn(0, srcH - 1)
                        }
                    }
                    outPixels[outRow + ox] = srcPixels[sy * srcW + sx]
                }
            }

            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
            return outBitmap
        }

        val matrix = Matrix().apply {
            postRotate(normRot.toFloat())
        }
        val orientedBitmap = Bitmap.createBitmap(
            sourceBitmap,
            0,
            0,
            srcW,
            srcH,
            matrix,
            true
        )

        val cropRect = calculateCropRect(
            srcWidth = orientedBitmap.width,
            srcHeight = orientedBitmap.height,
            aspectRatioMode = aspectRatioMode
        )

        val cropped = if (cropRect.width() == orientedBitmap.width && cropRect.height() == orientedBitmap.height) {
            orientedBitmap
        } else {
            Bitmap.createBitmap(
                orientedBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height()
            )
        }

        if (orientedBitmap != sourceBitmap && orientedBitmap != cropped) {
            orientedBitmap.recycle()
        }

        if (!mirrorHorizontally) {
            return cropped
        }

        val w = cropped.width
        val h = cropped.height
        val pixels = IntArray(w * h)
        cropped.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val rowOffset = y * w
            var left = 0
            var right = w - 1
            while (left < right) {
                val tmp = pixels[rowOffset + left]
                pixels[rowOffset + left] = pixels[rowOffset + right]
                pixels[rowOffset + right] = tmp
                left++
                right--
            }
        }
        val mirroredBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mirroredBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        if (cropped != sourceBitmap && !cropped.isRecycled) {
            cropped.recycle()
        }
        return mirroredBitmap
    }

    /**
     * Processes captured hardware JPEG bytes for [aspectRatioMode], [rotationDegrees], and [mirrorHorizontally].
     * - If the captured JPEG already matches the target aspect ratio (within 2px rounding), requires no rotation
     *   in pixels (`rotationDegrees == 0`), and requires no horizontal mirroring, returns the original hardware
     *   JPEG bytes directly without decoding or recompressing (`wasRecompressed = false`).
     * - Otherwise decodes at full unscaled ARGB_8888 quality, applies orientation/mirror/crop, and encodes
     *   at [jpegQuality] (default 95).
     */
    fun processCapturedJpegBytes(
        sourceBytes: ByteArray,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean = false,
        jpegQuality: Int = 95
    ): ProcessedPhotoPayload? {
        if (sourceBytes.isEmpty()) return null

        val normRot = ((rotationDegrees % 360) + 360) % 360
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, boundsOptions)
        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        val isSwapped = normRot == 90 || normRot == 270
        val orientedWidth = if (isSwapped) rawHeight else rawWidth
        val orientedHeight = if (isSwapped) rawWidth else rawHeight

        val cropRect = calculateCropRect(
            srcWidth = orientedWidth,
            srcHeight = orientedHeight,
            aspectRatioMode = aspectRatioMode
        )

        val isNativeAspectMatch = abs(cropRect.width() - orientedWidth) <= 2 &&
            abs(cropRect.height() - orientedHeight) <= 2

        // Fast lossless bypass when no pixel rotation, mirroring, or aspect-ratio cropping is required
        if (normRot == 0 && !mirrorHorizontally && isNativeAspectMatch) {
            return ProcessedPhotoPayload(
                jpegBytes = sourceBytes,
                width = orientedWidth,
                height = orientedHeight,
                wasRecompressed = false,
                exifOrientation = ExifInterface.ORIENTATION_NORMAL
            )
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decodedBitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOptions)
            ?: return null

        try {
            val processedBitmap = cropAndOrientBitmap(
                sourceBitmap = decodedBitmap,
                aspectRatioMode = aspectRatioMode,
                rotationDegrees = normRot,
                mirrorHorizontally = mirrorHorizontally
            )
            try {
                val outStream = ByteArrayOutputStream(sourceBytes.size.coerceAtLeast(65536))
                val safeQuality = jpegQuality.coerceIn(85, 100)
                val compressed = processedBitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, outStream)
                if (!compressed) return null
                return ProcessedPhotoPayload(
                    jpegBytes = outStream.toByteArray(),
                    width = processedBitmap.width,
                    height = processedBitmap.height,
                    wasRecompressed = true,
                    exifOrientation = ExifInterface.ORIENTATION_NORMAL
                )
            } finally {
                if (processedBitmap != decodedBitmap && !processedBitmap.isRecycled) {
                    processedBitmap.recycle()
                }
            }
        } finally {
            if (!decodedBitmap.isRecycled) {
                decodedBitmap.recycle()
            }
        }
    }

    /**
     * Converts a decimal degree coordinate (latitude or longitude) to EXIF rational DMS format
     * `"degrees/1,minutes/1,secondsMicros/10000"`.
     */
    fun formatDecimalDegreesToExifDms(coordinate: Double): String {
        val absCoord = abs(coordinate).coerceIn(0.0, 180.0)
        val degrees = floor(absCoord).toInt()
        val minutesFull = (absCoord - degrees) * 60.0
        val minutes = floor(minutesFull).toInt()
        val seconds = (minutesFull - minutes) * 60.0
        val secondsTenThousandths = (seconds * 10000.0).roundToInt()
        return "$degrees/1,$minutes/1,$secondsTenThousandths/10000"
    }

    /**
     * Copies camera EXIF metadata from [sourceJpegBytes] into [targetExif], updates image dimensions
     * and normal pixel orientation, and attaches valid GPS metadata when [location] is provided.
     */
    fun copyAndEnrichExifMetadata(
        sourceJpegBytes: ByteArray,
        targetExif: ExifInterface,
        finalWidth: Int,
        finalHeight: Int,
        exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        location: Location? = null,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        try {
            val sourceExif = ExifInterface(ByteArrayInputStream(sourceJpegBytes))
            for (tag in PRESERVED_EXIF_TAGS) {
                val value = sourceExif.getAttribute(tag)
                if (!value.isNullOrEmpty()) {
                    targetExif.setAttribute(tag, value)
                }
            }
        } catch (_: Exception) {
            // Source bytes may be synthetic in unit tests without an EXIF header; continue enriching targetExif
        }

        targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        targetExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, finalWidth.toString())
        targetExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, finalHeight.toString())
        targetExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, finalWidth.toString())
        targetExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, finalHeight.toString())

        if (targetExif.getAttribute(ExifInterface.TAG_DATETIME).isNullOrEmpty()) {
            val exifDateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
            targetExif.setAttribute(ExifInterface.TAG_DATETIME, exifDateStr)
            targetExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateStr)
        }

        if (location != null &&
            location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0
        ) {
            targetExif.setAttribute(
                ExifInterface.TAG_GPS_LATITUDE,
                formatDecimalDegreesToExifDms(location.latitude)
            )
            targetExif.setAttribute(
                ExifInterface.TAG_GPS_LATITUDE_REF,
                if (location.latitude >= 0.0) "N" else "S"
            )
            targetExif.setAttribute(
                ExifInterface.TAG_GPS_LONGITUDE,
                formatDecimalDegreesToExifDms(location.longitude)
            )
            targetExif.setAttribute(
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                if (location.longitude >= 0.0) "E" else "W"
            )
            val utcDate = Date(if (location.time > 0L) location.time else timestampMillis)
            val dateFormat = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val timeFormat = SimpleDateFormat("HH/1,mm/1,ss/1", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            targetExif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dateFormat.format(utcDate))
            targetExif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timeFormat.format(utcDate))
        }

        targetExif.saveAttributes()
    }
}

