package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CameraLens
import com.example.core.camera.LensCapabilities
import com.example.core.camera.ManualSensorCapabilities
import com.example.core.camera.NightSightDeviceSupport
import com.example.core.camera.NightSightSupportPath
import com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions
import com.example.core.camera.isCamera2HardwareNightSceneModeSupported
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.NightSightExposure
import com.example.core.processing.NightSightProcessingOutcome
import com.example.core.processing.NightSightProcessor
import com.example.core.storage.MediaStorePhotoSaver
import com.example.feature.preview.components.CameraPreviewPlaceholder
import com.example.ui.theme.PixelCameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import kotlin.math.sqrt
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightSightPhase33Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Synthesizes a realistic low-light scene with:
     * - Deep shadow background (RGB ~ 22, 24, 30) with optional synthetic sensor read/shot noise
     * - Mid-tone textured architecture block (RGB ~ 55, 60, 70) translated by (shiftX, shiftY)
     * - Bright streetlamp highlight circle (RGB ~ 242, 240, 232) to verify highlight knee protection
     */
    private fun createLowLightSceneBitmap(
        width: Int = 240,
        height: Int = 180,
        shiftX: Int = 0,
        shiftY: Int = 0,
        noiseAmplitude: Int = 0,
        randomSeed: Int = 42,
        movingObjectX: Int? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val rng = Random(randomSeed)

        val lampCx = width * 0.75f + shiftX
        val lampCy = height * 0.25f + shiftY
        val lampRadiusSq = 14f * 14f

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var r = 22
                var g = 25
                var b = 32

                // Textured mid-dark building structure in center-left (shifted by shiftX, shiftY)
                val bx = x - shiftX
                val by = y - shiftY
                if (bx in 40..130 && by in 45..140) {
                    val windowGrid = ((bx / 10) + (by / 10)) % 2 == 0
                    if (windowGrid) {
                        r = 64
                        g = 68
                        b = 76
                    } else {
                        r = 36
                        g = 39
                        b = 46
                    }
                }

                // Bright streetlamp highlight
                val dx = x - lampCx
                val dy = y - lampCy
                if (dx * dx + dy * dy <= lampRadiusSq) {
                    r = 244
                    g = 242
                    b = 235
                }

                // Optional moving object to test ghosting rejection
                if (movingObjectX != null && x in movingObjectX..(movingObjectX + 14) && y in 110..130) {
                    r = 200
                    g = 90
                    b = 70
                }

                if (noiseAmplitude > 0 && (dx * dx + dy * dy > lampRadiusSq)) {
                    r = (r + rng.nextInt(-noiseAmplitude, noiseAmplitude + 1)).coerceIn(0, 255)
                    g = (g + rng.nextInt(-noiseAmplitude, noiseAmplitude + 1)).coerceIn(0, 255)
                    b = (b + rng.nextInt(-noiseAmplitude, noiseAmplitude + 1)).coerceIn(0, 255)
                }

                pixels[row + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 98): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    @Test
    fun nightSightProcessor_liftsLowLightShadowsAndPreservesHighlightsWithoutBlowingOut() {
        val rawLowLight = createLowLightSceneBitmap(width = 240, height = 180, noiseAmplitude = 6)
        val jpegBytes = bitmapToJpegBytes(rawLowLight)

        val outcome = NightSightProcessor.processNightSightJpegBytes(
            sourceBytes = jpegBytes,
            additionalFramesBytes = emptyList(),
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            mirrorHorizontally = false,
            exposure = NightSightExposure.AUTO,
            supportPath = NightSightSupportPath.SENSOR_ENHANCED_INTEGRATION
        )

        assertTrue(outcome is NightSightProcessingOutcome.ProcessedNightSight)
        val processed = outcome as NightSightProcessingOutcome.ProcessedNightSight
        assertEquals(240, processed.width)
        assertEquals(180, processed.height)
        assertEquals(1, processed.framesMerged)

        val outBmp = BitmapFactory.decodeByteArray(processed.jpegBytes, 0, processed.jpegBytes.size)
        assertNotNull(outBmp)

        // Compare shadow brightness before and after
        val origShadowColor = rawLowLight.getPixel(15, 15)
        val outShadowColor = outBmp.getPixel(15, 15)
        val origShadowLuma = 0.299 * Color.red(origShadowColor) + 0.587 * Color.green(origShadowColor) + 0.114 * Color.blue(origShadowColor)
        val outShadowLuma = 0.299 * Color.red(outShadowColor) + 0.587 * Color.green(outShadowColor) + 0.114 * Color.blue(outShadowColor)
        assertTrue(
            "Expected Night Sight shadow luma ($outShadowLuma) to be lifted above original ($origShadowLuma)",
            outShadowLuma > origShadowLuma + 5.0
        )

        // Check streetlamp center highlight is preserved and not clipped to pure 255 white across all channels
        val lampX = (240 * 0.75f).toInt()
        val lampY = (180 * 0.25f).toInt()
        val outLampColor = outBmp.getPixel(lampX, lampY)
        val outLampLuma = 0.299 * Color.red(outLampColor) + 0.587 * Color.green(outLampColor) + 0.114 * Color.blue(outLampColor)
        assertTrue("Expected highlight to stay controlled (<= 250), got $outLampLuma", outLampLuma <= 250.0)
    }

    @Test
    fun nightSightProcessor_multiFrameAlignmentAndFusionReducesNoiseVariance() {
        val frame0 = createLowLightSceneBitmap(width = 200, height = 150, shiftX = 0, shiftY = 0, noiseAmplitude = 18, randomSeed = 101)
        val frame1 = createLowLightSceneBitmap(width = 200, height = 150, shiftX = 3, shiftY = -2, noiseAmplitude = 18, randomSeed = 202)
        val frame2 = createLowLightSceneBitmap(width = 200, height = 150, shiftX = -2, shiftY = 2, noiseAmplitude = 18, randomSeed = 303)

        // Single-frame noisy enhancement
        val singleEnhanced = NightSightProcessor.enhanceSingleFrameLowLight(frame0, NightSightExposure.AUTO)

        // Multi-frame aligned fusion + enhancement
        val (multiFused, shifts) = NightSightProcessor.fuseAndEnhanceMultiFrame(
            frames = listOf(frame0, frame1, frame2),
            exposure = NightSightExposure.AUTO
        )

        assertEquals(3, shifts.size)
        assertEquals(0 to 0, shifts[0])
        // Verify non-zero alignment shift detection for shifted frame1 (dx=3, dy=-2)
        assertTrue("Expected shift[1].first near 3, got ${shifts[1].first}", abs(shifts[1].first - 3) <= 1)
        assertTrue("Expected shift[1].second near -2, got ${shifts[1].second}", abs(shifts[1].second - (-2)) <= 1)

        // Measure pixel-to-pixel spatial variance in uniform dark patch [5..30, 5..30]
        fun computePatchStdDev(bmp: Bitmap): Double {
            val vals = mutableListOf<Double>()
            for (y in 8..28) {
                for (x in 8..28) {
                    val c = bmp.getPixel(x, y)
                    val luma = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                    vals.add(luma)
                }
            }
            val mean = vals.average()
            val variance = vals.map { (it - mean) * (it - mean) }.average()
            return sqrt(variance)
        }

        val singleStdDev = computePatchStdDev(singleEnhanced)
        val multiStdDev = computePatchStdDev(multiFused)

        assertTrue(
            "Expected multi-frame fused noise stdDev ($multiStdDev) < single-frame stdDev ($singleStdDev)",
            multiStdDev < singleStdDev
        )
    }

    @Test
    fun nightSightProcessor_suppressesMotionGhostingForMovingSubject() {
        // Frame 0 has NO moving object at (150, 120); Frame 1 & 2 have a bright moving object at x=150
        val frame0 = createLowLightSceneBitmap(width = 200, height = 150, movingObjectX = null)
        val frame1 = createLowLightSceneBitmap(width = 200, height = 150, movingObjectX = 150)
        val frame2 = createLowLightSceneBitmap(width = 200, height = 150, movingObjectX = 150)

        val (fused, _) = NightSightProcessor.fuseAndEnhanceMultiFrame(
            frames = listOf(frame0, frame1, frame2),
            exposure = NightSightExposure.AUTO
        )

        // At (155, 120), frame0 was dark background (~25), whereas frame1/2 had bright moving object (~200).
        // Motion rejection must reject the moving object from frames 1 & 2 so it doesn't leave a ghost!
        val fusedPixel = fused.getPixel(155, 120)
        assertTrue(
            "Expected moving object ghost to be rejected (Red channel ${Color.red(fusedPixel)} should stay dark < 80)",
            Color.red(fusedPixel) < 80
        )
    }

    @Test
    fun nightSightProcessor_handlesOrientationAspectRatioCropAndSelfieMirror() {
        val source = createLowLightSceneBitmap(width = 240, height = 180)
        val bytes = bitmapToJpegBytes(source)

        // 90 deg rotation + 1:1 aspect ratio + horizontal mirror
        val outcome = NightSightProcessor.processNightSightJpegBytes(
            sourceBytes = bytes,
            additionalFramesBytes = emptyList(),
            rotationDegrees = 90,
            aspectRatioMode = AspectRatioMode.RATIO_1_1,
            mirrorHorizontally = true,
            exposure = NightSightExposure.MAX
        )

        assertTrue(outcome is NightSightProcessingOutcome.ProcessedNightSight)
        val processed = outcome as NightSightProcessingOutcome.ProcessedNightSight
        assertEquals(180, processed.width)
        assertEquals(180, processed.height)
        assertEquals(1, processed.exifOrientation)
    }

    @Test
    fun nightSightProcessor_fallsBackGracefullyWhenExposureOffOrCorruptedBytes() {
        val source = createLowLightSceneBitmap(width = 160, height = 120)
        val validBytes = bitmapToJpegBytes(source)

        // 1. Exposure OFF -> FallbackSingleFrame
        val offOutcome = NightSightProcessor.processNightSightJpegBytes(
            sourceBytes = validBytes,
            additionalFramesBytes = listOf(validBytes),
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            exposure = NightSightExposure.OFF
        )
        assertTrue(offOutcome is NightSightProcessingOutcome.FallbackSingleFrame)
        val fallbackOff = offOutcome as NightSightProcessingOutcome.FallbackSingleFrame
        assertEquals(160, fallbackOff.width)
        assertEquals(120, fallbackOff.height)

        // 2. Corrupted companion frame is safely ignored while primary frame still succeeds
        val corruptedCompanion = ByteArray(32) { 0x13 }
        val partialOutcome = NightSightProcessor.processNightSightJpegBytes(
            sourceBytes = validBytes,
            additionalFramesBytes = listOf(corruptedCompanion, validBytes),
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            exposure = NightSightExposure.AUTO
        )
        assertTrue(partialOutcome is NightSightProcessingOutcome.ProcessedNightSight)
        val partialProcessed = partialOutcome as NightSightProcessingOutcome.ProcessedNightSight
        assertEquals(2, partialProcessed.framesMerged)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @Test
    fun cameraCapabilities_discoversNightSightSupportSeparatelyPerLensAndConfiguresCamera2() {
        // 1. Hardware Night Scene supported when both CONTROL_SCENE_MODE_NIGHT and CONTROL_MODE_USE_SCENE_MODE exist
        assertTrue(
            isCamera2HardwareNightSceneModeSupported(
                availableSceneModes = listOf(
                    CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED,
                    CameraCharacteristics.CONTROL_SCENE_MODE_NIGHT
                ),
                availableControlModes = listOf(
                    CameraCharacteristics.CONTROL_MODE_AUTO,
                    CameraCharacteristics.CONTROL_MODE_USE_SCENE_MODE
                )
            )
        )

        // 2. False when CONTROL_SCENE_MODE_NIGHT is absent
        assertFalse(
            isCamera2HardwareNightSceneModeSupported(
                availableSceneModes = listOf(CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED),
                availableControlModes = listOf(
                    CameraCharacteristics.CONTROL_MODE_AUTO,
                    CameraCharacteristics.CONTROL_MODE_USE_SCENE_MODE
                )
            )
        )

        // 3. Rear vs Front lens capabilities resolved independently
        val rearCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            manualCapabilities = ManualSensorCapabilities(isHardwareNightSceneModeSupported = true)
        )
        val frontCaps = LensCapabilities(
            lens = CameraLens.FRONT,
            manualCapabilities = ManualSensorCapabilities(isHardwareNightSceneModeSupported = false)
        )

        assertEquals(NightSightSupportPath.HARDWARE_SCENE_NIGHT, rearCaps.nightSightSupportPath)
        assertTrue(
            frontCaps.nightSightSupportPath == NightSightSupportPath.MULTI_FRAME_LOW_LIGHT_FUSION ||
                frontCaps.nightSightSupportPath == NightSightSupportPath.SENSOR_ENHANCED_INTEGRATION
        )

        // 4. Verify Camera2 interop options when Night Scene mode is enabled vs disabled
        val nightSceneOptions = buildNonProResetCamera2CaptureRequestOptions(
            isVideoMode = false,
            isHardwareAfLockActive = false,
            supportsAfAutoHold = false,
            enableHardwareNightSceneMode = true
        )
        assertEquals(
            CaptureRequest.CONTROL_MODE_USE_SCENE_MODE,
            nightSceneOptions.getCaptureRequestOption(CaptureRequest.CONTROL_MODE, null)
        )
        assertEquals(
            CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
            nightSceneOptions.getCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, null)
        )
    }

    @Test
    fun mediaStoreSaving_savesNightSightPhotoAtomicallyWithNormalizedExif() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val f0 = bitmapToJpegBytes(createLowLightSceneBitmap(width = 200, height = 150, randomSeed = 1))
        val f1 = bitmapToJpegBytes(createLowLightSceneBitmap(width = 200, height = 150, randomSeed = 2))

        val outcome = NightSightProcessor.processNightSightJpegBytes(
            sourceBytes = f0,
            additionalFramesBytes = listOf(f1),
            rotationDegrees = 90,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            exposure = NightSightExposure.AUTO
        ) as NightSightProcessingOutcome.ProcessedNightSight

        val saved = MediaStorePhotoSaver.savePhotoAtomically(
            context = context,
            outputBytes = outcome.jpegBytes,
            sourceExifBytes = f0,
            displayName = "IMG_NIGHT_TEST",
            mimeType = "image/jpeg",
            width = outcome.width,
            height = outcome.height,
            exifOrientation = outcome.exifOrientation,
            location = null
        )
        assertNotNull(saved)
        assertEquals(150, saved!!.width)
        assertEquals(200, saved.height)

        val tempFile = File.createTempFile("night_exif_verify", ".jpg", context.cacheDir)
        try {
            context.contentResolver.openInputStream(saved.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val exif = ExifInterface(tempFile.absolutePath)
            assertEquals(
                ExifInterface.ORIENTATION_NORMAL,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun nightSightUiControls_exposurePillsAndZoomShortcutsWorkInNightSightMode() {
        var currentExposure by mutableStateOf(NightSightExposure.AUTO)
        var currentZoom by mutableFloatStateOf(1.0f)

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraPreviewPlaceholder(
                    activeMode = CameraMode.NIGHT_SIGHT,
                    nightSightExposure = currentExposure,
                    onNightSightExposureChanged = { currentExposure = it },
                    zoomRatio = currentZoom,
                    minZoomRatio = 0.5f,
                    maxZoomRatio = 8.0f,
                    zoomShortcuts = listOf(0.5f, 1.0f, 2.0f, 5.0f),
                    onZoomRatioChanged = { currentZoom = it }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Verify Night Sight exposure selector bar and pills exist
        composeTestRule.onNodeWithTag("night_sight_exposure_selector_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("night_sight_exposure_pill_off").assertIsDisplayed()
        composeTestRule.onNodeWithTag("night_sight_exposure_pill_auto").assertIsDisplayed()
        composeTestRule.onNodeWithTag("night_sight_exposure_pill_max").assertIsDisplayed()

        // Tap Max pill -> updates exposure to MAX
        composeTestRule.onNodeWithTag("night_sight_exposure_pill_max").performClick()
        composeTestRule.waitForIdle()
        assertEquals(NightSightExposure.MAX, currentExposure)

        // Tap Off pill -> updates exposure to OFF
        composeTestRule.onNodeWithTag("night_sight_exposure_pill_off").performClick()
        composeTestRule.waitForIdle()
        assertEquals(NightSightExposure.OFF, currentExposure)

        // Verify zoom shortcuts still work alongside Night Sight controls
        composeTestRule.onNodeWithTag("zoom_shortcut_2x").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2.0f, currentZoom, 0.01f)
    }
}
