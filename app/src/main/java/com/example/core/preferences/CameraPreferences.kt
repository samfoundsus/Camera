package com.example.core.preferences

import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution

/**
 * Available theme preferences.
 */
enum class AppThemePreference(val displayName: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * Flash setting modes.
 */
enum class FlashMode(val displayName: String) {
    AUTO("Auto"),
    ON("On"),
    OFF("Off"),
    TORCH("Torch")
}

/**
 * Shutter timer delay modes.
 */
enum class TimerMode(val displayName: String, val seconds: Int) {
    OFF("Off", 0),
    SECONDS_3("3 seconds", 3),
    SECONDS_5("5 seconds", 5),
    SECONDS_10("10 seconds", 10)
}

/**
 * Viewfinder grid overlay modes.
 */
enum class GridMode(val displayName: String) {
    OFF("Off"),
    GRID_3X3("3 × 3"),
    GRID_4X4("4 × 4"),
    GOLDEN_RATIO("Golden ratio")
}

/**
 * Framing aspect ratio modes.
 */
enum class AspectRatioMode(val displayName: String, val ratioFloat: Float?) {
    RATIO_4_3("4:3", 3f / 4f),
    RATIO_1_1("1:1", 1f),
    RATIO_9_16("9:16", 9f / 16f)
}

/**
 * Video stabilization preferences.
 */
enum class VideoStabilizationMode(val displayName: String) {
    STANDARD("Standard"),
    ACTIVE("Active"),
    OFF("Off")
}

/**
 * HD still-capture output option.
 */
enum class HdOutputOption(val displayName: String, val subtitle: String) {
    MAX_NATIVE("Highest Native Output", "Full optical detail without upscaling"),
    STANDARD_BINNED("Standard Binned", "Faster capture and lower storage")
}

/**
 * Portrait retouch effect options.
 */
enum class PortraitRetouch(val displayName: String) {
    OFF("Off"),
    NATURAL("Natural"),
    SMOOTH("Smooth")
}

/**
 * Portrait background blur strength levels (software blur parameter).
 * Directly controls the background blur radius applied to the saved Portrait image.
 * Treated strictly as a software blur parameter within 1.0–16.0 (not a physical camera aperture).
 */
enum class PortraitBlurLevel(
    val displayName: String,
    val shortcutLabel: String,
    val blurStrength: Float,
    val apertureLabel: String = shortcutLabel
) {
    OFF("Off", "Off", 0.0f, "Off"),
    LIGHT("f/4.0", "f/4.0", 4.0f, "f/4.0"),
    MEDIUM("f/2.0", "f/2.0", 2.0f, "f/2.0"),
    DEEP("f/1.0", "f/1.0", 1.0f, "f/1.0");

    companion object {
        const val OFF_THRESHOLD = 0.05f
        const val MIN_BLUR_STRENGTH = 1.0f
        const val MAX_BLUR_STRENGTH = 16.0f

        val LEVEL_4: PortraitBlurLevel get() = LIGHT
        val LEVEL_2: PortraitBlurLevel get() = MEDIUM
        val LEVEL_1: PortraitBlurLevel get() = DEEP

        /**
         * Normalizes a continuous blur strength value.
         * Values <= 0.05f are mapped to 0.0f (Off).
         * Non-zero values are clamped to [MIN_BLUR_STRENGTH, maxStrength].
         */
        fun normalizeBlurStrength(rawStrength: Float, maxStrength: Float = MAX_BLUR_STRENGTH): Float {
            if (!rawStrength.isFinite() || rawStrength <= OFF_THRESHOLD) return 0.0f
            val effective = if (rawStrength < 1.0f) {
                // Smooth backward-compatible bridge for legacy fractional values: maps (0.05, 1.0) -> [1.0, 4.0]
                MIN_BLUR_STRENGTH + (rawStrength / 1.0f) * 3.0f
            } else {
                rawStrength
            }
            val clamped = effective.coerceIn(MIN_BLUR_STRENGTH, maxStrength)
            return ((clamped * 10f).toInt() / 10f)
        }

        /**
         * Maps any continuous blur strength to its nearest [PortraitBlurLevel] preset bucket.
         */
        fun fromBlurStrength(strength: Float): PortraitBlurLevel {
            if (strength <= OFF_THRESHOLD) return OFF
            return when {
                strength < 1.5f -> DEEP // "f/1.0"
                strength < 3.0f -> MEDIUM // "f/2.0"
                else -> LIGHT // "f/4.0"
            }
        }

        /**
         * Returns an f-stop formatted label for the blur strength software parameter (e.g. "f/2.0", "f/16.0", or "Off").
         */
        fun labelForStrength(strength: Float): String {
            if (strength <= OFF_THRESHOLD) return "Off"
            return String.format(java.util.Locale.US, "f/%.1f", strength)
        }

        fun simulatedApertureLabelForStrength(strength: Float): String = labelForStrength(strength)
    }
}

/**
 * Night sight exposure duration modes.
 */
enum class NightSightExposure(val displayName: String) {
    AUTO("Auto"),
    MAX("Max duration"),
    OFF("Off")
}

/**
 * Time lapse capture speed options.
 */
enum class TimeLapseSpeed(val displayName: String, val intervalSeconds: Float) {
    SPEED_5X("5× (Fast walk)", 0.5f),
    SPEED_10X("10× (Busy street)", 1.0f),
    SPEED_30X("30× (Moving clouds)", 3.0f),
    SPEED_120X("120× (Sunset)", 12.0f)
}

/**
 * Slow motion playback speed options.
 */
enum class SlowMoSpeed(val displayName: String, val expectedFps: Int) {
    SPEED_1_4X("1/4× (120 FPS)", 120),
    SPEED_1_8X("1/8× (240 FPS)", 240)
}

/**
 * Focus modes for Pro manual control.
 */
enum class ProFocusMode(val displayName: String) {
    AUTO("Auto Focus (AF)"),
    MANUAL("Manual Focus (MF)")
}

/**
 * RAW / DNG file capture formats.
 */
enum class ProRawFormat(val displayName: String, val description: String) {
    JPEG_ONLY("JPEG Only", "Standard compressed images"),
    RAW_PLUS_JPEG("RAW (DNG) + JPEG", "Full sensor RAW alongside JPEG"),
    RAW_DNG_ONLY("RAW (DNG) Only", "Uncompressed digital negative")
}

/**
 * Volume hardware button action preferences.
 */
enum class VolumeButtonAction(val displayName: String) {
    VOLUME("Control system volume"),
    SHUTTER("Shutter / Start recording"),
    ZOOM("Control zoom level")
}

/**
 * Immutable camera settings state for Pixel Camera UI and engine integration.
 */
data class CameraPreferences(
    val themePreference: AppThemePreference = AppThemePreference.SYSTEM,
    val dynamicColor: Boolean = true,
    val flashMode: FlashMode = FlashMode.AUTO,
    val timerMode: TimerMode = TimerMode.OFF,
    val gridMode: GridMode = GridMode.GRID_3X3,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3,
    val cameraSoundsEnabled: Boolean = true,
    val saveLocation: Boolean = false,
    val selfieMirror: Boolean = true,
    val volumeButtonAction: VolumeButtonAction = VolumeButtonAction.VOLUME,
    // Video quality & frame rate settings
    val videoResolution: VideoResolution = VideoResolution.FHD_1080P,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.FPS_30,
    val videoStabilization: VideoStabilizationMode = VideoStabilizationMode.STANDARD,
    // HD mode preferences
    val hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE,
    // Mode-specific options
    val portraitRetouch: PortraitRetouch = PortraitRetouch.NATURAL,
    val portraitBlurLevel: PortraitBlurLevel = PortraitBlurLevel.MEDIUM,
    val portraitBlurStrength: Float = portraitBlurLevel.blurStrength,
    val nightSightExposure: NightSightExposure = NightSightExposure.AUTO,
    val timeLapseSpeed: TimeLapseSpeed = TimeLapseSpeed.SPEED_10X,
    val slowMoSpeed: SlowMoSpeed = SlowMoSpeed.SPEED_1_4X,
    // Pro mode manual preferences
    val proIso: String = "AUTO",
    val proShutterSpeed: String = "AUTO",
    val proFocusMode: ProFocusMode = ProFocusMode.AUTO,
    val proFocusDistance: Float = 0.0f,
    val proWhiteBalance: String = "AUTO",
    val proEv: Float = 0.0f,
    val proRawFormat: ProRawFormat = ProRawFormat.JPEG_ONLY,
    val proHistogramEnabled: Boolean = false,
    val proFocusPeakingEnabled: Boolean = false,
    val proZoomRatio: Float = 1.0f,
    // Customizable mode selector layout
    val visibleModeNames: List<String> = DEFAULT_VISIBLE_MODE_NAMES,
    val hiddenModeNames: List<String> = DEFAULT_HIDDEN_MODE_NAMES
) {
    val visibleModes: List<com.example.core.model.CameraMode>
        get() = visibleModeNames.mapNotNull { name ->
            runCatching { com.example.core.model.CameraMode.valueOf(name) }.getOrNull()
        }.ifEmpty { listOf(com.example.core.model.CameraMode.PHOTO) }

    val hiddenModes: List<com.example.core.model.CameraMode>
        get() = hiddenModeNames.mapNotNull { name ->
            runCatching { com.example.core.model.CameraMode.valueOf(name) }.getOrNull()
        }

    companion object {
        val DEFAULT_VISIBLE_MODE_NAMES = listOf("NIGHT_SIGHT", "PORTRAIT", "PHOTO", "VIDEO", "HD", "PRO")
        val DEFAULT_HIDDEN_MODE_NAMES = listOf("CINEMATIC", "SLOW_MOTION", "TIME_LAPSE", "PANORAMA")
    }
}
