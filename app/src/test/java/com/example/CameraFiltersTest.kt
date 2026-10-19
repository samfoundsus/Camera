package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import com.example.core.preferences.CameraFilterCategory
import com.example.core.preferences.CameraFilterPreset
import com.example.core.preferences.CameraPreferencesRepository
import com.example.core.processing.CameraFilterProcessor
import com.example.core.processing.ProcessedPhotoPayload
import com.example.feature.preview.CameraFoundationScreen
import com.example.ui.theme.PixelCameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraFiltersTest {

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
        CameraFilterProcessor.clearThumbnailCache()
    }

    @Test
    fun filterPresets_all18NaturalAndCreativePresetsAreRegisteredAndCategorized() {
        val naturalPresets = CameraFilterProcessor.getPresetsByCategory(CameraFilterCategory.NATURAL)
        val creativePresets = CameraFilterProcessor.getPresetsByCategory(CameraFilterCategory.CREATIVE)

        assertEquals(
            listOf(
                CameraFilterPreset.ORIGINAL,
                CameraFilterPreset.NATURAL,
                CameraFilterPreset.WARM,
                CameraFilterPreset.COOL,
                CameraFilterPreset.RICH,
                CameraFilterPreset.SOFT,
                CameraFilterPreset.PORTRAIT_NATURAL,
                CameraFilterPreset.MATTE
            ),
            naturalPresets
        )

        assertEquals(
            listOf(
                CameraFilterPreset.VIVID,
                CameraFilterPreset.CINEMATIC,
                CameraFilterPreset.GOLDEN_HOUR,
                CameraFilterPreset.VINTAGE,
                CameraFilterPreset.CLASSIC_FILM,
                CameraFilterPreset.MONO,
                CameraFilterPreset.NOIR,
                CameraFilterPreset.FADE,
                CameraFilterPreset.DRAMATIC,
                CameraFilterPreset.MOODY
            ),
            creativePresets
        )

        // Verify central parameters exist for every preset
        for (preset in CameraFilterPreset.entries) {
            val params = CameraFilterProcessor.getParameters(preset)
            assertEquals(preset, params.preset)
            if (preset == CameraFilterPreset.ORIGINAL) {
                assertTrue(params.isIdentity)
            } else {
                assertFalse("Preset ${preset.name} should not be identity", params.isIdentity)
            }
        }
    }

    @Test
    fun sharedFilterPipeline_appliesConsistentTransformToBitmapsJpegsAndVideoPixels() {
        val width = 32
        val height = 32
        val sourceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (40 + (x * 6)).coerceIn(0, 255)
                val g = (60 + (y * 5)).coerceIn(0, 255)
                val b = (140 - (x * 2)).coerceIn(0, 255)
                sourceBitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        // 1. Original preset leaves pixels untouched
        val originalOut = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.ORIGINAL)
        assertEquals(sourceBitmap.getPixel(16, 16), originalOut.getPixel(16, 16))

        // 2. Warm vs Cool shifts red/blue balance in opposite directions
        val warmOut = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.WARM)
        val coolOut = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.COOL)
        val warmCenter = warmOut.getPixel(16, 16)
        val coolCenter = coolOut.getPixel(16, 16)
        assertTrue(
            "Warm filter should have higher red channel than Cool filter",
            Color.red(warmCenter) > Color.red(coolCenter)
        )
        assertTrue(
            "Cool filter should have higher blue channel than Warm filter",
            Color.blue(coolCenter) > Color.blue(warmCenter)
        )

        // 3. Mono & Noir produce true monochrome pixels (R == G == B)
        val monoOut = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.MONO)
        val noirOut = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.NOIR)
        val monoCenter = monoOut.getPixel(16, 16)
        assertEquals(Color.red(monoCenter), Color.green(monoCenter))
        assertEquals(Color.green(monoCenter), Color.blue(monoCenter))
        val noirCenter = noirOut.getPixel(16, 16)
        assertEquals(Color.red(noirCenter), Color.green(noirCenter))
        assertEquals(Color.green(noirCenter), Color.blue(noirCenter))

        // 4. Matte & Fade lift darkest shadow pixels compared to Original
        val darkShadowPixel = sourceBitmap.getPixel(0, 0)
        val matteOut = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.MATTE)
        val fadeOut = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.FADE)
        assertTrue(Color.red(matteOut.getPixel(0, 0)) > Color.red(darkShadowPixel))
        assertTrue(Color.red(fadeOut.getPixel(0, 0)) > Color.red(darkShadowPixel))

        // 5. Still Photo Payload processing applies filter to JPEG bytes
        val rawJpegStream = ByteArrayOutputStream()
        sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 96, rawJpegStream)
        val rawJpegBytes = rawJpegStream.toByteArray()
        val payload = ProcessedPhotoPayload(
            jpegBytes = rawJpegBytes,
            width = width,
            height = height,
            exifOrientation = 1,
            wasRecompressed = false
        )
        val untouchedPayload = CameraFilterProcessor.applyFilterToPhotoPayload(payload, CameraFilterPreset.ORIGINAL)
        assertSame(payload, untouchedPayload)

        val goldenHourPayload = CameraFilterProcessor.applyFilterToPhotoPayload(payload, CameraFilterPreset.GOLDEN_HOUR)
        assertTrue(goldenHourPayload.wasRecompressed)
        assertTrue(goldenHourPayload.jpegBytes.isNotEmpty())
        assertFalse(rawJpegBytes.contentEquals(goldenHourPayload.jpegBytes))

        // 6. Video pixel buffer processing and NV12 conversion match photo bitmap filtering
        val videoPixels = IntArray(width * height)
        sourceBitmap.getPixels(videoPixels, 0, width, 0, 0, width, height)
        CameraFilterProcessor.applyFilterToPixelsInPlace(videoPixels, CameraFilterPreset.CINEMATIC)
        val cinematicPhoto = CameraFilterProcessor.applyFilterToBitmap(sourceBitmap, CameraFilterPreset.CINEMATIC)
        assertEquals(cinematicPhoto.getPixel(16, 16), videoPixels[16 * width + 16])

        val nv12 = ByteArray(width * height * 3 / 2)
        CameraFilterProcessor.argbToNv12(videoPixels, width, height, nv12)
        assertTrue(nv12.any { it.toInt() != 0 })
    }

    @Test
    fun thumbnailGenerationAndCache_producesDistinctCachedThumbnails() {
        val origThumb1 = CameraFilterProcessor.getOrCreateThumbnail(CameraFilterPreset.ORIGINAL, 64)
        val origThumb2 = CameraFilterProcessor.getOrCreateThumbnail(CameraFilterPreset.ORIGINAL, 64)
        assertSame("Thumbnail should be served from LRU cache on second call", origThumb1, origThumb2)

        val monoThumb = CameraFilterProcessor.getOrCreateThumbnail(CameraFilterPreset.MONO, 64)
        val vividThumb = CameraFilterProcessor.getOrCreateThumbnail(CameraFilterPreset.VIVID, 64)
        assertNotEquals(origThumb1.getPixel(32, 32), monoThumb.getPixel(32, 32))
        assertNotEquals(monoThumb.getPixel(32, 32), vividThumb.getPixel(32, 32))
    }

    @Test
    fun filterSelectorTrayUi_opensSelectsPersistsAndResetsFilterWhileKeepingShutterAccessible() {
        val repository = CameraPreferencesRepository(context)

        composeTestRule.setContent {
            val prefs = repository.preferences.collectAsState().value
            PixelCameraTheme(
                themePreference = prefs.themePreference,
                dynamicColor = false
            ) {
                CameraFoundationScreen(
                    preferences = prefs,
                    preferencesRepository = repository,
                    onNavigateToSettings = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Filters button and shutter button are visible
        composeTestRule.onNodeWithTag("top_bar_filters_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shutter_button").assertIsDisplayed()

        // 2. Open filter tray
        composeTestRule.onNodeWithTag("top_bar_filters_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_filter_tray").assertIsDisplayed()
        // Shutter button remains accessible while filter tray is open
        composeTestRule.onNodeWithTag("shutter_button").assertIsDisplayed()

        // 3. Select Warm preset in Natural category
        composeTestRule.onNodeWithTag("filter_presets_lazy_row")
            .performScrollToNode(hasTestTag("filter_item_warm"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("filter_item_warm").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraFilterPreset.WARM, repository.preferences.value.selectedFilter)
        composeTestRule.onNodeWithTag("top_bar_filter_active_dot", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("filter_tray_close_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("preview_active_filter_badge").assertIsDisplayed()

        // 4. Re-open filter tray, switch to Creative category and select Noir
        composeTestRule.onNodeWithTag("top_bar_filters_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("filter_category_creative").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("filter_presets_lazy_row")
            .performScrollToNode(hasTestTag("filter_item_noir"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("filter_item_noir").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraFilterPreset.NOIR, repository.preferences.value.selectedFilter)

        // 5. Verify persistence across new repository instance
        val reloadedRepo = CameraPreferencesRepository(context)
        assertEquals(CameraFilterPreset.NOIR, reloadedRepo.preferences.value.selectedFilter)

        // 6. Reset to Original using the tray's reset button
        composeTestRule.onNodeWithTag("filter_reset_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraFilterPreset.ORIGINAL, repository.preferences.value.selectedFilter)
    }

    @Test
    fun filterModeMatrix_filtersAreVisibleOnlyInPhotoAndVideoModesAndAutoDismissOnModeSwitch() {
        // 1. Enum supportsFilters matrix check
        assertTrue("PHOTO mode must support filters", com.example.core.model.CameraMode.PHOTO.supportsFilters)
        assertTrue("VIDEO mode must support filters", com.example.core.model.CameraMode.VIDEO.supportsFilters)

        val excludedModes = listOf(
            com.example.core.model.CameraMode.PORTRAIT,
            com.example.core.model.CameraMode.NIGHT_SIGHT,
            com.example.core.model.CameraMode.HD,
            com.example.core.model.CameraMode.PRO,
            com.example.core.model.CameraMode.PANORAMA,
            com.example.core.model.CameraMode.ASTRO,
            com.example.core.model.CameraMode.SLOW_MOTION,
            com.example.core.model.CameraMode.TIME_LAPSE
        )

        for (mode in excludedModes) {
            assertFalse("Mode ${mode.name} must NOT support filters", mode.supportsFilters)
            assertEquals(
                "Mode ${mode.name} must resolve effective filter to ORIGINAL",
                CameraFilterPreset.ORIGINAL,
                CameraFilterProcessor.resolveEffectiveFilter(mode, CameraFilterPreset.WARM)
            )
        }

        // 2. UI entry-point mode matrix test
        val repository = CameraPreferencesRepository(context)
        repository.setSelectedFilter(CameraFilterPreset.VIVID)

        composeTestRule.setContent {
            val prefs = repository.preferences.collectAsState().value
            PixelCameraTheme(
                themePreference = prefs.themePreference,
                dynamicColor = false
            ) {
                CameraFoundationScreen(
                    preferences = prefs,
                    preferencesRepository = repository,
                    onNavigateToSettings = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // PHOTO mode: Filters button and active badge are displayed
        composeTestRule.onNodeWithTag("top_bar_filters_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_active_filter_badge").assertIsDisplayed()

        // Open filter tray
        composeTestRule.onNodeWithTag("top_bar_filters_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_filter_tray").assertIsDisplayed()

        // Switch to PORTRAIT mode: Filters button, tray, and badge must automatically disappear
        composeTestRule.onNodeWithTag("mode_item_portrait").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_bar_filters_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("camera_filter_tray").assertDoesNotExist()
        composeTestRule.onNodeWithTag("preview_active_filter_badge").assertDoesNotExist()

        // Switch to VIDEO mode: Filters button and active badge reappear
        composeTestRule.onNodeWithTag("mode_item_video").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_bar_filters_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_active_filter_badge").assertIsDisplayed()

        // Switch to NIGHT_SIGHT mode: Filters entry points disappear
        composeTestRule.onNodeWithTag("mode_item_night_sight").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_bar_filters_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("camera_filter_tray").assertDoesNotExist()
        composeTestRule.onNodeWithTag("preview_active_filter_badge").assertDoesNotExist()

        // Switch back to PHOTO mode: Filters button and badge reappear
        composeTestRule.onNodeWithTag("mode_item_photo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("top_bar_filters_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_active_filter_badge").assertIsDisplayed()
    }
}

