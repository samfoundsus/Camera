package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CrashLogManager
import com.example.core.preferences.CameraFilterPreset
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.CameraPreferencesRepository
import com.example.core.processing.CameraFilterProcessor
import com.example.core.processing.GpuCameraFilterEngine
import com.example.feature.preview.CameraFoundationScreen
import com.example.feature.preview.components.queryDeviceCameraCapabilities
import com.example.feature.settings.CameraHardwareDebugDialog
import com.example.feature.settings.formatHardwareDebugReportText
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupSmokeAndCrashRecoveryTest {

    @get:Rule(order = 0)
    val testTimeoutRule = MainThreadSafeTimeoutRule(30_000L)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        CrashLogManager.clearLastCrashLog(appContext)
        CrashLogManager.clearNonFatalWarnings()
        GpuCameraFilterEngine.resetGlSurfaceProcessorFailureForTesting()
    }

    @After
    fun tearDown() {
        CrashLogManager.clearLastCrashLog(appContext)
        CrashLogManager.clearNonFatalWarnings()
    }

    @Test
    fun mainActivity_coldLaunchWithNoCameraAndNoPermissions_doesNotCrash() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(
            "CameraApplication must be registered in AndroidManifest so CrashLogManager installs on launch",
            application is CameraApplication
        )

        Shadows.shadowOf(application).denyPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        assertEquals(
            "Startup smoke test requires an environment with zero camera IDs",
            0,
            cameraManager.cameraIdList.size
        )

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            val activity = controller.create().start().resume().visible().get()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertFalse("MainActivity must stay alive on cold launch without camera/permissions", activity.isFinishing)
            assertFalse("MainActivity must not be destroyed on cold launch", activity.isDestroyed)
            assertNull("No uncaught crash log should be written during healthy cold launch", CrashLogManager.readLastCrashLog(appContext))
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun cameraFoundationScreen_withNoCameraAndNoPermissions_rendersMainUiSafely() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(application).denyPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val discoveredCaps = queryDeviceCameraCapabilities(appContext)
        assertTrue("Capability discovery with no cameras must return empty map without throwing", discoveredCaps.isEmpty())

        val repo = CameraPreferencesRepository(appContext)
        val prefs = CameraPreferences()

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraFoundationScreen(
                    preferences = prefs,
                    preferencesRepository = repo,
                    onNavigateToSettings = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_foundation_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_top_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_mode_selector").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_shutter_bar").assertIsDisplayed()
    }

    @Test
    fun crashLogManager_persistsStackTraceWithCausedBy_andRendersInHiddenDebugDialog() {
        val rootCause = IllegalStateException("EGL14.eglCreateWindowSurface returned EGL_NO_SURFACE")
        val topException = RuntimeException("Camera startup GPU pipeline failure", rootCause)

        val logFile = CrashLogManager.recordCrashSync(
            context = appContext,
            threadName = "GlCameraFilterThread",
            throwable = topException
        )
        assertNotNull("Crash log file should be created in filesDir", logFile)
        assertTrue("Crash log file must exist on disk", logFile!!.exists())
        assertEquals(CrashLogManager.CRASH_LOG_FILE_NAME, logFile.name)

        val readBack = CrashLogManager.readLastCrashLog(appContext)
        assertNotNull("Crash log should be readable on next launch", readBack)
        assertTrue(readBack!!.contains("=== FATAL EXCEPTION REPORT ==="))
        assertTrue(readBack.contains("Thread: GlCameraFilterThread"))
        assertTrue(readBack.contains("Camera startup GPU pipeline failure"))
        assertTrue(readBack.contains("Caused by: java.lang.IllegalStateException: EGL14.eglCreateWindowSurface returned EGL_NO_SURFACE"))
        assertTrue(readBack.contains("Root Cause: java.lang.IllegalStateException"))

        val copiedReport = formatHardwareDebugReportText(
            report = null,
            lastCrashLog = readBack,
            nonFatalWarnings = listOf("[test] Startup fallback triggered")
        )
        assertTrue(copiedReport.contains("=== Last Recorded Crash Log ==="))
        assertTrue(copiedReport.contains("EGL14.eglCreateWindowSurface returned EGL_NO_SURFACE"))
        assertTrue(copiedReport.contains("Startup fallback triggered"))

        var dialogCrashLog by mutableStateOf<String?>(readBack)
        composeTestRule.setContent {
            PixelCameraTheme {
                CameraHardwareDebugDialog(
                    report = null,
                    activePhotoResolvedSize = null,
                    activeHdNativeResolvedSize = null,
                    activeHdBinnedResolvedSize = null,
                    activeExtensionsSummary = "None",
                    activeIspSummary = null,
                    recentLatencyRecords = emptyList(),
                    lastCrashLog = dialogCrashLog,
                    nonFatalWarnings = listOf("[test] Recovered GL fallback"),
                    onClearCrashLog = {
                        CrashLogManager.clearLastCrashLog(appContext)
                        dialogCrashLog = null
                    },
                    copied = false,
                    onCopyReport = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hardware_debug_crash_log_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hardware_debug_crash_log_text")
            .assertIsDisplayed()
            .assertTextContains("Camera startup GPU pipeline failure", substring = true)

        composeTestRule.onNodeWithTag("hardware_debug_clear_crash_log_button").performClick()
        composeTestRule.waitForIdle()

        assertNull("Clearing crash log must delete files/last_crash_log.txt", CrashLogManager.readLastCrashLog(appContext))
        composeTestRule.onNodeWithTag("hardware_debug_crash_log_empty").assertIsDisplayed()
    }

    @Test
    fun gpuFilterPipeline_onEmulatorOrHeadless_disablesGlCameraEffectAndFallsBackToCpuSafely() {
        assertTrue(
            "Robolectric/emulator runtime must be detected as an emulator environment",
            GpuCameraFilterEngine.isEmulatorEnvironment()
        )
        assertFalse(
            "CameraX GL SurfaceProcessor CameraEffect must be disabled on basic emulator/headless environments",
            GpuCameraFilterEngine.isGpuCameraEffectSupported()
        )

        val tinyThumb = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 150, 180))
        }
        try {
            val gpuResult = GpuCameraFilterEngine.applyFilterToBitmapOnGpu(
                source = tinyThumb,
                preset = CameraFilterPreset.VIVID,
                intensity = 1.0f
            )
            assertNull("Tiny thumbnails and emulator bitmaps must bypass EGL/HardwareRenderer and fall back to CPU", gpuResult)

            val cpuFiltered = CameraFilterProcessor.applyFilterToBitmap(
                source = tinyThumb,
                preset = CameraFilterPreset.VIVID,
                intensity = 1.0f,
                allowGpu = false
            )
            assertEquals(88, cpuFiltered.width)
            assertEquals(88, cpuFiltered.height)
            assertEquals("CPU-MT-LUT", CameraFilterProcessor.lastStillFilterBackend)
            if (cpuFiltered !== tinyThumb && !cpuFiltered.isRecycled) {
                cpuFiltered.recycle()
            }
        } finally {
            if (!tinyThumb.isRecycled) tinyThumb.recycle()
        }

        CameraFilterProcessor.warmUpThumbnailsBlocking(88)
        assertNotNull(
            "CPU thumbnail warm-up must populate thumbnail cache safely without EGL",
            CameraFilterProcessor.getCachedThumbnailOrNull(CameraFilterPreset.VIVID, 88)
        )
    }
}
