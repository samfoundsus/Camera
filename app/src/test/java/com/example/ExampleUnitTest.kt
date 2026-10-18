package com.example

import com.example.core.camera.AspectRatioPreference
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.CameraEngineState
import com.example.core.camera.CameraLens
import com.example.core.camera.DefaultCameraCapabilitiesRepository
import com.example.core.camera.LensCapabilities
import com.example.core.camera.LensFacing
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.model.CameraMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun cameraEngineState_initialDefaults() {
        val state = CameraEngineState()
        assertFalse(state.isInitialized)
        assertEquals(CameraMode.PHOTO, state.activeMode)
        assertEquals(LensFacing.BACK, state.lensFacing)
        assertEquals(AspectRatioPreference.RATIO_4_3, state.aspectRatio)
    }

    @Test
    fun cameraMode_displayNames() {
        assertEquals("Night Sight", CameraMode.NIGHT_SIGHT.displayName)
        assertEquals("Portrait", CameraMode.PORTRAIT.displayName)
        assertEquals("Camera", CameraMode.PHOTO.displayName)
        assertEquals("HD", CameraMode.HD.displayName)
        assertEquals("Pro", CameraMode.PRO.displayName)
        assertEquals("Video", CameraMode.VIDEO.displayName)
        assertEquals("Cinematic", CameraMode.CINEMATIC.displayName)
        assertEquals("Slow Motion", CameraMode.SLOW_MOTION.displayName)
        assertEquals("Time Lapse", CameraMode.TIME_LAPSE.displayName)
        assertEquals("Panorama", CameraMode.PANORAMA.displayName)
        assertEquals(10, CameraMode.entries.size)
    }

    @Test
    fun videoResolutions_exactDimensionsConfigured() {
        assertEquals(1280, VideoResolution.HD_720P.width)
        assertEquals(720, VideoResolution.HD_720P.height)
        assertEquals(1920, VideoResolution.FHD_1080P.width)
        assertEquals(1080, VideoResolution.FHD_1080P.height)
        // 2K / 1440p definition verification
        assertEquals(2560, VideoResolution.QHD_2K.width)
        assertEquals(1440, VideoResolution.QHD_2K.height)
        assertEquals("2K / 1440p", VideoResolution.QHD_2K.displayName)
        // 4K UHD definition verification
        assertEquals(3840, VideoResolution.UHD_4K.width)
        assertEquals(2160, VideoResolution.UHD_4K.height)
    }

    @Test
    fun photoOutputSize_calculatesMegapixelsAccuratelyWithoutFabrication() {
        val size12mp = PhotoOutputSize(width = 4032, height = 3024)
        assertEquals(12.19f, size12mp.megapixels, 0.05f)
        assertEquals("12.2 MP", size12mp.formattedMegapixels)
        assertEquals("4032 × 3024", size12mp.dimensionsLabel)

        val size50mp = PhotoOutputSize(width = 8192, height = 6144, isSensorMaxResolution = true)
        assertEquals(50.33f, size50mp.megapixels, 0.05f)
        assertEquals("50.3 MP", size50mp.formattedMegapixels)
        assertTrue(size50mp.isSensorMaxResolution)
    }

    @Test
    fun capabilitiesRepository_initialStateIsPendingDiscovery() {
        val repo = DefaultCameraCapabilitiesRepository()
        val state = repo.capabilitiesState.value
        assertTrue(state is CameraCapabilitiesState.PendingDiscovery)
        assertEquals(CameraLens.BACK_MAIN, (state as CameraCapabilitiesState.PendingDiscovery).activeLens)

        repo.setActiveLens(CameraLens.FRONT)
        assertEquals(CameraLens.FRONT, repo.activeLens.value)
    }

    @Test
    fun cameraPreferences_defaultValues() {
        val prefs = com.example.core.preferences.CameraPreferences()
        assertEquals(com.example.core.preferences.AppThemePreference.SYSTEM, prefs.themePreference)
        assertEquals(com.example.core.preferences.FlashMode.AUTO, prefs.flashMode)
        assertEquals(com.example.core.preferences.TimerMode.OFF, prefs.timerMode)
        assertEquals(com.example.core.preferences.GridMode.GRID_3X3, prefs.gridMode)
        assertEquals(com.example.core.preferences.AspectRatioMode.RATIO_4_3, prefs.aspectRatioMode)
        assertEquals(VideoResolution.FHD_1080P, prefs.videoResolution)
        assertEquals(VideoFrameRate.FPS_30, prefs.videoFrameRate)
        assertEquals(com.example.core.preferences.HdOutputOption.MAX_NATIVE, prefs.hdOutputOption)
        assertTrue(prefs.cameraSoundsEnabled)
        assertTrue(prefs.dynamicColor)
        assertFalse(prefs.saveLocation)
        assertTrue(prefs.selfieMirror)
    }

    @Test
    fun gridMode_supportsAllSpecifiedOptions() {
        val options = com.example.core.preferences.GridMode.entries
        assertEquals(4, options.size)
        assertTrue(options.contains(com.example.core.preferences.GridMode.OFF))
        assertTrue(options.contains(com.example.core.preferences.GridMode.GRID_3X3))
        assertTrue(options.contains(com.example.core.preferences.GridMode.GRID_4X4))
        assertTrue(options.contains(com.example.core.preferences.GridMode.GOLDEN_RATIO))
    }

    @Test
    fun aspectRatioMode_trueRatiosConfigured() {
        // Portrait 4:3 option (width:height = 3:4)
        assertEquals("4:3", com.example.core.preferences.AspectRatioMode.RATIO_4_3.displayName)
        assertEquals(3f / 4f, com.example.core.preferences.AspectRatioMode.RATIO_4_3.ratioFloat!!, 0.001f)

        // 1:1 Square option (width:height = 1:1)
        assertEquals("1:1", com.example.core.preferences.AspectRatioMode.RATIO_1_1.displayName)
        assertEquals(1f, com.example.core.preferences.AspectRatioMode.RATIO_1_1.ratioFloat!!, 0.001f)

        // 9:16 option (width:height = 9:16)
        assertEquals("9:16", com.example.core.preferences.AspectRatioMode.RATIO_9_16.displayName)
        assertEquals(9f / 16f, com.example.core.preferences.AspectRatioMode.RATIO_9_16.ratioFloat!!, 0.001f)

        assertEquals(3, com.example.core.preferences.AspectRatioMode.entries.size)
    }

    @Test
    fun previewFitting_neverExceedsAvailableBounds() {
        val availWidth = 380f
        val availHeight = 520f

        // Test 4:3 portrait fitting (width:height = 3:4)
        val ratio43 = 3f / 4f
        val h43 = availWidth / ratio43
        val fitsH43 = h43 <= availHeight
        val (finalW43, finalH43) = if (fitsH43) availWidth to h43 else (availHeight * ratio43) to availHeight
        assertTrue(finalW43 <= availWidth)
        assertTrue(finalH43 <= availHeight)
        assertEquals(3f / 4f, finalW43 / finalH43, 0.001f)

        // Test 1:1 square fitting (width:height = 1:1)
        val ratio11 = 1f
        val h11 = availWidth / ratio11
        val fitsH11 = h11 <= availHeight
        val (finalW11, finalH11) = if (fitsH11) availWidth to h11 else (availHeight * ratio11) to availHeight
        assertTrue(finalW11 <= availWidth)
        assertTrue(finalH11 <= availHeight)
        assertEquals(finalW11, finalH11, 0.001f)
        assertEquals(1f, finalW11 / finalH11, 0.001f)
    }

    @Test
    fun videoCapabilities_rearAndFrontFallbackReconciliation() {
        val rearCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            supportedVideoQualities = mapOf(
                VideoResolution.HD_720P to listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30, VideoFrameRate.FPS_60),
                VideoResolution.FHD_1080P to listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30, VideoFrameRate.FPS_60),
                VideoResolution.QHD_2K to listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30),
                VideoResolution.UHD_4K to listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30)
            ),
            isStabilizationSupported = true,
            isActiveStabilizationSupported = true,
            isOpticalStabilizationSupported = true,
            isTorchSupported = true
        )

        val frontCaps = LensCapabilities(
            lens = CameraLens.FRONT,
            supportedVideoQualities = mapOf(
                VideoResolution.HD_720P to listOf(VideoFrameRate.FPS_30),
                VideoResolution.FHD_1080P to listOf(VideoFrameRate.FPS_30)
            ),
            isStabilizationSupported = true,
            isActiveStabilizationSupported = false,
            isOpticalStabilizationSupported = false,
            isTorchSupported = false
        )

        // 1. Rear camera supports 720p, 1080p, 2K, 4K; at 4K it supports 24 & 30 FPS (falls back from 60 FPS -> 30 FPS)
        assertEquals(
            listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P, VideoResolution.QHD_2K, VideoResolution.UHD_4K),
            rearCaps.getSupportedVideoModeResolutions()
        )
        assertEquals(
            listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30, VideoFrameRate.FPS_60),
            rearCaps.getSupportedVideoModeFrameRates(VideoResolution.FHD_1080P)
        )
        assertEquals(
            listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30),
            rearCaps.getSupportedVideoModeFrameRates(VideoResolution.UHD_4K)
        )

        val rear4kReconciled = rearCaps.reconcileVideoSettings(
            requestedResolution = VideoResolution.UHD_4K,
            requestedFrameRate = VideoFrameRate.FPS_60,
            requestedStabilization = com.example.core.preferences.VideoStabilizationMode.ACTIVE,
            requestedFlashMode = com.example.core.preferences.FlashMode.TORCH
        )
        assertEquals(VideoResolution.UHD_4K, rear4kReconciled.resolution)
        assertEquals(VideoFrameRate.FPS_30, rear4kReconciled.frameRate)
        assertEquals(com.example.core.preferences.VideoStabilizationMode.STANDARD, rear4kReconciled.stabilization)
        assertEquals(com.example.core.preferences.FlashMode.TORCH, rear4kReconciled.flashMode)

        // Rear camera preserves 24 FPS at 4K when supported
        val rear4k24Reconciled = rearCaps.reconcileVideoSettings(
            requestedResolution = VideoResolution.UHD_4K,
            requestedFrameRate = VideoFrameRate.FPS_24,
            requestedStabilization = com.example.core.preferences.VideoStabilizationMode.STANDARD,
            requestedFlashMode = com.example.core.preferences.FlashMode.OFF
        )
        assertEquals(VideoResolution.UHD_4K, rear4k24Reconciled.resolution)
        assertEquals(VideoFrameRate.FPS_24, rear4k24Reconciled.frameRate)

        // Rear camera at 1080p 30fps supports Active stabilization
        val rear1080pReconciled = rearCaps.reconcileVideoSettings(
            requestedResolution = VideoResolution.FHD_1080P,
            requestedFrameRate = VideoFrameRate.FPS_30,
            requestedStabilization = com.example.core.preferences.VideoStabilizationMode.ACTIVE,
            requestedFlashMode = com.example.core.preferences.FlashMode.TORCH
        )
        assertEquals(VideoResolution.FHD_1080P, rear1080pReconciled.resolution)
        assertEquals(VideoFrameRate.FPS_30, rear1080pReconciled.frameRate)
        assertEquals(com.example.core.preferences.VideoStabilizationMode.ACTIVE, rear1080pReconciled.stabilization)

        // 2. Switching to Front camera when 4K / 60fps / Active / Torch were selected
        // Must fall back to 1080p, 30fps, Standard stabilization, and Flash Off
        val frontReconciled = frontCaps.reconcileVideoSettings(
            requestedResolution = VideoResolution.UHD_4K,
            requestedFrameRate = VideoFrameRate.FPS_60,
            requestedStabilization = com.example.core.preferences.VideoStabilizationMode.ACTIVE,
            requestedFlashMode = com.example.core.preferences.FlashMode.TORCH
        )
        assertEquals(VideoResolution.FHD_1080P, frontReconciled.resolution)
        assertEquals(VideoFrameRate.FPS_30, frontReconciled.frameRate)
        assertEquals(com.example.core.preferences.VideoStabilizationMode.STANDARD, frontReconciled.stabilization)
        assertEquals(com.example.core.preferences.FlashMode.OFF, frontReconciled.flashMode)
        assertEquals(
            listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P),
            frontCaps.getSupportedVideoModeResolutions()
        )
        assertFalse(frontCaps.isResolutionSupported(VideoResolution.QHD_2K))
        assertFalse(frontCaps.isResolutionSupported(VideoResolution.UHD_4K))
        assertFalse(VideoFrameRate.FPS_24 in frontCaps.getSupportedVideoModeFrameRates(VideoResolution.FHD_1080P))
        assertFalse(VideoFrameRate.FPS_60 in frontCaps.getSupportedVideoModeFrameRates(VideoResolution.FHD_1080P))
        assertFalse(frontCaps.isStabilizationModeSupported(com.example.core.preferences.VideoStabilizationMode.ACTIVE))
    }

    @Test
    fun zoomRangeResolution_separates20xTargetFromHardwareMaximum_andGeneratesShortcuts() {
        // 1. Rear logical multi-camera supporting 0.5x ultrawide up to 20.0x target
        val range20x = com.example.core.camera.resolveSupportedZoomRange(0.5f, 20.0f)
        assertEquals(0.5f, range20x.start, 0.001f)
        assertEquals(20.0f, range20x.endInclusive, 0.001f)
        assertEquals(
            listOf(0.5f, 1.0f, 2.0f, 5.0f, 20.0f),
            com.example.core.camera.generateZoomShortcuts(0.5f, 20.0f)
        )

        // 2. Device supporting > 20x (e.g. 30.0x) allows zooming up to actual supported 30.0x maximum
        val range30x = com.example.core.camera.resolveSupportedZoomRange(0.5f, 30.0f)
        assertEquals(0.5f, range30x.start, 0.001f)
        assertEquals(30.0f, range30x.endInclusive, 0.001f)
        assertEquals(
            listOf(0.5f, 1.0f, 2.0f, 5.0f, 20.0f, 30.0f),
            com.example.core.camera.generateZoomShortcuts(0.5f, 30.0f)
        )

        // 3. Device supporting < 20x (e.g. 1.0x..8.0x single rear camera) caps at 8.0x and never fabricates 0.5x or 20x
        val range8x = com.example.core.camera.resolveSupportedZoomRange(1.0f, 8.0f)
        assertEquals(1.0f, range8x.start, 0.001f)
        assertEquals(8.0f, range8x.endInclusive, 0.001f)
        val shortcuts8x = com.example.core.camera.generateZoomShortcuts(1.0f, 8.0f)
        assertEquals(listOf(1.0f, 2.0f, 5.0f, 8.0f), shortcuts8x)
        assertFalse(0.5f in shortcuts8x)
        assertFalse(20.0f in shortcuts8x)

        // 4. Front selfie camera supporting 1.0x..4.0x caps at 4.0x and generates [1.0, 2.0, 4.0]
        val selfieShortcuts = com.example.core.camera.generateZoomShortcuts(1.0f, 4.0f)
        assertEquals(listOf(1.0f, 2.0f, 4.0f), selfieShortcuts)

        // 5. Clamping & formatting verification
        assertEquals(4.0f, com.example.core.camera.clampZoomRatio(20.0f, 1.0f, 4.0f), 0.001f)
        assertEquals(1.0f, com.example.core.camera.clampZoomRatio(0.5f, 1.0f, 4.0f), 0.001f)
        assertEquals("1.0x", com.example.core.camera.formatZoomRatio(1.0f, asCompactShortcut = false))
        assertEquals("5.0x", com.example.core.camera.formatZoomRatio(5.0f, asCompactShortcut = false))
        assertEquals("20.0x", com.example.core.camera.formatZoomRatio(20.0f, asCompactShortcut = false))
        assertEquals("0.5x", com.example.core.camera.formatZoomRatio(0.5f, asCompactShortcut = true))
        assertEquals("20x", com.example.core.camera.formatZoomRatio(20.0f, asCompactShortcut = true))
    }

    @Test
    fun pinchToZoomCalculation_scalesSmoothly_andClampsToHardwareBounds() {
        // 1. Pinching outward from 1.0x by 2.5x scale factor on rear camera (0.5x..20.0x)
        val zoomedIn = com.example.core.camera.calculatePinchZoomRatio(
            currentZoomRatio = 1.0f,
            pinchScaleFactor = 2.5f,
            minZoomRatio = 0.5f,
            maxZoomRatio = 20.0f
        )
        assertEquals(2.5f, zoomedIn, 0.001f)

        // 2. Pinching outward beyond hardware maximum (e.g. 5.0x * 6.0 = 30.0x on 20.0x max camera) clamps to 20.0x
        val clampedAtRearMax = com.example.core.camera.calculatePinchZoomRatio(
            currentZoomRatio = 5.0f,
            pinchScaleFactor = 6.0f,
            minZoomRatio = 0.5f,
            maxZoomRatio = 20.0f
        )
        assertEquals(20.0f, clampedAtRearMax, 0.001f)

        // 3. Pinching inward below hardware minimum (e.g. 1.0x * 0.3 = 0.3x on 0.5x ultrawide min) clamps to 0.5x
        val clampedAtRearMin = com.example.core.camera.calculatePinchZoomRatio(
            currentZoomRatio = 1.0f,
            pinchScaleFactor = 0.3f,
            minZoomRatio = 0.5f,
            maxZoomRatio = 20.0f
        )
        assertEquals(0.5f, clampedAtRearMin, 0.001f)

        // 4. Front selfie camera (1.0x..4.0x) clamps outward pinch at 4.0x and inward pinch at 1.0x
        val frontZoomMax = com.example.core.camera.calculatePinchZoomRatio(
            currentZoomRatio = 2.0f,
            pinchScaleFactor = 3.0f,
            minZoomRatio = 1.0f,
            maxZoomRatio = 4.0f
        )
        assertEquals(4.0f, frontZoomMax, 0.001f)

        val frontZoomMin = com.example.core.camera.calculatePinchZoomRatio(
            currentZoomRatio = 1.5f,
            pinchScaleFactor = 0.4f,
            minZoomRatio = 1.0f,
            maxZoomRatio = 4.0f
        )
        assertEquals(1.0f, frontZoomMin, 0.001f)
    }

    @Test
    fun tapToFocusCoordinateNormalization_andExposureCompensationClamping() {
        // 1. Rear camera coordinate normalization (unmirrored)
        val rearCoord = com.example.core.camera.computeNormalizedMeteringCoordinate(
            tapX = 250f,
            tapY = 600f,
            previewWidth = 1000f,
            previewHeight = 1200f,
            isFrontCamera = false,
            isSelfieMirrorEnabled = true
        )
        assertEquals(0.25f, rearCoord.x, 0.001f)
        assertEquals(0.50f, rearCoord.y, 0.001f)
        assertFalse(rearCoord.isMirroredForFrontCamera)

        // 2. Front selfie camera coordinate normalization (horizontally mirrored: 1.0 - 0.25 = 0.75)
        val frontCoord = com.example.core.camera.computeNormalizedMeteringCoordinate(
            tapX = 250f,
            tapY = 600f,
            previewWidth = 1000f,
            previewHeight = 1200f,
            isFrontCamera = true,
            isSelfieMirrorEnabled = true
        )
        assertEquals(0.75f, frontCoord.x, 0.001f)
        assertEquals(0.50f, frontCoord.y, 0.001f)
        assertTrue(frontCoord.isMirroredForFrontCamera)

        // 3. Out-of-bounds tap coordinates clamp safely to [0, previewWidth] and [0, previewHeight]
        val clampedCoord = com.example.core.camera.computeNormalizedMeteringCoordinate(
            tapX = -50f,
            tapY = 2500f,
            previewWidth = 1000f,
            previewHeight = 1200f,
            isFrontCamera = false
        )
        assertEquals(0.0f, clampedCoord.x, 0.001f)
        assertEquals(1.0f, clampedCoord.y, 0.001f)

        // 4. Exposure compensation index clamping & EV conversion for rear (-12..12 @ 1/3 EV) and front (-4..4 @ 1/2 EV)
        assertEquals(12, com.example.core.camera.clampExposureIndex(18, -12..12))
        assertEquals(-12, com.example.core.camera.clampExposureIndex(-20, -12..12))
        assertEquals(4, com.example.core.camera.clampExposureIndex(9, -4..4))
        assertEquals(-4, com.example.core.camera.clampExposureIndex(-9, -4..4))

        assertEquals("0.0 EV", com.example.core.camera.formatEvValue(0, 1f / 3f))
        assertEquals("+1.0 EV", com.example.core.camera.formatEvValue(3, 1f / 3f))
        assertEquals("-0.7 EV", com.example.core.camera.formatEvValue(-2, 1f / 3f))
        assertEquals("+2.0 EV", com.example.core.camera.formatEvValue(4, 0.5f))

        // 5. Mode support: all non-Pro modes support tap-to-focus; Pro mode is strictly excluded
        assertTrue(com.example.core.camera.isTapToFocusEnabledForMode(com.example.core.model.CameraMode.PHOTO))
        assertTrue(com.example.core.camera.isTapToFocusEnabledForMode(com.example.core.model.CameraMode.PORTRAIT))
        assertTrue(com.example.core.camera.isTapToFocusEnabledForMode(com.example.core.model.CameraMode.NIGHT_SIGHT))
        assertTrue(com.example.core.camera.isTapToFocusEnabledForMode(com.example.core.model.CameraMode.VIDEO))
        assertTrue(com.example.core.camera.isTapToFocusEnabledForMode(com.example.core.model.CameraMode.HD))
        assertFalse(com.example.core.camera.isTapToFocusEnabledForMode(com.example.core.model.CameraMode.PRO))
    }

    @Test
    fun phase254_preferencesAndIsolation_verification() {
        val prefs = com.example.core.preferences.CameraPreferences(
            volumeButtonAction = com.example.core.preferences.VolumeButtonAction.SHUTTER,
            saveLocation = false,
            cameraSoundsEnabled = true,
            proIso = "800",
            proShutterSpeed = "1/250",
            proFocusMode = com.example.core.preferences.ProFocusMode.MANUAL
        )

        // 1. Verify VolumeButtonAction defaults and custom setting
        assertEquals(com.example.core.preferences.VolumeButtonAction.SHUTTER, prefs.volumeButtonAction)
        assertEquals("Shutter / Start recording", prefs.volumeButtonAction.displayName)

        // 2. Pro mode parameters do not bleed into non-Pro automatic defaults
        val defaultPrefs = com.example.core.preferences.CameraPreferences()
        assertEquals("AUTO", defaultPrefs.proIso)
        assertEquals("AUTO", defaultPrefs.proShutterSpeed)
        assertEquals(com.example.core.preferences.ProFocusMode.AUTO, defaultPrefs.proFocusMode)
        assertEquals(com.example.core.preferences.AspectRatioMode.RATIO_4_3, defaultPrefs.aspectRatioMode)

        // 3. AspectRatioMode excludes removed FULL option
        val aspectRatios = com.example.core.preferences.AspectRatioMode.entries
        assertTrue(aspectRatios.contains(com.example.core.preferences.AspectRatioMode.RATIO_4_3))
        assertTrue(aspectRatios.contains(com.example.core.preferences.AspectRatioMode.RATIO_1_1))
        assertTrue(aspectRatios.contains(com.example.core.preferences.AspectRatioMode.RATIO_9_16))
        assertEquals(3, aspectRatios.size)

        // 4. Fallback verification: Lens without 4K / 60FPS safely resolves to supported FHD 1080p 30FPS
        val caps = com.example.core.camera.LensCapabilities(
            lens = com.example.core.camera.CameraLens.BACK_MAIN,
            supportedVideoQualities = mapOf(
                com.example.core.camera.VideoResolution.FHD_1080P to listOf(com.example.core.camera.VideoFrameRate.FPS_30),
                com.example.core.camera.VideoResolution.HD_720P to listOf(com.example.core.camera.VideoFrameRate.FPS_30)
            ),
            isStabilizationSupported = true
        )

        val reconciled = caps.reconcileVideoSettings(
            requestedResolution = com.example.core.camera.VideoResolution.UHD_4K,
            requestedFrameRate = com.example.core.camera.VideoFrameRate.FPS_60,
            requestedStabilization = com.example.core.preferences.VideoStabilizationMode.ACTIVE,
            requestedFlashMode = com.example.core.preferences.FlashMode.AUTO
        )

        assertEquals(com.example.core.camera.VideoResolution.FHD_1080P, reconciled.resolution)
        assertEquals(com.example.core.camera.VideoFrameRate.FPS_30, reconciled.frameRate)
        assertEquals(com.example.core.preferences.VideoStabilizationMode.STANDARD, reconciled.stabilization)
    }
}

