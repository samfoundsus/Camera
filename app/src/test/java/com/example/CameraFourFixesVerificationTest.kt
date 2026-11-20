package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.CameraLens
import com.example.core.camera.HdMaxPhotoCaptureSource
import com.example.core.camera.LensCapabilities
import com.example.core.camera.LensFacing
import com.example.core.camera.NightSightCapturePhase
import com.example.core.camera.NightSightDeviceSupport
import com.example.core.camera.NightSightProgressState
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions
import com.example.core.camera.buildVerifiedVideoCapabilityProfile
import com.example.core.camera.calculateTargetVideoBitrate
import com.example.core.camera.resolveCameraLabelFromMetadata
import com.example.core.camera.selectBestVideoAeFpsRange
import com.example.core.camera.DefaultCameraCapabilitiesRepository
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.preferences.VideoStabilizationMode
import com.example.core.processing.NightSightProcessor
import com.example.core.processing.NightSightStreamingAccumulator
import com.example.core.processing.PortraitProcessingOutcome
import com.example.core.processing.PortraitProcessor
import com.example.feature.preview.components.CameraModeSelector
import com.example.feature.settings.SettingsScreen
import com.example.ui.components.CameraShutterButton
import com.example.ui.theme.PixelCameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraFourFixesVerificationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // =========================================================================
    // 1. VIDEO: Resolution/FPS Discovery, AE Range, Bitrate, Slow-Mo Isolation,
    //           Per-Resolution Stabilization & Metadata Camera Labels
    // =========================================================================

    @Test
    fun videoDiscovery_exposes720pAnd1080p60And2k30_hidesOddSizes_andIsolatesHighSpeedToSlowMo() {
        val profile = buildVerifiedVideoCapabilityProfile(
            streamVideoSizes = setOf(
                2560 to 1440,
                1920 to 1080,
                1280 to 720,
                1440 to 1080, // odd size — must be hidden
                960 to 540    // odd size — must be hidden
            ),
            minFrameDurationNanosByResolution = mapOf(
                VideoResolution.QHD_2K to 33_333_333L,  // 30 FPS max
                VideoResolution.FHD_1080P to 16_666_666L, // 60 FPS max
                VideoResolution.HD_720P to 16_666_666L    // 60 FPS max
            ),
            aeFpsRanges = listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60),
            highSpeedSizeToMaxFps = mapOf(
                (1280 to 720) to 240,
                (1920 to 1080) to 120
            ),
            cameraXSupportedResolutions = setOf(
                VideoResolution.HD_720P,
                VideoResolution.FHD_1080P,
                VideoResolution.QHD_2K
            ),
            camcorderMaxFpsByResolution = mapOf(
                VideoResolution.HD_720P to 60,
                VideoResolution.FHD_1080P to 60,
                VideoResolution.QHD_2K to 30
            ),
            codecMaxFpsByResolution = mapOf(
                VideoResolution.HD_720P to 120.0,
                VideoResolution.FHD_1080P to 60.0,
                VideoResolution.QHD_2K to 30.0
            ),
            availableVideoStabilizationModes = listOf(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ),
            availableOpticalStabilizationModes = listOf(
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            ),
            isCameraXVideoStabilizationSupported = true
        )

        // Exact expected normal video combinations
        assertEquals(
            setOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P, VideoResolution.QHD_2K),
            profile.supportedVideoQualities.keys
        )
        assertTrue(VideoFrameRate.FPS_60 in profile.supportedVideoQualities[VideoResolution.HD_720P].orEmpty())
        assertTrue(VideoFrameRate.FPS_30 in profile.supportedVideoQualities[VideoResolution.HD_720P].orEmpty())
        assertTrue(VideoFrameRate.FPS_60 in profile.supportedVideoQualities[VideoResolution.FHD_1080P].orEmpty())
        assertTrue(VideoFrameRate.FPS_30 in profile.supportedVideoQualities[VideoResolution.FHD_1080P].orEmpty())
        val qhdRates = profile.supportedVideoQualities[VideoResolution.QHD_2K].orEmpty()
        assertTrue(VideoFrameRate.FPS_30 in qhdRates)
        assertFalse(VideoFrameRate.FPS_60 in qhdRates)

        // High-speed 120/240 FPS stays strictly in Slow Motion, NOT normal Video
        assertFalse(VideoFrameRate.FPS_120 in profile.supportedVideoQualities[VideoResolution.HD_720P].orEmpty())
        assertFalse(VideoFrameRate.FPS_240 in profile.supportedVideoQualities[VideoResolution.HD_720P].orEmpty())
        assertTrue(VideoFrameRate.FPS_120 in profile.supportedSlowMoRates)
        assertTrue(VideoFrameRate.FPS_240 in profile.supportedSlowMoRates)

        // Stabilization supported
        assertTrue(profile.isStabilizationSupported)
        assertTrue(
            VideoStabilizationMode.STANDARD in
                profile.supportedStabilizationModesByConfig[VideoResolution.FHD_1080P to VideoFrameRate.FPS_30].orEmpty()
        )
    }

    @Test
    fun videoAeFpsRangeSelection_andBitrateScaling_andMetadataLabels() {
        val ranges = listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60)
        assertEquals(60 to 60, selectBestVideoAeFpsRange(ranges, 60))
        assertEquals(30 to 30, selectBestVideoAeFpsRange(ranges, 30))

        val rangesWithoutFixed60 = listOf(15 to 30, 30 to 30, 30 to 60)
        assertEquals(30 to 60, selectBestVideoAeFpsRange(rangesWithoutFixed60, 60))

        // Bitrate scales with both resolution and FPS
        val br720p30 = calculateTargetVideoBitrate(VideoResolution.HD_720P, 30)
        val br1080p30 = calculateTargetVideoBitrate(VideoResolution.FHD_1080P, 30)
        val br1080p60 = calculateTargetVideoBitrate(VideoResolution.FHD_1080P, 60)
        val br2k30 = calculateTargetVideoBitrate(VideoResolution.QHD_2K, 30)
        val br4k60 = calculateTargetVideoBitrate(VideoResolution.UHD_4K, 60)
        assertTrue(br1080p30 > br720p30)
        assertTrue(br1080p60 > br1080p30)
        assertTrue(br2k30 > br1080p30)
        assertTrue(br4k60 > br2k30)

        // Camera labels come strictly from metadata — never "Wide Main (1x)"
        val rearFallback = resolveCameraLabelFromMetadata(
            facing = LensFacing.BACK,
            focalLengthMm = null,
            cameraId = null
        )
        assertEquals("Rear Camera", rearFallback)
        assertNotEquals("Wide Main (1x)", CameraLens.BACK_MAIN.displayName)

        val rearWithMeta = resolveCameraLabelFromMetadata(
            facing = LensFacing.BACK,
            focalLengthMm = 4.71f,
            cameraId = "0",
            physicalCameraIds = listOf("2", "3"),
            allSameFacingFocalLengths = listOf(4.71f)
        )
        assertTrue(rearWithMeta.contains("Rear Camera"))
        assertTrue(rearWithMeta.contains("4.7mm"))
        assertTrue(rearWithMeta.contains("ID 0"))
    }

    // =========================================================================
    // 2. HD MODE: True Max MP Label, Honest Restriction Note & Debug Screen
    // =========================================================================

    @Test
    fun hdMode_calculatesRealMegapixels_flagsRestrictedSensor_andOpensHiddenDebugDialog() {
        val full108MpCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            highestNativeStillSize = PhotoOutputSize(12000, 9000, isSensorMaxResolution = true),
            sensorPixelArraySize = PhotoOutputSize(12000, 9000, isSensorMaxResolution = true),
            isFullSensorResolutionExposed = true,
            hdMaxPhotoCaptureSource = HdMaxPhotoCaptureSource("0", 12000, 9000, requiresMaxResPixelMode = true)
        )
        assertEquals("HD · 108 MP", full108MpCaps.hdModeButtonLabel)
        assertFalse(full108MpCaps.isSensorFullResolutionRestricted)

        val binned12MpCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            highestNativeStillSize = PhotoOutputSize(4000, 3000, isSensorMaxResolution = true),
            sensorPixelArraySize = PhotoOutputSize(12000, 9000, isSensorMaxResolution = true),
            isFullSensorResolutionExposed = false,
            hdMaxPhotoCaptureSource = HdMaxPhotoCaptureSource("0", 4000, 3000, requiresMaxResPixelMode = false)
        )
        assertEquals("HD · 12 MP", binned12MpCaps.hdModeButtonLabel)
        assertTrue(binned12MpCaps.isSensorFullResolutionRestricted)

        // Verify CameraModeSelector displays dynamic HD MP button label
        composeTestRule.setContent {
            PixelCameraTheme {
                CameraModeSelector(
                    selectedMode = CameraMode.HD,
                    visibleModes = listOf(CameraMode.PHOTO, CameraMode.HD, CameraMode.VIDEO),
                    hiddenModes = emptyList(),
                    modeLabelOverrides = mapOf(CameraMode.HD to binned12MpCaps.hdModeButtonLabel),
                    onModeSelected = {},
                    onMoreClick = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("HD · 12 MP").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_showsHdRestrictionNote_andLongPressAppVersionOpensDebugScreen() {
        val binnedCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            highestNativeStillSize = PhotoOutputSize(4000, 3000, isSensorMaxResolution = true),
            sensorPixelArraySize = PhotoOutputSize(12000, 9000, isSensorMaxResolution = true),
            isFullSensorResolutionExposed = false
        )
        val repo = DefaultCameraCapabilitiesRepository().apply {
            setDiscoveredCapabilities(mapOf(CameraLens.BACK_MAIN to binnedCaps))
        }

        composeTestRule.setContent {
            PixelCameraTheme {
                SettingsScreen(
                    preferences = CameraPreferences(),
                    capabilitiesRepository = repo,
                    onThemeChanged = {},
                    onDynamicColorChanged = {},
                    onAspectRatioChanged = {},
                    onGridChanged = {},
                    onTimerChanged = {},
                    onFlashChanged = {},
                    onSoundsChanged = {},
                    onLocationChanged = {},
                    onMirrorChanged = {},
                    onNavigateBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Honest restriction note is visible in settings
        composeTestRule.onNodeWithText("This device doesn't expose full sensor resolution to apps.", substring = true)
            .assertExists()

        // Long-press app version opens hidden hardware debug screen (scroll to bottom of Settings first)
        composeTestRule.onNodeWithTag("settings_app_version_row")
            .performScrollTo()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hardware_debug_dialog").assertIsDisplayed()
    }

    // =========================================================================
    // 3. NIGHT SIGHT: Streaming Memory (Recycles Immediately), Dynamic Plan,
    //                 and Distinct Shutter Ring Capture vs Processing States
    // =========================================================================

    @Test
    fun nightSight_streamingAccumulatorRecyclesImmediately_andMemoryPlanCapsFrames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val longExpPlan = NightSightDeviceSupport.computeSafeCapturePlan(
            context = context,
            frameWidth = 4000,
            frameHeight = 3000,
            exposure = NightSightExposure.MAX,
            maxSensorExposureNanos = 2_000_000_000L // 2s capable sensor
        )
        // For very long exposure, prefers fewer, longer frames instead of huge burst
        assertTrue(longExpPlan.frameCount in 2..4)
        assertTrue(longExpPlan.targetPerFrameExposureMs >= 500L)

        // Create 3 dark test frames and verify streaming accumulator recycles each bitmap immediately
        val w = 160
        val h = 120
        val accumulator = NightSightStreamingAccumulator(w, h)
        val inputBitmaps = (0 until 3).map { idx ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                val c = Canvas(this)
                c.drawColor(Color.rgb(18 + idx, 20 + idx, 26))
                val p = Paint().apply { color = Color.rgb(90, 95, 110) }
                c.drawCircle(80f, 60f, 24f, p)
            }
        }
        for (bmp in inputBitmaps) {
            val merged = accumulator.addFrame(bmp, recycleBitmap = true)
            assertTrue(merged)
            assertTrue("Streaming accumulator must recycle frame bitmap immediately", bmp.isRecycled)
        }
        assertEquals(3, accumulator.framesMerged)

        val progressValues = mutableListOf<Float>()
        val outcome = NightSightProcessor.finalizeStreamingAccumulator(
            accumulator = accumulator,
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            exposure = NightSightExposure.MAX,
            onProcessingProgress = { progressValues.add(it) }
        )
        assertEquals(3, outcome.framesMerged)
        assertTrue(outcome.jpegBytes.isNotEmpty())
        assertTrue(progressValues.isNotEmpty())
        assertTrue(progressValues.last() >= 0.95f)
    }

    @Test
    fun nightSightShutterRing_showsCaptureProgressCancel_andDistinctProcessingSpinner() {
        var cancelCalled = false
        var shutterClicks = 0
        val stateHolder = androidx.compose.runtime.mutableStateOf(
            NightSightProgressState(
                phase = NightSightCapturePhase.CAPTURING,
                currentFrame = 2,
                totalFrames = 6,
                progress = 2f / 6f,
                statusLabel = "2/6"
            )
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PixelCameraTheme {
                CameraShutterButton(
                    isVideoMode = false,
                    isRecording = false,
                    nightSightProgress = stateHolder.value,
                    onCancelNightSightCapture = { cancelCalled = true },
                    onClick = { shutterClicks++ }
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(250L)

        // During CAPTURING: progress ring + "2/6" visible, tap cancels instead of starting another capture
        composeTestRule.onNodeWithTag("night_sight_shutter_capture_progress", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("2/6", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertTrue(cancelCalled)
        assertEquals(0, shutterClicks)

        // During PROCESSING: distinct spinning ring visible, button disabled
        stateHolder.value = NightSightProgressState(
            phase = NightSightCapturePhase.PROCESSING,
            currentFrame = 6,
            totalFrames = 6,
            progress = 0.55f,
            statusLabel = "55%"
        )
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.onNodeWithTag("night_sight_shutter_processing_ring", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("55%", useUnmergedTree = true).assertIsDisplayed()

        // On COMPLETED: completion ring displayed
        stateHolder.value = NightSightProgressState(
            phase = NightSightCapturePhase.COMPLETED,
            progress = 1.0f,
            statusLabel = "Saved"
        )
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.onNodeWithTag("night_sight_shutter_completed", useUnmergedTree = true).assertIsDisplayed()
    }

    // =========================================================================
    // 4. PORTRAIT: Distance-Falloff Disc Bokeh, Untouched Subject Exposure,
    //              No-Subject Message, and AE Lock / EV Compensation Support
    // =========================================================================

    @Test
    fun portraitProcessor_appliesDepthDiscBlur_keepsSubjectUntouched_andDetectsNoSubject() {
        // 1. Flat uniform frame with no subject -> must return FallbackOriginal with "No subject found"
        val flatBmp = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 125, 130))
        }
        val flatStream = ByteArrayOutputStream().apply {
            flatBmp.compress(Bitmap.CompressFormat.JPEG, 95, this)
        }
        flatBmp.recycle()

        val flatOutcome = PortraitProcessor.processPortraitJpegBytes(
            sourceBytes = flatStream.toByteArray(),
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            blurLevel = PortraitBlurLevel.DEEP,
            blurStrengthOverride = 1.0f,
            retouch = PortraitRetouch.OFF
        )
        assertTrue(flatOutcome is PortraitProcessingOutcome.FallbackOriginal)
        val fallback = flatOutcome as PortraitProcessingOutcome.FallbackOriginal
        assertTrue(fallback.reason.startsWith("No subject found", ignoreCase = true))

        // 2. Portrait frame with crisp centered subject over high-contrast striped background
        //    Write directly to pixel buffer so pixels are populated under Robolectric JVM.
        val w = 240
        val h = 320
        val portraitBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val cx = w * 0.5f
        val headCy = h * 0.38f
        val headRx = w * 0.18f
        val headRy = h * 0.15f
        val torsoCy = h * 0.74f
        val torsoRx = w * 0.30f
        val torsoRy = h * 0.22f
        val bgDark = Color.rgb(30, 80, 150)
        val bgLight = Color.rgb(210, 220, 235)
        val faceColor = Color.rgb(214, 162, 132)
        val torsoColor = Color.rgb(190, 72, 68)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var c = if ((x / 8) % 2 == 0) bgDark else bgLight
                val tdx = (x - cx) / torsoRx
                val tdy = (y - torsoCy) / torsoRy
                if (tdx * tdx + tdy * tdy <= 1.0f) {
                    c = torsoColor
                }
                val hdx = (x - cx) / headRx
                val hdy = (y - headCy) / headRy
                if (hdx * hdx + hdy * hdy <= 1.0f) {
                    c = faceColor
                }
                pixels[row + x] = c
            }
        }
        portraitBmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val subjectCenterOrig = portraitBmp.getPixel(w / 2, (h * 0.38f).toInt())
        val portraitStream = ByteArrayOutputStream().apply {
            portraitBmp.compress(Bitmap.CompressFormat.JPEG, 98, this)
        }
        portraitBmp.recycle()

        val outcome = PortraitProcessor.processPortraitJpegBytes(
            sourceBytes = portraitStream.toByteArray(),
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            blurLevel = PortraitBlurLevel.DEEP,
            blurStrengthOverride = 14.0f,
            retouch = PortraitRetouch.OFF
        )
        assertTrue(outcome is PortraitProcessingOutcome.ProcessedPortrait)
        val processed = outcome as PortraitProcessingOutcome.ProcessedPortrait
        val outBmp = android.graphics.BitmapFactory.decodeByteArray(processed.jpegBytes, 0, processed.jpegBytes.size)

        // Subject center color/exposure must remain untouched (within JPEG tolerance)
        val outSubjectCenter = outBmp.getPixel(outBmp.width / 2, (outBmp.height * 0.38f).toInt())
        assertTrue(abs(Color.red(outSubjectCenter) - Color.red(subjectCenterOrig)) <= 12)
        assertTrue(abs(Color.green(outSubjectCenter) - Color.green(subjectCenterOrig)) <= 12)
        assertTrue(abs(Color.blue(outSubjectCenter) - Color.blue(subjectCenterOrig)) <= 12)

        // Far background stripes at top-left must be visibly blurred (reduced horizontal gradient)
        var bgDiffSum = 0
        for (x in 4 until 60) {
            val c1 = outBmp.getPixel(x, 12)
            val c2 = outBmp.getPixel(x + 1, 12)
            bgDiffSum += abs(Color.red(c1) - Color.red(c2))
        }
        // Original sharp 8px stripes had ~180 contrast jump every 8px (~1260 total across 56px);
        // disc bokeh reduces this substantially.
        assertTrue("Expected blurred far background, got edge diff sum=$bgDiffSum", bgDiffSum < 700)
        outBmp.recycle()

        // 3. Verify Portrait AE Lock + EV Compensation in Camera2 capture request options
        val portraitLockedOpts = buildNonProResetCamera2CaptureRequestOptions(
            isVideoMode = false,
            isHardwareAfLockActive = true,
            supportsAfAutoHold = true,
            enableHardwareFaceDetect = true,
            exposureCompensationIndex = 3,
            lockAe = true
        )
        assertEquals(true, portraitLockedOpts.getCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK))
        assertEquals(3, portraitLockedOpts.getCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
        assertEquals(
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE,
            portraitLockedOpts.getCaptureRequestOption(CaptureRequest.STATISTICS_FACE_DETECT_MODE)
        )
    }
}
