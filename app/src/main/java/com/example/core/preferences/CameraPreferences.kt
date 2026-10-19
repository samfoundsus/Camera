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
 * Astrophotography capture duration options.
 */
enum class AstroDuration(
    val displayName: String,
    val targetSeconds: Int,
    val targetFrames: Int
) {
    AUTO("Auto", 30, 6),
    SHORT_10S("10s", 10, 3),
    MEDIUM_30S("30s", 30, 6),
    LONG_1M("1 min", 60, 10),
    MAX_4M("4 min", 240, 16)
}

/**
 * Astrophotography focus mode options.
 */
enum class AstroFocusMode(val displayName: String) {
    AUTO("Auto"),
    INFINITY("Infinity (\u221e)"),
    MANUAL("Manual")
}

/**
 * Astrophotography capture strategy options.
 */
enum class AstroStrategy(val displayName: String) {
    AUTO("Auto Adaptive"),
    LONG_CAPTURE("Exposure Stacking")
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
 * HDR computational modes.
 */
enum class HdrMode(val displayName: String) {
    AUTO("HDR Auto"),
    ON("HDR On"),
    OFF("HDR Off")
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
 * Time lapse capture speed options (30 FPS output timeline).
 *
 * Capture interval math for 30 FPS output:
 * intervalSeconds = multiplier / 30.0f
 * - 30x   -> 1 frame every 1.0s   (30s real time = 1s playback)
 * - 60x   -> 1 frame every 2.0s   (60s real time = 1s playback)
 * - 120x  -> 1 frame every 4.0s   (120s real time = 1s playback)
 * - 300x  -> 1 frame every 10.0s  (300s real time = 1s playback)
 * - 600x  -> 1 frame every 20.0s  (600s real time = 1s playback)
 * - 1200x -> 1 frame every 40.0s  (1200s real time = 1s playback)
 * - 2400x -> 1 frame every 80.0s  (2400s real time = 1s playback)
 * - 6000x -> 1 frame every 200.0s (6000s real time = 1s playback)
 */
enum class TimeLapseSpeed(
    val multiplier: Int,
    val shortLabel: String,
    val displayName: String,
    val description: String,
    val intervalSeconds: Float
) {
    SPEED_30X(30, "30x", "30x", "Moving clouds & street traffic", 1.0f),
    SPEED_60X(60, "60x", "60x", "Sunsets & shadows", 2.0f),
    SPEED_120X(120, "120x", "120x", "Fast clouds & twilight", 4.0f),
    SPEED_300X(300, "300x", "300x", "Sun & moon arc", 10.0f),
    SPEED_600X(600, "600x", "600x", "Tides & slow sky motion", 20.0f),
    SPEED_1200X(1200, "1200x", "1200x", "Plant growth & long scenes", 40.0f),
    SPEED_2400X(2400, "2400x", "2400x", "Star trails & multi-hour sky", 80.0f),
    SPEED_6000X(6000, "6000x", "6000x", "Ultra-long duration capture", 200.0f);

    /** Capture interval in milliseconds for a 30 FPS time-lapse output timeline. */
    val captureIntervalMs: Long
        get() = (multiplier.toLong() * 1000L) / TARGET_OUTPUT_FPS.toLong()

    /** Computes the expected output playback duration in milliseconds for a given real recording duration. */
    fun expectedOutputDurationMs(realDurationMs: Long): Long {
        if (realDurationMs <= 0L) return 0L
        return (realDurationMs.toDouble() / multiplier.toDouble()).toLong()
    }

    companion object {
        const val TARGET_OUTPUT_FPS = 30

        fun fromStoredName(stored: String?): TimeLapseSpeed {
            if (stored.isNullOrBlank()) return SPEED_30X
            return entries.firstOrNull {
                it.name.equals(stored, ignoreCase = true) ||
                    it.shortLabel.equals(stored, ignoreCase = true)
            } ?: SPEED_30X
        }
    }
}

/**
 * Slow motion playback speed options.
 */
enum class SlowMoSpeed(val displayName: String, val expectedFps: Int, val slowDownFactor: Float) {
    SPEED_1_4X("1/4× (120 FPS)", 120, 4.0f),
    SPEED_1_8X("1/8× (240 FPS)", 240, 8.0f),
    SPEED_1_12X("1/12× (360 FPS)", 360, 12.0f),
    SPEED_1_16X("1/16× (480 FPS)", 480, 16.0f)
}

/**
 * Panorama sweep direction options.
 */
enum class PanoramaDirection(
    val displayName: String,
    val shortLabel: String,
    val arrowSymbol: String,
    val arrowDirectionName: String,
    val isHorizontal: Boolean,
    val sign: Int,
    val initialTrackOffset: Float
) {
    LEFT_TO_RIGHT("Left to Right", "Left → Right", "→", "RIGHT", true, 1, 0.0f),
    RIGHT_TO_LEFT("Right to Left", "Right → Left", "←", "LEFT", true, -1, 1.0f),
    TOP_TO_BOTTOM("Top to Bottom", "Top ↓ Bottom", "↓", "DOWN", false, 1, 0.0f),
    BOTTOM_TO_TOP("Bottom to Top", "Bottom ↑ Top", "↑", "UP", false, -1, 1.0f);

    companion object {
        fun fromStoredName(storedName: String?): PanoramaDirection {
            if (storedName.isNullOrBlank()) return LEFT_TO_RIGHT
            return entries.firstOrNull { it.name.equals(storedName, ignoreCase = true) } ?: LEFT_TO_RIGHT
        }
    }
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
 * Filter preset categories.
 */
enum class CameraFilterCategory(val displayName: String) {
    NATURAL("Natural"),
    CREATIVE("Creative")
}

/**
 * Selectable Pixel-inspired natural and creative color filter presets for photos and videos.
 */
enum class CameraFilterPreset(
    val id: String,
    val displayName: String,
    val category: CameraFilterCategory,
    val description: String
) {
    // A. Natural / Pixel-Inspired Presets
    ORIGINAL("original", "Original", CameraFilterCategory.NATURAL, "No additional filter effect"),
    NATURAL("natural", "Natural", CameraFilterCategory.NATURAL, "Subtle color and contrast refinement while preserving realistic colors"),
    WARM("warm", "Warm", CameraFilterCategory.NATURAL, "Gentle warm white balance and golden highlights"),
    COOL("cool", "Cool", CameraFilterCategory.NATURAL, "Restrained cool color balance"),
    RICH("rich", "Rich", CameraFilterCategory.NATURAL, "Slightly richer color and controlled contrast"),
    SOFT("soft", "Soft", CameraFilterCategory.NATURAL, "Gentle contrast and highlights"),
    PORTRAIT_NATURAL("portrait_natural", "Portrait Natural", CameraFilterCategory.NATURAL, "Restrained color refinement suitable for people"),
    MATTE("matte", "Matte", CameraFilterCategory.NATURAL, "Lifted shadows and a softer contrast curve"),

    // B. Creative Presets
    VIVID("vivid", "Vivid", CameraFilterCategory.CREATIVE, "Stronger but controlled saturation"),
    CINEMATIC("cinematic", "Cinematic", CameraFilterCategory.CREATIVE, "Cinematic contrast with carefully balanced shadows and highlights"),
    GOLDEN_HOUR("golden_hour", "Golden Hour", CameraFilterCategory.CREATIVE, "Warm, golden color treatment"),
    VINTAGE("vintage", "Vintage", CameraFilterCategory.CREATIVE, "Muted tones and a subtle film-like color curve"),
    CLASSIC_FILM("classic_film", "Classic Film", CameraFilterCategory.CREATIVE, "Restrained film-inspired colors and contrast"),
    MONO("mono", "Mono", CameraFilterCategory.CREATIVE, "Black and white"),
    NOIR("noir", "Noir", CameraFilterCategory.CREATIVE, "Dramatic monochrome contrast"),
    FADE("fade", "Fade", CameraFilterCategory.CREATIVE, "Low-contrast, faded aesthetic"),
    DRAMATIC("dramatic", "Dramatic", CameraFilterCategory.CREATIVE, "Stronger local-looking contrast using a lightweight global tone treatment"),
    MOODY("moody", "Moody", CameraFilterCategory.CREATIVE, "Darker, subdued colors with controlled highlights");

    val isOriginal: Boolean
        get() = this == ORIGINAL

    companion object {
        fun fromStoredName(stored: String?): CameraFilterPreset {
            if (stored.isNullOrBlank()) return ORIGINAL
            return entries.firstOrNull {
                it.name.equals(stored, ignoreCase = true) ||
                    it.id.equals(stored, ignoreCase = true) ||
                    it.displayName.equals(stored, ignoreCase = true)
            } ?: ORIGINAL
        }
    }
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
    // Shared photo & video filter preset
    val selectedFilter: CameraFilterPreset = CameraFilterPreset.ORIGINAL,
    // Video quality & frame rate settings
    val videoResolution: VideoResolution = VideoResolution.FHD_1080P,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.FPS_30,
    val videoStabilization: VideoStabilizationMode = VideoStabilizationMode.STANDARD,
    // HD mode preferences
    val hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE,
    // HDR computational mode
    val hdrMode: HdrMode = HdrMode.AUTO,
    // Mode-specific options
    val portraitRetouch: PortraitRetouch = PortraitRetouch.NATURAL,
    val portraitBlurLevel: PortraitBlurLevel = PortraitBlurLevel.MEDIUM,
    val portraitBlurStrength: Float = portraitBlurLevel.blurStrength,
    val nightSightExposure: NightSightExposure = NightSightExposure.AUTO,
    val astroDuration: AstroDuration = AstroDuration.AUTO,
    val astroFocus: AstroFocusMode = AstroFocusMode.AUTO,
    val astroStrategy: AstroStrategy = AstroStrategy.AUTO,
    val astroRawEnabled: Boolean = false,
    val timeLapseSpeed: TimeLapseSpeed = TimeLapseSpeed.SPEED_30X,
    val slowMoSpeed: SlowMoSpeed = SlowMoSpeed.SPEED_1_4X,
    val panoramaDirection: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT,
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
        val DEFAULT_HIDDEN_MODE_NAMES = listOf("ASTRO", "SLOW_MOTION", "TIME_LAPSE", "PANORAMA")
    }
}
