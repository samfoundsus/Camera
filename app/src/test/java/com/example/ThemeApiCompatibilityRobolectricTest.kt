package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.preferences.AppThemePreference
import com.example.ui.theme.DarkColorScheme
import com.example.ui.theme.LightColorScheme
import com.example.ui.theme.ThemeStrategy
import com.example.ui.theme.resolveThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric test suite validating Material You dynamic color behavior
 * across Android versions:
 * - API 29-30: Strict fallback to predefined static palettes.
 * - API 31+: Dynamic Monet color schemes derived from context.
 * - User theme preferences: System default, Light, and Dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeApiCompatibilityRobolectricTest {

    @Test
    @Config(sdk = [29])
    fun api29_strictlyUsesStaticPaletteFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Light mode on API 29
        val lightTheme = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.LIGHT,
            systemInDark = false,
            dynamicColor = true
        )
        assertEquals(ThemeStrategy.STATIC_PALETTE, lightTheme.strategy)
        assertEquals(LightColorScheme.primary, lightTheme.colorScheme.primary)
        assertFalse(lightTheme.isDark)

        // Dark mode on API 29
        val darkTheme = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.DARK,
            systemInDark = false,
            dynamicColor = true
        )
        assertEquals(ThemeStrategy.STATIC_PALETTE, darkTheme.strategy)
        assertEquals(DarkColorScheme.primary, darkTheme.colorScheme.primary)
        assertTrue(darkTheme.isDark)
    }

    @Test
    @Config(sdk = [30])
    fun api30_strictlyUsesStaticPaletteFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Light mode on API 30
        val lightTheme = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.LIGHT,
            systemInDark = false,
            dynamicColor = true
        )
        assertEquals(ThemeStrategy.STATIC_PALETTE, lightTheme.strategy)
        assertEquals(LightColorScheme.primary, lightTheme.colorScheme.primary)
        assertFalse(lightTheme.isDark)

        // Dark mode on API 30
        val darkTheme = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.DARK,
            systemInDark = false,
            dynamicColor = true
        )
        assertEquals(ThemeStrategy.STATIC_PALETTE, darkTheme.strategy)
        assertEquals(DarkColorScheme.primary, darkTheme.colorScheme.primary)
        assertTrue(darkTheme.isDark)
    }

    @Test
    @Config(sdk = [31])
    fun api31_usesDynamicMonetWhenEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Dynamic light on API 31
        val lightDynamic = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.LIGHT,
            systemInDark = false,
            dynamicColor = true
        )
        assertEquals(ThemeStrategy.DYNAMIC_MONET, lightDynamic.strategy)
        assertFalse(lightDynamic.isDark)

        // Dynamic dark on API 31
        val darkDynamic = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.DARK,
            systemInDark = false,
            dynamicColor = true
        )
        assertEquals(ThemeStrategy.DYNAMIC_MONET, darkDynamic.strategy)
        assertTrue(darkDynamic.isDark)

        // When user disables dynamic colors on API 31
        val lightStatic = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.LIGHT,
            systemInDark = false,
            dynamicColor = false
        )
        assertEquals(ThemeStrategy.STATIC_PALETTE, lightStatic.strategy)
        assertEquals(LightColorScheme.primary, lightStatic.colorScheme.primary)
    }

    @Test
    @Config(sdk = [34])
    fun api34_respectsThemePreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // System Default when system is light
        val systemLight = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.SYSTEM,
            systemInDark = false,
            dynamicColor = true
        )
        assertFalse(systemLight.isDark)

        // System Default when system is dark
        val systemDark = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.SYSTEM,
            systemInDark = true,
            dynamicColor = true
        )
        assertTrue(systemDark.isDark)

        // Forced Light ignores system dark
        val forcedLight = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.LIGHT,
            systemInDark = true,
            dynamicColor = true
        )
        assertFalse(forcedLight.isDark)

        // Forced Dark ignores system light
        val forcedDark = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.DARK,
            systemInDark = false,
            dynamicColor = true
        )
        assertTrue(forcedDark.isDark)
    }

    @Test
    fun apiLevelOverride_fallbackAlwaysUsedOnApi29And30RegardlessOfHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Even if host is on a different SDK level, passing sdkInt = 29 or 30 strictly enforces static palette
        val api29Theme = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.LIGHT,
            systemInDark = false,
            dynamicColor = true,
            sdkInt = 29
        )
        assertEquals(ThemeStrategy.STATIC_PALETTE, api29Theme.strategy)
        assertEquals(LightColorScheme.primary, api29Theme.colorScheme.primary)

        val api30Theme = resolveThemeColors(
            context = context,
            themePreference = AppThemePreference.DARK,
            systemInDark = false,
            dynamicColor = true,
            sdkInt = 30
        )
        assertEquals(ThemeStrategy.STATIC_PALETTE, api30Theme.strategy)
        assertEquals(DarkColorScheme.primary, api30Theme.colorScheme.primary)
    }
}
