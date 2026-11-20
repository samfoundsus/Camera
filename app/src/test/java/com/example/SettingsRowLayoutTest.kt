package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.example.core.camera.CameraLens
import com.example.core.camera.DefaultCameraCapabilitiesRepository
import com.example.core.camera.LensCapabilities
import com.example.core.camera.PhotoOutputSize
import com.example.core.preferences.CameraPreferences
import com.example.feature.settings.SettingsScreen
import com.example.ui.components.SETTINGS_ROW_VALUE_MAX_WIDTH_FRACTION
import com.example.ui.components.SettingsCard
import com.example.ui.components.SettingsRow
import com.example.ui.components.SettingsValueLayoutMode
import com.example.ui.components.shouldUseStackedSettingsLayout
import com.example.ui.components.wrapValueAtSeparators
import com.example.ui.theme.PixelCameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRowLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun shouldUseStackedSettingsLayout_picksSideBySideForShortValues_andStacksForLongOrWideValues() {
        val rowWidthPx = 960 // ~320dp @ 3x density
        val density = 3f

        // Short value ("Active") within 40% width -> side-by-side
        assertFalse(
            shouldUseStackedSettingsLayout(
                trailingText = "Active",
                layoutMode = SettingsValueLayoutMode.AUTO,
                availableWidthPx = rowWidthPx,
                measuredValueWidthPx = 120,
                dropdownWidthPx = 0,
                density = density
            )
        )

        // Multi-segment bullet metadata value -> always stacked
        assertTrue(
            shouldUseStackedSettingsLayout(
                trailingText = "Main Camera (1x) • 11.8 MP • 5.3mm",
                layoutMode = SettingsValueLayoutMode.AUTO,
                availableWidthPx = rowWidthPx,
                measuredValueWidthPx = 280,
                dropdownWidthPx = 0,
                density = density
            )
        )

        // Long single-segment value (>14 chars) -> stacked
        assertTrue(
            shouldUseStackedSettingsLayout(
                trailingText = "Highest Native Output",
                layoutMode = SettingsValueLayoutMode.AUTO,
                availableWidthPx = rowWidthPx,
                measuredValueWidthPx = 300,
                dropdownWidthPx = 72,
                density = density
            )
        )

        // Short string whose measured width exceeds 40% under large font/display size -> auto-stacks
        assertTrue(
            shouldUseStackedSettingsLayout(
                trailingText = "Discovered",
                layoutMode = SettingsValueLayoutMode.AUTO,
                availableWidthPx = 720,
                measuredValueWidthPx = 310, // > 40% of 720 (288px)
                dropdownWidthPx = 0,
                density = density
            )
        )

        // Explicit STACKED always stacks even for short strings
        assertTrue(
            shouldUseStackedSettingsLayout(
                trailingText = "1x",
                layoutMode = SettingsValueLayoutMode.STACKED,
                availableWidthPx = rowWidthPx,
                measuredValueWidthPx = 40,
                dropdownWidthPx = 0,
                density = density
            )
        )
    }

    @Test
    fun wrapValueAtSeparators_wrapsCleanlyAtBulletBoundariesWithoutSplittingTokens() {
        val raw = "Main Camera (1x) • 11.8 MP • 5.3mm"
        // Simple deterministic monospace measure: 10px per char
        val measure: (String) -> Int = { it.length * 10 }

        // Wide enough for 1 line -> unchanged
        assertEquals(raw, wrapValueAtSeparators(raw, maxLineWidthPx = 400, measureWidthPx = measure))

        // Medium width (270px): "Main Camera (1x) • 11.8 MP" is 26 chars (260px) -> fits line 1; "5.3mm" wraps to line 2
        val twoLine = wrapValueAtSeparators(raw, maxLineWidthPx = 270, measureWidthPx = measure)
        assertEquals("Main Camera (1x) • 11.8 MP\n5.3mm", twoLine)

        // Narrow width / large font (180px): wraps at both bullet separators into 3 clean lines
        val threeLine = wrapValueAtSeparators(raw, maxLineWidthPx = 180, measureWidthPx = measure)
        assertEquals("Main Camera (1x)\n11.8 MP • 5.3mm", threeLine)

        // Very narrow width (140px): "11.8 MP • 5.3mm" is 150px -> each segment gets its own line, never splitting "11.8 MP"
        val eachLine = wrapValueAtSeparators(raw, maxLineWidthPx = 140, measureWidthPx = measure)
        assertEquals("Main Camera (1x)\n11.8 MP\n5.3mm", eachLine)
    }

    @Test
    fun activeCameraLensRow_usesStackedFullWidthLeftAlignedLayout_andShortStatusUsesSideBySideCappedAt40Percent() {
        composeTestRule.setContent {
            PixelCameraTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    SettingsCard {
                        SettingsRow(
                            title = "Active Camera Lens",
                            subtitle = "Target lens for photo and video streams",
                            trailingText = "Main Camera (1x) • 11.8 MP • 5.3mm",
                            onClick = {},
                            clickable = false,
                            layoutMode = SettingsValueLayoutMode.STACKED,
                            useSecondaryValueStyle = true,
                            testTag = "setting_active_camera_lens_row"
                        )
                        SettingsRow(
                            title = "Hardware Discovery Status",
                            subtitle = "Discovered and active hardware camera streams.",
                            trailingText = "Active",
                            onClick = {},
                            clickable = false,
                            testTag = "setting_hardware_discovery_status_row"
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Active Camera Lens must be stacked: title -> subtitle -> full-width left-aligned value
        composeTestRule.onNodeWithTag("setting_active_camera_lens_row_stacked_layout", useUnmergedTree = true)
            .assertExists()

        val rowBounds = composeTestRule.onNodeWithTag("setting_active_camera_lens_row")
            .getUnclippedBoundsInRoot()
        val titleBounds = composeTestRule.onNodeWithTag("setting_active_camera_lens_row_title", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val subtitleBounds = composeTestRule.onNodeWithTag("setting_active_camera_lens_row_subtitle", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val valueBounds = composeTestRule.onNodeWithTag("setting_active_camera_lens_row_value", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val expectedInnerWidth = rowBounds.width - 40.dp // 20dp horizontal padding on each side
        assertTrue(
            "Title should span full inner row width ($expectedInnerWidth), actual=${titleBounds.width}",
            titleBounds.width >= expectedInnerWidth - 2.dp
        )
        assertTrue(
            "Subtitle should span full inner row width ($expectedInnerWidth), actual=${subtitleBounds.width}",
            subtitleBounds.width >= expectedInnerWidth - 2.dp
        )
        assertTrue(
            "Value should be stacked below subtitle (subtitle.bottom=${subtitleBounds.bottom}, value.top=${valueBounds.top})",
            valueBounds.top >= subtitleBounds.bottom
        )
        assertTrue(
            "Title should sit above subtitle (title.bottom=${titleBounds.bottom}, subtitle.top=${subtitleBounds.top})",
            subtitleBounds.top >= titleBounds.bottom
        )
        assertTrue(
            "Stacked value should be left-aligned with title (title.left=${titleBounds.left}, value.left=${valueBounds.left})",
            abs(valueBounds.left.value - titleBounds.left.value) <= 1.5f
        )

        // 2. Short status ("Active") stays side-by-side with value column capped <= 40% of inner row width
        composeTestRule.onNodeWithTag("setting_hardware_discovery_status_row_side_by_side_layout", useUnmergedTree = true)
            .assertExists()
        val statusRowBounds = composeTestRule.onNodeWithTag("setting_hardware_discovery_status_row")
            .getUnclippedBoundsInRoot()
        val statusValueContainerBounds = composeTestRule.onNodeWithTag(
            "setting_hardware_discovery_status_row_value_container",
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        val statusInnerWidth = statusRowBounds.width - 40.dp
        assertTrue(
            "Side-by-side value container (${statusValueContainerBounds.width}) must stay <= 40% of $statusInnerWidth",
            statusValueContainerBounds.width <= (statusInnerWidth * SETTINGS_ROW_VALUE_MAX_WIDTH_FRACTION) + 1.dp
        )
    }

    @Test
    fun settingsScreen_hardwareCapabilitiesAndAboutRows_stayReadableUnderLargeFontAndDisplaySize() {
        val caps = LensCapabilities(
            lens = CameraLens.BACK_MAIN,
            camera2CameraId = "0",
            sensorDescription = "Main Camera (1x) • 11.8 MP • 5.3mm",
            supportedPhotoSizes = listOf(PhotoOutputSize(4000, 3000)),
            highestNativeStillSize = PhotoOutputSize(4000, 3000)
        )
        val repo = DefaultCameraCapabilitiesRepository().apply {
            setDiscoveredCapabilities(mapOf(CameraLens.BACK_MAIN to caps))
        }

        // Simulate Android Large Display Size (narrow 300.dp width) + Largest Font Size (1.8x fontScale)
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 1.8f)
            ) {
                PixelCameraTheme {
                    Box(modifier = Modifier.width(300.dp)) {
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
            }
        }
        composeTestRule.waitForIdle()

        // Active Camera Lens row renders stacked with full width even at 1.8x font scale & 300dp width
        composeTestRule.onNodeWithTag("setting_active_camera_lens_row")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_active_camera_lens_row_stacked_layout", useUnmergedTree = true)
            .assertExists()
        val activeLensTitleBounds = composeTestRule.onNodeWithTag(
            "setting_active_camera_lens_row_title",
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        assertTrue(
            "Active Camera Lens title must not squeeze under large font/display size (width=${activeLensTitleBounds.width})",
            activeLensTitleBounds.width >= 200.dp
        )

        // Hardware Discovery Status row remains readable without squeezed title
        composeTestRule.onNodeWithTag("setting_hardware_discovery_status_row")
            .performScrollTo()
            .assertIsDisplayed()
        val discoveryTitleBounds = composeTestRule.onNodeWithTag(
            "setting_hardware_discovery_status_row_title",
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        assertTrue(
            "Hardware Discovery Status title must retain >= 150dp width (actual=${discoveryTitleBounds.width})",
            discoveryTitleBounds.width >= 150.dp
        )

        // About > Architecture row remains readable
        composeTestRule.onNodeWithTag("setting_architecture_row")
            .performScrollTo()
            .assertIsDisplayed()
        val archTitleBounds = composeTestRule.onNodeWithTag(
            "setting_architecture_row_title",
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        assertTrue(
            "Architecture title must retain >= 150dp width (actual=${archTitleBounds.width})",
            archTitleBounds.width >= 150.dp
        )

        // About > App Version row remains readable and long-press still opens hardware debug dialog
        composeTestRule.onNodeWithTag("settings_app_version_row")
            .performScrollTo()
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hardware_debug_dialog").assertIsDisplayed()
    }
}
