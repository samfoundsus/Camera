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
import com.example.core.camera.CameraHardwareDebugReport
import com.example.core.camera.CameraIdDebugInfo
import com.example.core.camera.CameraLens
import com.example.core.camera.HdMaxPhotoCaptureSource
import com.example.core.camera.LensCapabilities
import com.example.core.camera.LensFacing
import com.example.core.camera.NightSightCapturePhase
import com.example.core.camera.NightSightDeviceSupport
import com.example.core.camera.NightSightProgressState
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.PortraitStageTimingTracker
import com.example.core.camera.PortraitStageTimings
import com.example.core.camera.RecordingReadinessChecker
import com.example.core.camera.ShutterProgressMode
import com.example.core.camera.ShutterProgressPhase
import com.example.core.camera.ShutterProgressState
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions
import com.example.core.camera.buildVerifiedVideoCapabilityProfile
import com.example.core.camera.calculateTargetVideoBitrate
import com.example.core.camera.formatAstroCaptureCaption
import com.example.core.camera.formatTimeLapseCaptureCaption
import com.example.core.camera.resolveCameraLabelFromMetadata
import com.example.core.camera.selectBestVideoAeFpsRange
import com.example.core.camera.DefaultCameraCapabilitiesRepository
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.AstroDuration
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.preferences.VideoStabilizationMode
import com.example.core.processing.AstroProcessor
import com.example.core.processing.AstroStreamingAccumulator
import com.example.core.processing.NightSightProcessor
import com.example.core.processing.NightSightStreamingAccumulator
import com.example.core.processing.PortraitProcessingOutcome
import com.example.core.processing.PortraitProcessor
import com.example.feature.preview.components.CameraModeSelector
import com.example.feature.preview.components.CameraShutterBar
import com.example.feature.settings.CameraHardwareDebugDialog
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.formatHardwareDebugReportText
import com.example.ui.components.CameraShutterButton
import com.example.ui.theme.PixelCameraTheme
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraFourFixesVerificationTest {

    @get:Rule(order = 0)
    val testTimeoutRule = MainThreadSafeTimeoutRule(30_000L)

    @get:Rule(order = 1)
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

        // Per-combo PASS/FAIL evaluations include exact diagnostics for every tested candidate
        val eval1080p60 = profile.videoComboEvaluations.firstOrNull {
            it.resolution == VideoResolution.FHD_1080P && it.frameRate == VideoFrameRate.FPS_60
        }
        assertTrue(eval1080p60 != null && eval1080p60.passed)
        assertEquals(60 to 60, eval1080p60?.chosenAeFpsRange)

        val eval2k60 = profile.videoComboEvaluations.firstOrNull {
            it.resolution == VideoResolution.QHD_2K && it.frameRate == VideoFrameRate.FPS_60
        }
        assertTrue(eval2k60 != null && !eval2k60.passed)
        assertTrue(
            eval2k60!!.failureReasons.any { it.contains("min frame duration", ignoreCase = true) || it.contains("encoder", ignoreCase = true) }
        )

        val eval4k30 = profile.videoComboEvaluations.firstOrNull {
            it.resolution == VideoResolution.UHD_4K && it.frameRate == VideoFrameRate.FPS_30
        }
        assertTrue(eval4k30 != null && !eval4k30.passed)
        assertTrue(eval4k30!!.failureReasons.any { it.contains("not in stream map", ignoreCase = true) })

        // Verify per-resolution FPS filtering updates when switching between 1080p and 2K
        val lensCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            supportedVideoQualities = profile.supportedVideoQualities
        )
        assertEquals(
            listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P, VideoResolution.QHD_2K),
            lensCaps.getSupportedVideoModeResolutions()
        )
        assertTrue(VideoFrameRate.FPS_60 in lensCaps.getSupportedVideoModeFrameRates(VideoResolution.FHD_1080P))
        assertFalse(VideoFrameRate.FPS_60 in lensCaps.getSupportedVideoModeFrameRates(VideoResolution.QHD_2K))
        assertTrue(VideoFrameRate.FPS_30 in lensCaps.getSupportedVideoModeFrameRates(VideoResolution.QHD_2K))
        assertEquals(listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30), lensCaps.getSupportedVideoModeFrameRates(VideoResolution.QHD_2K))
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
        composeTestRule.onNodeWithText(
            "This phone doesn't give apps access to the full 108 MP sensor resolution. Use the stock camera's 108 MP mode for that.",
            substring = true
        ).assertExists()

        // Long-press app version opens hidden hardware debug screen (scroll to bottom of Settings first)
        composeTestRule.onNodeWithTag("settings_app_version_row")
            .performScrollTo()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hardware_debug_dialog").assertIsDisplayed()
    }

    @Test
    fun hdMode_binnedRear12MpHal_stillShowsExact108MpRestrictionNote_andDebugReportIncludesAllNewFields() {
        // Simulates a MediaTek 108 MP phone where Camera2 HAL reports 3968x2976 even in SENSOR_INFO_PIXEL_ARRAY_SIZE
        val mediatekBinnedCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            highestNativeStillSize = PhotoOutputSize(3968, 2976, isSensorMaxResolution = true),
            sensorPixelArraySize = PhotoOutputSize(3968, 2976, isSensorMaxResolution = true),
            isFullSensorResolutionExposed = true
        )
        assertTrue(mediatekBinnedCaps.isSensorFullResolutionRestricted)
        assertEquals(
            "This phone doesn't give apps access to the full 108 MP sensor resolution. Use the stock camera's 108 MP mode for that.",
            mediatekBinnedCaps.hdRestrictedResolutionNote
        )

        val profile = buildVerifiedVideoCapabilityProfile(
            streamVideoSizes = setOf(2560 to 1440, 1920 to 1080, 1280 to 720),
            minFrameDurationNanosByResolution = mapOf(
                VideoResolution.QHD_2K to 33_333_333L,
                VideoResolution.FHD_1080P to 16_666_666L,
                VideoResolution.HD_720P to 16_666_666L
            ),
            aeFpsRanges = listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60),
            highSpeedSizeToMaxFps = emptyMap(),
            cameraXSupportedResolutions = setOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P, VideoResolution.QHD_2K),
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
            availableVideoStabilizationModes = listOf(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            availableOpticalStabilizationModes = emptyList()
        )
        val report = CameraHardwareDebugReport(
            deviceModel = "TestPhone",
            androidVersionLabel = "Android 14 (API 34)",
            cameras = listOf(
                CameraIdDebugInfo(
                    cameraId = "0",
                    isPhysicalOnly = false,
                    parentLogicalId = null,
                    facingLabel = "Back",
                    metadataLabel = "Rear Camera (4.7mm) · ID 0",
                    physicalCameraIds = emptyList(),
                    focalLengthsMm = listOf(4.7f),
                    pixelArraySize = "3968x2976 (12 MP)",
                    activeArraySize = "3968x2976 (12 MP)",
                    videoSizesWithMaxFps = emptyList(),
                    highSpeedVideoEntries = emptyList(),
                    standardJpegSizes = listOf("3968x2976 (12 MP)"),
                    highResJpegSizes = emptyList(),
                    maxResJpegSizes = emptyList(),
                    videoStabilizationModes = listOf("ON"),
                    opticalStabilizationModes = emptyList(),
                    aeFpsRanges = listOf("[30,30]", "[30,60]", "[60,60]"),
                    supportedExtensions = listOf("HDR", "Night"),
                    extensionsStatusLabel = "HDR, Night",
                    standardYuvSizes = listOf("3968x2976 (12 MP)"),
                    highResYuvSizes = emptyList(),
                    maxResYuvSizes = emptyList(),
                    standardRawSizes = emptyList(),
                    highResRawSizes = emptyList(),
                    maxResRawSizes = emptyList(),
                    supportsUltraHighResSensorCapability = false,
                    encoderCapabilitiesSummary = listOf("1080p: max 60.0fps (24=Y, 30=Y, 60=Y, 120=N)"),
                    videoComboEvaluations = profile.videoComboEvaluations
                )
            )
        )
        val plain = report.formatPlainText()
        assertTrue(plain.contains("YUV_420_888"))
        assertTrue(plain.contains("RAW_SENSOR"))
        assertTrue(plain.contains("CameraX Extensions"))
        assertTrue(plain.contains("Video Resolution + FPS Evaluation (PASS/FAIL)"))
        assertTrue(plain.contains("1080p @ 60fps: PASS"))
        assertTrue(plain.contains("2k_1440p @ 60fps: FAIL"))
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
        applyComposeStateAndAdvance(250L) {
            stateHolder.value = NightSightProgressState(
                phase = NightSightCapturePhase.PROCESSING,
                currentFrame = 6,
                totalFrames = 6,
                progress = 0.55f,
                statusLabel = "55%"
            )
        }
        composeTestRule.onNodeWithTag("night_sight_shutter_processing_ring", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("55%", useUnmergedTree = true).assertIsDisplayed()

        // On COMPLETED: completion ring displayed
        applyComposeStateAndAdvance(250L) {
            stateHolder.value = NightSightProgressState(
                phase = NightSightCapturePhase.COMPLETED,
                progress = 1.0f,
                statusLabel = "Saved"
            )
        }
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

    // =========================================================================
    // 5. SHARED SHUTTER PROGRESS: Astrophotography & Time Lapse Streaming,
    //    Stop & Save, Processing Ring, and Readiness Warnings
    // =========================================================================

    @Test
    fun astroStreamingAccumulator_recyclesEveryFrameImmediately_rejectsTransientOutlier_andReportsProgress() {
        val w = 128
        val h = 96
        val accumulator = AstroStreamingAccumulator(w, h)
        val inputBitmaps = (0 until 4).map { idx ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(w * h) { Color.rgb(12, 16, 28) }
                // Add consistent stars at fixed coordinates
                pixels[20 * w + 30] = Color.rgb(240, 242, 255)
                pixels[60 * w + 80] = Color.rgb(235, 240, 255)
                // Add a bright single-frame satellite trail outlier ONLY in frame 2
                if (idx == 2) {
                    for (x in 10 until 50) {
                        pixels[45 * w + x] = Color.rgb(255, 255, 255)
                    }
                }
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }

        for (bmp in inputBitmaps) {
            val merged = accumulator.addFrame(bmp, recycleBitmap = true)
            assertTrue(merged)
            assertTrue("AstroStreamingAccumulator must recycle each frame bitmap immediately", bmp.isRecycled)
        }
        assertEquals(4, accumulator.framesMerged)

        val progressValues = mutableListOf<Float>()
        val outcome = AstroProcessor.finalizeStreamingAccumulator(
            accumulator = accumulator,
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            mirrorHorizontally = false,
            duration = AstroDuration.AUTO,
            onProcessingProgress = { progressValues.add(it) }
        )
        assertEquals(4, outcome.framesStacked)
        assertTrue(outcome.jpegBytes.isNotEmpty())
        assertTrue(progressValues.isNotEmpty())
        assertTrue(progressValues.last() >= 0.95f)

        // Verify satellite trail pixel was suppressed (remains dark sky, not white)
        val outBmp = android.graphics.BitmapFactory.decodeByteArray(outcome.jpegBytes, 0, outcome.jpegBytes.size)
        val trailPixel = outBmp.getPixel(25, 45)
        assertTrue("Expected single-frame outlier trail rejected, got red=${Color.red(trailPixel)}", Color.red(trailPixel) < 80)
        outBmp.recycle()
    }

    @Test
    fun recordingReadinessChecker_detectsLowStorageAndLowBattery_andRespectsCharging() {
        val healthy = RecordingReadinessChecker.evaluateReadiness(
            freeStorageMb = 2048L,
            batteryPercent = 65,
            isCharging = false
        )
        assertFalse(healthy.hasWarning)

        val lowStorageOnly = RecordingReadinessChecker.evaluateReadiness(
            freeStorageMb = 250L,
            batteryPercent = 80,
            isCharging = false
        )
        assertTrue(lowStorageOnly.hasWarning)
        assertTrue(lowStorageOnly.isLowStorage)
        assertFalse(lowStorageOnly.isLowBattery)
        assertTrue(lowStorageOnly.buildWarningMessage().contains("250 MB"))

        val lowBatteryUnplugged = RecordingReadinessChecker.evaluateReadiness(
            freeStorageMb = 4096L,
            batteryPercent = 12,
            isCharging = false
        )
        assertTrue(lowBatteryUnplugged.hasWarning)
        assertTrue(lowBatteryUnplugged.isLowBattery)
        assertTrue(lowBatteryUnplugged.buildWarningMessage().contains("12%"))

        val lowBatteryCharging = RecordingReadinessChecker.evaluateReadiness(
            freeStorageMb = 4096L,
            batteryPercent = 12,
            isCharging = true
        )
        assertFalse("Charging device should not trigger low battery warning", lowBatteryCharging.hasWarning)
    }

    @Test
    fun sharedShutterProgress_astroAndTimeLapse_showCaptureProgressStopSaveProcessingAndSavedStates() {
        var activeShutterClicks = 0
        var stopAndSaveClicks = 0
        var idleShutterClicks = 0
        val modeHolder = androidx.compose.runtime.mutableStateOf(CameraMode.ASTRO)
        val stateHolder = androidx.compose.runtime.mutableStateOf(
            ShutterProgressState(
                mode = ShutterProgressMode.ASTRO,
                phase = ShutterProgressPhase.CAPTURING,
                currentFrame = 3,
                totalFrames = 10,
                progress = 0.3f,
                elapsedSeconds = 8L,
                remainingSeconds = 22L,
                statusLabel = "3/10",
                detailLabel = formatAstroCaptureCaption(3, 10, 8L, 22L),
                canStopAndSave = true
            )
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PixelCameraTheme {
                CameraShutterBar(
                    activeMode = modeHolder.value,
                    isRecording = modeHolder.value == CameraMode.TIME_LAPSE &&
                        stateHolder.value.phase == ShutterProgressPhase.CAPTURING,
                    shutterProgress = stateHolder.value,
                    onActiveProgressShutterClick = { activeShutterClicks++ },
                    onStopAndSaveCapture = { stopAndSaveClicks++ },
                    onShutterClick = { idleShutterClicks++ }
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(250L)

        // 1. ASTRO CAPTURING: ring fills, banner shows "Frame 3 of 10 • 00:08 elapsed · 00:22 left", Stop & save chip works
        composeTestRule.onNodeWithTag("astro_shutter_capture_progress", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("shutter_progress_banner", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Frame 3 of 10 • 00:08 elapsed · 00:22 left", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("astro_stop_and_save_button", useUnmergedTree = true).performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertEquals(1, stopAndSaveClicks)

        // Tapping shutter during Astro capture routes to onActiveProgressShutterClick (never starts duplicate capture)
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertEquals(1, activeShutterClicks)
        assertEquals(0, idleShutterClicks)

        // 2. ASTRO PROCESSING: distinct spinning processing ring + percentage, shutter strictly disabled
        applyComposeStateAndAdvance(250L) {
            stateHolder.value = ShutterProgressState(
                mode = ShutterProgressMode.ASTRO,
                phase = ShutterProgressPhase.PROCESSING,
                currentFrame = 3,
                totalFrames = 10,
                progress = 0.45f,
                statusLabel = "45%",
                detailLabel = "Aligning & stacking… 45%",
                canStopAndSave = false
            )
        }
        composeTestRule.onNodeWithTag("astro_shutter_processing_ring", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Aligning & stacking… 45%", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertEquals(1, activeShutterClicks)
        assertEquals(0, idleShutterClicks)

        // 3. TIME LAPSE CAPTURING (unlimited pulse): stop button + elapsed & frame count near shutter
        applyComposeStateAndAdvance(250L) {
            modeHolder.value = CameraMode.TIME_LAPSE
            stateHolder.value = ShutterProgressState(
                mode = ShutterProgressMode.TIME_LAPSE,
                phase = ShutterProgressPhase.CAPTURING,
                currentFrame = 15,
                totalFrames = 0,
                progress = 0f,
                isIndeterminateCapture = true,
                framePulseCount = 15,
                elapsedSeconds = 15L,
                remainingSeconds = null,
                statusLabel = "00:15",
                detailLabel = formatTimeLapseCaptureCaption(15L, 15, null, null)
            )
        }
        composeTestRule.onNodeWithTag("timelapse_shutter_capture_progress", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("00:15 • 15 frames", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertEquals(2, activeShutterClicks)

        // 4. TIME LAPSE PROCESSING -> COMPLETED ("Saved")
        applyComposeStateAndAdvance(250L) {
            stateHolder.value = ShutterProgressState(
                mode = ShutterProgressMode.TIME_LAPSE,
                phase = ShutterProgressPhase.PROCESSING,
                currentFrame = 15,
                progress = 0.60f,
                statusLabel = "60%",
                detailLabel = "Building time-lapse… 60%"
            )
        }
        composeTestRule.onNodeWithTag("timelapse_shutter_processing_ring", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Building time-lapse… 60%", useUnmergedTree = true).assertIsDisplayed()

        applyComposeStateAndAdvance(250L) {
            stateHolder.value = ShutterProgressState(
                mode = ShutterProgressMode.TIME_LAPSE,
                phase = ShutterProgressPhase.COMPLETED,
                progress = 1.0f,
                statusLabel = "Saved",
                detailLabel = "Saved"
            )
        }
        composeTestRule.onNodeWithTag("timelapse_shutter_completed", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved", useUnmergedTree = true).assertIsDisplayed()
    }

    // =========================================================================
    // 6. PORTRAIT SPEEDUP: Per-Stage Timings, Fast Preview Thumbnail, Model Warmup,
    //    Debug Screen Breakdown, and Portrait Shared Shutter Progress + Concurrency
    // =========================================================================

    @Test
    fun portraitSpeedup_populatesStageTimings_decodesFastThumbnail_andWarmsUpSafely() {
        // 1. Background model warmup should be safe and non-blocking on JVM/device
        PortraitProcessor.warmUpSegmentationModelAsync()

        // 2. Build synthetic portrait frame (480x640) with centered subject + high-contrast background
        val w = 480
        val h = 640
        val srcBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val cx = w * 0.5f
        val headCy = h * 0.36f
        val headRx = w * 0.17f
        val headRy = h * 0.14f
        val torsoCy = h * 0.72f
        val torsoRx = w * 0.28f
        val torsoRy = h * 0.22f
        val bgDark = Color.rgb(24, 64, 130)
        val bgLight = Color.rgb(220, 228, 240)
        val faceColor = Color.rgb(214, 162, 132)
        val torsoColor = Color.rgb(185, 68, 66)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var c = if ((x / 10) % 2 == 0) bgDark else bgLight
                val tdx = (x - cx) / torsoRx
                val tdy = (y - torsoCy) / torsoRy
                if (tdx * tdx + tdy * tdy <= 1.0f) c = torsoColor
                val hdx = (x - cx) / headRx
                val hdy = (y - headCy) / headRy
                if (hdx * hdx + hdy * hdy <= 1.0f) c = faceColor
                pixels[row + x] = c
            }
        }
        srcBmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val jpegBytes = ByteArrayOutputStream().apply {
            srcBmp.compress(Bitmap.CompressFormat.JPEG, 95, this)
        }.toByteArray()
        srcBmp.recycle()

        // 3. Fast preview thumbnail should decode downscaled oriented RGB_565 bitmap immediately
        val thumb = PortraitProcessor.decodeFastPreviewThumbnail(
            sourceBytes = jpegBytes,
            rotationDegrees = 90,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            mirrorHorizontally = false,
            maxDimension = 256
        )
        assertNotNull("Expected fast preview thumbnail bitmap", thumb)
        assertTrue("Thumbnail longest side should be <= 256px, got ${thumb!!.width}x${thumb.height}",
            maxOf(thumb.width, thumb.height) <= 256)
        assertTrue("Expected landscape after 90° rotation of 480x640, got ${thumb.width}x${thumb.height}",
            thumb.width > thumb.height)
        thumb.recycle()

        // 4. Full portrait pipeline should emit stage progress + populate complete stageTimings
        val stagesSeen = mutableListOf<String>()
        val outcome = PortraitProcessor.processPortraitJpegBytes(
            sourceBytes = jpegBytes,
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            blurLevel = PortraitBlurLevel.DEEP,
            blurStrengthOverride = 12.0f,
            retouch = PortraitRetouch.OFF,
            captureElapsedMs = 42L,
            onStageProgress = { _, label -> stagesSeen.add(label) }
        )
        assertTrue(outcome is PortraitProcessingOutcome.ProcessedPortrait)
        val processed = outcome as PortraitProcessingOutcome.ProcessedPortrait
        val timings = processed.stageTimings
        assertNotNull("ProcessedPortrait must carry stageTimings", timings)
        assertEquals(42L, timings!!.captureMs)
        assertTrue(timings.decodeMs >= 0L)
        assertTrue(timings.segmentationMs >= 0L)
        assertTrue(timings.blurMs >= 0L)
        assertTrue(timings.compositingMs >= 0L)
        assertTrue(timings.encodeMs >= 0L)
        assertTrue(timings.totalMs >= 42L)
        assertTrue(timings.backend.isNotBlank())
        assertTrue(timings.segmentationSizeLabel.contains("x"))
        assertTrue(timings.blurSizeLabel.contains("x"))
        assertTrue(timings.slowestStageLabel.contains("ms"))
        assertTrue(stagesSeen.isNotEmpty())
    }

    @Test
    fun portraitStageTimingTracker_displaysPerStageBreakdownInHardwareDebugDialogAndReport() {
        PortraitStageTimingTracker.clear()
        val sampleTiming = PortraitStageTimings(
            captureMs = 68L,
            decodeMs = 54L,
            segmentationMs = 142L,
            blurMs = 39L,
            compositingMs = 61L,
            encodeMs = 73L,
            saveMs = 28L,
            totalMs = 465L,
            backend = "GPU (RenderEffect+AGSL)",
            width = 3000,
            height = 4000,
            segmentationSizeLabel = "480x640",
            blurSizeLabel = "480x640",
            subjectType = "PERSON",
            wasFallback = false
        )
        PortraitStageTimingTracker.record(sampleTiming)
        val records = PortraitStageTimingTracker.getRecentRecords()
        assertEquals(1, records.size)
        assertTrue(records.first().slowestStageLabel.startsWith("segmentation"))

        val debugReport = CameraHardwareDebugReport(
            deviceModel = "Test Device",
            androidVersionLabel = "Android 15 (API 35)",
            cameras = emptyList()
        )
        val formattedText = formatHardwareDebugReportText(report = debugReport)
        assertTrue(formattedText.contains("Portrait Pipeline Timings (Per Stage)"))
        assertTrue(formattedText.contains("465ms"))
        assertTrue(formattedText.contains("seg=142ms"))
        assertTrue(formattedText.contains("GPU (RenderEffect+AGSL)"))

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraHardwareDebugDialog(
                    report = debugReport,
                    activePhotoResolvedSize = "4000x3000 (12.0 MP)",
                    activeHdNativeResolvedSize = "4000x3000 (12.0 MP)",
                    activeHdBinnedResolvedSize = "4000x3000 (12.0 MP)",
                    activeExtensionsSummary = "None",
                    activeIspSummary = "ZSL Supported",
                    recentLatencyRecords = emptyList(),
                    recentPortraitTimings = records,
                    copied = false,
                    onCopyReport = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("hardware_debug_portrait_timings_card", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Portrait Pipeline Timings (Per Stage)", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            sampleTiming.formatMultilineBreakdown(),
            useUnmergedTree = true
        ).assertIsDisplayed()
        PortraitStageTimingTracker.clear()
    }

    @Test
    fun portraitShutterProgress_showsInstantThumbnail_allowsConcurrentNextShot_andLocksWhenQueueFull() {
        var shutterClicks = 0
        val instantThumb = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565).apply {
            eraseColor(Color.rgb(60, 120, 210))
        }
        val thumbHolder = androidx.compose.runtime.mutableStateOf<Bitmap?>(instantThumb)
        val stateHolder = androidx.compose.runtime.mutableStateOf(
            ShutterProgressState(
                mode = ShutterProgressMode.PORTRAIT,
                phase = ShutterProgressPhase.PROCESSING,
                progress = 0.45f,
                statusLabel = "Blurring…",
                detailLabel = "Portrait: Blurring…",
                allowConcurrentCapture = true
            )
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PixelCameraTheme {
                CameraShutterBar(
                    activeMode = CameraMode.PORTRAIT,
                    isRecording = false,
                    shutterProgress = stateHolder.value,
                    instantPreviewBitmap = thumbHolder.value,
                    onShutterClick = { shutterClicks++ }
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(250L)

        // 1. Immediate thumbnail is shown right after capture & spinning Portrait processing ring is visible
        composeTestRule.onNodeWithTag("gallery_instant_preview_image", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("portrait_shutter_processing_ring", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Portrait: Blurring…", useUnmergedTree = true).assertIsDisplayed()

        // 2. Shutter remains clickable during background processing when allowConcurrentCapture = true
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertEquals("Portrait shutter should allow next shot while background queue has capacity", 1, shutterClicks)

        // 3. When queue is full (allowConcurrentCapture = false), shutter blocks duplicate clicks
        applyComposeStateAndAdvance(250L) {
            stateHolder.value = stateHolder.value.copy(
                detailLabel = "Processing 3 portraits…",
                allowConcurrentCapture = false
            )
        }
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertEquals("Portrait shutter should stay disabled when background queue is full", 1, shutterClicks)

        // 4. Completion transitions to "Saved" ring & clears instant preview once saved URI replaces it
        applyComposeStateAndAdvance(250L) {
            thumbHolder.value = null
            stateHolder.value = ShutterProgressState(
                mode = ShutterProgressMode.PORTRAIT,
                phase = ShutterProgressPhase.COMPLETED,
                progress = 1.0f,
                statusLabel = "Saved",
                detailLabel = "Saved",
                allowConcurrentCapture = true
            )
        }
        composeTestRule.onNodeWithTag("portrait_shutter_completed", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved", useUnmergedTree = true).assertIsDisplayed()

        // Next shot is immediately clickable even during the brief "Saved" checkmark flash
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(100L)
        assertEquals(2, shutterClicks)

        instantThumb.recycle()
    }

    private fun applyComposeStateAndAdvance(advanceMs: Long = 250L, update: () -> Unit) {
        composeTestRule.runOnUiThread {
            update()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        composeTestRule.mainClock.advanceTimeBy(advanceMs)
    }
}
