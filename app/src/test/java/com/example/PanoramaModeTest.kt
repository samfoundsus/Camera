package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ApplicationProvider
import com.example.core.preferences.CameraPreferencesRepository
import com.example.core.preferences.PanoramaDirection
import com.example.core.preferences.TimeLapseSpeed
import com.example.core.processing.PanoramaCapturePhase
import com.example.core.processing.PanoramaGuidanceStatus
import com.example.core.processing.PanoramaMotionTracker
import com.example.core.processing.PanoramaProcessor
import com.example.core.processing.PanoramaStitchOutcome
import com.example.core.storage.MediaStorePhotoSaver
import com.example.feature.preview.CameraFoundationScreen
import com.example.feature.preview.MediaOpenResult
import com.example.feature.preview.openCapturedMediaUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanoramaModeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pixel_camera_preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    /**
     * Helper to build an Android rotation vector `(qx, qy, qz, qw)` representing a rotation of
     * [angleDegrees] around the unit axis `(ax, ay, az)`.
     */
    private fun makeRotationVector(angleDegrees: Float, ax: Float, ay: Float, az: Float): FloatArray {
        val halfRad = Math.toRadians(angleDegrees.toDouble() * 0.5)
        val s = sin(halfRad).toFloat()
        val c = cos(halfRad).toFloat()
        return floatArrayOf(ax * s, ay * s, az * s, c)
    }

    /**
     * Generates a rich synthetic wide landscape scene bitmap (e.g. 720x240) with sky gradient,
     * mountains, vertical architectural pillars, and textured ground so overlapping crop windows
     * have genuine visual features for alignment and seam blending.
     * Writes directly to the Bitmap's pixel buffer so pixels are populated under Robolectric JVM.
     */
    private fun createSyntheticWideScene(sceneWidth: Int = 720, sceneHeight: Int = 240): Bitmap {
        val bmp = Bitmap.createBitmap(sceneWidth, sceneHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(sceneWidth * sceneHeight)

        val landmarkPositions = intArrayOf(70, 145, 225, 315, 405, 495, 580, 655)
        val landmarkHalfWidths = intArrayOf(12, 18, 10, 22, 14, 19, 11, 16)
        val landmarkTops = intArrayOf(38, 62, 28, 54, 34, 68, 42, 58)
        val landmarkColors = intArrayOf(
            Color.rgb(230, 65, 45),
            Color.rgb(245, 200, 40),
            Color.rgb(40, 210, 130),
            Color.rgb(205, 85, 240),
            Color.rgb(250, 135, 30),
            Color.rgb(55, 185, 245),
            Color.rgb(240, 80, 140),
            Color.rgb(175, 235, 55)
        )

        val horizonBaseY = (sceneHeight * 0.56f).toInt()

        for (y in 0 until sceneHeight) {
            val yRatio = y.toFloat() / sceneHeight.toFloat()
            val rowOffset = y * sceneWidth
            for (x in 0 until sceneWidth) {
                val xRatio = x.toFloat() / sceneWidth.toFloat()
                val mountainTop = horizonBaseY -
                    (sin(x * 0.028) * 26.0 + cos(x * 0.067) * 14.0).toInt()

                var color = when {
                    y < mountainTop -> {
                        Color.rgb(
                            (35 + yRatio * 110 + xRatio * 50).toInt().coerceIn(0, 255),
                            (100 + yRatio * 105).toInt().coerceIn(0, 255),
                            (235 - xRatio * 45).toInt().coerceIn(0, 255)
                        )
                    }
                    y < horizonBaseY + 14 -> {
                        val ridgeShade = (95 + ((x * 7 + y * 13) % 45)).coerceIn(0, 255)
                        Color.rgb(ridgeShade, (ridgeShade * 0.85f).toInt(), (ridgeShade * 1.1f).toInt().coerceIn(0, 255))
                    }
                    else -> {
                        val checker = if (((x / 14) + (y / 10)) % 2 == 0) 18 else -14
                        Color.rgb(
                            (75 + checker + (xRatio * 35).toInt()).coerceIn(0, 255),
                            (145 + checker).coerceIn(0, 255),
                            (65 + checker / 2).coerceIn(0, 255)
                        )
                    }
                }

                for (i in landmarkPositions.indices) {
                    val cx = landmarkPositions[i]
                    val hw = landmarkHalfWidths[i]
                    val topY = landmarkTops[i]
                    val bottomY = (sceneHeight * 0.82f).toInt()
                    if (x in (cx - hw)..(cx + hw) && y in topY..bottomY) {
                        val dx = x - cx
                        val dy = y - (topY + 14)
                        color = if (dx * dx + dy * dy <= 36) {
                            Color.WHITE
                        } else {
                            landmarkColors[i]
                        }
                        break
                    }
                }

                pixels[rowOffset + x] = color
            }
        }

        bmp.setPixels(pixels, 0, sceneWidth, 0, 0, sceneWidth, sceneHeight)
        return bmp
    }

    private fun extractSubBitmap(source: Bitmap, cropX: Int, cropY: Int, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, cropX, cropY, width, height)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun launchPanoramaScreen(): CameraPreferencesRepository {
        val repo = CameraPreferencesRepository(context)
        repo.setCustomModeLayout(
            visibleNames = listOf("PHOTO", "VIDEO", "PANORAMA"),
            hiddenNames = listOf("NIGHT_SIGHT", "PORTRAIT", "HD", "PRO", "ASTRO", "SLOW_MOTION", "TIME_LAPSE")
        )
        composeTestRule.setContent {
            val prefs by repo.preferences.collectAsState()
            CameraFoundationScreen(
                preferences = prefs,
                preferencesRepository = repo,
                onNavigateToSettings = {}
            )
        }
        composeTestRule.onNodeWithText("Panorama").performClick()
        composeTestRule.waitForIdle()
        return repo
    }

    // =========================================================================
    // TEST 1, TEST 2, TEST 3, TEST 4, TEST 9:
    // Direction-aware track orientation, arrow starting endpoint, and immediate switching while idle
    // =========================================================================
    @Test
    fun panoramaDirections_renderCorrectTrackOrientationAndStartingArrowEndpoint() {
        val repo = launchPanoramaScreen()

        // TEST 1: Select Left -> Right
        // Expected: horizontal track, arrow at LEFT endpoint (never center), pointing RIGHT
        composeTestRule.onNodeWithTag("panorama_direction_left_to_right").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PanoramaDirection.LEFT_TO_RIGHT, repo.preferences.value.panoramaDirection)

        val horizTrackBoundsLR = composeTestRule.onNodeWithTag("panorama_track_horizontal")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Horizontal track should be wider than tall", horizTrackBoundsLR.width > horizTrackBoundsLR.height)

        val arrowBoundsLR = composeTestRule.onNodeWithTag("panorama_arrow_indicator")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        composeTestRule.onNodeWithTag("panorama_arrow_direction_right").assertIsDisplayed()
        assertTrue(
            "Left-to-Right arrow must start at the LEFT endpoint, not center (arrowCenter=${arrowBoundsLR.center.x}, trackCenter=${horizTrackBoundsLR.center.x})",
            arrowBoundsLR.center.x < horizTrackBoundsLR.left + horizTrackBoundsLR.width * 0.25f
        )

        // TEST 2 & TEST 9: Switch to Right -> Left while idle
        // Expected: horizontal track, arrow immediately moves to RIGHT endpoint, pointing LEFT
        composeTestRule.onNodeWithTag("panorama_direction_right_to_left").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PanoramaDirection.RIGHT_TO_LEFT, repo.preferences.value.panoramaDirection)

        val horizTrackBoundsRL = composeTestRule.onNodeWithTag("panorama_track_horizontal")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val arrowBoundsRL = composeTestRule.onNodeWithTag("panorama_arrow_indicator")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        composeTestRule.onNodeWithTag("panorama_arrow_direction_left").assertIsDisplayed()
        assertTrue(
            "Right-to-Left arrow must start at the RIGHT endpoint (arrowCenter=${arrowBoundsRL.center.x}, trackRight=${horizTrackBoundsRL.right})",
            arrowBoundsRL.center.x > horizTrackBoundsRL.left + horizTrackBoundsRL.width * 0.75f
        )

        // TEST 3 & TEST 9: Switch to Top -> Bottom while idle
        // Expected: VERTICAL track, arrow at TOP endpoint, pointing DOWN
        composeTestRule.onNodeWithTag("panorama_direction_top_to_bottom").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PanoramaDirection.TOP_TO_BOTTOM, repo.preferences.value.panoramaDirection)

        val vertTrackBoundsTB = composeTestRule.onNodeWithTag("panorama_track_vertical")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Vertical track must be taller than wide", vertTrackBoundsTB.height > vertTrackBoundsTB.width * 2f)

        val arrowBoundsTB = composeTestRule.onNodeWithTag("panorama_arrow_indicator")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        composeTestRule.onNodeWithTag("panorama_arrow_direction_down").assertIsDisplayed()
        assertTrue(
            "Top-to-Bottom arrow must start at the TOP endpoint (arrowCenterY=${arrowBoundsTB.center.y}, trackTop=${vertTrackBoundsTB.top})",
            arrowBoundsTB.center.y < vertTrackBoundsTB.top + vertTrackBoundsTB.height * 0.28f
        )

        // TEST 4 & TEST 9: Switch to Bottom -> Top while idle
        // Expected: VERTICAL track, arrow at BOTTOM endpoint, pointing UP
        composeTestRule.onNodeWithTag("panorama_direction_bottom_to_top").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PanoramaDirection.BOTTOM_TO_TOP, repo.preferences.value.panoramaDirection)

        val vertTrackBoundsBT = composeTestRule.onNodeWithTag("panorama_track_vertical")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val arrowBoundsBT = composeTestRule.onNodeWithTag("panorama_arrow_indicator")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        composeTestRule.onNodeWithTag("panorama_arrow_direction_up").assertIsDisplayed()
        assertTrue(
            "Bottom-to-Top arrow must start at the BOTTOM endpoint (arrowCenterY=${arrowBoundsBT.center.y}, trackBottom=${vertTrackBoundsBT.bottom})",
            arrowBoundsBT.center.y > vertTrackBoundsBT.top + vertTrackBoundsBT.height * 0.72f
        )
    }

    // =========================================================================
    // TEST 5: Press shutter and keep phone stationary -> no artificial arrow movement
    // =========================================================================
    @Test
    fun stationaryPhoneAfterShutter_producesZeroArtificialArrowMovement() {
        launchPanoramaScreen()

        val initialArrowBounds = composeTestRule.onNodeWithTag("panorama_arrow_indicator")
            .fetchSemanticsNode().boundsInRoot

        // Press shutter to start capture
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.waitForIdle()

        // Verify capture UI is active and progress is 0% (no artificial jump to 16%)
        composeTestRule.onNodeWithTag("panorama_progress_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 / 6 frames • 0% (Left → Right)").assertIsDisplayed()

        val capturingArrowBounds = composeTestRule.onNodeWithTag("panorama_arrow_indicator")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Arrow must not jump forward when shutter is pressed and phone is stationary",
            initialArrowBounds.center.x,
            capturingArrowBounds.center.x,
            1.5f
        )

        // Also verify PanoramaMotionTracker directly with stationary rotation-vector and gyro samples over 5 seconds
        val tracker = PanoramaMotionTracker(targetSweepDegrees = 90f, targetFrames = 6)
        tracker.startSession(PanoramaDirection.LEFT_TO_RIGHT, Surface.ROTATION_0)
        val startRotVec = makeRotationVector(0f, 0f, 1f, 0f)
        for (step in 0..50) {
            val tsNs = 1_000_000_000L + step * 100_000_000L
            val progRot = tracker.onRotationVectorSample(startRotVec, tsNs)
            val progGyro = tracker.onGyroscopeSample(0.005f, -0.004f, 0.002f, 0.1f) // sub-deadband noise
            assertEquals("Stationary rotation vector must keep progress at 0.0f", 0f, progRot, 1e-4f)
            assertEquals("Stationary gyroscope noise must keep progress at 0.0f", 0f, progGyro, 1e-4f)
        }
        assertFalse("Stationary tracker must not request extra keyframes after anchor frame #1", tracker.shouldAcquireNextKeyframe(1, 0f))
    }

    // =========================================================================
    // TEST 6 & TEST 7:
    // Move phone in selected direction -> arrow advances proportionally;
    // Move phone in opposite direction -> no artificial forward progress and warns WRONG_DIRECTION
    // =========================================================================
    @Test
    fun motionTracker_tracksForwardAndReverseMovementAcrossAllFourDirectionsAndRotations() {
        val tracker = PanoramaMotionTracker(targetSweepDegrees = 90f, targetFrames = 6)

        // 1. LEFT_TO_RIGHT: clockwise rotation around +Y_screen (negative angle around +Y) advances progress 0 -> 1
        tracker.startSession(PanoramaDirection.LEFT_TO_RIGHT, Surface.ROTATION_0)
        tracker.onRotationVectorSample(makeRotationVector(0f, 0f, 1f, 0f), 1_000_000_000L)

        // TEST 7: Move in opposite direction first (Right -> Left, positive angle around +Y)
        for (i in 1..5) {
            val p = tracker.onRotationVectorSample(
                makeRotationVector(i * 5f, 0f, 1f, 0f),
                1_000_000_000L + i * 50_000_000L
            )
            assertEquals("Opposite movement must not generate artificial forward progress", 0f, p, 1e-4f)
        }
        val wrongDirState = tracker.snapshotLiveState(PanoramaCapturePhase.CAPTURING, 1)
        assertEquals(PanoramaGuidanceStatus.WRONG_DIRECTION, wrongDirState.guidanceStatus)

        // TEST 6: Now move in the selected direction (Left -> Right, negative angle around +Y: -30 deg, -60 deg, -90 deg)
        var prevOffsetLR = PanoramaProcessor.computeArrowTrackOffset(PanoramaDirection.LEFT_TO_RIGHT, 0f)
        for (deg in listOf(30f, 60f, 90f)) {
            repeat(6) { step ->
                tracker.onRotationVectorSample(
                    makeRotationVector(-deg, 0f, 1f, 0f),
                    2_000_000_000L + (deg.toLong() * 10 + step) * 40_000_000L
                )
            }
            val currentOffset = PanoramaProcessor.computeArrowTrackOffset(
                PanoramaDirection.LEFT_TO_RIGHT,
                tracker.currentProgress()
            )
            assertTrue(
                "Arrow offset for LEFT_TO_RIGHT must advance rightward ($currentOffset > $prevOffsetLR)",
                currentOffset > prevOffsetLR
            )
            prevOffsetLR = currentOffset
        }
        assertTrue("Expected near-full progress at 90 deg sweep", tracker.currentProgress() > 0.90f)

        // 2. RIGHT_TO_LEFT: counter-clockwise rotation around +Y_screen (+angle around +Y) moves arrow offset 1.0 -> 0.0
        tracker.startSession(PanoramaDirection.RIGHT_TO_LEFT, Surface.ROTATION_0)
        tracker.onRotationVectorSample(makeRotationVector(0f, 0f, 1f, 0f), 5_000_000_000L)
        val startOffsetRL = PanoramaProcessor.computeArrowTrackOffset(PanoramaDirection.RIGHT_TO_LEFT, tracker.currentProgress())
        assertEquals(1.0f, startOffsetRL, 1e-4f)
        repeat(6) { step ->
            tracker.onRotationVectorSample(
                makeRotationVector(+45f, 0f, 1f, 0f),
                5_100_000_000L + step * 40_000_000L
            )
        }
        val midOffsetRL = PanoramaProcessor.computeArrowTrackOffset(PanoramaDirection.RIGHT_TO_LEFT, tracker.currentProgress())
        assertTrue("Arrow offset for RIGHT_TO_LEFT must move leftward from 1.0 toward 0.0 (got $midOffsetRL)", midOffsetRL < 0.65f)

        // 3. TOP_TO_BOTTOM: tilting top of phone forward away from user (-angle around +X) moves arrow offset 0.0 -> 1.0
        tracker.startSession(PanoramaDirection.TOP_TO_BOTTOM, Surface.ROTATION_0)
        tracker.onRotationVectorSample(makeRotationVector(0f, 1f, 0f, 0f), 6_000_000_000L)
        repeat(6) { step ->
            tracker.onRotationVectorSample(
                makeRotationVector(-45f, 1f, 0f, 0f),
                6_100_000_000L + step * 40_000_000L
            )
        }
        val midOffsetTB = PanoramaProcessor.computeArrowTrackOffset(PanoramaDirection.TOP_TO_BOTTOM, tracker.currentProgress())
        assertTrue("Arrow offset for TOP_TO_BOTTOM must move downward from 0.0 toward 1.0 (got $midOffsetTB)", midOffsetTB > 0.35f)

        // 4. BOTTOM_TO_TOP: tilting top of phone upward (+angle around +X) moves arrow offset 1.0 -> 0.0
        tracker.startSession(PanoramaDirection.BOTTOM_TO_TOP, Surface.ROTATION_0)
        tracker.onRotationVectorSample(makeRotationVector(0f, 1f, 0f, 0f), 7_000_000_000L)
        repeat(6) { step ->
            tracker.onRotationVectorSample(
                makeRotationVector(+45f, 1f, 0f, 0f),
                7_100_000_000L + step * 40_000_000L
            )
        }
        val midOffsetBT = PanoramaProcessor.computeArrowTrackOffset(PanoramaDirection.BOTTOM_TO_TOP, tracker.currentProgress())
        assertTrue("Arrow offset for BOTTOM_TO_TOP must move upward from 1.0 toward 0.0 (got $midOffsetBT)", midOffsetBT < 0.65f)
    }

    // =========================================================================
    // TEST 8 & TEST 10:
    // Frame coverage synchronization, stopping without enough frames vs completing with valid frames,
    // mode switching / cancellation cleanup
    // =========================================================================
    @Test
    fun panoramaCompletionAndCancellation_validatesFrameCoverageAndResetsCleanly() {
        // Verify that reaching 100% sensor rotation WITHOUT capturing overlapping frames does NOT auto-complete
        val tracker = PanoramaMotionTracker(targetSweepDegrees = 90f, targetFrames = 6)
        tracker.startSession(PanoramaDirection.LEFT_TO_RIGHT, Surface.ROTATION_0)
        tracker.onRotationVectorSample(makeRotationVector(0f, 0f, 1f, 0f), 1_000_000_000L)
        repeat(10) { step ->
            tracker.onRotationVectorSample(
                makeRotationVector(-90f, 0f, 1f, 0f),
                1_100_000_000L + step * 40_000_000L
            )
        }
        assertTrue(tracker.currentProgress() >= 0.98f)
        assertFalse(
            "Must not auto-complete when sensor reached endpoint but only 1 frame was captured",
            tracker.isSweepReadyToAutoComplete(capturedFrameCount = 1)
        )
        val laggingState = tracker.snapshotLiveState(PanoramaCapturePhase.CAPTURING, capturedFrameCount = 1)
        assertTrue("Expected isCaptureLagging when sensor outpaces frame capture", laggingState.isCaptureLagging)
        assertEquals(PanoramaGuidanceStatus.TOO_FAST, laggingState.guidanceStatus)

        // Once sufficient overlapping frames are captured (e.g. 5 frames), auto-completion is allowed
        assertTrue(
            "Expected auto-complete ready when both sensor reached endpoint and sufficient frames were captured",
            tracker.isSweepReadyToAutoComplete(capturedFrameCount = 5)
        )

        // UI Test: Pressing shutter and immediately pressing Finish while stationary (only 1 frame)
        // cancels cleanly and resets to IDLE instead of saving an invalid 1-frame panorama
        val repo = launchPanoramaScreen()
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("panorama_finish_button").performClick()
        composeTestRule.waitForIdle()

        // Should have returned to IDLE with direction selector visible again
        composeTestRule.onNodeWithTag("panorama_direction_selector_row").assertIsDisplayed()

        // TEST 10: Start capture, advance a slice, then switch camera mode -> capture state cancels cleanly
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("panorama_step_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Capture Slice (2/6)").assertIsDisplayed()

        // Switch to Photo (Camera) mode and back to Panorama mode
        composeTestRule.onNodeWithTag("mode_item_photo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mode_item_panorama").performScrollTo()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mode_item_panorama").performClick()
        composeTestRule.waitForIdle()

        // Verify Panorama is cleanly in IDLE state with arrow reset to endpoint
        composeTestRule.onNodeWithTag("panorama_direction_selector_row").assertIsDisplayed()
        assertEquals(PanoramaDirection.LEFT_TO_RIGHT, repo.preferences.value.panoramaDirection)
    }

    @Test
    fun panoramaPreferences_persistsDirectionWithoutAffectingOtherModes() {
        val repo1 = CameraPreferencesRepository(context)
        assertEquals(PanoramaDirection.LEFT_TO_RIGHT, repo1.preferences.value.panoramaDirection)

        repo1.setTimeLapseSpeed(TimeLapseSpeed.SPEED_120X)
        repo1.setProIso("800")

        repo1.setPanoramaDirection(PanoramaDirection.RIGHT_TO_LEFT)
        assertEquals(PanoramaDirection.RIGHT_TO_LEFT, repo1.preferences.value.panoramaDirection)

        val repo2 = CameraPreferencesRepository(context)
        assertEquals(PanoramaDirection.RIGHT_TO_LEFT, repo2.preferences.value.panoramaDirection)
        assertEquals(TimeLapseSpeed.SPEED_120X, repo2.preferences.value.timeLapseSpeed)
        assertEquals("800", repo2.preferences.value.proIso)
    }

    @Test
    fun classifyLiveGuidance_detectsTooFastWrongDirectionAndOffAlignment() {
        val steady = PanoramaProcessor.classifyLiveGuidance(
            forwardDeltaFraction = 0.03f,
            driftNormalized = 0.05f,
            deltaTimeSec = 0.10f,
            direction = PanoramaDirection.LEFT_TO_RIGHT
        )
        assertEquals(PanoramaGuidanceStatus.MOVING_STEADY, steady)

        val tooFast = PanoramaProcessor.classifyLiveGuidance(
            forwardDeltaFraction = 0.14f,
            driftNormalized = 0.05f,
            deltaTimeSec = 0.10f,
            direction = PanoramaDirection.LEFT_TO_RIGHT
        )
        assertEquals(PanoramaGuidanceStatus.TOO_FAST, tooFast)
        assertTrue(tooFast.isWarning)
        assertTrue(tooFast.instructionText.contains("Move slowly"))

        val wrongDir = PanoramaProcessor.classifyLiveGuidance(
            forwardDeltaFraction = -0.05f,
            driftNormalized = 0.0f,
            deltaTimeSec = 0.10f,
            direction = PanoramaDirection.LEFT_TO_RIGHT
        )
        assertEquals(PanoramaGuidanceStatus.WRONG_DIRECTION, wrongDir)
        assertTrue(wrongDir.isWarning)

        val driftHigh = PanoramaProcessor.classifyLiveGuidance(
            forwardDeltaFraction = 0.02f,
            driftNormalized = -0.45f,
            deltaTimeSec = 0.10f,
            direction = PanoramaDirection.LEFT_TO_RIGHT
        )
        assertEquals(PanoramaGuidanceStatus.OFF_ALIGNMENT_HIGH, driftHigh)
        assertTrue(driftHigh.isWarning)

        val driftLow = PanoramaProcessor.classifyLiveGuidance(
            forwardDeltaFraction = 0.02f,
            driftNormalized = 0.45f,
            deltaTimeSec = 0.10f,
            direction = PanoramaDirection.LEFT_TO_RIGHT
        )
        assertEquals(PanoramaGuidanceStatus.OFF_ALIGNMENT_LOW, driftLow)
        assertTrue(driftLow.isWarning)
    }

    @Test
    fun estimatePairwiseAlignment_recoversHorizontalAndVerticalTranslations() {
        val scene = createSyntheticWideScene(sceneWidth = 640, sceneHeight = 240)
        val frameW = 240
        val frameH = 180
        val trueShiftX = 64
        val trueShiftY = 10

        val frame1 = extractSubBitmap(scene, 80, 20, frameW, frameH)
        val frame2 = extractSubBitmap(scene, 80 + trueShiftX, 20 + trueShiftY, frameW, frameH)

        val alignment = PanoramaProcessor.estimatePairwiseAlignment(
            prevBitmap = frame1,
            currBitmap = frame2,
            direction = PanoramaDirection.LEFT_TO_RIGHT
        )

        assertTrue("Expected dx near $trueShiftX but got ${alignment.dx}", abs(alignment.dx - trueShiftX) <= 10f)
        assertTrue("Expected dy near $trueShiftY but got ${alignment.dy}", abs(alignment.dy - trueShiftY) <= 6f)
        assertTrue("Expected positive confidence", alignment.confidence > 0.3f)

        frame1.recycle()
        frame2.recycle()
        scene.recycle()
    }

    @Test
    fun stitchPanoramaBitmaps_producesWideSeamlessPanoramaAndHandlesInsufficientFrames() {
        val singleBmp = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888)
        val failOutcome = PanoramaProcessor.stitchPanoramaBitmaps(listOf(singleBmp))
        assertTrue(failOutcome is PanoramaStitchOutcome.Failure)
        singleBmp.recycle()

        val scene = createSyntheticWideScene(sceneWidth = 760, sceneHeight = 240)
        val frameW = 240
        val frameH = 180
        val stepX = 72

        val frames = (0 until 4).map { idx ->
            val left = 40 + idx * stepX
            val top = 24 + (idx % 2) * 6
            extractSubBitmap(scene, left, top, frameW, frameH)
        }

        val outcome = PanoramaProcessor.stitchPanoramaBitmaps(
            frames = frames,
            direction = PanoramaDirection.LEFT_TO_RIGHT,
            maxFrameDimension = 320,
            jpegQuality = 94
        )
        assertTrue("Expected PanoramaStitchOutcome.Success", outcome is PanoramaStitchOutcome.Success)
        val success = outcome as PanoramaStitchOutcome.Success

        assertEquals(4, success.stitchedFrameCount)
        assertTrue(
            "Expected wide panoramic output (width=${success.width} > height=${success.height} * 1.5)",
            success.width > (success.height * 1.5f).toInt()
        )
        assertTrue("Expected non-empty JPEG payload", success.jpegBytes.size > 1024)

        val decoded = BitmapFactory.decodeByteArray(success.jpegBytes, 0, success.jpegBytes.size)
        assertNotNull(decoded)
        assertEquals(success.width, decoded.width)
        assertEquals(success.height, decoded.height)

        decoded.recycle()
        success.bitmap.recycle()
        frames.forEach { it.recycle() }
        scene.recycle()
    }

    @Test
    fun processPanoramaJpegBytes_savesValidPanoramaToMediaStoreAndOpensCleanly() {
        val scene = createSyntheticWideScene(sceneWidth = 680, sceneHeight = 220)
        val jpegFrames = (0 until 3).map { idx ->
            val slice = extractSubBitmap(scene, 50 + idx * 70, 20, 220, 160)
            val bos = ByteArrayOutputStream()
            slice.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            slice.recycle()
            bos.toByteArray()
        }
        scene.recycle()

        val stitchOutcome = PanoramaProcessor.processPanoramaJpegBytes(
            framesBytes = jpegFrames,
            rotationDegrees = 0,
            mirrorHorizontally = false,
            direction = PanoramaDirection.LEFT_TO_RIGHT
        )
        assertTrue(stitchOutcome is PanoramaStitchOutcome.Success)
        val stitched = stitchOutcome as PanoramaStitchOutcome.Success

        val savedItem = MediaStorePhotoSaver.savePhotoAtomically(
            context = context,
            outputBytes = stitched.jpegBytes,
            sourceExifBytes = stitched.jpegBytes,
            displayName = "PANO_TEST_2026.jpg",
            mimeType = "image/jpeg",
            width = stitched.width,
            height = stitched.height,
            exifOrientation = stitched.exifOrientation
        )
        stitched.bitmap.recycle()

        assertNotNull("Expected MediaStorePhotoSaver to return non-null SavedMediaItem", savedItem)
        val uri = savedItem!!.uri
        assertEquals("image/jpeg", context.contentResolver.getType(uri))

        val openResult = openCapturedMediaUri(context, uri)
        assertTrue(
            "Expected Opened or NoCompatibleViewer fallback, got $openResult",
            openResult == MediaOpenResult.Opened || openResult == MediaOpenResult.NoCompatibleViewer
        )
    }
}
