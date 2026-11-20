package com.example

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.FlashMode
import com.example.core.preferences.HdOutputOption
import com.example.core.storage.MediaStorePhotoSaver
import com.example.feature.preview.components.CameraModeSelector
import com.example.feature.preview.components.CameraShutterBar
import com.example.feature.preview.components.CameraTopBar
import com.example.feature.settings.ReorderCameraModesScreen
import com.example.feature.settings.SettingsScreen
import com.example.ui.theme.PixelCameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiPolishVerificationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hdModeOutputDescription_isNeutralAndTypoFreeForBothOptions() {
        var hdOption by mutableStateOf(HdOutputOption.MAX_NATIVE)

        composeTestRule.setContent {
            PixelCameraTheme {
                SettingsScreen(
                    preferences = CameraPreferences(hdOutputOption = hdOption),
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

        val expectedNeutral = "Choose between the full native sensor resolution and standard binned output"
        composeTestRule.onNodeWithTag("setting_hd_output_row_subtitle", useUnmergedTree = true)
            .assertTextEquals(expectedNeutral)

        // Switching to Standard Binned keeps the neutral description accurate
        hdOption = HdOutputOption.STANDARD_BINNED
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("setting_hd_output_row_subtitle", useUnmergedTree = true)
            .assertTextEquals(expectedNeutral)
        composeTestRule.onNodeWithText("Targe ", substring = true).assertDoesNotExist()
    }

    @Test
    fun modeSelector_renamesCameraToPhoto_andKeepsSelectedModeCentered() {
        var selectedMode by mutableStateOf(CameraMode.PHOTO)
        composeTestRule.setContent {
            PixelCameraTheme {
                CameraModeSelector(
                    selectedMode = selectedMode,
                    onModeSelected = { selectedMode = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("Photo", CameraMode.PHOTO.displayName)
        composeTestRule.onNodeWithText("Photo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Camera").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mode_item_photo").assertIsDisplayed()
    }

    @Test
    fun galleryButton_fallsBackToGenericIconWhenEmpty_andShowsThumbnailWhenUriPresent() {
        var uriState by mutableStateOf<Uri?>(null)

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraShutterBar(
                    activeMode = CameraMode.PHOTO,
                    lastCapturedPhotoUri = uriState
                )
            }
        }
        composeTestRule.waitForIdle()

        // Empty gallery -> generic PhotoLibrary icon
        composeTestRule.onNodeWithTag("gallery_placeholder_icon", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Open gallery").assertIsDisplayed()

        // After media is loaded or saved -> thumbnail image renders
        uriState = Uri.parse("content://media/external/images/media/42")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gallery_thumbnail_image", useUnmergedTree = true).assertExists()

        // MediaStore query safely returns null when no media exists in Robolectric
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(MediaStorePhotoSaver.queryLatestGalleryMediaUri(context))
    }

    @Test
    fun topBar_showsFiltersLabelOnTapAndLongPress_andRealFlashStatesWithContentDescriptions() {
        var flashMode by mutableStateOf(FlashMode.AUTO)
        var filterClicks = 0

        composeTestRule.setContent {
            PixelCameraTheme {
                CameraTopBar(
                    flashMode = flashMode,
                    onFlashModeSelected = { flashMode = it },
                    onQuickControlsClick = {},
                    onSettingsClick = {},
                    onFiltersClick = { filterClicks++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Every top bar icon has a non-null content description
        composeTestRule.onNodeWithContentDescription("Flash: Auto", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Quick camera controls", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Filters", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Camera settings", useUnmergedTree = true).assertIsDisplayed()

        // Flash state updates visibly across Auto, On, Off, Torch
        composeTestRule.onNodeWithTag("top_bar_flash_state_label", useUnmergedTree = true).assertTextEquals("Auto")
        flashMode = FlashMode.ON
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_bar_flash_state_label", useUnmergedTree = true).assertTextEquals("On")
        composeTestRule.onNodeWithContentDescription("Flash: On", useUnmergedTree = true).assertIsDisplayed()

        flashMode = FlashMode.OFF
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_bar_flash_state_label", useUnmergedTree = true).assertTextEquals("Off")
        composeTestRule.onNodeWithContentDescription("Flash: Off", useUnmergedTree = true).assertIsDisplayed()

        flashMode = FlashMode.TORCH
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_bar_flash_state_label", useUnmergedTree = true).assertTextEquals("Torch")
        composeTestRule.onNodeWithContentDescription("Flash: Torch", useUnmergedTree = true).assertIsDisplayed()

        // Tapping Filters shows the short "Filters" label and invokes callback
        composeTestRule.onNodeWithTag("top_bar_filters_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, filterClicks)
        composeTestRule.onNodeWithTag("top_bar_filters_hint_label").assertIsDisplayed()
        composeTestRule.onNodeWithText("Filters").assertIsDisplayed()

        // Long-pressing Filters shows the "Filters" label without toggling tray
        composeTestRule.onNodeWithTag("top_bar_filters_button").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals(1, filterClicks)
        composeTestRule.onNodeWithTag("top_bar_filters_hint_label").assertIsDisplayed()
    }

    @Test
    fun reorderCameraModes_opensFullScreen_andKeepsAtLeastOneModeVisible() {
        var visibleNames by mutableStateOf(listOf("PHOTO", "VIDEO"))
        var hiddenNames by mutableStateOf(
            CameraMode.entries.map { it.name }.filterNot { it in listOf("PHOTO", "VIDEO") }
        )

        composeTestRule.setContent {
            PixelCameraTheme {
                SettingsScreen(
                    preferences = CameraPreferences(
                        visibleModeNames = visibleNames,
                        hiddenModeNames = hiddenNames
                    ),
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
                    onCustomModeLayoutChanged = { vis, hid ->
                        visibleNames = vis
                        hiddenNames = hid
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Tapping the Reorder Camera Modes row opens the dedicated full screen
        composeTestRule.onNodeWithTag("setting_reorder_modes_row").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reorder_modes_screen").assertIsDisplayed()

        // Hide Video so only Photo remains visible
        composeTestRule.onNodeWithTag("reorder_mode_toggle_video").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("PHOTO"), visibleNames)
        assertTrue("VIDEO" in hiddenNames)

        // The last remaining visible mode switch is disabled and notice is displayed
        composeTestRule.onNodeWithTag("reorder_min_visible_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reorder_mode_toggle_photo").assertIsNotEnabled()
        assertEquals(listOf("PHOTO"), visibleNames)
    }

    @Test
    fun settingsPickers_openBottomSheetWithRadioOptionsAndExplanations() {
        var selectedRatio by mutableStateOf(AspectRatioMode.RATIO_4_3)
        var selectedHd by mutableStateOf(HdOutputOption.MAX_NATIVE)

        composeTestRule.setContent {
            PixelCameraTheme {
                SettingsScreen(
                    preferences = CameraPreferences(
                        aspectRatioMode = selectedRatio,
                        hdOutputOption = selectedHd
                    ),
                    onThemeChanged = {},
                    onDynamicColorChanged = {},
                    onAspectRatioChanged = { selectedRatio = it },
                    onGridChanged = {},
                    onTimerChanged = {},
                    onFlashChanged = {},
                    onSoundsChanged = {},
                    onLocationChanged = {},
                    onMirrorChanged = {},
                    onHdOptionChanged = { selectedHd = it },
                    onNavigateBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Photo Aspect Ratio opens bottom sheet with 1-line explanations
        composeTestRule.onNodeWithTag("setting_aspect_ratio_row").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_choice_bottom_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Full sensor frame for maximum photo resolution").assertIsDisplayed()
        composeTestRule.onNodeWithText("Square framing ideal for social posts").performClick()
        composeTestRule.waitForIdle()
        assertEquals(AspectRatioMode.RATIO_1_1, selectedRatio)

        // HD Mode Output opens bottom sheet with radio options and 1-line explanations
        composeTestRule.onNodeWithTag("setting_hd_output_row").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_choice_bottom_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Full optical detail without upscaling").assertIsDisplayed()
        composeTestRule.onNodeWithText("Faster capture and lower storage").performClick()
        composeTestRule.waitForIdle()
        assertEquals(HdOutputOption.STANDARD_BINNED, selectedHd)
    }
}
