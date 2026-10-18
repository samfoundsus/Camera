package com.example.core.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadInitialPreferences())
    val preferences: StateFlow<CameraPreferences> = _preferences.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _preferences.value = loadInitialPreferences()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun loadInitialPreferences(): CameraPreferences {
        val themePref = try {
            AppThemePreference.valueOf(
                prefs.getString(KEY_THEME, AppThemePreference.SYSTEM.name) ?: AppThemePreference.SYSTEM.name
            )
        } catch (_: Exception) {
            AppThemePreference.SYSTEM
        }

        val flash = try {
            FlashMode.valueOf(
                prefs.getString(KEY_FLASH, FlashMode.AUTO.name) ?: FlashMode.AUTO.name
            )
        } catch (_: Exception) {
            FlashMode.AUTO
        }

        val timer = try {
            TimerMode.valueOf(
                prefs.getString(KEY_TIMER, TimerMode.OFF.name) ?: TimerMode.OFF.name
            )
        } catch (_: Exception) {
            TimerMode.OFF
        }

        val grid = try {
            GridMode.valueOf(
                prefs.getString(KEY_GRID, GridMode.GRID_3X3.name) ?: GridMode.GRID_3X3.name
            )
        } catch (_: Exception) {
            GridMode.GRID_3X3
        }

        val ratio = try {
            AspectRatioMode.valueOf(
                prefs.getString(KEY_RATIO, AspectRatioMode.RATIO_4_3.name) ?: AspectRatioMode.RATIO_4_3.name
            )
        } catch (_: Exception) {
            prefs.edit().putString(KEY_RATIO, AspectRatioMode.RATIO_4_3.name).apply()
            AspectRatioMode.RATIO_4_3
        }

        val videoRes = try {
            VideoResolution.valueOf(
                prefs.getString(KEY_VIDEO_RES, VideoResolution.FHD_1080P.name) ?: VideoResolution.FHD_1080P.name
            )
        } catch (_: Exception) {
            VideoResolution.FHD_1080P
        }

        val videoFps = try {
            VideoFrameRate.valueOf(
                prefs.getString(KEY_VIDEO_FPS, VideoFrameRate.FPS_30.name) ?: VideoFrameRate.FPS_30.name
            )
        } catch (_: Exception) {
            VideoFrameRate.FPS_30
        }

        val videoStab = try {
            VideoStabilizationMode.valueOf(
                prefs.getString(KEY_VIDEO_STAB, VideoStabilizationMode.STANDARD.name) ?: VideoStabilizationMode.STANDARD.name
            )
        } catch (_: Exception) {
            VideoStabilizationMode.STANDARD
        }

        val hdOption = try {
            HdOutputOption.valueOf(
                prefs.getString(KEY_HD_OPTION, HdOutputOption.MAX_NATIVE.name) ?: HdOutputOption.MAX_NATIVE.name
            )
        } catch (_: Exception) {
            HdOutputOption.MAX_NATIVE
        }

        val portraitRetouch = try {
            PortraitRetouch.valueOf(
                prefs.getString(KEY_PORTRAIT_RETOUCH, PortraitRetouch.NATURAL.name) ?: PortraitRetouch.NATURAL.name
            )
        } catch (_: Exception) {
            PortraitRetouch.NATURAL
        }

        val portraitBlurLevel = try {
            PortraitBlurLevel.valueOf(
                prefs.getString(KEY_PORTRAIT_BLUR_LEVEL, PortraitBlurLevel.MEDIUM.name) ?: PortraitBlurLevel.MEDIUM.name
            )
        } catch (_: Exception) {
            PortraitBlurLevel.MEDIUM
        }

        val portraitBlurStrength = if (prefs.contains(KEY_PORTRAIT_BLUR_STRENGTH)) {
            PortraitBlurLevel.normalizeBlurStrength(
                prefs.getFloat(KEY_PORTRAIT_BLUR_STRENGTH, portraitBlurLevel.blurStrength)
            )
        } else {
            portraitBlurLevel.blurStrength
        }

        val nightExposure = try {
            NightSightExposure.valueOf(
                prefs.getString(KEY_NIGHT_EXPOSURE, NightSightExposure.AUTO.name) ?: NightSightExposure.AUTO.name
            )
        } catch (_: Exception) {
            NightSightExposure.AUTO
        }

        val timeLapse = try {
            TimeLapseSpeed.valueOf(
                prefs.getString(KEY_TIME_LAPSE_SPEED, TimeLapseSpeed.SPEED_10X.name) ?: TimeLapseSpeed.SPEED_10X.name
            )
        } catch (_: Exception) {
            TimeLapseSpeed.SPEED_10X
        }

        val slowMo = try {
            SlowMoSpeed.valueOf(
                prefs.getString(KEY_SLOW_MO_SPEED, SlowMoSpeed.SPEED_1_4X.name) ?: SlowMoSpeed.SPEED_1_4X.name
            )
        } catch (_: Exception) {
            SlowMoSpeed.SPEED_1_4X
        }

        val proFocusMode = try {
            ProFocusMode.valueOf(
                prefs.getString(KEY_PRO_FOCUS_MODE, ProFocusMode.AUTO.name) ?: ProFocusMode.AUTO.name
            )
        } catch (_: Exception) {
            ProFocusMode.AUTO
        }

        val proRawFormat = try {
            ProRawFormat.valueOf(
                prefs.getString(KEY_PRO_RAW_FORMAT, ProRawFormat.JPEG_ONLY.name) ?: ProRawFormat.JPEG_ONLY.name
            )
        } catch (_: Exception) {
            ProRawFormat.JPEG_ONLY
        }

        val proIso = prefs.getString(KEY_PRO_ISO, "AUTO") ?: "AUTO"
        val proShutter = prefs.getString(KEY_PRO_SHUTTER, "AUTO") ?: "AUTO"
        val proFocusDist = prefs.getFloat(KEY_PRO_FOCUS_DIST, 0.0f)
        val proWb = prefs.getString(KEY_PRO_WB, "AUTO") ?: "AUTO"
        val proEv = prefs.getFloat(KEY_PRO_EV, 0.0f)
        val proHistogram = prefs.getBoolean(KEY_PRO_HISTOGRAM, false)
        val proFocusPeaking = prefs.getBoolean(KEY_PRO_PEAKING, false)
        val proZoom = prefs.getFloat(KEY_PRO_ZOOM, 1.0f)

        val dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        val sounds = prefs.getBoolean(KEY_CAMERA_SOUNDS, true)
        val location = prefs.getBoolean(KEY_SAVE_LOCATION, false)
        val mirror = prefs.getBoolean(KEY_SELFIE_MIRROR, true)

        val volumeAction = try {
            VolumeButtonAction.valueOf(
                prefs.getString(KEY_VOLUME_ACTION, VolumeButtonAction.VOLUME.name) ?: VolumeButtonAction.VOLUME.name
            )
        } catch (_: Exception) {
            VolumeButtonAction.VOLUME
        }

        val visibleStr = prefs.getString(KEY_VISIBLE_MODES, CameraPreferences.DEFAULT_VISIBLE_MODE_NAMES.joinToString(","))
        val hiddenStr = prefs.getString(KEY_HIDDEN_MODES, CameraPreferences.DEFAULT_HIDDEN_MODE_NAMES.joinToString(","))
        val visibleList = visibleStr?.split(",")?.filter { it.isNotBlank() }
            ?.ifEmpty { CameraPreferences.DEFAULT_VISIBLE_MODE_NAMES }
            ?: CameraPreferences.DEFAULT_VISIBLE_MODE_NAMES
        val hiddenList = hiddenStr?.split(",")?.filter { it.isNotBlank() }
            ?: CameraPreferences.DEFAULT_HIDDEN_MODE_NAMES

        return CameraPreferences(
            themePreference = themePref,
            dynamicColor = dynamicColor,
            flashMode = flash,
            timerMode = timer,
            gridMode = grid,
            aspectRatioMode = ratio,
            cameraSoundsEnabled = sounds,
            saveLocation = location,
            selfieMirror = mirror,
            volumeButtonAction = volumeAction,
            videoResolution = videoRes,
            videoFrameRate = videoFps,
            videoStabilization = videoStab,
            hdOutputOption = hdOption,
            portraitRetouch = portraitRetouch,
            portraitBlurLevel = portraitBlurLevel,
            portraitBlurStrength = portraitBlurStrength,
            nightSightExposure = nightExposure,
            timeLapseSpeed = timeLapse,
            slowMoSpeed = slowMo,
            proIso = proIso,
            proShutterSpeed = proShutter,
            proFocusMode = proFocusMode,
            proFocusDistance = proFocusDist,
            proWhiteBalance = proWb,
            proEv = proEv,
            proRawFormat = proRawFormat,
            proHistogramEnabled = proHistogram,
            proFocusPeakingEnabled = proFocusPeaking,
            proZoomRatio = proZoom,
            visibleModeNames = visibleList,
            hiddenModeNames = hiddenList
        )
    }

    fun setThemePreference(preference: AppThemePreference) {
        prefs.edit().putString(KEY_THEME, preference.name).apply()
        _preferences.value = _preferences.value.copy(themePreference = preference)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _preferences.value = _preferences.value.copy(dynamicColor = enabled)
    }

    fun setFlashMode(flashMode: FlashMode) {
        prefs.edit().putString(KEY_FLASH, flashMode.name).apply()
        _preferences.value = _preferences.value.copy(flashMode = flashMode)
    }

    fun cycleFlashMode() {
        val next = when (_preferences.value.flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
        }
        setFlashMode(next)
    }

    fun setTimerMode(timerMode: TimerMode) {
        prefs.edit().putString(KEY_TIMER, timerMode.name).apply()
        _preferences.value = _preferences.value.copy(timerMode = timerMode)
    }

    fun setGridMode(gridMode: GridMode) {
        prefs.edit().putString(KEY_GRID, gridMode.name).apply()
        _preferences.value = _preferences.value.copy(gridMode = gridMode)
    }

    fun setAspectRatioMode(ratioMode: AspectRatioMode) {
        prefs.edit().putString(KEY_RATIO, ratioMode.name).apply()
        _preferences.value = _preferences.value.copy(aspectRatioMode = ratioMode)
    }

    fun setCameraSounds(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAMERA_SOUNDS, enabled).apply()
        _preferences.value = _preferences.value.copy(cameraSoundsEnabled = enabled)
    }

    fun setSaveLocation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_LOCATION, enabled).apply()
        _preferences.value = _preferences.value.copy(saveLocation = enabled)
    }

    fun setSelfieMirror(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SELFIE_MIRROR, enabled).apply()
        _preferences.value = _preferences.value.copy(selfieMirror = enabled)
    }

    fun setVolumeButtonAction(action: VolumeButtonAction) {
        prefs.edit().putString(KEY_VOLUME_ACTION, action.name).apply()
        _preferences.value = _preferences.value.copy(volumeButtonAction = action)
    }

    fun setVideoResolution(resolution: VideoResolution) {
        prefs.edit().putString(KEY_VIDEO_RES, resolution.name).apply()
        _preferences.value = _preferences.value.copy(videoResolution = resolution)
    }

    fun setVideoFrameRate(fps: VideoFrameRate) {
        prefs.edit().putString(KEY_VIDEO_FPS, fps.name).apply()
        _preferences.value = _preferences.value.copy(videoFrameRate = fps)
    }

    fun setVideoStabilization(mode: VideoStabilizationMode) {
        prefs.edit().putString(KEY_VIDEO_STAB, mode.name).apply()
        _preferences.value = _preferences.value.copy(videoStabilization = mode)
    }

    fun setHdOutputOption(option: HdOutputOption) {
        prefs.edit().putString(KEY_HD_OPTION, option.name).apply()
        _preferences.value = _preferences.value.copy(hdOutputOption = option)
    }

    fun setPortraitRetouch(retouch: PortraitRetouch) {
        prefs.edit().putString(KEY_PORTRAIT_RETOUCH, retouch.name).apply()
        _preferences.value = _preferences.value.copy(portraitRetouch = retouch)
    }

    fun setPortraitBlurLevel(level: PortraitBlurLevel) {
        val strength = level.blurStrength
        prefs.edit()
            .putString(KEY_PORTRAIT_BLUR_LEVEL, level.name)
            .putFloat(KEY_PORTRAIT_BLUR_STRENGTH, strength)
            .apply()
        _preferences.value = _preferences.value.copy(
            portraitBlurLevel = level,
            portraitBlurStrength = strength
        )
    }

    fun setPortraitBlurStrength(strength: Float) {
        val normalized = PortraitBlurLevel.normalizeBlurStrength(strength)
        val mappedLevel = PortraitBlurLevel.fromBlurStrength(normalized)
        prefs.edit()
            .putFloat(KEY_PORTRAIT_BLUR_STRENGTH, normalized)
            .putString(KEY_PORTRAIT_BLUR_LEVEL, mappedLevel.name)
            .apply()
        _preferences.value = _preferences.value.copy(
            portraitBlurLevel = mappedLevel,
            portraitBlurStrength = normalized
        )
    }

    fun setNightSightExposure(exposure: NightSightExposure) {
        prefs.edit().putString(KEY_NIGHT_EXPOSURE, exposure.name).apply()
        _preferences.value = _preferences.value.copy(nightSightExposure = exposure)
    }

    fun setTimeLapseSpeed(speed: TimeLapseSpeed) {
        prefs.edit().putString(KEY_TIME_LAPSE_SPEED, speed.name).apply()
        _preferences.value = _preferences.value.copy(timeLapseSpeed = speed)
    }

    fun setSlowMoSpeed(speed: SlowMoSpeed) {
        prefs.edit().putString(KEY_SLOW_MO_SPEED, speed.name).apply()
        _preferences.value = _preferences.value.copy(slowMoSpeed = speed)
    }

    fun setProIso(iso: String) {
        prefs.edit().putString(KEY_PRO_ISO, iso).apply()
        _preferences.value = _preferences.value.copy(proIso = iso)
    }

    fun setProShutterSpeed(shutterSpeed: String) {
        prefs.edit().putString(KEY_PRO_SHUTTER, shutterSpeed).apply()
        _preferences.value = _preferences.value.copy(proShutterSpeed = shutterSpeed)
    }

    fun setProFocusMode(focusMode: ProFocusMode) {
        prefs.edit().putString(KEY_PRO_FOCUS_MODE, focusMode.name).apply()
        _preferences.value = _preferences.value.copy(proFocusMode = focusMode)
    }

    fun setProFocusDistance(distance: Float) {
        prefs.edit().putFloat(KEY_PRO_FOCUS_DIST, distance).apply()
        _preferences.value = _preferences.value.copy(proFocusDistance = distance)
    }

    fun setProWhiteBalance(wb: String) {
        prefs.edit().putString(KEY_PRO_WB, wb).apply()
        _preferences.value = _preferences.value.copy(proWhiteBalance = wb)
    }

    fun setProEv(ev: Float) {
        prefs.edit().putFloat(KEY_PRO_EV, ev).apply()
        _preferences.value = _preferences.value.copy(proEv = ev)
    }

    fun setProRawFormat(format: ProRawFormat) {
        prefs.edit().putString(KEY_PRO_RAW_FORMAT, format.name).apply()
        _preferences.value = _preferences.value.copy(proRawFormat = format)
    }

    fun setProHistogramEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRO_HISTOGRAM, enabled).apply()
        _preferences.value = _preferences.value.copy(proHistogramEnabled = enabled)
    }

    fun setProFocusPeakingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRO_PEAKING, enabled).apply()
        _preferences.value = _preferences.value.copy(proFocusPeakingEnabled = enabled)
    }

    fun setProZoomRatio(ratio: Float) {
        prefs.edit().putFloat(KEY_PRO_ZOOM, ratio).apply()
        _preferences.value = _preferences.value.copy(proZoomRatio = ratio)
    }

    fun setCustomModeLayout(visibleNames: List<String>, hiddenNames: List<String>) {
        val safeVisible = if (visibleNames.isEmpty()) listOf("PHOTO") else visibleNames
        _preferences.value = _preferences.value.copy(
            visibleModeNames = safeVisible,
            hiddenModeNames = hiddenNames
        )
        prefs.edit()
            .putString(KEY_VISIBLE_MODES, safeVisible.joinToString(","))
            .putString(KEY_HIDDEN_MODES, hiddenNames.joinToString(","))
            .apply()
    }

    fun resetModeLayoutToDefaults() {
        setCustomModeLayout(
            visibleNames = CameraPreferences.DEFAULT_VISIBLE_MODE_NAMES,
            hiddenNames = CameraPreferences.DEFAULT_HIDDEN_MODE_NAMES
        )
    }

    fun resetProSettings() {
        setProIso("AUTO")
        setProShutterSpeed("AUTO")
        setProFocusMode(ProFocusMode.AUTO)
        setProFocusDistance(0.0f)
        setProWhiteBalance("AUTO")
        setProEv(0.0f)
        setProRawFormat(ProRawFormat.JPEG_ONLY)
    }

    companion object {
        private const val PREFS_NAME = "pixel_camera_preferences"
        private const val KEY_THEME = "key_app_theme"
        private const val KEY_DYNAMIC_COLOR = "key_dynamic_color"
        private const val KEY_FLASH = "key_flash_mode"
        private const val KEY_TIMER = "key_timer_mode"
        private const val KEY_GRID = "key_grid_mode"
        private const val KEY_RATIO = "key_ratio_mode"
        private const val KEY_CAMERA_SOUNDS = "key_camera_sounds"
        private const val KEY_SAVE_LOCATION = "key_save_location"
        private const val KEY_SELFIE_MIRROR = "key_selfie_mirror"
        private const val KEY_VOLUME_ACTION = "key_volume_button_action"
        private const val KEY_VIDEO_RES = "key_video_resolution"
        private const val KEY_VIDEO_FPS = "key_video_fps"
        private const val KEY_VIDEO_STAB = "key_video_stabilization"
        private const val KEY_HD_OPTION = "key_hd_option"
        private const val KEY_PORTRAIT_RETOUCH = "key_portrait_retouch"
        private const val KEY_PORTRAIT_BLUR_LEVEL = "key_portrait_blur_level"
        private const val KEY_PORTRAIT_BLUR_STRENGTH = "key_portrait_blur_strength"
        private const val KEY_NIGHT_EXPOSURE = "key_night_exposure"
        private const val KEY_TIME_LAPSE_SPEED = "key_time_lapse_speed"
        private const val KEY_SLOW_MO_SPEED = "key_slow_mo_speed"
        private const val KEY_PRO_ISO = "key_pro_iso"
        private const val KEY_PRO_SHUTTER = "key_pro_shutter"
        private const val KEY_PRO_FOCUS_MODE = "key_pro_focus_mode"
        private const val KEY_PRO_FOCUS_DIST = "key_pro_focus_dist"
        private const val KEY_PRO_WB = "key_pro_wb"
        private const val KEY_PRO_EV = "key_pro_ev"
        private const val KEY_PRO_RAW_FORMAT = "key_pro_raw_format"
        private const val KEY_PRO_HISTOGRAM = "key_pro_histogram"
        private const val KEY_PRO_PEAKING = "key_pro_peaking"
        private const val KEY_PRO_ZOOM = "key_pro_zoom"
        private const val KEY_VISIBLE_MODES = "key_visible_modes"
        private const val KEY_HIDDEN_MODES = "key_hidden_modes"
    }
}
