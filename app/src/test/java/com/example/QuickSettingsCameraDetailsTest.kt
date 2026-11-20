package com.example

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.CameraLens
import com.example.core.camera.HdMaxPhotoCaptureSource
import com.example.core.camera.LensCapabilities
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.HdOutputOption
import com.example.core.preferences.VideoStabilizationMode
import com.example.ui.components.CameraQuickControlsSheet
import com.example.ui.components.buildQuickSettingsCameraDetailsLine
import com.example.ui.theme.PixelCameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickSettingsCameraDetailsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createRear108MpCapabilities(
        hasEis: Boolean = true,
        hasOis: Boolean = true,
        hasPreviewStab: Boolean = true
    ): LensCapabilities = LensCapabilities(
        lens = CameraLens.BACK_MAIN,
        camera2CameraId = "0",
        focalLengthMm = 3.3f,
        sensorDescription = "Rear Camera (3.3mm) · ID 0",
        supportedPhotoSizes = listOf(
            PhotoOutputSize(12000, 9000, isSensorMaxResolution = true),
            PhotoOutputSize(4000, 3000),
            PhotoOutputSize(4000, 2250)
        ),
        highestNativeStillSize = PhotoOutputSize(12000, 9000, isSensorMaxResolution = true),
        hdMaxPhotoCaptureSource = HdMaxPhotoCaptureSource(
            cameraId = "0",
            width = 12000,
            height = 9000,
            requiresMaxResPixelMode = true
        ),
        supportedVideoQualities = mapOf(
            VideoResolution.HD_720P to listOf(VideoFrameRate.FPS_30, VideoFrameRate.FPS_60),
            VideoResolution.FHD_1080P to listOf(VideoFrameRate.FPS_30, VideoFrameRate.FPS_60),
            VideoResolution.QHD_2K to listOf(VideoFrameRate.FPS_30, VideoFrameRate.FPS_60)
        ),
        supportedSlowMoRates = listOf(VideoFrameRate.FPS_120, VideoFrameRate.FPS_240),
        supportedSlowMoResolutionsByRate = mapOf(
            VideoFrameRate.FPS_120 to listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P),
            VideoFrameRate.FPS_240 to listOf(VideoResolution.HD_720P)
        ),
        supportedStabilizationModesByConfig = mapOf(
            (VideoResolution.HD_720P to VideoFrameRate.FPS_30) to listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD),
            (VideoResolution.HD_720P to VideoFrameRate.FPS_60) to listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD),
            (VideoResolution.FHD_1080P to VideoFrameRate.FPS_30) to listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD),
            (VideoResolution.FHD_1080P to VideoFrameRate.FPS_60) to listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD),
            (VideoResolution.QHD_2K to VideoFrameRate.FPS_30) to listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD),
            (VideoResolution.QHD_2K to VideoFrameRate.FPS_60) to if (hasOis) {
                listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD)
            } else {
                listOf(VideoStabilizationMode.OFF)
            }
        ),
        isStabilizationSupported = hasEis || hasOis || hasPreviewStab,
        isDigitalVideoStabilizationSupported = hasEis,
        isPreviewStabilizationSupported = hasPreviewStab,
        isOpticalStabilizationSupported = hasOis,
        isActiveStabilizationSupported = (hasEis && hasOis) || hasPreviewStab
    )

    @Test
    fun stillModes_removeFocalLength_showModeRealMegapixels_andAccurateStab() {
        val rearCaps = createRear108MpCapabilities(hasEis = true, hasOis = true)
        val prefs43 = CameraPreferences(
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            hdOutputOption = HdOutputOption.MAX_NATIVE
        )

        // HD mode uses full max-resolution 108 MP
        val hdLine = buildQuickSettingsCameraDetailsLine(rearCaps, CameraMode.HD, prefs43)
        assertEquals("Rear Camera · ID 0 · 108 MP · EIS+OIS", hdLine)
        assertFalse(hdLine.contains("mm"))

        // Standard still modes show the actual resolution saved by that mode (12 MP binned 4:3)
        val stillModes = listOf(
            CameraMode.PHOTO,
            CameraMode.PORTRAIT,
            CameraMode.NIGHT_SIGHT,
            CameraMode.PRO,
            CameraMode.PANORAMA,
            CameraMode.ASTRO
        )
        for (mode in stillModes) {
            val line = buildQuickSettingsCameraDetailsLine(rearCaps, mode, prefs43)
            assertEquals("Rear Camera · ID 0 · 12 MP · EIS+OIS", line)
            assertFalse("Focal length must never appear in $mode line", line.contains("mm"))
        }

        // Switching Photo aspect ratio to 16:9 updates MP to 9.0 MP (4000x2250 -> below 10 MP uses 1 decimal)
        val photo16x9 = buildQuickSettingsCameraDetailsLine(
            rearCaps,
            CameraMode.PHOTO,
            prefs43.copy(aspectRatioMode = AspectRatioMode.RATIO_9_16)
        )
        assertEquals("Rear Camera · ID 0 · 9.0 MP · EIS+OIS", photo16x9)
    }

    @Test
    fun subTenMegapixelCapture_usesOneDecimalEverywhere_neverRounds258UpTo3() {
        val caps26Mp = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            camera2CameraId = "0",
            sensorDescription = "Rear Camera · ID 0",
            supportedPhotoSizes = listOf(PhotoOutputSize(1856, 1392)),
            highestNativeStillSize = PhotoOutputSize(1856, 1392),
            hdMaxPhotoCaptureSource = HdMaxPhotoCaptureSource(
                cameraId = "0",
                width = 1856,
                height = 1392,
                requiresMaxResPixelMode = false
            ),
            isDigitalVideoStabilizationSupported = true,
            isOpticalStabilizationSupported = false
        )
        val prefs = CameraPreferences(
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            hdOutputOption = HdOutputOption.MAX_NATIVE
        )

        // Header line must show 2.6 MP, NEVER 3 MP
        val headerLine = buildQuickSettingsCameraDetailsLine(caps26Mp, CameraMode.HD, prefs)
        assertEquals("Rear Camera · ID 0 · 2.6 MP · EIS", headerLine)
        assertFalse(headerLine.contains("3 MP"))

        // HD mode button label uses the exact same single source of truth
        assertEquals(
            "HD · 2.6 MP",
            com.example.core.camera.CaptureResolutionResolver.formatHdModeButtonLabel(
                lensCaps = caps26Mp,
                aspectRatioMode = AspectRatioMode.RATIO_4_3,
                hdOutputOption = HdOutputOption.MAX_NATIVE
            )
        )

        // Exact pixel dimensions + MP label
        val resolved = com.example.core.camera.CaptureResolutionResolver.resolveActualCaptureSize(
            lensCaps = caps26Mp,
            activeMode = CameraMode.HD,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            hdOutputOption = HdOutputOption.MAX_NATIVE
        )
        assertEquals(
            "2.6 MP (1856 x 1392)",
            com.example.core.camera.CaptureResolutionResolver.formatMegapixelsWithDimensions(resolved)
        )
    }

    @Test
    fun videoModes_showHighestResAndMaxFpsAtIt_andRespectRealEisOis() {
        val rearEisOnly = createRear108MpCapabilities(hasEis = true, hasOis = false, hasPreviewStab = true)
        val videoPrefs30 = CameraPreferences(
            videoResolution = VideoResolution.QHD_2K,
            videoFrameRate = VideoFrameRate.FPS_30
        )

        // Preview EIS without OIS must show EIS only — never EIS+OIS
        assertEquals(
            "Rear Camera · ID 0 · 2K up to 60fps · EIS",
            buildQuickSettingsCameraDetailsLine(rearEisOnly, CameraMode.VIDEO, videoPrefs30)
        )
        assertEquals(
            "Rear Camera · ID 0 · 2K up to 60fps · EIS",
            buildQuickSettingsCameraDetailsLine(rearEisOnly, CameraMode.TIME_LAPSE, videoPrefs30)
        )
        assertEquals(
            "Rear Camera · ID 0 · 1080p up to 120fps · EIS",
            buildQuickSettingsCameraDetailsLine(rearEisOnly, CameraMode.SLOW_MOTION, videoPrefs30)
        )

        // Switching Video FPS to 60 at 2K where EIS is unsupported drops EIS dynamically
        val videoPrefs60 = videoPrefs30.copy(videoFrameRate = VideoFrameRate.FPS_60)
        assertEquals(
            "Rear Camera · ID 0 · 2K up to 60fps",
            buildQuickSettingsCameraDetailsLine(rearEisOnly, CameraMode.VIDEO, videoPrefs60)
        )

        // Camera with neither EIS nor OIS omits stabilization completely
        val noStabCaps = createRear108MpCapabilities(hasEis = false, hasOis = false, hasPreviewStab = false)
        assertEquals(
            "Rear Camera · ID 0 · 108 MP",
            buildQuickSettingsCameraDetailsLine(noStabCaps, CameraMode.HD, CameraPreferences())
        )
        assertEquals(
            "Rear Camera · ID 0 · 2K up to 60fps",
            buildQuickSettingsCameraDetailsLine(noStabCaps, CameraMode.VIDEO, videoPrefs30)
        )
    }

    @Test
    fun missingPhotoDimensions_omitsMegapixelsInsteadOfShowingWrongNumber() {
        val unknownMpCaps = LensCapabilities(
            lens = CameraLens.FRONT,
            camera2CameraId = "1",
            focalLengthMm = 2.8f,
            sensorDescription = "Front Camera (2.8mm) · ID 1",
            supportedPhotoSizes = emptyList(),
            highestNativeStillSize = null,
            hdMaxPhotoCaptureSource = null,
            isDigitalVideoStabilizationSupported = false,
            isPreviewStabilizationSupported = false,
            isOpticalStabilizationSupported = false
        )

        val line = buildQuickSettingsCameraDetailsLine(unknownMpCaps, CameraMode.PHOTO, CameraPreferences())
        assertEquals("Front Camera · ID 1", line)
        assertFalse(line.contains("MP"))
        assertFalse(line.contains("mm"))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun quickControlsSheet_keepsHeaderAndHdCardInSyncAcrossQualityRatioAndMissingDimensions() {
        val rearCaps = createRear108MpCapabilities(hasEis = true, hasOis = false, hasPreviewStab = true)
        val caps26Mp = LensCapabilities(
            lens = CameraLens.FRONT,
            camera2CameraId = "1",
            sensorDescription = "Front Camera · ID 1",
            supportedPhotoSizes = listOf(PhotoOutputSize(1856, 1392)),
            highestNativeStillSize = PhotoOutputSize(1856, 1392),
            hdMaxPhotoCaptureSource = HdMaxPhotoCaptureSource(
                cameraId = "1",
                width = 1856,
                height = 1392,
                requiresMaxResPixelMode = false
            ),
            isDigitalVideoStabilizationSupported = false,
            isPreviewStabilizationSupported = false,
            isOpticalStabilizationSupported = false
        )
        val missingSizeCaps = LensCapabilities(
            lens = CameraLens.BACK_ULTRAWIDE,
            camera2CameraId = "2",
            sensorDescription = "Rear Camera · ID 2",
            supportedPhotoSizes = emptyList(),
            highestNativeStillSize = null,
            hdMaxPhotoCaptureSource = null,
            isDigitalVideoStabilizationSupported = false,
            isPreviewStabilizationSupported = false,
            isOpticalStabilizationSupported = false
        )

        var activeLens by mutableStateOf(CameraLens.BACK_MAIN)
        var prefs by mutableStateOf(
            CameraPreferences(
                aspectRatioMode = AspectRatioMode.RATIO_4_3,
                hdOutputOption = HdOutputOption.MAX_NATIVE
            )
        )

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraQuickControlsSheet(
                    activeMode = CameraMode.HD,
                    preferences = prefs,
                    capabilitiesState = CameraCapabilitiesState.Verified(
                        activeLens = activeLens,
                        availableLenses = listOf(CameraLens.BACK_MAIN, CameraLens.FRONT, CameraLens.BACK_ULTRAWIDE),
                        capabilitiesByLens = mapOf(
                            CameraLens.BACK_MAIN to rearCaps,
                            CameraLens.FRONT to caps26Mp,
                            CameraLens.BACK_ULTRAWIDE to missingSizeCaps
                        )
                    ),
                    onFlashChanged = {},
                    onTimerChanged = {},
                    onAspectRatioChanged = { prefs = prefs.copy(aspectRatioMode = it) },
                    onGridChanged = {},
                    onVideoResolutionChanged = {},
                    onVideoFpsChanged = {},
                    onHdOptionChanged = { prefs = prefs.copy(hdOutputOption = it) },
                    onOpenFullSettings = {},
                    onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                )
            }
        }

        // 1. Highest Native Output (4:3) -> both header and HD card report 108 MP (12000 x 9000)
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Rear Camera · ID 0 · 108 MP · EIS")
        composeTestRule.onNodeWithTag("hd_resolution_card_title")
            .assertTextEquals("HD · 108 MP")
        composeTestRule.onNodeWithTag("hd_resolution_card_body")
            .assertTextEquals("Target output: 108 MP (12000 x 9000) native sensor capture.")

        // 2. Switch HD Capture Quality to Standard Binned -> BOTH header and HD card switch to 12 MP (4000 x 3000)
        prefs = prefs.copy(hdOutputOption = HdOutputOption.STANDARD_BINNED)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Rear Camera · ID 0 · 12 MP · EIS")
        composeTestRule.onNodeWithTag("hd_resolution_card_title")
            .assertTextEquals("HD · 12 MP")
        composeTestRule.onNodeWithTag("hd_resolution_card_body")
            .assertTextEquals("Target output: 12 MP (4000 x 3000) standard binned capture.")

        // 3. Switch aspect ratio to 9:16 (16:9 landscape stream 4000 x 2250) -> BOTH show 9.0 MP
        prefs = prefs.copy(aspectRatioMode = AspectRatioMode.RATIO_9_16)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Rear Camera · ID 0 · 9.0 MP · EIS")
        composeTestRule.onNodeWithTag("hd_resolution_card_title")
            .assertTextEquals("HD · 9.0 MP")
        composeTestRule.onNodeWithTag("hd_resolution_card_body")
            .assertTextEquals("Target output: 9.0 MP (4000 x 2250) standard binned capture.")

        // 4. Switch to 1856 x 1392 camera at 4:3 -> BOTH show 2.6 MP (NEVER 3 MP)
        activeLens = CameraLens.FRONT
        prefs = prefs.copy(aspectRatioMode = AspectRatioMode.RATIO_4_3, hdOutputOption = HdOutputOption.MAX_NATIVE)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Front Camera · ID 1 · 2.6 MP")
        composeTestRule.onNodeWithTag("hd_resolution_card_title")
            .assertTextEquals("HD · 2.6 MP")
        composeTestRule.onNodeWithTag("hd_resolution_card_body")
            .assertTextEquals("Target output: 2.6 MP (1856 x 1392) native sensor capture.")

        // 5. Switch 1856 x 1392 camera to 9:16 crop -> 1856 x 1044 = 1.937 MP -> BOTH show 1.9 MP
        prefs = prefs.copy(aspectRatioMode = AspectRatioMode.RATIO_9_16)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Front Camera · ID 1 · 1.9 MP")
        composeTestRule.onNodeWithTag("hd_resolution_card_title")
            .assertTextEquals("HD · 1.9 MP")
        composeTestRule.onNodeWithTag("hd_resolution_card_body")
            .assertTextEquals("Target output: 1.9 MP (1856 x 1044) native sensor capture.")

        // 6. Camera with missing dimensions -> BOTH header and HD card omit MP completely (no hardcoded fallback)
        activeLens = CameraLens.BACK_ULTRAWIDE
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Rear Camera · Ultrawide · ID 2")
        composeTestRule.onNodeWithTag("hd_resolution_card_title")
            .assertTextEquals("HD")
        composeTestRule.onNodeWithTag("hd_resolution_card_body")
            .assertTextEquals("Target output: Dynamic native output for Rear Camera · Ultrawide.")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun quickControlsSheet_updatesDetailsLineWhenSwitchingCameraModeResolutionAndFps() {
        val rearCaps = createRear108MpCapabilities(hasEis = true, hasOis = false, hasPreviewStab = true)
        val frontCaps = LensCapabilities(
            lens = CameraLens.FRONT,
            camera2CameraId = "1",
            focalLengthMm = 2.4f,
            sensorDescription = "Front Camera (2.4mm) · ID 1",
            supportedPhotoSizes = listOf(PhotoOutputSize(4608, 3456)),
            highestNativeStillSize = PhotoOutputSize(4608, 3456),
            supportedVideoQualities = mapOf(
                VideoResolution.HD_720P to listOf(VideoFrameRate.FPS_30),
                VideoResolution.FHD_1080P to listOf(VideoFrameRate.FPS_30)
            ),
            isDigitalVideoStabilizationSupported = false,
            isPreviewStabilizationSupported = false,
            isOpticalStabilizationSupported = false
        )

        var activeLens by mutableStateOf(CameraLens.BACK_MAIN)
        var activeMode by mutableStateOf(CameraMode.HD)
        var prefs by mutableStateOf(
            CameraPreferences(
                aspectRatioMode = AspectRatioMode.RATIO_4_3,
                hdOutputOption = HdOutputOption.MAX_NATIVE,
                videoResolution = VideoResolution.QHD_2K,
                videoFrameRate = VideoFrameRate.FPS_30
            )
        )

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraQuickControlsSheet(
                    activeMode = activeMode,
                    preferences = prefs,
                    capabilitiesState = CameraCapabilitiesState.Verified(
                        activeLens = activeLens,
                        availableLenses = listOf(CameraLens.BACK_MAIN, CameraLens.FRONT),
                        capabilitiesByLens = mapOf(
                            CameraLens.BACK_MAIN to rearCaps,
                            CameraLens.FRONT to frontCaps
                        )
                    ),
                    onFlashChanged = {},
                    onTimerChanged = {},
                    onAspectRatioChanged = { prefs = prefs.copy(aspectRatioMode = it) },
                    onGridChanged = {},
                    onVideoResolutionChanged = { prefs = prefs.copy(videoResolution = it) },
                    onVideoFpsChanged = { prefs = prefs.copy(videoFrameRate = it) },
                    onOpenFullSettings = {},
                    onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                )
            }
        }

        // 1. Rear HD mode -> 108 MP + EIS
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertIsDisplayed()
            .assertTextEquals("Rear Camera · ID 0 · 108 MP · EIS")

        // 2. Switch to Video mode (2K @ 30fps)
        activeMode = CameraMode.VIDEO
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Rear Camera · ID 0 · 2K up to 60fps · EIS")

        // 3. Switch FPS to 60 at 2K -> EIS unavailable at 2K60 so EIS disappears
        prefs = prefs.copy(videoFrameRate = VideoFrameRate.FPS_60)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Rear Camera · ID 0 · 2K up to 60fps")

        // 4. Switch camera to Front -> updates to Front Camera · ID 1 · 1080p up to 30fps
        activeLens = CameraLens.FRONT
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Front Camera · ID 1 · 1080p up to 30fps")

        // 5. Switch Front camera to Photo mode -> 16 MP (4608x3456 = 15.93 MP rounded)
        activeMode = CameraMode.PHOTO
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_settings_camera_details_line")
            .assertTextEquals("Front Camera · ID 1 · 16 MP")
    }
}
