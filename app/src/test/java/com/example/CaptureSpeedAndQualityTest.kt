package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.BoundedPhotoSaveQueue
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.CameraCaptureQualityPolicy
import com.example.core.camera.CameraExtensionAvailability
import com.example.core.camera.CameraExtensionsHelper
import com.example.core.camera.CameraHardwareDebugReport
import com.example.core.camera.CameraIdDebugInfo
import com.example.core.camera.CameraLens
import com.example.core.camera.CaptureLatencyTracker
import com.example.core.camera.HdrSupportPath
import com.example.core.camera.IspQualityCapabilities
import com.example.core.camera.LensCapabilities
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.StreamingQualityFrameMerger
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.FlashMode
import com.example.core.preferences.HdrMode
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PhotoCaptureSpeedMode
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.storage.MediaStorePhotoSaver
import com.example.feature.settings.CameraHardwareDebugDialog
import com.example.feature.settings.SettingsScreen
import com.example.ui.components.CameraQuickControlsSheet
import com.example.ui.theme.PixelCameraTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureSpeedAndQualityTest {

    @get:Rule(order = 0)
    val testTimeoutRule = MainThreadSafeTimeoutRule(30_000L)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        CaptureLatencyTracker.clear()
    }

    @After
    fun tearDown() {
        CaptureLatencyTracker.clear()
    }

    @Test
    fun captureModePolicy_selectsZslOrMinimizeLatencyForFastPhoto_andMaximizeQualityForQualityModes() {
        // Fast Photo with ZSL supported & no extension & no forced flash -> ZERO_SHUTTER_LAG
        assertEquals(
            ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG,
            CameraCaptureQualityPolicy.resolveCameraXCaptureMode(
                activeMode = CameraMode.PHOTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST,
                isZslSupported = true,
                activeExtensionMode = ExtensionMode.NONE,
                flashMode = FlashMode.AUTO
            )
        )

        // Fast Photo without ZSL -> MINIMIZE_LATENCY
        assertEquals(
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
            CameraCaptureQualityPolicy.resolveCameraXCaptureMode(
                activeMode = CameraMode.PHOTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST,
                isZslSupported = false,
                activeExtensionMode = ExtensionMode.NONE,
                flashMode = FlashMode.AUTO
            )
        )

        // Fast Photo with OEM Extension -> MINIMIZE_LATENCY (never combines ZSL with Extensions)
        assertEquals(
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
            CameraCaptureQualityPolicy.resolveCameraXCaptureMode(
                activeMode = CameraMode.PHOTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST,
                isZslSupported = true,
                activeExtensionMode = ExtensionMode.HDR,
                flashMode = FlashMode.AUTO
            )
        )

        // Quality Photo -> MAXIMIZE_QUALITY
        assertEquals(
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
            CameraCaptureQualityPolicy.resolveCameraXCaptureMode(
                activeMode = CameraMode.PHOTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.QUALITY,
                isZslSupported = true,
                activeExtensionMode = ExtensionMode.NONE,
                flashMode = FlashMode.AUTO
            )
        )

        // HD, Night Sight, Portrait -> MAXIMIZE_QUALITY regardless of Fast toggle
        for (mode in listOf(CameraMode.HD, CameraMode.NIGHT_SIGHT, CameraMode.PORTRAIT)) {
            assertEquals(
                ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
                CameraCaptureQualityPolicy.resolveCameraXCaptureMode(
                    activeMode = mode,
                    photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST,
                    isZslSupported = true
                )
            )
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @Test
    fun ispQualityPolicy_appliesHighQualityOnlyWhenSupported_andFastForPreviewOrFastMode() {
        val fullCaps = IspQualityCapabilities(
            availableNoiseReductionModes = listOf(
                CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            ),
            availableEdgeModes = listOf(
                CaptureRequest.EDGE_MODE_FAST,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            ),
            availableTonemapModes = listOf(
                CaptureRequest.TONEMAP_MODE_FAST,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            ),
            availableColorCorrectionAberrationModes = listOf(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            ),
            isZslSupported = true
        )

        val hqBuilder = CaptureRequestOptions.Builder()
        CameraCaptureQualityPolicy.applyIspQualityToCaptureRequestOptions(
            builder = hqBuilder,
            ispCaps = fullCaps,
            preferHighQuality = true
        )
        val hqOpts = hqBuilder.build()
        assertEquals(
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            hqOpts.getCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE)
        )
        assertEquals(
            CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            hqOpts.getCaptureRequestOption(CaptureRequest.EDGE_MODE)
        )
        assertEquals(
            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            hqOpts.getCaptureRequestOption(CaptureRequest.TONEMAP_MODE)
        )
        assertEquals(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            hqOpts.getCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE)
        )

        // Preview / Fast mode uses FAST
        val fastBuilder = CaptureRequestOptions.Builder()
        CameraCaptureQualityPolicy.applyIspQualityToCaptureRequestOptions(
            builder = fastBuilder,
            ispCaps = fullCaps,
            preferHighQuality = false
        )
        val fastOpts = fastBuilder.build()
        assertEquals(
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            fastOpts.getCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE)
        )
        assertEquals(
            CaptureRequest.EDGE_MODE_FAST,
            fastOpts.getCaptureRequestOption(CaptureRequest.EDGE_MODE)
        )

        // Unsupported capabilities are omitted (never blindly forced)
        val partialCaps = IspQualityCapabilities(
            availableNoiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY),
            availableEdgeModes = emptyList(),
            availableTonemapModes = emptyList(),
            availableColorCorrectionAberrationModes = emptyList(),
            isZslSupported = false
        )
        val partialBuilder = CaptureRequestOptions.Builder()
        CameraCaptureQualityPolicy.applyIspQualityToCaptureRequestOptions(
            builder = partialBuilder,
            ispCaps = partialCaps,
            preferHighQuality = true
        )
        val partialOpts = partialBuilder.build()
        assertEquals(
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            partialOpts.getCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE)
        )
        assertNull(partialOpts.getCaptureRequestOption(CaptureRequest.EDGE_MODE))
        assertNull(partialOpts.getCaptureRequestOption(CaptureRequest.TONEMAP_MODE))
        assertNull(partialOpts.getCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE))
        assertEquals(100, CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY)
    }

    @Test
    fun convergenceGate_skipsPrecaptureWhen3AConvergedAndNoFlash_andWaitsWhenUnconvergedOrFlashNeeded() {
        // Already converged in Photo mode with AUTO flash -> 0ms skip!
        assertFalse(
            CameraCaptureQualityPolicy.needsPrecaptureConvergenceWait(
                aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
                afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                awbState = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                flashMode = FlashMode.AUTO,
                activeMode = CameraMode.PHOTO,
                hasActiveTapFocus = false
            )
        )

        // AE searching -> must wait
        assertTrue(
            CameraCaptureQualityPolicy.needsPrecaptureConvergenceWait(
                aeState = CaptureResult.CONTROL_AE_STATE_SEARCHING,
                afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                awbState = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                flashMode = FlashMode.OFF,
                activeMode = CameraMode.PHOTO,
                hasActiveTapFocus = false
            )
        )

        // AF scanning -> must wait
        assertTrue(
            CameraCaptureQualityPolicy.needsPrecaptureConvergenceWait(
                aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
                afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                awbState = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                flashMode = FlashMode.OFF,
                activeMode = CameraMode.PHOTO,
                hasActiveTapFocus = false
            )
        )

        // AWB searching -> must wait
        assertTrue(
            CameraCaptureQualityPolicy.needsPrecaptureConvergenceWait(
                aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
                afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                awbState = CaptureResult.CONTROL_AWB_STATE_SEARCHING,
                flashMode = FlashMode.OFF,
                activeMode = CameraMode.PHOTO,
                hasActiveTapFocus = false
            )
        )

        // Flash ON or FLASH_REQUIRED -> must wait
        assertTrue(
            CameraCaptureQualityPolicy.needsPrecaptureConvergenceWait(
                aeState = CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
                afState = CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                awbState = CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                flashMode = FlashMode.AUTO,
                activeMode = CameraMode.PHOTO,
                hasActiveTapFocus = false
            )
        )
    }

    @Test
    fun extensionsHelper_neverSelectsUnavailableExtension_andSelectsRealMatchingExtensions() {
        val noneAvailable = CameraExtensionAvailability()
        assertEquals(
            ExtensionMode.NONE,
            CameraExtensionsHelper.resolveDesiredExtensionMode(
                availability = noneAvailable,
                activeMode = CameraMode.PHOTO,
                hdrMode = HdrMode.ON,
                portraitRetouch = PortraitRetouch.NATURAL,
                portraitBlurLevel = PortraitBlurLevel.MEDIUM,
                nightSightExposure = NightSightExposure.AUTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.QUALITY
            )
        )

        val fullAvailable = CameraExtensionAvailability(
            isHdrAvailable = true,
            isNightAvailable = true,
            isAutoAvailable = true,
            isFaceRetouchAvailable = true,
            isBokehAvailable = true
        )

        assertEquals(
            ExtensionMode.HDR,
            CameraExtensionsHelper.resolveDesiredExtensionMode(
                availability = fullAvailable,
                activeMode = CameraMode.PHOTO,
                hdrMode = HdrMode.ON,
                portraitRetouch = PortraitRetouch.OFF,
                portraitBlurLevel = PortraitBlurLevel.OFF,
                nightSightExposure = NightSightExposure.AUTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST
            )
        )

        assertEquals(
            ExtensionMode.NIGHT,
            CameraExtensionsHelper.resolveDesiredExtensionMode(
                availability = fullAvailable,
                activeMode = CameraMode.NIGHT_SIGHT,
                hdrMode = HdrMode.AUTO,
                portraitRetouch = PortraitRetouch.OFF,
                portraitBlurLevel = PortraitBlurLevel.OFF,
                nightSightExposure = NightSightExposure.AUTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST
            )
        )

        assertEquals(
            ExtensionMode.FACE_RETOUCH,
            CameraExtensionsHelper.resolveDesiredExtensionMode(
                availability = fullAvailable,
                activeMode = CameraMode.PORTRAIT,
                hdrMode = HdrMode.AUTO,
                portraitRetouch = PortraitRetouch.NATURAL,
                portraitBlurLevel = PortraitBlurLevel.MEDIUM,
                nightSightExposure = NightSightExposure.AUTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST
            )
        )

        assertEquals(
            ExtensionMode.BOKEH,
            CameraExtensionsHelper.resolveDesiredExtensionMode(
                availability = fullAvailable,
                activeMode = CameraMode.PORTRAIT,
                hdrMode = HdrMode.AUTO,
                portraitRetouch = PortraitRetouch.OFF,
                portraitBlurLevel = PortraitBlurLevel.MEDIUM,
                nightSightExposure = NightSightExposure.AUTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST
            )
        )

        assertEquals(
            ExtensionMode.AUTO,
            CameraExtensionsHelper.resolveDesiredExtensionMode(
                availability = fullAvailable,
                activeMode = CameraMode.PHOTO,
                hdrMode = HdrMode.OFF,
                portraitRetouch = PortraitRetouch.OFF,
                portraitBlurLevel = PortraitBlurLevel.OFF,
                nightSightExposure = NightSightExposure.AUTO,
                photoCaptureSpeedMode = PhotoCaptureSpeedMode.QUALITY
            )
        )
    }

    @Test
    fun boundedPhotoSaveQueue_runsOffCallingThread_allowsConcurrentBacklog_andCapsMemorySafely() {
        val queue = BoundedPhotoSaveQueue(maxInFlight = 2)
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val allDone = CountDownLatch(2)
        val ranOnBackgroundThread = AtomicBoolean(false)
        val callerThreadId = Thread.currentThread().id
        val executedCount = AtomicInteger(0)

        try {
            assertTrue(
                queue.tryEnqueue {
                    ranOnBackgroundThread.set(Thread.currentThread().id != callerThreadId)
                    firstStarted.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                    executedCount.incrementAndGet()
                    allDone.countDown()
                }
            )
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            // Second shot accepted while first is still saving
            assertTrue(
                queue.tryEnqueue {
                    executedCount.incrementAndGet()
                    allDone.countDown()
                }
            )

            // Queue is now full (2 in flight); 3rd shot rejected safely to prevent OOM
            assertTrue(queue.isFull)
            assertFalse(queue.tryEnqueue { executedCount.incrementAndGet() })

            releaseFirst.countDown()
            assertTrue(allDone.await(3, TimeUnit.SECONDS))
            assertTrue(ranOnBackgroundThread.get())
            assertEquals(2, executedCount.get())
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun captureLatencyTracker_recordsAndRetainsRecentShutterToSavedMetrics() {
        val rec = CaptureLatencyTracker.record(
            modeName = "Photo",
            speedModeLabel = "Fast",
            extensionLabel = "None",
            capturePathLabel = "DirectJPEG",
            shutterPressedAtMs = 1_000L,
            frameCapturedAtMs = 1_140L,
            savedAtMs = 1_215L,
            width = 4000,
            height = 3000
        )

        assertEquals(140L, rec.shutterToCaptureMs)
        assertEquals(75L, rec.captureToSavedMs)
        assertEquals(215L, rec.totalShutterToSavedMs)
        assertTrue(rec.formatSummaryLine().contains("total=215ms"))
        assertTrue(rec.formatSummaryLine().contains("DirectJPEG"))

        val recent = CaptureLatencyTracker.getRecentRecords()
        assertEquals(1, recent.size)
        assertEquals(215L, recent.first().totalShutterToSavedMs)
    }

    @Test
    fun streamingQualityFrameMerger_mergesFourNoisyFramesOneByOne_andReducesNoiseVariance() {
        val width = 64
        val height = 48
        val baseGray = 128

        fun makeNoisyJpeg(seedOffset: Int): ByteArray {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sign = if ((x + y + seedOffset) % 2 == 0) 1 else -1
                    val v = (baseGray + sign * 12).coerceIn(0, 255)
                    bmp.setPixel(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
                }
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
            bmp.recycle()
            return out.toByteArray()
        }

        val frame1 = makeNoisyJpeg(0)
        val frame2 = makeNoisyJpeg(1)
        val frame3 = makeNoisyJpeg(2)
        val frame4 = makeNoisyJpeg(3)

        val merger = StreamingQualityFrameMerger.createFromFirstJpeg(frame1)
        assertNotNull(merger)
        assertEquals(1, merger!!.framesMerged)
        assertTrue(StreamingQualityFrameMerger.mergeNextJpeg(merger, frame2))
        assertTrue(StreamingQualityFrameMerger.mergeNextJpeg(merger, frame3))
        assertTrue(StreamingQualityFrameMerger.mergeNextJpeg(merger, frame4))
        assertEquals(4, merger.framesMerged)

        val payload = StreamingQualityFrameMerger.finalizeToPayload(
            merger = merger,
            rotationDegrees = 0,
            aspectRatioMode = AspectRatioMode.RATIO_4_3,
            mirrorHorizontally = false,
            jpegQuality = 100
        )
        assertNotNull(payload)
        assertEquals(width, payload!!.width)
        assertEquals(height, payload.height)
        assertTrue(payload.jpegBytes.isNotEmpty())

        // Verify decoded merged frame has significantly lower deviation from true baseGray (128) than frame1
        val singleBmp = BitmapFactory.decodeByteArray(frame1, 0, frame1.size)
        val mergedBmp = BitmapFactory.decodeByteArray(payload.jpegBytes, 0, payload.jpegBytes.size)
        assertNotNull(singleBmp)
        assertNotNull(mergedBmp)

        var singleErrSum = 0.0
        var mergedErrSum = 0.0
        var count = 0
        for (y in 8 until height - 8) {
            for (x in 8 until width - 8) {
                val sr = (singleBmp.getPixel(x, y) shr 16) and 0xFF
                val mr = (mergedBmp.getPixel(x, y) shr 16) and 0xFF
                singleErrSum += kotlin.math.abs(sr - baseGray)
                mergedErrSum += kotlin.math.abs(mr - baseGray)
                count++
            }
        }
        singleBmp.recycle()
        mergedBmp.recycle()

        val avgSingleErr = singleErrSum / count
        val avgMergedErr = mergedErrSum / count
        assertTrue(
            "Expected merged noise error ($avgMergedErr) < single frame error ($avgSingleErr)",
            avgMergedErr < avgSingleErr
        )
    }

    @Test
    fun directCameraJpegSaver_preservesDcimCameraWithoutRecompression() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        bmp.recycle()
        val rawJpeg = baos.toByteArray()

        val saved = MediaStorePhotoSaver.savePhotoAtomically(
            context = context,
            outputBytes = rawJpeg,
            sourceExifBytes = rawJpeg,
            displayName = "IMG_FAST_DIRECT_TEST",
            mimeType = "image/jpeg",
            width = 32,
            height = 24,
            exifOrientation = android.media.ExifInterface.ORIENTATION_NORMAL,
            location = null,
            isDirectCameraJpeg = true
        )
        assertNotNull(saved)
        assertEquals("IMG_FAST_DIRECT_TEST", saved!!.displayName)
        assertEquals("image/jpeg", saved.mimeType)
        assertEquals(rawJpeg.size.toLong(), saved.sizeBytes)
    }

    @Test
    fun settingsScreen_photoCaptureSpeedModeToggle_updatesBetweenFastAndQuality() {
        val selectedMode = AtomicReference(PhotoCaptureSpeedMode.FAST)

        composeTestRule.setContent {
            PixelCameraTheme {
                SettingsScreen(
                    preferences = CameraPreferences(photoCaptureSpeedMode = PhotoCaptureSpeedMode.FAST),
                    onThemeChanged = {},
                    onDynamicColorChanged = {},
                    onAspectRatioChanged = {},
                    onGridChanged = {},
                    onTimerChanged = {},
                    onFlashChanged = {},
                    onSoundsChanged = {},
                    onLocationChanged = {},
                    onMirrorChanged = {},
                    onNavigateBack = {},
                    onPhotoCaptureSpeedModeChanged = { selectedMode.set(it) }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("setting_capture_speed_mode_row")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(
            "Max-quality ISP + 3–5 frame streaming noise reduction",
            substring = true
        ).performClick()
        composeTestRule.waitForIdle()

        assertEquals(PhotoCaptureSpeedMode.QUALITY, selectedMode.get())
    }

    @Test
    fun hardwareDebugDialog_displaysRecordedShutterToSavedLatencyMetrics() {
        CaptureLatencyTracker.record(
            modeName = "Photo",
            speedModeLabel = "Fast",
            extensionLabel = "HDR",
            capturePathLabel = "Ext(HDR)-DirectJPEG",
            shutterPressedAtMs = 5_000L,
            frameCapturedAtMs = 5_110L,
            savedAtMs = 5_165L,
            width = 4000,
            height = 3000
        )

        val report = CameraHardwareDebugReport(
            deviceModel = "Test Phone",
            androidVersionLabel = "Android 15 (SDK 35)",
            cameras = listOf(
                CameraIdDebugInfo(
                    cameraId = "0",
                    isPhysicalOnly = false,
                    parentLogicalId = null,
                    facingLabel = "Rear",
                    metadataLabel = "Rear Camera",
                    physicalCameraIds = emptyList(),
                    focalLengthsMm = listOf(4.2f),
                    pixelArraySize = "4000x3000 (12.0 MP)",
                    activeArraySize = "4000x3000",
                    videoSizesWithMaxFps = emptyList(),
                    highSpeedVideoEntries = emptyList(),
                    standardJpegSizes = listOf("4000x3000"),
                    highResJpegSizes = emptyList(),
                    maxResJpegSizes = emptyList(),
                    videoStabilizationModes = listOf("ON"),
                    opticalStabilizationModes = listOf("ON"),
                    aeFpsRanges = listOf("[30, 30]"),
                    ispQualitySummary = "ZSL=Yes | NR-HQ, Edge-HQ, Tonemap-HQ, Aberration-HQ",
                    supportedExtensions = listOf("HDR")
                )
            )
        )

        val plainReport = report.formatPlainText()
        assertTrue(plainReport.contains("Recent Capture Latency"))
        assertTrue(plainReport.contains("total=165ms"))

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraHardwareDebugDialog(
                    report = report,
                    activePhotoResolvedSize = "12 MP (4000 × 3000)",
                    activeHdNativeResolvedSize = "12 MP (4000 × 3000)",
                    activeHdBinnedResolvedSize = "12 MP (4000 × 3000)",
                    activeExtensionsSummary = "HDR",
                    activeIspSummary = "ZSL=Yes | NR-HQ, Edge-HQ, Tonemap-HQ, Aberration-HQ",
                    recentLatencyRecords = CaptureLatencyTracker.getRecentRecords(),
                    copied = false,
                    onCopyReport = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("hardware_debug_latency_card")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("total=165ms", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun quickControlsSheet_hidesUnavailableHdrAndOnlyShowsDetectedExtensions() {
        val lensWithNoHdrAndNightOnly = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            camera2CameraId = "0",
            supportedPhotoSizes = listOf(PhotoOutputSize(4000, 3000)),
            manualCapabilities = com.example.core.camera.ManualSensorCapabilities(
                isExposureCompensationSupported = false,
                evRange = null,
                isHardwareHdrSceneModeSupported = false
            ),
            extensionAvailability = CameraExtensionAvailability(
                isHdrAvailable = false,
                isNightAvailable = true,
                isAutoAvailable = false,
                isFaceRetouchAvailable = false,
                isBokehAvailable = false
            )
        )
        assertFalse(lensWithNoHdrAndNightOnly.isRealHdrSupported)
        assertEquals(HdrSupportPath.UNAVAILABLE, lensWithNoHdrAndNightOnly.hdrSupportPath)

        composeTestRule.setContent {
            PixelCameraTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                CameraQuickControlsSheet(
                    preferences = CameraPreferences(),
                    onFlashChanged = {},
                    onTimerChanged = {},
                    onAspectRatioChanged = {},
                    onGridChanged = {},
                    onOpenFullSettings = {},
                    onDismissRequest = {},
                    sheetState = sheetState,
                    activeMode = CameraMode.PHOTO,
                    capabilitiesState = CameraCapabilitiesState.Verified(
                        activeLens = CameraLens.BACK_MAIN,
                        availableLenses = listOf(CameraLens.BACK_MAIN),
                        capabilitiesByLens = mapOf(CameraLens.BACK_MAIN to lensWithNoHdrAndNightOnly)
                    )
                )
            }
        }
        composeTestRule.waitForIdle()

        // HDR+ toggle hidden when neither HDR extension nor EV/hardware HDR is available
        composeTestRule.onNodeWithTag("quick_hdr_row").assertDoesNotExist()
        // Only genuinely available CameraX Extension ("Night") is displayed
        composeTestRule.onNodeWithTag("quick_extensions_info_card")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Night", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
