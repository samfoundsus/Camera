package com.example.ui.theme

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.example.core.preferences.AppThemePreference

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = PixelDarkPrimary,
    onPrimary = PixelDarkOnPrimary,
    primaryContainer = PixelDarkPrimaryContainer,
    onPrimaryContainer = PixelDarkOnPrimaryContainer,
    secondary = PixelDarkSecondary,
    onSecondary = PixelDarkOnSecondary,
    secondaryContainer = PixelDarkSecondaryContainer,
    onSecondaryContainer = PixelDarkOnSecondaryContainer,
    tertiary = PixelDarkTertiary,
    onTertiary = PixelDarkOnTertiary,
    background = PixelDarkBackground,
    onBackground = PixelDarkOnSurface,
    surface = PixelDarkSurface,
    onSurface = PixelDarkOnSurface,
    surfaceVariant = PixelDarkSurfaceVariant,
    onSurfaceVariant = PixelDarkOnSurfaceVariant,
    surfaceContainer = PixelDarkSurfaceContainer,
    outline = PixelDarkOutline,
    outlineVariant = PixelDarkOutlineVariant
)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = PixelLightPrimary,
    onPrimary = PixelLightOnPrimary,
    primaryContainer = PixelLightPrimaryContainer,
    onPrimaryContainer = PixelLightOnPrimaryContainer,
    secondary = PixelLightSecondary,
    onSecondary = PixelLightOnSecondary,
    secondaryContainer = PixelLightSecondaryContainer,
    onSecondaryContainer = PixelLightOnSecondaryContainer,
    tertiary = PixelLightTertiary,
    onTertiary = PixelLightOnTertiary,
    background = PixelLightBackground,
    onBackground = PixelLightOnSurface,
    surface = PixelLightSurface,
    onSurface = PixelLightOnSurface,
    surfaceVariant = PixelLightSurfaceVariant,
    onSurfaceVariant = PixelLightOnSurfaceVariant,
    surfaceContainer = PixelLightSurfaceContainer,
    outline = PixelLightOutline,
    outlineVariant = PixelLightOutlineVariant
)

enum class ThemeStrategy(val label: String) {
    DYNAMIC_MONET("Material You Dynamic Monet (Android 12+)"),
    STATIC_PALETTE("Pixel Static Palette (Android 10-11 / Fallback)")
}

val LocalThemeStrategy = staticCompositionLocalOf { ThemeStrategy.STATIC_PALETTE }

/**
 * Result of resolving theme colors for the current configuration.
 */
data class ResolvedTheme(
    val colorScheme: ColorScheme,
    val strategy: ThemeStrategy,
    val isDark: Boolean
)

/**
 * Resolves the Material 3 ColorScheme based on Android API level, system dark mode,
 * and user theme preferences.
 *
 * Android 10 and Android 11 (API 29–30):
 * - Uses predefined static dark and light color palettes.
 * - Never calls Android 12 Monet APIs on these versions.
 *
 * Android 12 and later (API 31+):
 * - Uses system Monet / Material You dynamic colors when available.
 * - Supports light and dark themes.
 * - Gracefully falls back to the static palette if dynamic colors cannot be extracted.
 *
 * Respects user's [themePreference] (System default, Light, or Dark).
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun getDynamicColorScheme(context: android.content.Context, isDark: Boolean): ColorScheme {
    return if (isDark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
}

fun resolveThemeColors(
    context: android.content.Context,
    themePreference: AppThemePreference,
    systemInDark: Boolean,
    dynamicColor: Boolean = true,
    sdkInt: Int = Build.VERSION.SDK_INT
): ResolvedTheme {
    val isDark = when (themePreference) {
        AppThemePreference.SYSTEM -> systemInDark
        AppThemePreference.LIGHT -> false
        AppThemePreference.DARK -> true
    }

    val canUseDynamic = dynamicColor && sdkInt >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return if (canUseDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            val scheme = getDynamicColorScheme(context, isDark)
            ResolvedTheme(
                colorScheme = scheme,
                strategy = ThemeStrategy.DYNAMIC_MONET,
                isDark = isDark
            )
        } catch (_: Throwable) {
            val fallback = if (isDark) DarkColorScheme else LightColorScheme
            ResolvedTheme(
                colorScheme = fallback,
                strategy = ThemeStrategy.STATIC_PALETTE,
                isDark = isDark
            )
        }
    } else {
        val fallback = if (isDark) DarkColorScheme else LightColorScheme
        ResolvedTheme(
            colorScheme = fallback,
            strategy = ThemeStrategy.STATIC_PALETTE,
            isDark = isDark
        )
    }
}

/**
 * Camera Theme
 *
 * Android 10 and Android 11 (API 29–30):
 * - Uses predefined static dark and light color palettes.
 * - Never calls Android 12 Monet APIs on these versions.
 *
 * Android 12 and later (API 31+):
 * - Uses system Monet / Material You dynamic colors when available.
 * - Supports light and dark themes.
 * - Gracefully falls back to the static palette if dynamic colors cannot be extracted.
 *
 * Respects user's [themePreference] (System default, Light, or Dark).
 */
@Composable
fun PixelCameraTheme(
    themePreference: AppThemePreference = AppThemePreference.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val resolved = androidx.compose.runtime.remember(themePreference, dynamicColor, systemInDark, context) {
        resolveThemeColors(
            context = context,
            themePreference = themePreference,
            systemInDark = systemInDark,
            dynamicColor = dynamicColor
        )
    }

    CompositionLocalProvider(LocalThemeStrategy provides resolved.strategy) {
        MaterialTheme(
            colorScheme = resolved.colorScheme,
            typography = Typography,
            content = content
        )
    }
}

