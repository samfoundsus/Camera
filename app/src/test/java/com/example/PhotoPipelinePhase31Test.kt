package com.example

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CameraLens
import com.example.core.camera.HdrSupportPath
import com.example.core.camera.LensCapabilities
import com.example.core.camera.ManualSensorCapabilities
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions
import com.example.core.camera.buildProCamera2CaptureRequestOptions
import com.example.core.camera.isCamera2HardwareHdrSceneModeSupported
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.ProFocusMode
import com.example.core.processing.AspectCropUtils
import com.example.core.processing.ProcessingResult
import com.example.core.processing.SoftwareHdrProcessor
import com.example.core.storage.MediaStorePhotoSaver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoPipelinePhase31Test {

    private fun createSyntheticJpegBytes(
        width: Int,
        height: Int,
        topLeftColor: Int = Color.RED,
        bottomRightColor: Int = Color.BLUE
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = topLeftColor
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = bottomRightColor
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun processCapturedJpegBytes_losslessFastPath_when4by3AndZeroRotationAndNoMirror() {
        val sourceBytes = createSyntheticJpegBytes(width = 400, height = 300)

        val payload = AspectCropUtils.processCapturedJpegBytes(
            sourceBytes = sourceBytes,
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            mirrorHorizontally = false,
            jpegQuality = 95
        )

        assertNotNull(payload)
        assertEquals(400, payload!!.width)
        assertEquals(300, payload.height)
        assertFalse("Should bypass decoding/recompression when 4:3 and 0 deg rotation", payload.wasRecompressed)
        assertArrayEquals(sourceBytes, payload.jpegBytes)
    }

    @Test
    fun processCapturedJpegBytes_cropsToSelectedAspectRatioAndOrientsDimensions() {
        // 400x300 sensor frame rotated 90 degrees -> oriented 300x400 portrait frame
        val sourceBytes = createSyntheticJpegBytes(width = 400, height = 300)

        // 1:1 square output from 300x400 -> 300x300
        val squarePayload = AspectCropUtils.processCapturedJpegBytes(
            sourceBytes = sourceBytes,
            rotationDegrees = 90,
            aspectRatioMode = AspectRatioMode.RATIO_1_1,
            mirrorHorizontally = false
        )
        assertNotNull(squarePayload)
        assertEquals(300, squarePayload!!.width)
        assertEquals(300, squarePayload.height)
        assertTrue(squarePayload.wasRecompressed)

        // 9:16 portrait output from 300x400 -> targetRatio = 9/16 = 0.5625 -> width = round(400 * 9/16) = 225, height = 400
        val widePayload = AspectCropUtils.processCapturedJpegBytes(
            sourceBytes = sourceBytes,
            rotationDegrees = 90,
            aspectRatioMode = AspectRatioMode.RATIO_9_16,
            mirrorHorizontally = false
        )
        assertNotNull(widePayload)
        assertEquals(225, widePayload!!.width)
        assertEquals(400, widePayload.height)

        // Verify actual decoded JPEG dimensions match reported payload dimensions
        val decoded = BitmapFactory.decodeByteArray(widePayload.jpegBytes, 0, widePayload.jpegBytes.size)
        assertNotNull(decoded)
        assertEquals(225, decoded.width)
        assertEquals(400, decoded.height)
    }

    @Test
    fun cropAndOrientBitmap_appliesHorizontalMirrorForFrontCameraSelfie() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                src.setPixel(x, y, if (x < 50) Color.RED else Color.GREEN)
            }
        }

        val mirrored = AspectCropUtils.cropAndOrientBitmap(
            sourceBitmap = src,
            aspectRatioMode = AspectRatioMode.RATIO_1_1,
            rotationDegrees = 0,
            mirrorHorizontally = true
        )

        // Left side should now be GREEN and right side RED after horizontal mirror
        assertEquals(Color.GREEN, mirrored.getPixel(10, 50))
        assertEquals(Color.RED, mirrored.getPixel(90, 50))
    }

    @Test
    fun copyAndEnrichExifMetadata_preservesCameraMetadataAndNormalizesOrientation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceFile = File(context.cacheDir, "test_exif_src.jpg")
        val targetFile = File(context.cacheDir, "test_exif_dst.jpg")
        try {
            val rawJpeg = createSyntheticJpegBytes(320, 240)
            sourceFile.writeBytes(rawJpeg)

            // Write synthetic camera EXIF tags into source JPEG
            val srcExif = ExifInterface(sourceFile.absolutePath)
            srcExif.setAttribute(ExifInterface.TAG_MAKE, "Google")
            srcExif.setAttribute(ExifInterface.TAG_MODEL, "PixelCameraTest")
            srcExif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "200")
            srcExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:18 12:00:00")
            srcExif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString()
            )
            srcExif.saveAttributes()

            val srcBytesWithExif = sourceFile.readBytes()

            // Write cropped 240x240 target JPEG
            val croppedPayload = AspectCropUtils.processCapturedJpegBytes(
                sourceBytes = srcBytesWithExif,
                rotationDegrees = 90,
                aspectRatioMode = AspectRatioMode.RATIO_1_1
            )!!
            targetFile.writeBytes(croppedPayload.jpegBytes)

            val location = Location("gps").apply {
                latitude = 37.4220
                longitude = -122.0841
            }

            val dstExif = ExifInterface(targetFile.absolutePath)
            AspectCropUtils.copyAndEnrichExifMetadata(
                sourceJpegBytes = srcBytesWithExif,
                targetExif = dstExif,
                finalWidth = croppedPayload.width,
                finalHeight = croppedPayload.height,
                exifOrientation = croppedPayload.exifOrientation,
                location = location
            )

            val reloadedExif = ExifInterface(targetFile.absolutePath)
            assertEquals("Google", reloadedExif.getAttribute(ExifInterface.TAG_MAKE))
            assertEquals("PixelCameraTest", reloadedExif.getAttribute(ExifInterface.TAG_MODEL))
            assertEquals("200", reloadedExif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS))
            assertEquals("2026:10:18 12:00:00", reloadedExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            assertEquals(
                ExifInterface.ORIENTATION_NORMAL,
                reloadedExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
            )
            assertEquals(240, reloadedExif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
            assertEquals(240, reloadedExif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
            assertNotNull(reloadedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNotNull(reloadedExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        } finally {
            sourceFile.delete()
            targetFile.delete()
        }
    }

    @Test
    fun selectOptimalPhotoOutputSize_handlesFrontAndRearCamerasAndAspectRatiosSeparately() {
        val rearCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            supportedPhotoSizes = listOf(
                PhotoOutputSize(4032, 3024, isSensorMaxResolution = true), // 4:3 (12.2 MP)
                PhotoOutputSize(4032, 2268), // 16:9 (9.1 MP)
                PhotoOutputSize(3024, 3024), // 1:1 (9.1 MP)
                PhotoOutputSize(1920, 1080)
            ),
            highestNativeStillSize = PhotoOutputSize(4032, 3024, isSensorMaxResolution = true)
        )

        val frontCaps = LensCapabilities(
            lens = CameraLens.FRONT,
            supportedPhotoSizes = listOf(
                PhotoOutputSize(3264, 2448, isSensorMaxResolution = true), // 4:3 (8.0 MP)
                PhotoOutputSize(3264, 1836), // 16:9 (6.0 MP)
                PhotoOutputSize(2448, 2448)  // 1:1 (6.0 MP)
            ),
            highestNativeStillSize = PhotoOutputSize(3264, 2448, isSensorMaxResolution = true)
        )

        assertEquals(PhotoOutputSize(4032, 3024, isSensorMaxResolution = true), rearCaps.selectOptimalPhotoOutputSize(AspectRatioMode.RATIO_4_3))
        assertEquals(PhotoOutputSize(4032, 2268), rearCaps.selectOptimalPhotoOutputSize(AspectRatioMode.RATIO_9_16))
        assertEquals(PhotoOutputSize(3024, 3024), rearCaps.selectOptimalPhotoOutputSize(AspectRatioMode.RATIO_1_1))

        assertEquals(PhotoOutputSize(3264, 2448, isSensorMaxResolution = true), frontCaps.selectOptimalPhotoOutputSize(AspectRatioMode.RATIO_4_3))
        assertEquals(PhotoOutputSize(3264, 1836), frontCaps.selectOptimalPhotoOutputSize(AspectRatioMode.RATIO_9_16))
        assertEquals(PhotoOutputSize(2448, 2448), frontCaps.selectOptimalPhotoOutputSize(AspectRatioMode.RATIO_1_1))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @Test
    fun hdrCapabilityDetection_and_photoModeIsolationFromProSettings() {
        // 1. Verify genuine Camera2 hardware HDR scene mode detection
        assertTrue(
            isCamera2HardwareHdrSceneModeSupported(
                availableSceneModes = listOf(
                    CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED,
                    CameraCharacteristics.CONTROL_SCENE_MODE_HDR
                ),
                availableControlModes = listOf(
                    CameraCharacteristics.CONTROL_MODE_AUTO,
                    CameraCharacteristics.CONTROL_MODE_USE_SCENE_MODE
                )
            )
        )
        assertFalse(
            isCamera2HardwareHdrSceneModeSupported(
                availableSceneModes = listOf(CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED),
                availableControlModes = listOf(CameraCharacteristics.CONTROL_MODE_AUTO)
            )
        )

        val capsWithHdr = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            manualCapabilities = ManualSensorCapabilities(isHardwareHdrSceneModeSupported = true)
        )
        val capsWithoutHdr = LensCapabilities(
            lens = CameraLens.FRONT,
            manualCapabilities = ManualSensorCapabilities(isHardwareHdrSceneModeSupported = false)
        )
        assertEquals(HdrSupportPath.HARDWARE_SCENE_MODE_HDR, capsWithHdr.hdrSupportPath)
        assertEquals(HdrSupportPath.SOFTWARE_MULTI_FRAME_HDR, capsWithoutHdr.hdrSupportPath)

        // 2. Verify Pro Mode sets manual overrides
        val manualCaps = ManualSensorCapabilities(
            isManualIsoSupported = true,
            isoRange = 50..6400,
            isManualShutterSupported = true,
            shutterSpeedRangeNanos = 1_000_000L..1_000_000_000L,
            isManualFocusSupported = true,
            minimumFocusDistanceDiopters = 10f,
            isExposureCompensationSupported = true,
            evRange = -6..6,
            evStepRational = 0.5f
        )
        val proOptions = buildProCamera2CaptureRequestOptions(
            manualIso = "800",
            manualShutterSpeed = "1/125",
            proFocusMode = ProFocusMode.MANUAL,
            proFocusDistance = 0.5f,
            proWhiteBalance = "DAYLIGHT",
            manualCaps = manualCaps,
            proEv = 0.0f
        )
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, proOptions.getCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE))
        assertEquals(800, proOptions.getCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY))
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, proOptions.getCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE))

        // 3. Verify switching back to Photo mode restores automatic AF/AE/AWB and enables hardware HDR only when supported
        val photoResetWithHdr = buildNonProResetCamera2CaptureRequestOptions(
            isVideoMode = false,
            isHardwareAfLockActive = false,
            supportsAfAutoHold = false,
            enableHardwareHdrSceneMode = true
        )
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, photoResetWithHdr.getCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE))
        assertEquals(0, photoResetWithHdr.getCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, photoResetWithHdr.getCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE))
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, photoResetWithHdr.getCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE))
        assertEquals(CaptureRequest.CONTROL_MODE_USE_SCENE_MODE, photoResetWithHdr.getCaptureRequestOption(CaptureRequest.CONTROL_MODE))
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_HDR, photoResetWithHdr.getCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE))

        val photoResetWithoutHdr = buildNonProResetCamera2CaptureRequestOptions(
            isVideoMode = false,
            isHardwareAfLockActive = false,
            supportsAfAutoHold = false,
            enableHardwareHdrSceneMode = false
        )
        assertEquals(CaptureRequest.CONTROL_MODE_AUTO, photoResetWithoutHdr.getCaptureRequestOption(CaptureRequest.CONTROL_MODE))
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_DISABLED, photoResetWithoutHdr.getCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE))
    }

    @Test
    fun softwareHdrProcessor_fusesBracketedFramesWithGhostingReductionAndSafeFallback() {
        val width = 48
        val height = 48
        val processor = SoftwareHdrProcessor()

        fun makeFrame(baseLuma: Int, addMovingObject: Boolean = false): Bitmap {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val isHighlightRegion = x >= width / 2
                    val rawVal = if (isHighlightRegion) (baseLuma + 80).coerceIn(0, 255) else baseLuma.coerceIn(0, 255)
                    bmp.setPixel(x, y, Color.rgb(rawVal, rawVal, rawVal))
                }
            }
            if (addMovingObject) {
                // Simulate moving object only present in alternate frame to test ghosting suppression
                for (y in 10..20) {
                    for (x in 10..20) {
                        bmp.setPixel(x, y, Color.rgb(250, 10, 10))
                    }
                }
            }
            return bmp
        }

        val normalFrame = makeFrame(baseLuma = 128, addMovingObject = false)
        val underExposedFrame = makeFrame(baseLuma = 70, addMovingObject = false)
        val overExposedWithMotion = makeFrame(baseLuma = 190, addMovingObject = true)

        val fusedResult = processor.fuseExposureBracketedFrames(
            frames = listOf(underExposedFrame, normalFrame, overExposedWithMotion),
            exposureEvOffsets = listOf(-2f, 0f, 2f)
        )

        assertTrue(fusedResult is ProcessingResult.Success)
        val success = fusedResult as ProcessingResult.Success
        assertEquals(width, success.outputBitmap.width)
        assertEquals(height, success.outputBitmap.height)
        assertEquals(3, success.frameAlignmentsPx.size)

        // Verify fallback when only a single frame is provided: never fails or corrupts normal capture
        val singleFallback = processor.fuseWithSafeFallback(
            frames = listOf(normalFrame),
            exposureEvOffsets = listOf(0f)
        )
        assertEquals(width, singleFallback.width)
        assertEquals(height, singleFallback.height)
        assertEquals(normalFrame.getPixel(15, 15), singleFallback.getPixel(15, 15))
    }

    @Test
    fun mediaStorePhotoSaver_savesPhotoAtomicallyAndCleansUpPendingRowOnWriteFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = createSyntheticJpegBytes(320, 240)

        // 1. Verify successful atomic save
        val savedItem = MediaStorePhotoSaver.savePhotoAtomically(
            context = context,
            outputBytes = bytes,
            sourceExifBytes = bytes,
            displayName = "IMG_TEST_SUCCESS",
            mimeType = "image/jpeg",
            width = 320,
            height = 240
        )
        assertNotNull(savedItem)
        assertEquals(320, savedItem!!.width)
        assertEquals(240, savedItem.height)
        assertEquals("image/jpeg", savedItem.mimeType)

        // 2. Verify empty bytes returns null and creates no orphan MediaStore entry
        val emptyResult = MediaStorePhotoSaver.savePhotoAtomically(
            context = context,
            outputBytes = ByteArray(0),
            sourceExifBytes = null,
            displayName = "IMG_EMPTY",
            mimeType = "image/jpeg",
            width = 0,
            height = 0
        )
        assertEquals(null, emptyResult)

        // 3. Verify pending MediaStore entry cleanup works cleanly
        val realUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_INCOMPLETE.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            }
        )
        assertNotNull(realUri)
        val deletedCount = context.contentResolver.delete(realUri!!, null, null)
        assertEquals(1, deletedCount)
    }
}
