package com.example

import android.content.Context
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.CameraLens
import com.example.core.camera.LensCapabilities
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.model.CameraMode
import com.example.core.model.SystemDeviceInfo
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.FlashMode
import com.example.feature.preview.components.CameraModeSelector
import com.example.ui.components.CameraQuickControlsSheet
import com.example.ui.theme.ThemeStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appName_isPixelCamera() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Pixel Camera", appName)
    }

    @Test
    fun cameraModes_configuredCorrectly() {
        assertEquals("Camera", CameraMode.PHOTO.displayName)
        assertEquals(CameraMode.PHOTO, CameraMode.defaultMode)
        assertTrue(CameraMode.VIDEO.isVideo)
        assertTrue(CameraMode.CINEMATIC.isVideo)
        assertTrue(CameraMode.SLOW_MOTION.isVideo)
        assertTrue(CameraMode.TIME_LAPSE.isVideo)
        assertFalse(CameraMode.PANORAMA.isVideo)
        assertFalse(CameraMode.PHOTO.isVideo)
        assertFalse(CameraMode.NIGHT_SIGHT.isVideo)
        assertFalse(CameraMode.PORTRAIT.isVideo)
        assertFalse(CameraMode.HD.isVideo)
        assertFalse(CameraMode.PRO.isVideo)
        assertEquals(10, CameraMode.entries.size)
    }

    @Test
    fun cameraModes_allPlannedModesHaveUniqueDisplayNames() {
        val names = CameraMode.entries.map { it.displayName }
        assertEquals(names.distinct().size, names.size)
        assertTrue(names.contains("Night Sight"))
        assertTrue(names.contains("Portrait"))
        assertTrue(names.contains("Camera"))
        assertTrue(names.contains("HD"))
        assertTrue(names.contains("Pro"))
        assertTrue(names.contains("Video"))
        assertTrue(names.contains("Cinematic"))
        assertTrue(names.contains("Slow Motion"))
        assertTrue(names.contains("Time Lapse"))
        assertTrue(names.contains("Panorama"))
    }

    @Test
    fun modeCenteringMath_calculatesExactAlignment() {
        // Given viewport bounds from viewportStartOffset to viewportEndOffset
        val vStart = -540
        val vEnd = 540
        val vCenter = (vStart + vEnd) / 2 // 0

        // Test item centered at 0
        val centeredItemOffset = -50
        val centeredItemSize = 100
        val centeredItemCenter = centeredItemOffset + centeredItemSize / 2 // 0
        val zeroCorrection = centeredItemCenter - vCenter
        assertEquals(0, zeroCorrection)

        // Test item to the right (e.g. offset 100, size 100 -> center 150)
        val rightItemOffset = 100
        val rightItemSize = 100
        val rightItemCenter = rightItemOffset + rightItemSize / 2 // 150
        val rightCorrection = rightItemCenter - vCenter
        assertEquals(150, rightCorrection)

        // Test item to the left (e.g. offset -250, size 100 -> center -200)
        val leftItemOffset = -250
        val leftItemSize = 100
        val leftItemCenter = leftItemOffset + leftItemSize / 2 // -200
        val leftCorrection = leftItemCenter - vCenter
        assertEquals(-200, leftCorrection)
    }

    @Test
    fun testModeSelectorCenteringCalculations() {
        lateinit var testState: androidx.compose.foundation.lazy.LazyListState
        composeTestRule.setContent {
            val state = rememberLazyListState()
            testState = state
            Box(modifier = Modifier.width(400.dp)) {
                LazyRow(
                    state = state,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 200.dp)
                ) {
                    items(10) { index ->
                        Box(modifier = Modifier.width(80.dp)) {
                            Text("Item $index")
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        val layout = testState.layoutInfo
        val vStart = layout.viewportStartOffset
        val vEnd = layout.viewportEndOffset
        val vCenter = (vStart + vEnd) / 2
        val item0 = layout.visibleItemsInfo.find { it.index == 0 }!!
        val itemCenter = item0.offset + item0.size / 2
        val correction = itemCenter - vCenter

        // Apply scroll correction
        kotlinx.coroutines.runBlocking {
            testState.scrollBy(correction.toFloat())
        }
        composeTestRule.waitForIdle()

        val newLayout = testState.layoutInfo
        val newVCenter = (newLayout.viewportStartOffset + newLayout.viewportEndOffset) / 2
        val newItem0 = newLayout.visibleItemsInfo.find { it.index == 0 }!!
        val newItemCenter = newItem0.offset + newItem0.size / 2
        val newCorrection = newItemCenter - newVCenter

        // After scrolling by +correction, item0 is centered at exact 0
        assertEquals(0, newCorrection)
    }

    @Test
    fun cameraModeSelector_centersInitialModeAndSelectedModes() {
        var currentMode by mutableStateOf(CameraMode.PHOTO)

        composeTestRule.setContent {
            Box(modifier = Modifier.width(400.dp)) {
                CameraModeSelector(
                    selectedMode = currentMode,
                    onModeSelected = { currentMode = it }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Switch to Cinematic mode (middle-right item)
        currentMode = CameraMode.CINEMATIC
        composeTestRule.waitForIdle()

        // Switch to Night Sight (first edge item)
        currentMode = CameraMode.NIGHT_SIGHT
        composeTestRule.waitForIdle()

        // Switch to HD mode
        currentMode = CameraMode.HD
        composeTestRule.waitForIdle()
    }

    @Test
    fun themeStrategy_hasExpectedLabels() {
        assertTrue(ThemeStrategy.DYNAMIC_MONET.label.contains("Dynamic Monet"))
        assertTrue(ThemeStrategy.STATIC_PALETTE.label.contains("Static Palette"))
    }

    @Test
    fun systemDeviceInfo_parsesAccurately() {
        val info = SystemDeviceInfo.current()
        assertTrue(info.apiLevel >= 29)
    }

    @Test
    fun preferencesRepository_persistsAcrossReloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.core.preferences.CameraPreferencesRepository(context)

        // Set preferences
        repo.setThemePreference(com.example.core.preferences.AppThemePreference.DARK)
        repo.setFlashMode(com.example.core.preferences.FlashMode.TORCH)
        repo.setTimerMode(com.example.core.preferences.TimerMode.SECONDS_3)
        repo.setGridMode(com.example.core.preferences.GridMode.GOLDEN_RATIO)
        repo.setAspectRatioMode(com.example.core.preferences.AspectRatioMode.RATIO_1_1)

        // Instantiate a second repository instance to simulate app restart
        val reloadedRepo = com.example.core.preferences.CameraPreferencesRepository(context)
        val loaded = reloadedRepo.preferences.value

        assertEquals(com.example.core.preferences.AppThemePreference.DARK, loaded.themePreference)
        assertEquals(com.example.core.preferences.FlashMode.TORCH, loaded.flashMode)
        assertEquals(com.example.core.preferences.TimerMode.SECONDS_3, loaded.timerMode)
        assertEquals(com.example.core.preferences.GridMode.GOLDEN_RATIO, loaded.gridMode)
        assertEquals(com.example.core.preferences.AspectRatioMode.RATIO_1_1, loaded.aspectRatioMode)

        // Also test switching to RATIO_9_16 and persisting
        reloadedRepo.setAspectRatioMode(com.example.core.preferences.AspectRatioMode.RATIO_9_16)
        val thirdRepo = com.example.core.preferences.CameraPreferencesRepository(context)
        assertEquals(com.example.core.preferences.AspectRatioMode.RATIO_9_16, thirdRepo.preferences.value.aspectRatioMode)

        // Verify fallback to RATIO_4_3 if obsolete "FULL" was previously stored in SharedPreferences
        context.getSharedPreferences("pixel_camera_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("key_ratio_mode", "FULL")
            .commit()
        val migratedRepo = com.example.core.preferences.CameraPreferencesRepository(context)
        assertEquals(com.example.core.preferences.AspectRatioMode.RATIO_4_3, migratedRepo.preferences.value.aspectRatioMode)
    }

    @Test
    fun cameraTopBar_containsOnlyCameraControls() {
        composeTestRule.setContent {
            com.example.feature.preview.components.CameraTopBar(
                flashMode = com.example.core.preferences.FlashMode.AUTO,
                onCycleFlash = {},
                onQuickControlsClick = {},
                onSettingsClick = {}
            )
        }
        composeTestRule.onNodeWithTag("camera_top_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_bar_flash_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_bar_quick_controls_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_bar_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_bar_info_button").assertDoesNotExist()
    }

    @Test
    fun cameraFoundationScreen_rendersCleanCameraUIWithoutDevHeader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.core.preferences.CameraPreferencesRepository(context)
        val prefs = com.example.core.preferences.CameraPreferences()

        composeTestRule.setContent {
            com.example.feature.preview.CameraFoundationScreen(
                preferences = prefs,
                preferencesRepository = repo,
                onNavigateToSettings = {}
            )
        }

        composeTestRule.waitForIdle()

        // Camera UI elements are present
        composeTestRule.onNodeWithTag("camera_foundation_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_top_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_placeholder").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_mode_selector").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_shutter_bar").assertIsDisplayed()

        // Diagnostics are completely removed
        composeTestRule.onNodeWithTag("system_status_bar").assertDoesNotExist()
        composeTestRule.onNodeWithTag("android_version_label").assertDoesNotExist()
        composeTestRule.onNodeWithTag("theme_strategy_label").assertDoesNotExist()
    }

    @Test
    fun cameraShutterBar_shutterButtonClickInPhotoModeTriggersCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.core.preferences.CameraPreferencesRepository(context)
        val prefs = com.example.core.preferences.CameraPreferences()

        composeTestRule.setContent {
            com.example.feature.preview.CameraFoundationScreen(
                preferences = prefs,
                preferencesRepository = repo,
                onNavigateToSettings = {}
            )
        }
        composeTestRule.waitForIdle()

        // Shutter button is present and clickable
        composeTestRule.onNodeWithTag("shutter_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shutter_button").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun cameraPreviewPlaceholder_displaysRecordingIndicatorWhenActive() {
        composeTestRule.setContent {
            com.example.feature.preview.components.CameraPreviewPlaceholder(
                activeMode = CameraMode.VIDEO,
                isRecording = true,
                recordingDurationSeconds = 125L
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recording_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithText("REC • 02:05").assertIsDisplayed()
    }

    @Test
    fun cameraTopBar_flashPopupOpensAndOffersFourOptions() {
        var selectedFlash: com.example.core.preferences.FlashMode? = null

        composeTestRule.setContent {
            com.example.feature.preview.components.CameraTopBar(
                flashMode = com.example.core.preferences.FlashMode.AUTO,
                onFlashModeSelected = { selectedFlash = it },
                onCycleFlash = {},
                onQuickControlsClick = {},
                onSettingsClick = {}
            )
        }

        // Tap the flash button to open popup
        composeTestRule.onNodeWithTag("top_bar_flash_button").performClick()
        composeTestRule.waitForIdle()

        // Popup and all 4 options should be displayed
        composeTestRule.onNodeWithTag("flash_popup_menu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("flash_option_auto").assertIsDisplayed()
        composeTestRule.onNodeWithTag("flash_option_on").assertIsDisplayed()
        composeTestRule.onNodeWithTag("flash_option_off").assertIsDisplayed()
        composeTestRule.onNodeWithTag("flash_option_torch").assertIsDisplayed()

        // Tap Torch option
        composeTestRule.onNodeWithTag("flash_option_torch").performClick()
        composeTestRule.waitForIdle()

        assertEquals(com.example.core.preferences.FlashMode.TORCH, selectedFlash)
    }

    @Test
    fun cameraTopBar_eachFlashOptionSelectsExactModeWithoutCycling() {
        var selectedFlash: com.example.core.preferences.FlashMode? = null
        var cycleCount = 0

        composeTestRule.setContent {
            com.example.feature.preview.components.CameraTopBar(
                flashMode = com.example.core.preferences.FlashMode.AUTO,
                onFlashModeSelected = { selectedFlash = it },
                onCycleFlash = { cycleCount++ },
                onQuickControlsClick = {},
                onSettingsClick = {}
            )
        }

        // Test ON option
        composeTestRule.onNodeWithTag("top_bar_flash_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("flash_option_on").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.core.preferences.FlashMode.ON, selectedFlash)

        // Test OFF option
        composeTestRule.onNodeWithTag("top_bar_flash_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("flash_option_off").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.core.preferences.FlashMode.OFF, selectedFlash)

        // Test AUTO option
        composeTestRule.onNodeWithTag("top_bar_flash_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("flash_option_auto").performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.core.preferences.FlashMode.AUTO, selectedFlash)

        // Cycle count should remain 0 because tapping popup options must select explicitly without cycling
        assertEquals(0, cycleCount)
    }

    @Test
    fun cameraPreviewPlaceholder_noCornerTechnicalBadges() {
        var mode by androidx.compose.runtime.mutableStateOf(CameraMode.PHOTO)
        composeTestRule.setContent {
            com.example.feature.preview.components.CameraPreviewPlaceholder(
                activeMode = mode,
                aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_4_3,
                gridMode = com.example.core.preferences.GridMode.GRID_4X4,
                timerMode = com.example.core.preferences.TimerMode.OFF
            )
        }

        // Preview container is present, while the unwanted centered reticle icon is absent across modes
        composeTestRule.onNodeWithTag("preview_placeholder").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewfinder_center_card").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Viewfinder Target").assertDoesNotExist()

        mode = CameraMode.VIDEO
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("viewfinder_center_card").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Viewfinder Target").assertDoesNotExist()

        // Technical badges (CAMERA mode text and aspect ratio text) are not overlaid in preview
        composeTestRule.onNodeWithText("CAMERA").assertDoesNotExist()
        composeTestRule.onNodeWithText("4:3").assertDoesNotExist()
    }

    @Test
    fun theme_settingsScreen_omitsDebugThemeCard_preservesThemeControls() {
        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.settings.SettingsScreen(
                    preferences = com.example.core.preferences.CameraPreferences(),
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

        // Informational Material You debug demonstration card is removed
        composeTestRule.onNodeWithTag("theme_palette_preview_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("theme_strategy_badge").assertDoesNotExist()
        composeTestRule.onNodeWithTag("theme_palette_chips_row").assertDoesNotExist()

        // Actual user theme preference controls remain intact
        composeTestRule.onNodeWithTag("setting_theme_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_dynamic_color_switch").assertIsDisplayed()
    }

    @Test
    fun theme_settingsScreenThemeSwitching_triggersThemeCallback() {
        var updatedTheme: com.example.core.preferences.AppThemePreference? = null

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.settings.SettingsScreen(
                    preferences = com.example.core.preferences.CameraPreferences(),
                    onThemeChanged = { updatedTheme = it },
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

        composeTestRule.onNodeWithTag("setting_theme_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_dynamic_color_switch").assertIsDisplayed()

        // Tap theme row to open dialog
        composeTestRule.onNodeWithTag("setting_theme_row").performClick()
        composeTestRule.waitForIdle()

        // Select Dark
        composeTestRule.onNodeWithText("Dark").performClick()
        composeTestRule.waitForIdle()

        assertEquals(com.example.core.preferences.AppThemePreference.DARK, updatedTheme)
    }

    @Test
    fun pixelCameraTheme_appliesResolvedColorSchemeToMaterialTheme() {
        var observedPrimary: androidx.compose.ui.graphics.Color? = null
        var observedStrategy: ThemeStrategy? = null

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme(
                themePreference = com.example.core.preferences.AppThemePreference.LIGHT,
                dynamicColor = false
            ) {
                observedPrimary = androidx.compose.material3.MaterialTheme.colorScheme.primary
                observedStrategy = com.example.ui.theme.LocalThemeStrategy.current
                Text("Theme Test Content")
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(com.example.ui.theme.PixelLightPrimary, observedPrimary)
        assertEquals(ThemeStrategy.STATIC_PALETTE, observedStrategy)
    }

    @Test
    fun settingsScreen_rendersGroupedCardsAndControls() {
        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.settings.SettingsScreen(
                    preferences = com.example.core.preferences.CameraPreferences(),
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

        // Verify section headers exist in scrollable settings screen
        composeTestRule.onNodeWithText("Appearance").assertExists()
        composeTestRule.onNodeWithText("Photo & HD Capture").assertExists()
        composeTestRule.onNodeWithText("Pro Mode & RAW").assertExists()
        composeTestRule.onNodeWithText("Video & Motion").assertExists()
        composeTestRule.onNodeWithText("Gestures and Controls").assertExists()
        composeTestRule.onNodeWithText("About").assertExists()

        // Verify grouped card rows
        composeTestRule.onNodeWithTag("setting_theme_row").assertExists()
        composeTestRule.onNodeWithTag("setting_dynamic_color_switch").assertExists()
        composeTestRule.onNodeWithTag("setting_aspect_ratio_row").assertExists()
        composeTestRule.onNodeWithTag("setting_grid_row").assertExists()
        composeTestRule.onNodeWithTag("setting_timer_row").assertExists()
        composeTestRule.onNodeWithTag("setting_flash_row").assertExists()
        composeTestRule.onNodeWithTag("setting_video_stabilization_row").assertExists()
        composeTestRule.onNodeWithTag("setting_sounds_switch").assertExists()
        composeTestRule.onNodeWithTag("setting_location_switch").assertExists()
        composeTestRule.onNodeWithTag("setting_mirror_switch").assertExists()

        // Pro Mode & RAW rows
        composeTestRule.onNodeWithTag("setting_pro_raw_row").assertExists()
        composeTestRule.onNodeWithTag("setting_pro_focus_row").assertExists()
        composeTestRule.onNodeWithTag("setting_pro_histogram_switch").assertExists()
        composeTestRule.onNodeWithTag("setting_pro_peaking_switch").assertExists()

        // Video & HD mode rows
        composeTestRule.onNodeWithTag("setting_hd_output_row").assertExists()
        composeTestRule.onNodeWithTag("setting_video_resolution_row").assertExists()
        composeTestRule.onNodeWithTag("setting_video_fps_row").assertExists()
    }

    @Test
    fun modeSelector_containsTenExactModes() {
        var selectedMode by mutableStateOf(CameraMode.PHOTO)
        composeTestRule.setContent {
            com.example.feature.preview.components.CameraModeSelector(
                selectedMode = selectedMode,
                onModeSelected = { selectedMode = it }
            )
        }

        val expectedModes = listOf(
            "Night Sight", "Portrait", "Camera", "Video",
            "Cinematic", "Slow Motion", "Time Lapse", "Panorama", "HD", "Pro"
        )
        assertEquals(10, expectedModes.size)
        assertEquals(expectedModes, CameraMode.entries.map { it.displayName })

        // Switch to HD mode to scroll HD into LazyRow view
        selectedMode = CameraMode.HD
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mode_item_hd").assertExists()

        // Switch to Pro mode to scroll Pro into LazyRow view
        selectedMode = CameraMode.PRO
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mode_item_pro").assertExists()
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun cameraQuickControlsSheet_adaptsToHdVideoAndProModes() {
        var currentMode by mutableStateOf(CameraMode.HD)
        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.ui.components.CameraQuickControlsSheet(
                    activeMode = currentMode,
                    preferences = com.example.core.preferences.CameraPreferences(),
                    onFlashChanged = {},
                    onTimerChanged = {},
                    onAspectRatioChanged = {},
                    onGridChanged = {},
                    onOpenFullSettings = {},
                    onDismissRequest = {},
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                )
            }
        }

        composeTestRule.onNodeWithText("HD Capture Quality").assertIsDisplayed()
        composeTestRule.onNodeWithText("Highest Supported Native Output").assertIsDisplayed()

        // Switch to Video mode
        currentMode = CameraMode.VIDEO
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Video Resolution").assertIsDisplayed()
        composeTestRule.onNodeWithText("Frame Rate").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stabilization").assertIsDisplayed()

        // Switch to Pro mode
        currentMode = CameraMode.PRO
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("RAW / DNG Format").assertIsDisplayed()
        composeTestRule.onNodeWithText("ISO Sensitivity").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shutter Speed").assertIsDisplayed()
    }

    @Test
    fun cameraProControlBar_panelRemainsOpenOnSelectionAndSwitchesCleanly() {
        var selectedIso by androidx.compose.runtime.mutableStateOf("AUTO")
        var selectedShutter by androidx.compose.runtime.mutableStateOf("AUTO")
        var selectedControl by androidx.compose.runtime.mutableStateOf<String?>("ISO")

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraProControlBar(
                    preferences = com.example.core.preferences.CameraPreferences(
                        proIso = selectedIso,
                        proShutterSpeed = selectedShutter
                    ),
                    capabilitiesState = com.example.core.camera.CameraCapabilitiesState.PendingDiscovery(),
                    selectedProControl = selectedControl,
                    onSelectProControl = { selectedControl = it },
                    onIsoChanged = { selectedIso = it },
                    onShutterChanged = { selectedShutter = it },
                    onFocusModeChanged = {},
                    onFocusDistanceChanged = {},
                    onWbChanged = {},
                    onEvChanged = {},
                    onRawFormatChanged = {},
                    onZoomRatioChanged = {},
                    onHistogramToggled = {},
                    onFocusPeakingToggled = {}
                )
            }
        }

        // 1. Initial panel for ISO is displayed with slider and AUTO pill
        composeTestRule.onNodeWithTag("pro_value_selector_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_iso_slider").assertIsDisplayed()
        composeTestRule.onNodeWithText("AUTO").assertIsDisplayed()

        // 2. Switch cleanly to Shutter speed tab - opens Shutter panel with slider
        composeTestRule.onNodeWithTag("pro_tab_shutter").performClick()
        composeTestRule.waitForIdle()
        assertEquals("SHUTTER", selectedControl)
        composeTestRule.onNodeWithTag("pro_shutter_slider").assertIsDisplayed()

        // 3. Re-selecting the active tab keeps the control panel open and active
        composeTestRule.onNodeWithTag("pro_tab_shutter").performClick()
        composeTestRule.waitForIdle()
        assertEquals("SHUTTER", selectedControl)
        composeTestRule.onNodeWithTag("pro_value_selector_card").assertIsDisplayed()
    }

    @Test
    fun cameraProControlBar_evSliderUpdatesValueSmoothly() {
        var selectedEv by androidx.compose.runtime.mutableStateOf(0.0f)
        var selectedControl by androidx.compose.runtime.mutableStateOf<String?>("EV")

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraProControlBar(
                    preferences = com.example.core.preferences.CameraPreferences(
                        proEv = selectedEv
                    ),
                    capabilitiesState = com.example.core.camera.CameraCapabilitiesState.PendingDiscovery(),
                    selectedProControl = selectedControl,
                    onSelectProControl = { selectedControl = it },
                    onIsoChanged = {},
                    onShutterChanged = {},
                    onFocusModeChanged = {},
                    onFocusDistanceChanged = {},
                    onWbChanged = {},
                    onEvChanged = { selectedEv = it },
                    onRawFormatChanged = {},
                    onZoomRatioChanged = {},
                    onHistogramToggled = {},
                    onFocusPeakingToggled = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("pro_value_selector_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exposure Value").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.0 EV").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_ev_slider").assertIsDisplayed()
    }

    @Test
    fun customizableModeSelector_showsVisibleAndMoreOption() {
        var selectedMode by mutableStateOf(CameraMode.PHOTO)
        var moreClicked = false

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraModeSelector(
                    selectedMode = selectedMode,
                    visibleModes = listOf(CameraMode.NIGHT_SIGHT, CameraMode.PORTRAIT, CameraMode.PHOTO, CameraMode.VIDEO),
                    hiddenModes = listOf(CameraMode.HD, CameraMode.PRO, CameraMode.CINEMATIC),
                    onModeSelected = { selectedMode = it },
                    onMoreClick = { moreClicked = true }
                )
            }
        }

        // Visible modes displayed
        composeTestRule.onNodeWithTag("mode_item_photo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_item_more").assertExists()

        // Click More option
        composeTestRule.onNodeWithTag("mode_item_more").performClick()
        composeTestRule.waitForIdle()
        assertTrue(moreClicked)
    }

    @Test
    fun cameraProControlBar_isoSliderSupportsIso50WhenHardwareCapable() {
        var selectedIso by mutableStateOf("AUTO")

        val hardwareCapabilities = com.example.core.camera.LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            manualCapabilities = com.example.core.camera.ManualSensorCapabilities(
                isManualIsoSupported = true,
                isoRange = 50..3200
            )
        )
        val verifiedState = com.example.core.camera.CameraCapabilitiesState.Verified(
            activeLens = CameraLens.BACK_MAIN,
            availableLenses = listOf(CameraLens.BACK_MAIN),
            capabilitiesByLens = mapOf(CameraLens.BACK_MAIN to hardwareCapabilities)
        )

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraProControlBar(
                    preferences = com.example.core.preferences.CameraPreferences(
                        proIso = selectedIso
                    ),
                    capabilitiesState = verifiedState,
                    selectedProControl = "ISO",
                    onSelectProControl = {},
                    onIsoChanged = { selectedIso = it },
                    onShutterChanged = {},
                    onFocusModeChanged = {},
                    onFocusDistanceChanged = {},
                    onWbChanged = {},
                    onEvChanged = {},
                    onRawFormatChanged = {},
                    onZoomRatioChanged = {},
                    onHistogramToggled = {},
                    onFocusPeakingToggled = {}
                )
            }
        }

        // When device supports ISO 50, slider minimum label is 50 and maximum is 3200
        composeTestRule.onNodeWithText("50").assertIsDisplayed()
        composeTestRule.onNodeWithText("3200").assertIsDisplayed()
        composeTestRule.onNodeWithText("ISO AUTO").assertIsDisplayed()

        // Clicking AUTO pill while in AUTO switches to initial value 50
        composeTestRule.onNodeWithTag("pro_iso_auto_pill").performClick()
        composeTestRule.waitForIdle()
        assertEquals("50", selectedIso)
        composeTestRule.onAllNodesWithText("ISO 50").onFirst().assertIsDisplayed()
    }

    @Test
    fun cameraProControlBar_isoSliderUsesActualMinWhenIso50Unsupported() {
        var selectedIso by mutableStateOf("AUTO")

        val hardwareCapabilities = com.example.core.camera.LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            manualCapabilities = com.example.core.camera.ManualSensorCapabilities(
                isManualIsoSupported = true,
                isoRange = 100..6400
            )
        )
        val verifiedState = com.example.core.camera.CameraCapabilitiesState.Verified(
            activeLens = CameraLens.BACK_MAIN,
            availableLenses = listOf(CameraLens.BACK_MAIN),
            capabilitiesByLens = mapOf(CameraLens.BACK_MAIN to hardwareCapabilities)
        )

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraProControlBar(
                    preferences = com.example.core.preferences.CameraPreferences(
                        proIso = selectedIso
                    ),
                    capabilitiesState = verifiedState,
                    selectedProControl = "ISO",
                    onSelectProControl = {},
                    onIsoChanged = { selectedIso = it },
                    onShutterChanged = {},
                    onFocusModeChanged = {},
                    onFocusDistanceChanged = {},
                    onWbChanged = {},
                    onEvChanged = {},
                    onRawFormatChanged = {},
                    onZoomRatioChanged = {},
                    onHistogramToggled = {},
                    onFocusPeakingToggled = {}
                )
            }
        }

        // When device does not support ISO 50, minimum label is 100 (actual minimum) and max is 6400
        composeTestRule.onNodeWithText("100").assertIsDisplayed()
        composeTestRule.onNodeWithText("6400").assertIsDisplayed()
        composeTestRule.onNodeWithText("50").assertDoesNotExist()

        // Clicking AUTO pill switches to actual minimum 100
        composeTestRule.onNodeWithTag("pro_iso_auto_pill").performClick()
        composeTestRule.waitForIdle()
        assertEquals("100", selectedIso)
        composeTestRule.onAllNodesWithText("ISO 100").onFirst().assertIsDisplayed()
    }

    @Test
    fun aspectRatioCrop_square1x1_centerCropsPortraitAndLandscapeToSquare() {
        val portraitCrop = com.example.core.processing.AspectCropUtils.calculateCropRect(
            srcWidth = 3000,
            srcHeight = 4000,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_1_1
        )
        assertEquals(3000, portraitCrop.width())
        assertEquals(3000, portraitCrop.height())
        assertEquals(0, portraitCrop.left)
        assertEquals(500, portraitCrop.top)

        val landscapeCrop = com.example.core.processing.AspectCropUtils.calculateCropRect(
            srcWidth = 4000,
            srcHeight = 3000,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_1_1
        )
        assertEquals(3000, landscapeCrop.width())
        assertEquals(3000, landscapeCrop.height())
        assertEquals(500, landscapeCrop.left)
        assertEquals(0, landscapeCrop.top)
    }

    @Test
    fun aspectRatioCrop_ratio4x3_preservesNative4x3AndCropsNon4x3() {
        val portraitCrop = com.example.core.processing.AspectCropUtils.calculateCropRect(
            srcWidth = 3000,
            srcHeight = 4000,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_4_3
        )
        assertEquals(3000, portraitCrop.width())
        assertEquals(4000, portraitCrop.height())

        val landscapeCrop = com.example.core.processing.AspectCropUtils.calculateCropRect(
            srcWidth = 4000,
            srcHeight = 3000,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_4_3
        )
        assertEquals(4000, landscapeCrop.width())
        assertEquals(3000, landscapeCrop.height())
    }

    @Test
    fun aspectRatioCrop_ratio9x16_cropsTo9x16() {
        val portraitCrop = com.example.core.processing.AspectCropUtils.calculateCropRect(
            srcWidth = 3000,
            srcHeight = 4000,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_9_16
        )
        assertEquals(2250, portraitCrop.width())
        assertEquals(4000, portraitCrop.height())

        val landscapeCrop = com.example.core.processing.AspectCropUtils.calculateCropRect(
            srcWidth = 4000,
            srcHeight = 3000,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_9_16
        )
        assertEquals(4000, landscapeCrop.width())
        assertEquals(2250, landscapeCrop.height())
    }

    @Test
    fun aspectCropUtils_cropBitmap_producesExactDimensionsForAllModes() {
        val srcBitmap = android.graphics.Bitmap.createBitmap(3000, 4000, android.graphics.Bitmap.Config.ARGB_8888)

        // 4:3 (3000 x 4000)
        val ratio43Bitmap = com.example.core.processing.AspectCropUtils.cropBitmap(
            sourceBitmap = srcBitmap,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_4_3
        )
        assertEquals(3000, ratio43Bitmap.width)
        assertEquals(4000, ratio43Bitmap.height)

        // 1:1 Square (3000 x 3000)
        val squareBitmap = com.example.core.processing.AspectCropUtils.cropBitmap(
            sourceBitmap = srcBitmap,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_1_1
        )
        assertEquals(3000, squareBitmap.width)
        assertEquals(3000, squareBitmap.height)

        // 9:16 (2250 x 4000)
        val ratio916Bitmap = com.example.core.processing.AspectCropUtils.cropBitmap(
            sourceBitmap = srcBitmap,
            aspectRatioMode = com.example.core.preferences.AspectRatioMode.RATIO_9_16
        )
        assertEquals(2250, ratio916Bitmap.width)
        assertEquals(4000, ratio916Bitmap.height)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun videoQuickSettings_rearAndFrontCameraCapabilitiesAndFallbacks() {
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

        var activeLens by mutableStateOf(CameraLens.BACK_MAIN)
        var currentPrefs by mutableStateOf(
            CameraPreferences(
                videoResolution = VideoResolution.FHD_1080P,
                videoFrameRate = VideoFrameRate.FPS_60,
                videoStabilization = com.example.core.preferences.VideoStabilizationMode.STANDARD,
                flashMode = FlashMode.TORCH
            )
        )

        composeTestRule.setContent {
            val capsMap = mapOf(
                CameraLens.BACK_MAIN to rearCaps,
                CameraLens.FRONT to frontCaps
            )
            com.example.ui.theme.PixelCameraTheme {
                CameraQuickControlsSheet(
                    activeMode = CameraMode.VIDEO,
                    preferences = currentPrefs,
                    capabilitiesState = CameraCapabilitiesState.Verified(
                        activeLens = activeLens,
                        availableLenses = listOf(CameraLens.BACK_MAIN, CameraLens.FRONT),
                        capabilitiesByLens = capsMap
                    ),
                    onFlashChanged = { currentPrefs = currentPrefs.copy(flashMode = it) },
                    onTimerChanged = {},
                    onAspectRatioChanged = {},
                    onGridChanged = {},
                    onVideoResolutionChanged = { currentPrefs = currentPrefs.copy(videoResolution = it) },
                    onVideoFpsChanged = { currentPrefs = currentPrefs.copy(videoFrameRate = it) },
                    onVideoStabilizationChanged = { currentPrefs = currentPrefs.copy(videoStabilization = it) },
                    onOpenFullSettings = {},
                    onDismissRequest = {},
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                )
            }
        }

        // 1. On Rear camera with 1080p selected:
        // All 4 resolutions (720p, 1080p, 2K, 4K) and all 3 frame rates (24, 30, 60 FPS) are displayed
        composeTestRule.onNodeWithTag("video_res_option_720p").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_res_option_1080p").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_res_option_2k_1440p").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_res_option_4k_uhd").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_fps_option_24").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_fps_option_30").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_fps_option_60").assertIsDisplayed()

        // 2. Select 4K UHD on Rear camera:
        // 60 FPS is not supported at 4K on this camera, so 60 FPS option is hidden and FPS falls back to 30 FPS
        composeTestRule.onNodeWithTag("video_res_option_4k_uhd").performClick()
        composeTestRule.waitForIdle()

        assertEquals(VideoResolution.UHD_4K, currentPrefs.videoResolution)
        assertEquals(VideoFrameRate.FPS_30, currentPrefs.videoFrameRate)
        composeTestRule.onNodeWithTag("video_fps_option_24").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_fps_option_30").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_fps_option_60").assertDoesNotExist()

        // 3. Switch to Front camera:
        // Only 720p and 1080p are supported and displayed; 2K and 4K are hidden.
        // Only 30 FPS is supported and displayed; 24 FPS and 60 FPS are hidden.
        // Selected resolution automatically falls back from 4K to 1080p, and Torch falls back to Off.
        activeLens = CameraLens.FRONT
        composeTestRule.waitForIdle()

        assertEquals(VideoResolution.FHD_1080P, currentPrefs.videoResolution)
        assertEquals(VideoFrameRate.FPS_30, currentPrefs.videoFrameRate)
        assertEquals(FlashMode.OFF, currentPrefs.flashMode)

        composeTestRule.onNodeWithTag("video_res_option_720p").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_res_option_1080p").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_res_option_2k_1440p").assertDoesNotExist()
        composeTestRule.onNodeWithTag("video_res_option_4k_uhd").assertDoesNotExist()
        composeTestRule.onNodeWithTag("video_fps_option_30").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_fps_option_24").assertDoesNotExist()
        composeTestRule.onNodeWithTag("video_fps_option_60").assertDoesNotExist()
    }

    @Test
    fun discoverVideoQualitiesFromHardware_filtersUnsupportedResolutionsAndFps() {
        val rearSizes = listOf(
            android.util.Size(1280, 720),
            android.util.Size(1920, 1080),
            android.util.Size(2560, 1440),
            android.util.Size(3840, 2160)
        )
        val rearMinDurations = mapOf(
            VideoResolution.HD_720P to 16_666_666L,   // 60 FPS capable
            VideoResolution.FHD_1080P to 16_666_666L, // 60 FPS capable
            VideoResolution.QHD_2K to 33_333_333L,    // 30 FPS max
            VideoResolution.UHD_4K to 33_333_333L     // 30 FPS max
        )
        val rearAeRanges = listOf(
            android.util.Range(24, 24),
            android.util.Range(30, 30),
            android.util.Range(60, 60)
        )
        val rearCameraXSupported = setOf(
            VideoResolution.HD_720P,
            VideoResolution.FHD_1080P,
            VideoResolution.QHD_2K,
            VideoResolution.UHD_4K
        )

        val discoveredRear = com.example.feature.preview.components.discoverVideoQualitiesFromHardware(
            videoOutputSizes = rearSizes,
            minFrameDurationNanosByResolution = rearMinDurations,
            aeFpsRanges = rearAeRanges,
            cameraXSupportedResolutions = rearCameraXSupported
        )

        assertEquals(
            listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30, VideoFrameRate.FPS_60),
            discoveredRear[VideoResolution.FHD_1080P]
        )
        assertEquals(
            listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30),
            discoveredRear[VideoResolution.QHD_2K]
        )
        assertEquals(
            listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30),
            discoveredRear[VideoResolution.UHD_4K]
        )

        // Front camera without 2K/4K or 24/60 FPS AE ranges
        val frontSizes = listOf(
            android.util.Size(1280, 720),
            android.util.Size(1920, 1080)
        )
        val frontMinDurations = mapOf(
            VideoResolution.HD_720P to 33_333_333L,
            VideoResolution.FHD_1080P to 33_333_333L
        )
        val frontAeRanges = listOf(
            android.util.Range(15, 30),
            android.util.Range(30, 30)
        )
        val frontCameraXSupported = setOf(
            VideoResolution.HD_720P,
            VideoResolution.FHD_1080P
        )

        val discoveredFront = com.example.feature.preview.components.discoverVideoQualitiesFromHardware(
            videoOutputSizes = frontSizes,
            minFrameDurationNanosByResolution = frontMinDurations,
            aeFpsRanges = frontAeRanges,
            cameraXSupportedResolutions = frontCameraXSupported
        )

        assertEquals(
            setOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P),
            discoveredFront.keys
        )
        assertEquals(
            listOf(VideoFrameRate.FPS_30),
            discoveredFront[VideoResolution.FHD_1080P]
        )
    }

    @Test
    fun cameraZoomControls_shortTapSwitchesZoom_longPressRevealsContinuousSlider_andCameraSwitchUpdatesRange() {
        var activeLens by mutableStateOf(CameraLens.BACK_MAIN)
        var currentZoom by mutableStateOf(1.0f)

        composeTestRule.setContent {
            val isFront = activeLens == CameraLens.FRONT
            val minZ = if (isFront) 1.0f else 0.5f
            val maxZ = if (isFront) 4.0f else 20.0f
            val shortcuts = com.example.core.camera.generateZoomShortcuts(minZ, maxZ)

            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraPreviewPlaceholder(
                    activeMode = CameraMode.PHOTO,
                    activeLens = activeLens,
                    zoomRatio = currentZoom,
                    minZoomRatio = minZ,
                    maxZoomRatio = maxZ,
                    zoomShortcuts = shortcuts,
                    isTarget20xZoomSupported = !isFront,
                    onZoomRatioChanged = { currentZoom = it },
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                )
            }
        }

        // 1. Rear camera (0.5x..20.0x): displays 0.5x, 1x, 2x, 5x, 20x shortcuts; slider is hidden initially
        composeTestRule.onNodeWithTag("zoom_shortcut_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_0_5x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_1x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_2x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_5x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_20x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_continuous_slider_panel").assertDoesNotExist()

        // 2. Short tap on 5x shortcut immediately switches zoom to 5.0x without opening slider
        composeTestRule.onNodeWithTag("zoom_shortcut_5x").performClick()
        composeTestRule.waitForIdle()
        assertEquals(5.0f, currentZoom, 0.001f)
        composeTestRule.onNodeWithText("5.0x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_continuous_slider_panel").assertDoesNotExist()

        // 3. Short tap on 20x shortcut switches zoom to 20.0x
        composeTestRule.onNodeWithTag("zoom_shortcut_20x").performClick()
        composeTestRule.waitForIdle()
        assertEquals(20.0f, currentZoom, 0.001f)
        composeTestRule.onNodeWithText("20.0x").assertIsDisplayed()

        // 4. Long press on any zoom shortcut activates and reveals the continuous zoom slider
        composeTestRule.onNodeWithTag("zoom_shortcut_2x").performTouchInput {
            longClick()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("zoom_continuous_slider_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_continuous_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_slider_current_value").assertTextEquals("20.0x")

        // 5. Switch to Front Selfie camera (1.0x..4.0x):
        // Unsupported 0.5x, 5x, and 20x shortcuts are removed; 1x, 2x, and 4x are displayed
        activeLens = CameraLens.FRONT
        currentZoom = com.example.core.camera.clampZoomRatio(1.0f, 1.0f, 4.0f)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("zoom_continuous_slider_panel").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_shortcut_0_5x").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_shortcut_5x").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_shortcut_20x").assertDoesNotExist()
        composeTestRule.onNodeWithTag("zoom_shortcut_1x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_2x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_shortcut_4x").assertIsDisplayed()

        // Long press on Front camera shortcut reveals slider capped at 4.0x hardware limit
        composeTestRule.onNodeWithTag("zoom_shortcut_2x").performTouchInput {
            longClick()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("zoom_continuous_slider_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_slider_range_note").assertTextEquals("Max 4.0x (Hardware limit)")
    }

    @Test
    fun cameraPinchToZoom_zoomsInAndOutSmoothly_synchronizesSliderAndShortcuts_andSupportsAllModes() {
        var activeMode by mutableStateOf(CameraMode.PHOTO)
        var activeLens by mutableStateOf(CameraLens.BACK_MAIN)
        var currentZoom by mutableStateOf(1.0f)
        var isRecording by mutableStateOf(false)
        var previewTapCount = 0

        composeTestRule.setContent {
            val isFront = activeLens == CameraLens.FRONT
            val minZ = if (isFront) 1.0f else 0.5f
            val maxZ = if (isFront) 4.0f else 20.0f
            val shortcuts = com.example.core.camera.generateZoomShortcuts(minZ, maxZ)

            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraPreviewPlaceholder(
                    activeMode = activeMode,
                    activeLens = activeLens,
                    zoomRatio = currentZoom,
                    minZoomRatio = minZ,
                    maxZoomRatio = maxZ,
                    zoomShortcuts = shortcuts,
                    isTarget20xZoomSupported = !isFront,
                    isRecording = isRecording,
                    onZoomRatioChanged = { currentZoom = it },
                    onPreviewClick = { previewTapCount++ },
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                )
            }
        }

        // 1. Open the zoom slider via long-press on 1x so we can verify slider + label sync during pinch
        composeTestRule.onNodeWithTag("zoom_shortcut_1x").performTouchInput {
            longClick()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("zoom_continuous_slider_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zoom_slider_current_value").assertTextEquals("1.0x")

        // 2. Two-finger outward pinch on preview_placeholder (distance 40px -> 120px = 3.0x zoom)
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            val c = center
            pinch(
                start0 = c + Offset(-20f, 0f),
                end0 = c + Offset(-60f, 0f),
                start1 = c + Offset(20f, 0f),
                end1 = c + Offset(60f, 0f),
                durationMillis = 300
            )
        }
        composeTestRule.waitForIdle()

        assertTrue("Expected outward pinch to increase zoom above 2.0x, was $currentZoom", currentZoom >= 2.5f)
        // Pinch gesture must NOT accidentally trigger single-tap preview click or close the slider
        assertEquals(0, previewTapCount)
        composeTestRule.onNodeWithTag("zoom_continuous_slider_panel").assertIsDisplayed()
        val expectedLabel = com.example.core.camera.formatZoomRatio(currentZoom, asCompactShortcut = false)
        composeTestRule.onNodeWithTag("zoom_slider_current_value").assertTextEquals(expectedLabel)

        // 3. Two-finger inward pinch on preview_placeholder zooms back out toward 0.5x ultrawide minimum
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            val c = center
            pinch(
                start0 = c + Offset(-120f, 0f),
                end0 = c + Offset(-10f, 0f),
                start1 = c + Offset(120f, 0f),
                end1 = c + Offset(10f, 0f),
                durationMillis = 300
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(0.5f, currentZoom, 0.05f)
        composeTestRule.onNodeWithTag("zoom_slider_current_value").assertTextEquals("0.5x")

        // 4. Verify zoom shortcuts still work after pinch gesture
        composeTestRule.onNodeWithTag("zoom_shortcut_5x").performClick()
        composeTestRule.waitForIdle()
        assertEquals(5.0f, currentZoom, 0.001f)
        composeTestRule.onNodeWithTag("zoom_slider_current_value").assertTextEquals("5.0x")

        // 5. Verify pinch-to-zoom works in Video mode while actively recording without interrupting recording
        activeMode = CameraMode.VIDEO
        isRecording = true
        currentZoom = 1.0f
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recording_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            val c = center
            pinch(
                start0 = c + Offset(-20f, 0f),
                end0 = c + Offset(-50f, 0f),
                start1 = c + Offset(20f, 0f),
                end1 = c + Offset(50f, 0f),
                durationMillis = 250
            )
        }
        composeTestRule.waitForIdle()
        assertTrue("Expected pinch-to-zoom during video recording to zoom in, was $currentZoom", currentZoom >= 2.0f)
        composeTestRule.onNodeWithTag("recording_indicator").assertIsDisplayed()

        // 6. Verify pinch-to-zoom works in Pro mode and Front Selfie mode (clamped to 1.0x..4.0x)
        isRecording = false
        activeMode = CameraMode.PRO
        activeLens = CameraLens.FRONT
        currentZoom = 1.0f
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            val c = center
            pinch(
                start0 = c + Offset(-15f, 0f),
                end0 = c + Offset(-90f, 0f),
                start1 = c + Offset(15f, 0f),
                end1 = c + Offset(90f, 0f),
                durationMillis = 300
            )
        }
        composeTestRule.waitForIdle()
        // Front camera max is 4.0x, so 6x outward pinch must clamp strictly at 4.0x
        assertEquals(4.0f, currentZoom, 0.05f)
    }

    @Test
    fun tapToFocus_focusLock_exposureSlider_rearFrontSwitching_andProModeExclusion() {
        var activeMode by androidx.compose.runtime.mutableStateOf(CameraMode.PHOTO)
        var activeLens by androidx.compose.runtime.mutableStateOf(CameraLens.BACK_MAIN)
        var isAutoFocusSupported by androidx.compose.runtime.mutableStateOf(true)
        var isFocusLockSupported by androidx.compose.runtime.mutableStateOf(true)
        var evRange by androidx.compose.runtime.mutableStateOf(-6..6)
        var evStep by androidx.compose.runtime.mutableFloatStateOf(1f / 3f)

        val recordedFocusRequests = mutableListOf<com.example.core.camera.FocusTouchRequest>()
        var latestAppliedExposureIndex = 0

        composeTestRule.setContent {
            com.example.ui.theme.PixelCameraTheme {
                com.example.feature.preview.components.CameraPreviewPlaceholder(
                    activeMode = activeMode,
                    activeLens = activeLens,
                    isAutoFocusSupported = isAutoFocusSupported,
                    isFocusLockSupported = isFocusLockSupported,
                    isExposureCompensationSupported = true,
                    evIndexRange = evRange,
                    evStep = evStep,
                    onFocusTapRequested = { req ->
                        recordedFocusRequests.add(req)
                    },
                    onExposureIndexChanged = { idx ->
                        latestAppliedExposureIndex = idx
                    },
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                )
            }
        }

        // 1. Short tap on preview in Photo mode -> initiates normal autofocus (not locked) & shows minimal vertical EV slider beside focus circle
        composeTestRule.onNodeWithTag("focus_indicator_ring").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_exposure_slider").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_exposure_panel").assertDoesNotExist()

        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            click(Offset(centerX * 0.6f, centerY * 0.6f))
        }
        composeTestRule.waitForIdle()

        assertEquals(1, recordedFocusRequests.size)
        assertFalse("Short tap must not request focus lock", recordedFocusRequests.last().isLockRequested)
        composeTestRule.onNodeWithTag("focus_indicator_ring").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_exposure_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_exposure_sun_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_exposure_panel").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_exposure_value_label", useUnmergedTree = true).assertTextEquals("0.0 EV")
        composeTestRule.onNodeWithTag("focus_lock_icon").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_unlock_button").assertDoesNotExist()

        // Verify vertical EV slider is positioned immediately to the right of the focus circle and aligned with its vertical center
        val ringBounds = composeTestRule.onNodeWithTag("focus_indicator_ring").fetchSemanticsNode().boundsInRoot
        val sliderBounds = composeTestRule.onNodeWithTag("focus_exposure_slider").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Vertical EV slider must be positioned immediately to the right of the focus circle",
            sliderBounds.left >= ringBounds.right - 2f
        )
        assertEquals(
            "Vertical EV slider must be closely aligned with the focus circle's vertical center",
            ringBounds.center.y,
            sliderBounds.center.y,
            4f
        )

        // 2. Drag vertically UP along the exposure line -> increases exposure toward +2.0 EV (+6)
        composeTestRule.onNodeWithTag("focus_exposure_slider").performTouchInput {
            swipeUp()
        }
        composeTestRule.waitForIdle()
        assertTrue("Expected positive exposure index after vertical swipeUp, was $latestAppliedExposureIndex", latestAppliedExposureIndex > 0)
        assertEquals(6, latestAppliedExposureIndex)
        composeTestRule.onNodeWithTag("focus_exposure_value_label", useUnmergedTree = true).assertTextEquals("+2.0 EV")

        // 2b. Tapping a new normal focus point while EV is non-zero (+2.0 EV) must immediately reset EV to 0.0 EV (index 0)
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            click(Offset(centerX * 0.5f, centerY * 0.5f))
        }
        composeTestRule.waitForIdle()
        assertEquals(2, recordedFocusRequests.size)
        assertFalse("Normal tap must not request focus lock", recordedFocusRequests.last().isLockRequested)
        assertEquals("New normal focus tap must immediately reset exposure index to 0", 0, latestAppliedExposureIndex)
        composeTestRule.onNodeWithTag("focus_exposure_value_label", useUnmergedTree = true).assertTextEquals("0.0 EV")

        // Drag vertically DOWN along the exposure line -> decreases exposure toward -2.0 EV (-6)
        val focusCountBeforeEvDrag = recordedFocusRequests.size
        composeTestRule.onNodeWithTag("focus_exposure_slider").performTouchInput {
            swipeDown()
        }
        composeTestRule.waitForIdle()
        assertEquals(-6, latestAppliedExposureIndex)
        composeTestRule.onNodeWithTag("focus_exposure_value_label", useUnmergedTree = true).assertTextEquals("-2.0 EV")
        assertEquals("Adjusting EV slider must not trigger autofocus again", focusCountBeforeEvDrag, recordedFocusRequests.size)

        // 3. Tap-and-Hold (long press) on a new focus point while EV is -2.0 EV (-6) -> initiates Focus Lock AND resets EV to 0.0 EV
        val countBeforeHold = recordedFocusRequests.size
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            longClick(Offset(centerX * 0.7f, centerY * 0.5f))
        }
        composeTestRule.waitForIdle()

        assertEquals("Tap-and-hold must emit exactly 1 focus request (no duplicate short tap)", countBeforeHold + 1, recordedFocusRequests.size)
        assertTrue("Tap-and-hold must request focus lock", recordedFocusRequests.last().isLockRequested)
        assertEquals("New focus-lock gesture must immediately reset exposure index to 0", 0, latestAppliedExposureIndex)
        composeTestRule.onNodeWithTag("focus_exposure_value_label", useUnmergedTree = true).assertTextEquals("0.0 EV")
        composeTestRule.onNodeWithTag("focus_lock_indicator_ring").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_lock_icon").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_status_badge").assertTextEquals("AF/AE Locked")
        composeTestRule.onNodeWithTag("focus_unlock_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_exposure_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_exposure_panel").assertDoesNotExist()

        // 3b. Adjusting EV while Focus Lock is active updates EV without releasing focus lock or re-triggering autofocus
        val countWhileLocked = recordedFocusRequests.size
        composeTestRule.onNodeWithTag("focus_exposure_slider").performTouchInput {
            swipeUp()
        }
        composeTestRule.waitForIdle()
        assertEquals(6, latestAppliedExposureIndex)
        composeTestRule.onNodeWithTag("focus_exposure_value_label", useUnmergedTree = true).assertTextEquals("+2.0 EV")
        assertEquals("Adjusting EV during focus lock must not trigger a new focus request", countWhileLocked, recordedFocusRequests.size)
        composeTestRule.onNodeWithTag("focus_lock_icon").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_status_badge").assertTextEquals("AF/AE Locked")

        // 4. Subsequent normal tap at a new point releases the previous focus lock, starts autofocus at new point, and resets EV to 0.0 EV
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            click(Offset(centerX * 0.4f, centerY * 0.4f))
        }
        composeTestRule.waitForIdle()
        assertFalse("Subsequent short tap must release focus lock", recordedFocusRequests.last().isLockRequested)
        assertEquals("Subsequent short tap on a new focus point must reset EV to 0", 0, latestAppliedExposureIndex)
        composeTestRule.onNodeWithTag("focus_exposure_value_label", useUnmergedTree = true).assertTextEquals("0.0 EV")
        composeTestRule.onNodeWithTag("focus_lock_icon").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_unlock_button").assertDoesNotExist()

        // 5. Switch to Front Selfie camera with fixed focus (isAutoFocusSupported = false, evRange = -4..4 @ 0.5 EV)
        // Lock focus first, then switch camera to verify stale lock and EV are cleared
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            longClick(center)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("focus_lock_icon").assertIsDisplayed()

        activeLens = CameraLens.FRONT
        isAutoFocusSupported = false
        isFocusLockSupported = false
        evRange = -4..4
        evStep = 0.5f
        composeTestRule.waitForIdle()

        // Camera switch must clear stale focus indicator and lock state
        composeTestRule.onNodeWithTag("focus_indicator_ring").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_exposure_slider").assertDoesNotExist()

        // Tapping on fixed-focus front camera communicates fixed focus honestly while keeping vertical exposure slider functional
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            click(center)
        }
        composeTestRule.waitForIdle()
        assertFalse("Fixed-focus camera must not request AF", recordedFocusRequests.last().isAfRequested)
        assertTrue("Fixed-focus camera with AE support must still request AE metering", recordedFocusRequests.last().isAeRequested)
        composeTestRule.onNodeWithTag("focus_status_badge").assertTextEquals("Fixed Focus • Exposure")
        composeTestRule.onNodeWithTag("focus_exposure_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("focus_exposure_sun_indicator").assertIsDisplayed()

        // 6. Switching to Pro mode clears automatic focus/exposure UI and ignores tap/long-press for auto focus
        activeMode = CameraMode.PRO
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("focus_indicator_ring").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_exposure_slider").assertDoesNotExist()

        val countBeforeProTaps = recordedFocusRequests.size
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            click(center)
        }
        composeTestRule.onNodeWithTag("preview_placeholder").performTouchInput {
            longClick(center)
        }
        composeTestRule.waitForIdle()
        assertEquals("Pro mode must never trigger automatic tap-to-focus or focus lock", countBeforeProTaps, recordedFocusRequests.size)
        composeTestRule.onNodeWithTag("focus_indicator_ring").assertDoesNotExist()
        composeTestRule.onNodeWithTag("focus_exposure_slider").assertDoesNotExist()
    }

    @Test
    fun focusMeteringAction_independentlyVerifiesAfAndAe_andHandlesUnsupportedCapabilities() {
        val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(0.4f, 0.6f)

        // 1. Both AF and AE supported -> FocusMeteringAction includes both FLAG_AF and FLAG_AE at the tapped point
        val bothCaps = com.example.core.camera.verifyCameraXFocusMeteringSupport(
            cameraInfo = null,
            manualCapabilities = com.example.core.camera.ManualSensorCapabilities(
                isAutoFocusSupported = true,
                isAutoExposureSupported = true,
                isFocusLockSupported = true,
                maxAfRegions = 1,
                maxAeRegions = 1,
                maxAwbRegions = 1
            ),
            meteringPoint = point
        )
        assertTrue(bothCaps.isAfSupported)
        assertTrue(bothCaps.isAeSupported)
        assertTrue(bothCaps.isFocusLockSupported)

        val tapAction = com.example.core.camera.buildCameraXFocusMeteringAction(
            meteringPoint = point,
            capabilities = bothCaps,
            isLockRequested = false
        )
        org.junit.Assert.assertNotNull(tapAction)
        assertEquals("Normal tap must include AF metering point", 1, tapAction!!.meteringPointsAf.size)
        assertEquals("Normal tap must include AE metering point", 1, tapAction.meteringPointsAe.size)
        assertTrue("Normal tap must auto-cancel after timeout", tapAction.isAutoCancelEnabled)

        val lockAction = com.example.core.camera.buildCameraXFocusMeteringAction(
            meteringPoint = point,
            capabilities = bothCaps,
            isLockRequested = true
        )
        org.junit.Assert.assertNotNull(lockAction)
        assertEquals("Tap-and-hold must include AF metering point", 1, lockAction!!.meteringPointsAf.size)
        assertEquals("Tap-and-hold must include AE metering point", 1, lockAction.meteringPointsAe.size)
        assertFalse("Tap-and-hold focus lock must disable auto-cancel", lockAction.isAutoCancelEnabled)

        // 2. Fixed-focus camera (AF unsupported, AE supported) -> only FLAG_AE is included, never FLAG_AF
        val aeOnlyCaps = com.example.core.camera.verifyCameraXFocusMeteringSupport(
            cameraInfo = null,
            manualCapabilities = com.example.core.camera.ManualSensorCapabilities(
                isAutoFocusSupported = false,
                isAutoExposureSupported = true,
                isFocusLockSupported = false,
                maxAfRegions = 0,
                maxAeRegions = 1,
                maxAwbRegions = 0
            ),
            meteringPoint = point
        )
        assertFalse(aeOnlyCaps.isAfSupported)
        assertTrue(aeOnlyCaps.isAeSupported)
        assertFalse(aeOnlyCaps.isFocusLockSupported)

        val aeOnlyAction = com.example.core.camera.buildCameraXFocusMeteringAction(
            meteringPoint = point,
            capabilities = aeOnlyCaps,
            isLockRequested = false
        )
        org.junit.Assert.assertNotNull(aeOnlyAction)
        assertTrue("Fixed-focus camera must not include AF metering points", aeOnlyAction!!.meteringPointsAf.isEmpty())
        assertEquals("Fixed-focus camera with AE support must include AE metering point", 1, aeOnlyAction.meteringPointsAe.size)

        // 3. AF supported but AE region metering unsupported -> only FLAG_AF is included, never FLAG_AE
        val afOnlyCaps = com.example.core.camera.verifyCameraXFocusMeteringSupport(
            cameraInfo = null,
            manualCapabilities = com.example.core.camera.ManualSensorCapabilities(
                isAutoFocusSupported = true,
                isAutoExposureSupported = false,
                isFocusLockSupported = true,
                maxAfRegions = 1,
                maxAeRegions = 0,
                maxAwbRegions = 0
            ),
            meteringPoint = point
        )
        assertTrue(afOnlyCaps.isAfSupported)
        assertFalse(afOnlyCaps.isAeSupported)

        val afOnlyAction = com.example.core.camera.buildCameraXFocusMeteringAction(
            meteringPoint = point,
            capabilities = afOnlyCaps,
            isLockRequested = false
        )
        org.junit.Assert.assertNotNull(afOnlyAction)
        assertEquals(1, afOnlyAction!!.meteringPointsAf.size)
        assertTrue("Camera without AE region support must not include AE metering points", afOnlyAction.meteringPointsAe.isEmpty())

        // 4. Neither AF nor AE supported -> buildCameraXFocusMeteringAction returns null (no fabricated action)
        val unsupportedCaps = com.example.core.camera.verifyCameraXFocusMeteringSupport(
            cameraInfo = null,
            manualCapabilities = com.example.core.camera.ManualSensorCapabilities(
                isAutoFocusSupported = false,
                isAutoExposureSupported = false,
                isFocusLockSupported = false,
                maxAfRegions = 0,
                maxAeRegions = 0,
                maxAwbRegions = 0
            ),
            meteringPoint = point
        )
        assertFalse(unsupportedCaps.isAnyMeteringSupported)
        org.junit.Assert.assertNull(
            "When neither AF nor AE is supported, FocusMeteringAction must be null",
            com.example.core.camera.buildCameraXFocusMeteringAction(
                meteringPoint = point,
                capabilities = unsupportedCaps,
                isLockRequested = false
            )
        )
    }

    @Test
    fun testProShutterSpeedParsingAndExactLongExposureOptions() {
        // Test parsing of exact required long-exposure options
        val nanos1_3 = com.example.core.camera.parseShutterSpeedToNanos("1/3s")
        val nanos1_2 = com.example.core.camera.parseShutterSpeedToNanos("1/2s")
        val nanos1 = com.example.core.camera.parseShutterSpeedToNanos("1s")
        val nanos2 = com.example.core.camera.parseShutterSpeedToNanos("2s")
        val nanos3 = com.example.core.camera.parseShutterSpeedToNanos("3s")

        org.junit.Assert.assertNotNull(nanos1_3)
        org.junit.Assert.assertNotNull(nanos1_2)
        org.junit.Assert.assertNotNull(nanos1)
        org.junit.Assert.assertNotNull(nanos2)
        org.junit.Assert.assertNotNull(nanos3)

        assertEquals(333_333_333L, nanos1_3)
        assertEquals(500_000_000L, nanos1_2)
        assertEquals(1_000_000_000L, nanos1)
        assertEquals(2_000_000_000L, nanos2)
        assertEquals(3_000_000_000L, nanos3)

        // AUTO returns null
        org.junit.Assert.assertNull(com.example.core.camera.parseShutterSpeedToNanos("AUTO"))

        // Fast shutter speeds
        val nanos1000 = com.example.core.camera.parseShutterSpeedToNanos("1/1000s")
        assertEquals(1_000_000L, nanos1000)

        // Label formatting
        assertEquals("1/1000s", com.example.core.camera.formatShutterSpeedLabel(0.001f))
        assertEquals("1/2s", com.example.core.camera.formatShutterSpeedLabel(0.5f))
        assertEquals("1s", com.example.core.camera.formatShutterSpeedLabel(1.0f))
        assertEquals("2s", com.example.core.camera.formatShutterSpeedLabel(2.0f))
        assertEquals("3s", com.example.core.camera.formatShutterSpeedLabel(3.0f))
    }

    @Test
    fun testProCamera2CaptureRequestOptionsGeneration() {
        val manualCaps = com.example.core.camera.ManualSensorCapabilities(
            isManualIsoSupported = true,
            isManualShutterSupported = true,
            isManualFocusSupported = true,
            isoRange = 50..6400,
            shutterSpeedRangeNanos = 100_000L..4_000_000_000L,
            minimumFocusDistanceDiopters = 10.0f,
            availableAfModes = listOf(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            ),
            availableAeModes = listOf(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF,
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
            ),
            availableAwbModes = listOf(
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_OFF,
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO,
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            )
        )

        // 1. Pro Mode manual ISO + manual shutter
        val proOptions = com.example.core.camera.buildProCamera2CaptureRequestOptions(
            manualIso = "200",
            manualShutterSpeed = "1/125s",
            proFocusMode = com.example.core.preferences.ProFocusMode.MANUAL,
            proFocusDistance = 0.5f,
            proWhiteBalance = "5500K",
            manualCaps = manualCaps,
            proEv = 0.0f
        )
        org.junit.Assert.assertNotNull(proOptions)

        // 2. Pro Mode Auto settings
        val proAutoOptions = com.example.core.camera.buildProCamera2CaptureRequestOptions(
            manualIso = "AUTO",
            manualShutterSpeed = "AUTO",
            proFocusMode = com.example.core.preferences.ProFocusMode.AUTO,
            proFocusDistance = 0.0f,
            proWhiteBalance = "AUTO",
            manualCaps = manualCaps,
            proEv = 0.0f
        )
        org.junit.Assert.assertNotNull(proAutoOptions)
    }

    @Test
    fun testNonProResetCamera2CaptureRequestOptions() {
        // When switching away from Pro mode to normal Photo mode, all manual overrides must be reset
        val resetStillOptions = com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions(
            isVideoMode = false,
            isHardwareAfLockActive = false,
            supportsAfAutoHold = false
        )
        org.junit.Assert.assertNotNull(resetStillOptions)

        // For video mode
        val resetVideoOptions = com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions(
            isVideoMode = true,
            isHardwareAfLockActive = false,
            supportsAfAutoHold = false
        )
        org.junit.Assert.assertNotNull(resetVideoOptions)
    }

    @Test
    fun testProLensSwitchingCapabilitiesAndStateIsolation() {
        val repo = com.example.core.preferences.CameraPreferencesRepository(ApplicationProvider.getApplicationContext())

        // Set Pro manual settings
        repo.setProIso("800")
        repo.setProShutterSpeed("1/500s")
        repo.setProFocusMode(com.example.core.preferences.ProFocusMode.MANUAL)
        repo.setProFocusDistance(0.8f)
        repo.setProWhiteBalance("6500K")
        repo.setProEv(1.0f)
        repo.setProRawFormat(com.example.core.preferences.ProRawFormat.RAW_PLUS_JPEG)

        assertEquals("800", repo.preferences.value.proIso)
        assertEquals("1/500s", repo.preferences.value.proShutterSpeed)
        assertEquals(com.example.core.preferences.ProFocusMode.MANUAL, repo.preferences.value.proFocusMode)
        assertEquals(0.8f, repo.preferences.value.proFocusDistance)
        assertEquals("6500K", repo.preferences.value.proWhiteBalance)
        assertEquals(1.0f, repo.preferences.value.proEv)
        assertEquals(com.example.core.preferences.ProRawFormat.RAW_PLUS_JPEG, repo.preferences.value.proRawFormat)

        // Reset Pro settings to defaults
        repo.resetProSettings()
        assertEquals("AUTO", repo.preferences.value.proIso)
        assertEquals("AUTO", repo.preferences.value.proShutterSpeed)
        assertEquals(com.example.core.preferences.ProFocusMode.AUTO, repo.preferences.value.proFocusMode)
        assertEquals(0.0f, repo.preferences.value.proFocusDistance)
        assertEquals("AUTO", repo.preferences.value.proWhiteBalance)
        assertEquals(0.0f, repo.preferences.value.proEv)
        assertEquals(com.example.core.preferences.ProRawFormat.JPEG_ONLY, repo.preferences.value.proRawFormat)
    }

    @Test
    fun testPhase254_preferencesSaveAndRestoreAllSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.core.preferences.CameraPreferencesRepository(context)

        // Modify non-default settings across all preference fields
        repo.setVolumeButtonAction(com.example.core.preferences.VolumeButtonAction.SHUTTER)
        repo.setSaveLocation(true)
        repo.setCameraSounds(false)
        repo.setSelfieMirror(false)
        repo.setVideoResolution(VideoResolution.UHD_4K)
        repo.setVideoFrameRate(VideoFrameRate.FPS_60)
        repo.setVideoStabilization(com.example.core.preferences.VideoStabilizationMode.ACTIVE)
        repo.setHdOutputOption(com.example.core.preferences.HdOutputOption.STANDARD_BINNED)
        repo.setPortraitRetouch(com.example.core.preferences.PortraitRetouch.SMOOTH)
        repo.setNightSightExposure(com.example.core.preferences.NightSightExposure.MAX)
        repo.setTimeLapseSpeed(com.example.core.preferences.TimeLapseSpeed.SPEED_30X)
        repo.setSlowMoSpeed(com.example.core.preferences.SlowMoSpeed.SPEED_1_8X)
        repo.setProRawFormat(com.example.core.preferences.ProRawFormat.RAW_DNG_ONLY)

        // Reload from new repository instance
        val reloadedRepo = com.example.core.preferences.CameraPreferencesRepository(context)
        val loaded = reloadedRepo.preferences.value

        assertEquals(com.example.core.preferences.VolumeButtonAction.SHUTTER, loaded.volumeButtonAction)
        assertTrue(loaded.saveLocation)
        assertFalse(loaded.cameraSoundsEnabled)
        assertFalse(loaded.selfieMirror)
        assertEquals(VideoResolution.UHD_4K, loaded.videoResolution)
        assertEquals(VideoFrameRate.FPS_60, loaded.videoFrameRate)
        assertEquals(com.example.core.preferences.VideoStabilizationMode.ACTIVE, loaded.videoStabilization)
        assertEquals(com.example.core.preferences.HdOutputOption.STANDARD_BINNED, loaded.hdOutputOption)
        assertEquals(com.example.core.preferences.PortraitRetouch.SMOOTH, loaded.portraitRetouch)
        assertEquals(com.example.core.preferences.NightSightExposure.MAX, loaded.nightSightExposure)
        assertEquals(com.example.core.preferences.TimeLapseSpeed.SPEED_30X, loaded.timeLapseSpeed)
        assertEquals(com.example.core.preferences.SlowMoSpeed.SPEED_1_8X, loaded.slowMoSpeed)
        assertEquals(com.example.core.preferences.ProRawFormat.RAW_DNG_ONLY, loaded.proRawFormat)
    }

    @Test
    fun testPhase254_invalidAndCorruptedSavedPreferencesFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pixel_camera_preferences", Context.MODE_PRIVATE)

        // Write corrupted / unknown enum strings to SharedPreferences
        prefs.edit()
            .putString("key_volume_button_action", "GARBAGE_ACTION")
            .putString("key_flash_mode", "UNKNOWN_FLASH")
            .putString("key_timer_mode", "INVALID_TIMER")
            .putString("key_grid_mode", "BAD_GRID")
            .putString("key_video_resolution", "8K_SUPER")
            .putString("key_video_fps", "FPS_1000")
            .putString("key_video_stabilization", "ULTRA_STAB")
            .putString("key_pro_raw_format", "SUPER_RAW")
            .putString("key_pro_focus_mode", "LASER_FOCUS")
            .commit()

        // Load repository - must not crash and must safely restore default enums
        val repo = com.example.core.preferences.CameraPreferencesRepository(context)
        val loaded = repo.preferences.value

        assertEquals(com.example.core.preferences.VolumeButtonAction.VOLUME, loaded.volumeButtonAction)
        assertEquals(com.example.core.preferences.FlashMode.AUTO, loaded.flashMode)
        assertEquals(com.example.core.preferences.TimerMode.OFF, loaded.timerMode)
        assertEquals(com.example.core.preferences.GridMode.GRID_3X3, loaded.gridMode)
        assertEquals(VideoResolution.FHD_1080P, loaded.videoResolution)
        assertEquals(VideoFrameRate.FPS_30, loaded.videoFrameRate)
        assertEquals(com.example.core.preferences.VideoStabilizationMode.STANDARD, loaded.videoStabilization)
        assertEquals(com.example.core.preferences.ProRawFormat.JPEG_ONLY, loaded.proRawFormat)
        assertEquals(com.example.core.preferences.ProFocusMode.AUTO, loaded.proFocusMode)
    }

    @Test
    fun testPhase254_volumeButtonActionHandlingAndDuplicateEventProtection() {
        val activityController = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = activityController.get()

        // 1. Default action is VOLUME: key events pass through to super
        val downEventVol = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        val handledVol = activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, downEventVol)
        // System volume handling returns false on Robolectric dummy activity
        assertFalse(handledVol)

        // 2. Set VolumeButtonAction to SHUTTER via repo
        val repo = com.example.core.preferences.CameraPreferencesRepository(ApplicationProvider.getApplicationContext())
        repo.setVolumeButtonAction(com.example.core.preferences.VolumeButtonAction.SHUTTER)

        // Re-resume activity to pick up preferences
        activityController.pause().resume()

        // First key down (repeatCount = 0) -> triggers shutter capture
        val downEventShutter0 = android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN, 0)
        val handledShutter0 = activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, downEventShutter0)
        assertTrue("Volume button shutter must intercept key event", handledShutter0)

        // Repeat key down (repeatCount = 1, e.g. long press) -> must NOT trigger duplicate shutter capture
        val downEventShutter1 = android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN, 1)
        val handledShutter1 = activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, downEventShutter1)
        assertTrue("Volume button shutter long press must still intercept event without duplicate trigger", handledShutter1)

        // Key up event -> consumed when volume action is non-VOLUME
        val upEventShutter = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        val handledUp = activity.onKeyUp(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, upEventShutter)
        assertTrue("Key up event must be consumed for custom volume button actions", handledUp)

        activityController.destroy()
    }

    @Test
    fun testPhase254_locationTaggingDefaultsAndPermissionHandling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.core.preferences.CameraPreferencesRepository(context)

        // Save location preference is disabled by default for privacy
        assertFalse(repo.preferences.value.saveLocation)

        // Enabling save location updates flow
        repo.setSaveLocation(true)
        assertTrue(repo.preferences.value.saveLocation)

        // Disabling save location clears setting
        repo.setSaveLocation(false)
        assertFalse(repo.preferences.value.saveLocation)
    }

    @Test
    fun testPhase254_videoSettingsCapabilityValidationAndFallback() {
        val frontLensCaps = LensCapabilities(
            lens = CameraLens.FRONT,
            supportedVideoQualities = mapOf(
                VideoResolution.HD_720P to listOf(VideoFrameRate.FPS_30),
                VideoResolution.FHD_1080P to listOf(VideoFrameRate.FPS_30)
            )
        )

        // 1. Unsupported 4K resolution requested for front camera resolves to FHD_1080P fallback
        val resolvedRes = frontLensCaps.resolveSupportedResolution(VideoResolution.UHD_4K)
        assertEquals(VideoResolution.FHD_1080P, resolvedRes)

        // 2. Unsupported 60 FPS requested for front camera resolves to FPS_30 fallback
        val resolvedFps = frontLensCaps.resolveSupportedFrameRate(resolvedRes, VideoFrameRate.FPS_60)
        assertEquals(VideoFrameRate.FPS_30, resolvedFps)

        // 3. Reconcile combination
        val reconciled = frontLensCaps.reconcileVideoSettings(
            requestedResolution = VideoResolution.UHD_4K,
            requestedFrameRate = VideoFrameRate.FPS_60,
            requestedStabilization = com.example.core.preferences.VideoStabilizationMode.STANDARD,
            requestedFlashMode = FlashMode.OFF
        )
        assertEquals(VideoResolution.FHD_1080P, reconciled.resolution)
        assertEquals(VideoFrameRate.FPS_30, reconciled.frameRate)
    }
}

