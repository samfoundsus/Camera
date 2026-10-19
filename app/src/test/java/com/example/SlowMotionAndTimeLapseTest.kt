package com.example

import android.content.Context
import android.net.Uri
import android.util.Range
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CameraLens
import com.example.core.camera.LensCapabilities
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.model.CameraMode
import com.example.core.preferences.CameraPreferencesRepository
import com.example.core.preferences.SlowMoSpeed
import com.example.core.preferences.TimeLapseSpeed
import com.example.feature.preview.MediaOpenResult
import com.example.feature.preview.components.CameraPreviewPlaceholder
import com.example.feature.preview.components.VideoSampleMeta
import com.example.feature.preview.components.computeSlowMotionSampleSchedule
import com.example.feature.preview.components.computeTimeLapseSampleSchedule
import com.example.feature.preview.components.saveVideoFileToMediaStore
import com.example.feature.preview.components.selectBestTargetFpsRange
import com.example.feature.preview.openCapturedMediaUri
import com.example.ui.theme.PixelCameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlowMotionAndTimeLapseTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun timeLapseSpeed_containsOnlyRequiredEightMultipliers_andRemoves5xAnd10x() {
        val speeds = TimeLapseSpeed.entries
        assertEquals(8, speeds.size)
        assertEquals(
            listOf(30, 60, 120, 300, 600, 1200, 2400, 6000),
            speeds.map { it.multiplier }
        )
        assertEquals(
            listOf("30x", "60x", "120x", "300x", "600x", "1200x", "2400x", "6000x"),
            speeds.map { it.shortLabel }
        )
        assertFalse(speeds.any { it.multiplier == 5 || it.multiplier == 10 })

        // Verify interval math at 30 FPS output
        assertEquals(1000L, TimeLapseSpeed.SPEED_30X.captureIntervalMs)
        assertEquals(2000L, TimeLapseSpeed.SPEED_60X.captureIntervalMs)
        assertEquals(4000L, TimeLapseSpeed.SPEED_120X.captureIntervalMs)
        assertEquals(10_000L, TimeLapseSpeed.SPEED_300X.captureIntervalMs)
        assertEquals(20_000L, TimeLapseSpeed.SPEED_600X.captureIntervalMs)
        assertEquals(40_000L, TimeLapseSpeed.SPEED_1200X.captureIntervalMs)
        assertEquals(80_000L, TimeLapseSpeed.SPEED_2400X.captureIntervalMs)
        assertEquals(200_000L, TimeLapseSpeed.SPEED_6000X.captureIntervalMs)

        // Verify expected output duration calculation
        assertEquals(1000L, TimeLapseSpeed.SPEED_30X.expectedOutputDurationMs(30_000L))
        assertEquals(1000L, TimeLapseSpeed.SPEED_60X.expectedOutputDurationMs(60_000L))
        assertEquals(1000L, TimeLapseSpeed.SPEED_120X.expectedOutputDurationMs(120_000L))
    }

    @Test
    fun timeLapseSpeed_persistsAndRestoresViaPreferencesRepository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pixel_camera_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        val repo = CameraPreferencesRepository(context)
        assertEquals(TimeLapseSpeed.SPEED_30X, repo.preferences.value.timeLapseSpeed)

        for (speed in TimeLapseSpeed.entries) {
            repo.setTimeLapseSpeed(speed)
            assertEquals(speed, repo.preferences.value.timeLapseSpeed)

            val reloaded = CameraPreferencesRepository(context)
            assertEquals(speed, reloaded.preferences.value.timeLapseSpeed)
        }
    }

    @Test
    fun timeLapsePreviewShortcut_isCompactByDefault_opensPopupWithEightSpeeds_andUpdatesSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pixel_camera_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        val repo = CameraPreferencesRepository(context)

        composeTestRule.setContent {
            val prefs = repo.preferences.value
            var selectedSpeed by remember { mutableStateOf(prefs.timeLapseSpeed) }
            PixelCameraTheme {
                CameraPreviewPlaceholder(
                    activeMode = CameraMode.TIME_LAPSE,
                    timeLapseSpeed = selectedSpeed,
                    onTimeLapseSpeedChanged = { newSpeed ->
                        selectedSpeed = newSpeed
                        repo.setTimeLapseSpeed(newSpeed)
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Compact selector button is displayed above zoom shortcut bar
        composeTestRule.onNodeWithTag("time_lapse_speed_selector_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("30x").assertIsDisplayed()

        // 2. All eight options are NOT permanently displayed before opening the popup
        assertEquals(0, composeTestRule.onAllNodesWithText("60x").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("6000x").fetchSemanticsNodes().size)

        // 3. Tapping the compact selector opens the popup with all 8 speeds
        composeTestRule.onNodeWithTag("time_lapse_speed_selector_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("time_lapse_speed_popup").assertIsDisplayed()
        for (speed in TimeLapseSpeed.entries) {
            composeTestRule.onNodeWithTag("time_lapse_speed_option_${speed.multiplier}x").assertIsDisplayed()
        }

        // 4. Selecting 120x updates state immediately, persists to preferences, and closes popup
        composeTestRule.onNodeWithTag("time_lapse_speed_option_120x").performClick()
        composeTestRule.waitForIdle()

        assertEquals(TimeLapseSpeed.SPEED_120X, repo.preferences.value.timeLapseSpeed)
        assertEquals(0, composeTestRule.onAllNodesWithText("6000x").fetchSemanticsNodes().size)
        composeTestRule.onNodeWithText("120x").assertIsDisplayed()
    }

    @Test
    fun slowMoCapabilities_resolvesCompatibleResolutionAndTargetFpsRanges() {
        val availableRanges = arrayOf(
            Range(15, 30),
            Range(30, 30),
            Range(30, 60),
            Range(30, 120),
            Range(120, 120),
            Range(240, 240)
        )

        val range120 = selectBestTargetFpsRange(availableRanges, 120)
        assertEquals(120, range120.upper)
        assertEquals(120, range120.lower)

        val range240 = selectBestTargetFpsRange(availableRanges, 240)
        assertEquals(240, range240.upper)

        // When requesting 480 FPS on hardware capped at 240 FPS, selects highest supported (240)
        val rangeFallback = selectBestTargetFpsRange(availableRanges, 480)
        assertEquals(240, rangeFallback.upper)

        val lensCaps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            supportedSlowMoRates = listOf(VideoFrameRate.FPS_120, VideoFrameRate.FPS_240),
            supportedSlowMoResolutionsByRate = mapOf(
                VideoFrameRate.FPS_120 to listOf(VideoResolution.FHD_1080P, VideoResolution.HD_720P),
                VideoFrameRate.FPS_240 to listOf(VideoResolution.HD_720P)
            )
        )
        assertEquals(
            VideoResolution.FHD_1080P,
            lensCaps.resolveSlowMoResolution(VideoFrameRate.FPS_120, VideoResolution.UHD_4K)
        )
        assertEquals(
            VideoResolution.HD_720P,
            lensCaps.resolveSlowMoResolution(VideoFrameRate.FPS_240, VideoResolution.FHD_1080P)
        )
    }

    @Test
    fun openCapturedMediaUri_handlesMissingUri_andOpensValidVideoInAppWhenNoExternalViewerExists() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Empty or missing content URI fails gracefully and does not claim success
        val emptyResult = openCapturedMediaUri(context, Uri.EMPTY)
        assertEquals(MediaOpenResult.MissingOrInvalidUri, emptyResult)

        val missingUri = Uri.parse("content://media/external/video/media/99999999")
        val missingResult = openCapturedMediaUri(context, missingUri)
        assertEquals(MediaOpenResult.MissingOrInvalidUri, missingResult)

        // 2. Valid saved file URI resolves to NoCompatibleViewer (which triggers InAppMediaViewerDialog) or Opened
        val tempVideo = File.createTempFile("valid_test_video_", ".mp4", context.cacheDir)
        tempVideo.writeBytes(ByteArray(1024) { 0x42 })
        val validResult = openCapturedMediaUri(context, Uri.fromFile(tempVideo))
        assertTrue(
            validResult == MediaOpenResult.Opened ||
                validResult == MediaOpenResult.NoCompatibleViewer
        )
        tempVideo.delete()
    }

    @Test
    fun saveVideoFileToMediaStore_rejectsEmptyOrMissingFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val emptyFile = File.createTempFile("empty_vid_", ".mp4", context.cacheDir)
        val savedEmpty = saveVideoFileToMediaStore(
            context = context,
            videoFile = emptyFile,
            displayName = "VID_EMPTY.mp4"
        )
        assertEquals(null, savedEmpty)
        emptyFile.delete()
    }

    @Test
    fun slowMotionAndTimeLapseSampleSchedules_scalePresentationTimestampsAndSubsampleFramesCorrectly() {
        // 1. 10-second 120 FPS capture (1,201 samples over 10,000,000 us)
        val slowMoSourceSamples = (0..1200).map { idx ->
            VideoSampleMeta(
                index = idx,
                ptsUs = (idx * 10_000_000L) / 1200L,
                isSync = idx % 30 == 0
            )
        }

        // At 120 FPS (4x slowDownFactor), 10s capture -> 40s (40,000,000 us) output duration
        val schedule120 = computeSlowMotionSampleSchedule(
            samples = slowMoSourceSamples,
            slowFactor = SlowMoSpeed.SPEED_1_4X.slowDownFactor
        )
        assertEquals(slowMoSourceSamples.size, schedule120.size)
        assertEquals(0L, schedule120.first().outputPtsUs)
        assertEquals(40_000_000L, schedule120.last().outputPtsUs)

        // At 240 FPS (8x slowDownFactor), 10s capture -> 80s (80,000,000 us) output duration
        val schedule240 = computeSlowMotionSampleSchedule(
            samples = slowMoSourceSamples,
            slowFactor = SlowMoSpeed.SPEED_1_8X.slowDownFactor
        )
        assertEquals(80_000_000L, schedule240.last().outputPtsUs)

        // 2. 120-second source capture with sync keyframes every 1.0s (121 keyframes from 0s to 120s)
        val timeLapseSourceSamples = (0..120).map { sec ->
            VideoSampleMeta(
                index = sec,
                ptsUs = sec * 1_000_000L,
                isSync = true
            )
        }

        // At 30x: 120s real capture -> 4.0s (4,000,000 us) output duration, 1 frame every 1s (121 frames)
        val tl30 = computeTimeLapseSampleSchedule(timeLapseSourceSamples, TimeLapseSpeed.SPEED_30X)
        assertEquals(121, tl30.size)
        assertEquals(4_000_000L, tl30.last().outputPtsUs)

        // At 60x: 120s real capture -> 2.0s (2,000,000 us) output duration, 1 frame every 2s (61 frames)
        val tl60 = computeTimeLapseSampleSchedule(timeLapseSourceSamples, TimeLapseSpeed.SPEED_60X)
        assertEquals(61, tl60.size)
        assertEquals(2_000_000L, tl60.last().outputPtsUs)

        // At 120x: 120s real capture -> 1.0s (1,000,000 us) output duration, 1 frame every 4s (31 frames)
        val tl120 = computeTimeLapseSampleSchedule(timeLapseSourceSamples, TimeLapseSpeed.SPEED_120X)
        assertEquals(31, tl120.size)
        assertEquals(1_000_000L, tl120.last().outputPtsUs)

        // At 300x: 120s real capture -> 0.4s (400,000 us) output duration, 1 frame every 10s (13 frames)
        val tl300 = computeTimeLapseSampleSchedule(timeLapseSourceSamples, TimeLapseSpeed.SPEED_300X)
        assertEquals(13, tl300.size)
        assertEquals(400_000L, tl300.last().outputPtsUs)
    }
}
