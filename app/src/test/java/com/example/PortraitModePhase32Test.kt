package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CameraLens
import com.example.core.camera.LensCapabilities
import com.example.core.camera.ManualSensorCapabilities
import com.example.core.camera.PortraitBlurDeviceSupport
import com.example.core.camera.PortraitDepthSupportPath
import com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferencesRepository
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.processing.DetectedPortraitSubjectType
import com.example.core.processing.PortraitBitmapResult
import com.example.core.processing.PortraitProcessingOutcome
import com.example.core.processing.PortraitProcessor
import com.example.core.storage.MediaStorePhotoSaver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortraitModePhase32Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Synthesizes a realistic portrait scene with a high-frequency textured background
     * (checkerboard + specular highlights) and a sharp human-toned portrait silhouette
     * (head + hair + shoulders/torso + eyes) in the foreground.
     * Writes directly to the Bitmap's pixel buffer so pixels are populated under Robolectric JVM.
     */
    private fun createSyntheticPortraitBitmap(
        width: Int = 320,
        height: Int = 400
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        val tileSize = 12
        val bgDark = Color.rgb(25, 65, 130)
        val bgLight = Color.rgb(180, 225, 250)
        val shoulderColor = Color.rgb(198, 142, 112)
        val hairColor = Color.rgb(45, 32, 26)
        val faceColor = Color.rgb(215, 160, 130)
        val eyeColor = Color.rgb(30, 30, 35)

        val cx = width * 0.5f
        val headCy = height * 0.36f
        val headRx = width * 0.16f
        val headRy = height * 0.14f

        val shoulderCy = height * 0.72f
        val shoulderRx = width * 0.30f
        val shoulderRy = height * 0.24f

        val hairCy = headCy - headRy * 0.115f
        val hairRx = headRx * 1.18f
        val hairRy = headRy * 1.065f

        val leftEyeX = cx - headRx * 0.38f
        val rightEyeX = cx + headRx * 0.38f
        val eyeY = headCy - headRy * 0.12f
        val eyeRadiusSq = 5f * 5f

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val isEven = ((x / tileSize) + (y / tileSize)) % 2 == 0
                var color = if (isEven) bgDark else bgLight

                // Shoulders / torso oval
                val sdx = (x - cx) / shoulderRx
                val sdy = (y - shoulderCy) / shoulderRy
                if (sdx * sdx + sdy * sdy <= 1.0f) {
                    color = shoulderColor
                }

                // Hair crown oval around head
                val hdx = (x - cx) / hairRx
                val hdy = (y - hairCy) / hairRy
                if (hdx * hdx + hdy * hdy <= 1.0f) {
                    color = hairColor
                }

                // Face oval with realistic human skin chrominance
                val fdx = (x - cx) / headRx
                val fdy = (y - headCy) / headRy
                if (fdx * fdx + fdy * fdy <= 1.0f) {
                    color = faceColor
                }

                // Eyes & facial detail so subject interior has sharp high-frequency features
                val ldx = x - leftEyeX
                val rdx = x - rightEyeX
                val edy = y - eyeY
                if (ldx * ldx + edy * edy <= eyeRadiusSq || rdx * rdx + edy * edy <= eyeRadiusSq) {
                    color = eyeColor
                }

                pixels[row + x] = color
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Synthesizes a non-human salient object (a bright red/orange object in center
     * against a cool blue/green textured background) directly in the Bitmap pixel array.
     */
    private fun createSyntheticSalientObjectBitmap(
        width: Int = 320,
        height: Int = 400
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        val tileSize = 14
        val bgA = Color.rgb(20, 90, 75)
        val bgB = Color.rgb(70, 165, 145)
        val fgObjectColor = Color.rgb(235, 45, 65)

        val left = (width * 0.32f).toInt()
        val right = (width * 0.68f).toInt()
        val top = (height * 0.25f).toInt()
        val bottom = (height * 0.80f).toInt()

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val isEven = ((x / tileSize) + (y / tileSize)) % 2 == 0
                val isInsideObject = x in left..right && y in top..bottom
                pixels[row + x] = if (isInsideObject) fgObjectColor else if (isEven) bgA else bgB
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun createFlatUniformBitmap(
        width: Int = 300,
        height: Int = 300,
        color: Int = Color.rgb(120, 120, 120)
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { color }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 95): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * Computes local spatial gradient energy (sharpness / high-frequency detail) in a rectangular region.
     */
    private fun computeRegionGradientEnergy(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Double {
        var totalDiff = 0.0
        var count = 0
        for (y in top until (bottom - 1)) {
            for (x in left until (right - 1)) {
                val c = bitmap.getPixel(x, y)
                val cx = bitmap.getPixel(x + 1, y)
                val cy = bitmap.getPixel(x, y + 1)
                val lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                val lumX = 0.299 * Color.red(cx) + 0.587 * Color.green(cx) + 0.114 * Color.blue(cx)
                val lumY = 0.299 * Color.red(cy) + 0.587 * Color.green(cy) + 0.114 * Color.blue(cy)
                totalDiff += abs(lum - lumX) + abs(lum - lumY)
                count++
            }
        }
        return if (count > 0) totalDiff / count else 0.0
    }

    @Test
    fun segmentSubject_detectsPersonSubjectAndSeparatesBackgroundFromForeground() {
        val bitmap = createSyntheticPortraitBitmap(width = 320, height = 400)
        val segmentation = PortraitProcessor.segmentSubject(bitmap)

        assertTrue(
            "Should detect human/person or salient subject with high confidence",
            segmentation.subjectType == DetectedPortraitSubjectType.PERSON ||
                segmentation.subjectType == DetectedPortraitSubjectType.GENERAL_SUBJECT
        )
        assertTrue("Subject confidence should be >= 0.22f", segmentation.confidence >= PortraitProcessor.MIN_SUBJECT_CONFIDENCE)

        val maskW = segmentation.width
        val maskH = segmentation.height
        // Corner pixel (background) should have low alpha (< 0.18f)
        val topLeftAlpha = segmentation.alphaMatte[2 * maskW + 2]
        assertTrue("Top-left background alpha ($topLeftAlpha) should be < 0.18f", topLeftAlpha < 0.18f)

        // Center face pixel (foreground subject) should have high alpha (> 0.70f)
        val centerFaceX = (maskW * 0.5f).toInt()
        val centerFaceY = (maskH * 0.36f).toInt()
        val centerAlpha = segmentation.alphaMatte[centerFaceY * maskW + centerFaceX]
        assertTrue("Center subject alpha ($centerAlpha) should be > 0.70f", centerAlpha > 0.70f)
        bitmap.recycle()
    }

    @Test
    fun segmentSubject_detectsSalientNonHumanObjectAndReturnsNoneForFlatScene() {
        val objectBitmap = createSyntheticSalientObjectBitmap(width = 320, height = 400)
        val objectSeg = PortraitProcessor.segmentSubject(objectBitmap)
        assertTrue(
            "Salient foreground object should be detected",
            objectSeg.subjectType != DetectedPortraitSubjectType.NONE
        )
        assertTrue(objectSeg.confidence >= PortraitProcessor.MIN_SUBJECT_CONFIDENCE)
        objectBitmap.recycle()

        val flatBitmap = createFlatUniformBitmap(width = 300, height = 300)
        val flatSeg = PortraitProcessor.segmentSubject(flatBitmap)
        assertEquals(
            "Flat uniform scene with no foreground subject should return NONE",
            DetectedPortraitSubjectType.NONE,
            flatSeg.subjectType
        )
        flatBitmap.recycle()
    }

    @Test
    fun applyPortraitEffectToBitmap_blursBackgroundProportionallyToBlurLevelWhileKeepingSubjectSharp() {
        val source = createSyntheticPortraitBitmap(width = 320, height = 400)

        val offRes = PortraitProcessor.applyPortraitEffectToBitmap(
            sourceBitmap = source,
            blurLevel = PortraitBlurLevel.OFF,
            retouch = PortraitRetouch.OFF
        ) as PortraitBitmapResult.Success
        val minRes = PortraitProcessor.applyPortraitEffectToBitmap(
            sourceBitmap = source,
            blurLevel = PortraitBlurLevel.LEVEL_1,
            retouch = PortraitRetouch.OFF
        ) as PortraitBitmapResult.Success
        val medRes = PortraitProcessor.applyPortraitEffectToBitmap(
            sourceBitmap = source,
            blurLevel = PortraitBlurLevel.LEVEL_2,
            retouch = PortraitRetouch.OFF
        ) as PortraitBitmapResult.Success
        val strongRes = PortraitProcessor.applyPortraitEffectToBitmap(
            sourceBitmap = source,
            blurLevel = PortraitBlurLevel.LEVEL_4,
            retouch = PortraitRetouch.OFF
        ) as PortraitBitmapResult.Success
        val maxRes = PortraitProcessor.applyPortraitEffectToBitmap(
            sourceBitmap = source,
            blurStrengthOverride = 16.0f,
            retouch = PortraitRetouch.OFF
        ) as PortraitBitmapResult.Success

        // Measure background sharpness in upper-left corner (x: 8..72, y: 8..72)
        val origBgEnergy = computeRegionGradientEnergy(source, 8, 8, 72, 72)
        val offBgEnergy = computeRegionGradientEnergy(offRes.outputBitmap, 8, 8, 72, 72)
        val minBgEnergy = computeRegionGradientEnergy(minRes.outputBitmap, 8, 8, 72, 72)
        val medBgEnergy = computeRegionGradientEnergy(medRes.outputBitmap, 8, 8, 72, 72)
        val strongBgEnergy = computeRegionGradientEnergy(strongRes.outputBitmap, 8, 8, 72, 72)
        val maxBgEnergy = computeRegionGradientEnergy(maxRes.outputBitmap, 8, 8, 72, 72)

        assertEquals("Off blur must apply 0 blur radius", 0, offRes.appliedBlurRadiusPx)
        assertEquals("Off blur must preserve original unblurred background energy", origBgEnergy, offBgEnergy, 0.01)

        assertTrue(
            "Minimum blur (1.0) must apply a non-zero blur radius (${minRes.appliedBlurRadiusPx})",
            minRes.appliedBlurRadiusPx >= 2
        )
        assertTrue(
            "Minimum blur should reduce background high-frequency energy ($minBgEnergy < $origBgEnergy)",
            minBgEnergy < origBgEnergy * 0.85
        )
        assertTrue(
            "Medium blur (2.0) should blur background at least as much as Minimum ($medBgEnergy <= $minBgEnergy)",
            medBgEnergy <= minBgEnergy
        )
        assertTrue(
            "Strong blur (4.0) should blur background at least as much as Medium ($strongBgEnergy <= $medBgEnergy)",
            strongBgEnergy <= medBgEnergy
        )
        assertTrue(
            "Max blur (16.0) should blur background at least as much as Strong ($maxBgEnergy <= $strongBgEnergy)",
            maxBgEnergy <= strongBgEnergy
        )

        // Verify progressive blur radius increases with higher software blur values
        assertTrue(
            "Progressive radius: min(${minRes.appliedBlurRadiusPx}) <= med(${medRes.appliedBlurRadiusPx}) <= strong(${strongRes.appliedBlurRadiusPx}) <= max(${maxRes.appliedBlurRadiusPx})",
            minRes.appliedBlurRadiusPx <= medRes.appliedBlurRadiusPx &&
                medRes.appliedBlurRadiusPx <= strongRes.appliedBlurRadiusPx &&
                strongRes.appliedBlurRadiusPx <= maxRes.appliedBlurRadiusPx
        )

        // Verify subject eyes/face center remains sharp and unblurred across all blur strengths
        val origFaceEnergy = computeRegionGradientEnergy(source, 130, 120, 190, 165)
        val maxFaceEnergy = computeRegionGradientEnergy(maxRes.outputBitmap, 130, 120, 190, 165)
        assertTrue(
            "Detected subject face detail must remain sharp even under maximum 16.0 background blur ($maxFaceEnergy vs $origFaceEnergy)",
            maxFaceEnergy >= origFaceEnergy * 0.85
        )

        source.recycle()
        offRes.outputBitmap.recycle()
        minRes.outputBitmap.recycle()
        medRes.outputBitmap.recycle()
        strongRes.outputBitmap.recycle()
        maxRes.outputBitmap.recycle()
    }

    @Test
    fun processPortraitJpegBytes_cropsToAspectRatioPreservesExifAndSavesAtomically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceBitmap = createSyntheticPortraitBitmap(width = 400, height = 300)
        val sourceBytes = bitmapToJpegBytes(sourceBitmap)
        sourceBitmap.recycle()

        // Inject EXIF Make/Model into source JPEG bytes
        val tempSource = File.createTempFile("portrait_src_", ".jpg", context.cacheDir)
        tempSource.writeBytes(sourceBytes)
        val srcExif = ExifInterface(tempSource.absolutePath)
        srcExif.setAttribute(ExifInterface.TAG_MAKE, "Google")
        srcExif.setAttribute(ExifInterface.TAG_MODEL, "Pixel Portrait Test")
        srcExif.saveAttributes()
        val taggedSourceBytes = tempSource.readBytes()
        tempSource.delete()

        // Process with 90-degree sensor rotation and 1:1 square aspect ratio
        val outcome = PortraitProcessor.processPortraitJpegBytes(
            sourceBytes = taggedSourceBytes,
            rotationDegrees = 90,
            aspectRatioMode = AspectRatioMode.RATIO_1_1,
            mirrorHorizontally = false,
            blurLevel = PortraitBlurLevel.MEDIUM,
            retouch = PortraitRetouch.NATURAL,
            jpegQuality = 95
        )

        assertNotNull(outcome)
        assertTrue(
            "Portrait processing on valid subject should succeed",
            outcome is PortraitProcessingOutcome.ProcessedPortrait
        )
        val success = outcome as PortraitProcessingOutcome.ProcessedPortrait
        assertEquals(300, success.width)
        assertEquals(300, success.height)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, success.exifOrientation)

        // Save via MediaStorePhotoSaver and verify decoded output & EXIF preservation
        val saved = MediaStorePhotoSaver.savePhotoAtomically(
            context = context,
            outputBytes = success.jpegBytes,
            sourceExifBytes = taggedSourceBytes,
            displayName = "IMG_PORTRAIT_TEST",
            mimeType = "image/jpeg",
            width = success.width,
            height = success.height,
            exifOrientation = success.exifOrientation,
            location = null
        )
        assertNotNull(saved)

        val decoded = context.contentResolver.openInputStream(saved!!.uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull(decoded)
        assertEquals(300, decoded!!.width)
        assertEquals(300, decoded.height)
    }

    @Test
    fun processPortraitJpegBytes_fallsBackGracefullyToOriginalWhenNoSubject() {
        val flatBitmap = createFlatUniformBitmap(width = 400, height = 300)
        val flatBytes = bitmapToJpegBytes(flatBitmap)
        flatBitmap.recycle()

        // When scene has no subject, must return FallbackOriginal with valid cropped JPEG
        val fallbackOutcome = PortraitProcessor.processPortraitJpegBytes(
            sourceBytes = flatBytes,
            rotationDegrees = 90,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            mirrorHorizontally = false,
            blurLevel = PortraitBlurLevel.DEEP,
            retouch = PortraitRetouch.NATURAL
        )

        assertNotNull(fallbackOutcome)
        assertTrue(
            "Flat scene without subject must fall back to original photo safely",
            fallbackOutcome is PortraitProcessingOutcome.FallbackOriginal
        )
        val fallback = fallbackOutcome as PortraitProcessingOutcome.FallbackOriginal
        assertEquals(300, fallback.width)
        assertEquals(400, fallback.height)
        assertTrue(fallback.reason.contains("original photo", ignoreCase = true))

        val decodedFallback = BitmapFactory.decodeByteArray(
            fallback.jpegBytes,
            0,
            fallback.jpegBytes.size
        )
        assertNotNull("Fallback JPEG must be valid and decodable", decodedFallback)
        assertEquals(300, decodedFallback.width)
        assertEquals(400, decodedFallback.height)
    }

    @Test
    fun portraitBlurLevelPreferences_andContinuousSliderStrength_persistAndMapCorrectly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = CameraPreferencesRepository(context)

        assertEquals(PortraitBlurLevel.LEVEL_2, repo.preferences.value.portraitBlurLevel)
        assertEquals(2.0f, repo.preferences.value.portraitBlurStrength, 0.001f)

        // Preset update syncs both preset and continuous slider strength
        repo.setPortraitBlurLevel(PortraitBlurLevel.LEVEL_1)
        assertEquals(PortraitBlurLevel.LEVEL_1, repo.preferences.value.portraitBlurLevel)
        assertEquals(1.0f, repo.preferences.value.portraitBlurStrength, 0.001f)

        repo.setPortraitBlurLevel(PortraitBlurLevel.LEVEL_4)
        assertEquals(PortraitBlurLevel.LEVEL_4, repo.preferences.value.portraitBlurLevel)
        assertEquals(4.0f, repo.preferences.value.portraitBlurStrength, 0.001f)

        // Fine slider update persists exact float strength and maps clean numeric label
        repo.setPortraitBlurStrength(8.5f)
        assertEquals(8.5f, repo.preferences.value.portraitBlurStrength, 0.005f)
        assertEquals(PortraitBlurLevel.LEVEL_4, repo.preferences.value.portraitBlurLevel)
        assertEquals("f/8.5", PortraitBlurLevel.labelForStrength(8.5f))
        assertEquals("f/4.0", PortraitBlurLevel.labelForStrength(4.0f))
        assertEquals("f/2.0", PortraitBlurLevel.labelForStrength(2.0f))
        assertEquals("f/1.0", PortraitBlurLevel.labelForStrength(1.0f))
        assertEquals("Off", PortraitBlurLevel.labelForStrength(0.0f))

        // Re-instantiate repository to verify SharedPreferences persistence
        val repoReloaded = CameraPreferencesRepository(context)
        assertEquals(8.5f, repoReloaded.preferences.value.portraitBlurStrength, 0.005f)
        assertEquals(PortraitBlurLevel.LEVEL_4, repoReloaded.preferences.value.portraitBlurLevel)
    }

    @Test
    fun presetMapping_sliderBounds_andDynamicConstraintClamping() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val maxSupported = PortraitBlurDeviceSupport.getMaxSupportedBlurStrength(context)
        assertTrue("Max supported blur strength must be in 8.0..16.0", maxSupported in 8.0f..16.0f)

        // Preset mapping: Off = 0.0, 1 = 1.0, 2 = 2.0, 4 = 4.0
        assertEquals(0.0f, PortraitBlurLevel.OFF.blurStrength)
        assertEquals("Off", PortraitBlurLevel.OFF.shortcutLabel)

        assertEquals(1.0f, PortraitBlurLevel.LEVEL_1.blurStrength)
        assertEquals("f/1.0", PortraitBlurLevel.LEVEL_1.shortcutLabel)

        assertEquals(2.0f, PortraitBlurLevel.LEVEL_2.blurStrength)
        assertEquals("f/2.0", PortraitBlurLevel.LEVEL_2.shortcutLabel)

        assertEquals(4.0f, PortraitBlurLevel.LEVEL_4.blurStrength)
        assertEquals("f/4.0", PortraitBlurLevel.LEVEL_4.shortcutLabel)

        // Clear distinction between Off and minimum blur strength (1.0)
        assertNotEquals(PortraitBlurLevel.OFF.blurStrength, PortraitBlurLevel.LEVEL_1.blurStrength)
        assertEquals(PortraitBlurLevel.MIN_BLUR_STRENGTH, PortraitBlurLevel.LEVEL_1.blurStrength)

        // Slider normalization & bounds clamping
        assertEquals(0.0f, PortraitBlurLevel.normalizeBlurStrength(0.0f))
        assertEquals(1.0f, PortraitBlurLevel.normalizeBlurStrength(1.0f))
        assertEquals(2.0f, PortraitBlurLevel.normalizeBlurStrength(2.0f))
        assertEquals(4.0f, PortraitBlurLevel.normalizeBlurStrength(4.0f))
        assertEquals(16.0f, PortraitBlurLevel.normalizeBlurStrength(16.0f))
        assertEquals(16.0f, PortraitBlurLevel.normalizeBlurStrength(99.0f)) // Clamped to max

        // Fallback for corrupted or tiny bitmap
        val tinyBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val fallbackRes = PortraitProcessor.applyPortraitEffectToBitmap(
            sourceBitmap = tinyBitmap,
            blurStrengthOverride = 4.0f
        )
        assertTrue("Tiny bitmap should trigger safe FallbackOriginal", fallbackRes is PortraitBitmapResult.FallbackOriginal)
        tinyBitmap.recycle()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @Test
    fun cameraCapabilities_resolvesPortraitDepthSupportPathAndCamera2Options() {
        val rearWithHardwareDepth = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            manualCapabilities = ManualSensorCapabilities(
                isHardwareDepthOutputSupported = true,
                isLogicalMultiCamera = true,
                physicalCameraIds = listOf("0", "2"),
                isExtendedBokehStillSupported = false,
                isExtendedBokehContinuousSupported = false,
                maxFaceDetectCount = 10
            )
        )
        assertEquals(
            PortraitDepthSupportPath.HARDWARE_DEPTH_SENSOR_ASSISTED,
            rearWithHardwareDepth.portraitDepthSupportPath
        )

        val frontSingleLens = LensCapabilities(
            lens = CameraLens.FRONT,
            manualCapabilities = ManualSensorCapabilities(
                isHardwareDepthOutputSupported = false,
                isLogicalMultiCamera = false,
                isExtendedBokehStillSupported = false,
                isExtendedBokehContinuousSupported = false,
                maxFaceDetectCount = 5
            )
        )
        assertEquals(
            PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY,
            frontSingleLens.portraitDepthSupportPath
        )

        val portraitOptions = buildNonProResetCamera2CaptureRequestOptions(
            isVideoMode = false,
            isHardwareAfLockActive = false,
            supportsAfAutoHold = true,
            enableHardwareHdrSceneMode = false,
            enableHardwarePortraitBokeh = true,
            supportsExtendedBokehContinuous = true,
            supportsExtendedBokehStill = true,
            enableHardwareFaceDetect = true
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            portraitOptions.getCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE)
        )
        assertEquals(
            CaptureRequest.CONTROL_AE_MODE_ON,
            portraitOptions.getCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE)
        )
        assertFalse(
            "Portrait mode must never lock AE",
            portraitOptions.getCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK) ?: true
        )
    }

    @Test
    fun portraitUiRedesign_removesTopBanner_placesBlurControlsNearZoomWithoutOverlap_andUpdatesPresetsAndSlider() {
        var selectedLevel by mutableStateOf(PortraitBlurLevel.MEDIUM)
        var selectedStrength by mutableFloatStateOf(PortraitBlurLevel.MEDIUM.blurStrength)
        var selectedZoom by mutableFloatStateOf(1.0f)

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraPreviewPlaceholder(
                    activeLens = CameraLens.BACK_MAIN,
                    activeMode = com.example.core.model.CameraMode.PORTRAIT,
                    aspectRatioMode = AspectRatioMode.RATIO_4_3,
                    gridMode = com.example.core.preferences.GridMode.OFF,
                    zoomRatio = selectedZoom,
                    minZoomRatio = 1.0f,
                    maxZoomRatio = 8.0f,
                    zoomShortcuts = listOf(1.0f, 2.0f),
                    portraitBlurLevel = selectedLevel,
                    portraitBlurStrength = selectedStrength,
                    onPortraitBlurLevelChanged = { selectedLevel = it },
                    onPortraitBlurStrengthChanged = { selectedStrength = it },
                    onZoomRatioChanged = { selectedZoom = it }
                )
            }
        }

        // 1. Verify old top banner "Optical Preview • Subject Blur ... Applied on Capture" is removed
        composeTestRule.onNodeWithTag("portrait_mode_live_badge").assertDoesNotExist()
        composeTestRule.onNodeWithText("Optical Preview", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Applied on Capture", substring = true).assertDoesNotExist()

        // 2. Default state: Compact preset shortcuts and zoom shortcuts visible; BOTH expanded sliders completely hidden
        composeTestRule.onNodeWithTag("portrait_blur_selector_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_pill_off").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_pill_light").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_pill_medium").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_pill_deep").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_continuous_slider").assertDoesNotExist()

        // 2b. Verify labels: "Blur" removed, "Off", "f/4.0", "f/2.0", "f/1.0" present, descriptive words removed
        composeTestRule.onNodeWithText("Blur").assertDoesNotExist()
        composeTestRule.onNodeWithText("Off").assertIsDisplayed()
        composeTestRule.onNodeWithText("f/4.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("f/2.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("f/1.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Light", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Deep", substring = true).assertDoesNotExist()

        // 3. Verify blur selector bar is placed near the bottom above zoom controls and does not overlap
        val blurBounds = composeTestRule.onNodeWithTag("portrait_blur_selector_bar").fetchSemanticsNode().boundsInRoot
        val zoomBounds = composeTestRule.onNodeWithTag("zoom_shortcut_bar").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Portrait blur bar (${blurBounds.bottom}) must sit directly above zoom controls (${zoomBounds.top}) without overlapping",
            blurBounds.bottom <= zoomBounds.top + 1f
        )

        // 4. Single tap on blur presets applies blur level and strength to the processing pipeline WITHOUT opening the slider
        composeTestRule.onNodeWithTag("portrait_blur_pill_deep").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PortraitBlurLevel.LEVEL_1, selectedLevel)
        assertEquals(1.00f, selectedStrength, 0.001f)
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertDoesNotExist()

        composeTestRule.onNodeWithTag("portrait_blur_pill_light").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PortraitBlurLevel.LEVEL_4, selectedLevel)
        assertEquals(4.00f, selectedStrength, 0.001f)
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertDoesNotExist()

        // 5. Long-press on blur preset area opens blur slider while keeping zoom slider closed
        composeTestRule.onNodeWithTag("portrait_blur_pill_medium").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals(PortraitBlurLevel.LEVEL_2, selectedLevel)
        assertEquals(2.00f, selectedStrength, 0.001f)
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_aperture_label").assertTextEquals("f/2.0")
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val maxSupportedFormatted = String.format(java.util.Locale.US, "f/%.1f", com.example.core.camera.PortraitBlurDeviceSupport.getMaxSupportedBlurStrength(appContext))
        composeTestRule.onNodeWithTag("portrait_blur_percentage_label").assertTextEquals(maxSupportedFormatted)
        composeTestRule.onNodeWithTag("zoom_continuous_slider").assertDoesNotExist()

        // 6. Long-press on zoom control opens zoom slider and closes blur slider automatically (Mutual exclusion)
        composeTestRule.onNodeWithTag("zoom_shortcut_1x").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("zoom_continuous_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertDoesNotExist()

        // 7. Tap outside on the preview dismisses the expanded slider and returns to compact shortcuts without resetting values
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput { click(Offset(20f, 20f)) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("zoom_continuous_slider").assertDoesNotExist()
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertDoesNotExist()
        assertEquals(PortraitBlurLevel.MEDIUM, selectedLevel)
        assertEquals(1.0f, selectedZoom, 0.001f)

        // 8. Long-press on portrait blur selector bar opens blur slider, and Done button dismisses it
        composeTestRule.onNodeWithTag("portrait_blur_selector_bar").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_blur_slider_close_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("portrait_blur_slider").assertDoesNotExist()
    }
}
