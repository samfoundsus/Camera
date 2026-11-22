package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.camera.FilterDebugTracker
import com.example.core.camera.NightSightCapturePhase
import com.example.core.camera.NightSightProgressState
import com.example.core.camera.SHUTTER_PROGRESS_SHOW_DELAY_MS
import com.example.core.camera.ShutterProgressMode
import com.example.core.camera.ShutterProgressPhase
import com.example.core.camera.ShutterProgressState
import com.example.core.camera.UiPerformanceMonitor
import com.example.core.camera.formatAstroCaptureCaption
import com.example.core.camera.formatPanoramaProgressCaption
import com.example.core.camera.formatTimeLapseCaptureCaption
import com.example.core.camera.formatVideoRecordingCaption
import com.example.core.camera.toShutterProgressMode
import com.example.core.camera.toShutterProgressState
import com.example.core.model.CameraMode
import com.example.core.preferences.CameraFilterPreset
import com.example.core.processing.CameraFilterProcessor
import com.example.core.processing.GpuCameraFilterEngine
import com.example.core.processing.ProcessedPhotoPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterAndUnifiedShutterVerificationTest {

    @Before
    fun setUp() {
        FilterDebugTracker.clear()
        UiPerformanceMonitor.clear()
    }

    @Test
    fun filterModeScoping_onlyPhotoAndVideoApplyNonOriginalFilters() {
        for (mode in CameraMode.entries) {
            val resolved = CameraFilterProcessor.resolveEffectiveFilter(
                mode = mode,
                selectedFilter = CameraFilterPreset.CINEMATIC
            )
            if (mode == CameraMode.PHOTO || mode == CameraMode.VIDEO) {
                assertTrue("Mode ${mode.name} should support filters", mode.supportsFilters)
                assertEquals(CameraFilterPreset.CINEMATIC, resolved)
            } else {
                assertFalse("Mode ${mode.name} must not apply filters", mode.supportsFilters)
                assertEquals(
                    "Mode ${mode.name} must resolve effective filter to ORIGINAL",
                    CameraFilterPreset.ORIGINAL,
                    resolved
                )
            }
        }
    }

    @Test
    fun filterPipeline_allNonOriginalPresetsModifyBitmapAndOriginalBypassesCleanly() {
        val sample = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        for (y in 0 until 24) {
            for (x in 0 until 24) {
                sample.setPixel(
                    x,
                    y,
                    Color.rgb(
                        (30 + x * 9).coerceIn(0, 255),
                        (50 + y * 7).coerceIn(0, 255),
                        (200 - x * 6).coerceIn(0, 255)
                    )
                )
            }
        }

        // ORIGINAL bypass
        val origBitmap = CameraFilterProcessor.applyFilterToBitmap(sample, CameraFilterPreset.ORIGINAL)
        assertEquals("Bypass(Original)", CameraFilterProcessor.lastStillFilterBackend)
        assertEquals(sample.getPixel(12, 12), origBitmap.getPixel(12, 12))

        val bos = ByteArrayOutputStream()
        sample.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        val rawPayload = ProcessedPhotoPayload(
            jpegBytes = bos.toByteArray(),
            width = 24,
            height = 24,
            wasRecompressed = false
        )
        val unchangedPayload = CameraFilterProcessor.applyFilterToPhotoPayload(rawPayload, CameraFilterPreset.ORIGINAL)
        assertSame(rawPayload, unchangedPayload)

        // Every non-Original preset produces visible pixel changes
        for (preset in CameraFilterPreset.entries) {
            if (preset == CameraFilterPreset.ORIGINAL) continue
            val out = CameraFilterProcessor.applyFilterToBitmap(sample, preset)
            var changedCount = 0
            for (y in 0 until 24) {
                for (x in 0 until 24) {
                    if (out.getPixel(x, y) != sample.getPixel(x, y)) {
                        changedCount++
                    }
                }
            }
            assertTrue(
                "Preset ${preset.name} must visibly transform sample pixels (changed=$changedCount)",
                changedCount > 100
            )
            assertNotEquals("Bypass(Original)", CameraFilterProcessor.lastStillFilterBackend)
            out.recycle()
        }

        val monoPayload = CameraFilterProcessor.applyFilterToPhotoPayload(rawPayload, CameraFilterPreset.NOIR)
        assertTrue(monoPayload.wasRecompressed)
        assertFalse(monoPayload.jpegBytes.contentEquals(rawPayload.jpegBytes))

        origBitmap.recycle()
        sample.recycle()
    }

    @Test
    fun gpuShaders_containAllRequiredUniformsAndSamplerModes() {
        val oesShader = GpuCameraFilterEngine.buildFilterFragmentShader(isExternalOes = true)
        assertTrue(oesShader.contains("#extension GL_OES_EGL_image_external : require"))
        assertTrue(oesShader.contains("uniform samplerExternalOES uTexture;"))

        val tex2dShader = GpuCameraFilterEngine.buildFilterFragmentShader(isExternalOes = false)
        assertFalse(tex2dShader.contains("GL_OES_EGL_image_external"))
        assertTrue(tex2dShader.contains("uniform sampler2D uTexture;"))

        val agslShader = GpuCameraFilterEngine.buildAgslFilterShaderSource()
        assertTrue(agslShader.contains("uniform shader inputShader;"))

        val requiredUniforms = listOf(
            "uIsIdentity",
            "uIntensity",
            "uIsMono",
            "uContrast",
            "uSaturation",
            "uWarmth",
            "uTint",
            "uExposure",
            "uShadowLift",
            "uHighlightRolloff",
            "uSCurve",
            "uSkinProtect",
            "uLumaWeights",
            "uShadowTint",
            "uHighlightTint",
            "evalToneCurve"
        )
        for (symbol in requiredUniforms) {
            assertTrue("OES shader missing $symbol", oesShader.contains(symbol))
            assertTrue("2D shader missing $symbol", tex2dShader.contains(symbol))
            assertTrue("AGSL shader missing $symbol", agslShader.contains(symbol))
        }
    }

    @Test
    fun filterDebugTracker_recordsLivePreviewAndSavedFileVerification() {
        FilterDebugTracker.recordPreviewFilter(
            mode = CameraMode.PHOTO,
            selectedFilter = CameraFilterPreset.GOLDEN_HOUR,
            effectiveFilter = CameraFilterPreset.GOLDEN_HOUR,
            wasAppliedToPreview = true,
            backend = "GL-CameraEffect(PREVIEW)"
        )
        FilterDebugTracker.recordSavedFileFilter(
            mediaType = "Photo",
            modeName = CameraMode.PHOTO.displayName,
            selectedFilter = CameraFilterPreset.GOLDEN_HOUR,
            effectiveFilter = CameraFilterPreset.GOLDEN_HOUR,
            wasAppliedToFile = true,
            backend = "GPU-AGSL",
            durationMs = 42L
        )
        FilterDebugTracker.recordSavedFileFilter(
            mediaType = "Video",
            modeName = CameraMode.VIDEO.displayName,
            selectedFilter = CameraFilterPreset.NOIR,
            effectiveFilter = CameraFilterPreset.NOIR,
            wasAppliedToFile = true,
            backend = "GL-CameraEffect-RealtimeVideo",
            durationMs = 15L
        )

        val preview = FilterDebugTracker.getLatestPreviewRecord()
        assertNotNull(preview)
        assertTrue(preview!!.wasApplied)
        assertEquals(CameraFilterPreset.GOLDEN_HOUR, preview.effectiveFilter)

        val saved = FilterDebugTracker.getRecentSavedFileRecords()
        assertEquals(2, saved.size)
        // Most recent saved file first
        assertEquals("Video", saved[0].target)
        assertEquals(CameraFilterPreset.NOIR, saved[0].effectiveFilter)
        assertEquals("Photo", saved[1].target)
        assertEquals(42L, saved[1].durationMs)

        val summary = FilterDebugTracker.formatMultilineSummary()
        assertTrue(summary.contains("Golden Hour"))
        assertTrue(summary.contains("GL-CameraEffect(PREVIEW)"))
        assertTrue(summary.contains("Noir"))
        assertTrue(summary.contains("APPLIED"))
    }

    @Test
    fun uiPerformanceMonitor_tracksFrameTimingsJankAndMainThreadStalls() {
        UiPerformanceMonitor.setActiveScreen("CAMERA")
        UiPerformanceMonitor.recordFrameDuration(totalFrameMs = 8.0, mainThreadMs = 5.0, source = "FrameMetrics")
        UiPerformanceMonitor.recordFrameDuration(totalFrameMs = 12.0, mainThreadMs = 9.0, source = "FrameMetrics")
        UiPerformanceMonitor.recordFrameDuration(totalFrameMs = 24.5, mainThreadMs = 21.0, source = "FrameMetrics")
        UiPerformanceMonitor.recordFrameDuration(totalFrameMs = 10.0, mainThreadMs = 7.0, source = "FrameMetrics")

        val snapshot = UiPerformanceMonitor.getSnapshot()
        assertEquals(4L, snapshot.totalFrames)
        assertEquals(1L, snapshot.jankyFrames)
        assertEquals(1L, snapshot.mainThreadStallCount)
        assertEquals(25.0, snapshot.jankPercent, 0.01)
        assertEquals(24.5, snapshot.maxFrameMs, 0.01)
        assertEquals(1, snapshot.recentStalls.size)
        assertEquals("CAMERA", snapshot.recentStalls[0].screenName)
        assertEquals(21.0, snapshot.recentStalls[0].durationMs, 0.01)

        val summary = snapshot.formatMultilineSummary()
        assertTrue(summary.contains("Frames: 4"))
        assertTrue(summary.contains("Main-Thread Stalls (>16ms): 1"))
    }

    @Test
    fun unifiedShutterProgressState_coversAll10ModesAndFormatting() {
        assertEquals(180L, SHUTTER_PROGRESS_SHOW_DELAY_MS)
        assertEquals(CameraMode.entries.size, ShutterProgressMode.entries.size)

        // Every CameraMode maps uniquely to a ShutterProgressMode
        val mappedModes = CameraMode.entries.map { it.toShutterProgressMode() }.toSet()
        assertEquals(ShutterProgressMode.entries.toSet(), mappedModes)

        // Video-like recording modes vs concurrent photo queue modes
        assertTrue(ShutterProgressMode.VIDEO.isVideoLikeRecording)
        assertTrue(ShutterProgressMode.SLOW_MOTION.isVideoLikeRecording)
        assertTrue(ShutterProgressMode.TIME_LAPSE.isVideoLikeRecording)
        assertFalse(ShutterProgressMode.PHOTO.isVideoLikeRecording)

        assertTrue(ShutterProgressMode.PHOTO.supportsConcurrentPhotoQueue)
        assertTrue(ShutterProgressMode.HD.supportsConcurrentPhotoQueue)
        assertTrue(ShutterProgressMode.PORTRAIT.supportsConcurrentPhotoQueue)
        assertTrue(ShutterProgressMode.PRO.supportsConcurrentPhotoQueue)
        assertFalse(ShutterProgressMode.ASTRO.supportsConcurrentPhotoQueue)

        // Active state detection
        assertFalse(ShutterProgressState(phase = ShutterProgressPhase.IDLE).isActive)
        assertTrue(ShutterProgressState(phase = ShutterProgressPhase.CAPTURING).isActive)
        assertTrue(ShutterProgressState(phase = ShutterProgressPhase.PROCESSING).isActive)
        assertFalse(ShutterProgressState(phase = ShutterProgressPhase.COMPLETED).isActive)

        // Night Sight legacy adapter
        val legacyProcessing = NightSightProgressState(
            phase = NightSightCapturePhase.PROCESSING,
            currentFrame = 8,
            totalFrames = 8,
            progress = 0.65f,
            statusLabel = "Merging…"
        ).toShutterProgressState()
        assertEquals(ShutterProgressMode.NIGHT_SIGHT, legacyProcessing.mode)
        assertEquals(ShutterProgressPhase.PROCESSING, legacyProcessing.phase)
        assertEquals("Processing… 65%", legacyProcessing.detailLabel)

        // Captions across modes
        assertEquals("REC • 01:05 · 60fps", formatVideoRecordingCaption(65L, isSlowMotion = false, fpsLabel = "60fps"))
        assertEquals("Slow-Mo • 00:12 · 120fps", formatVideoRecordingCaption(12L, isSlowMotion = true, fpsLabel = "120fps"))
        assertEquals("Panorama 50% • 6 frames", formatPanoramaProgressCaption(0.5f, 6))
        assertEquals("Sweep slowly • 10%", formatPanoramaProgressCaption(0.1f, 0))
        assertEquals("Frame 3 of 12 • 00:15 elapsed · 00:45 left", formatAstroCaptureCaption(3, 12, 15L, 45L))
        assertEquals("00:30 • 15 frames", formatTimeLapseCaptureCaption(30L, 15))
    }
}
