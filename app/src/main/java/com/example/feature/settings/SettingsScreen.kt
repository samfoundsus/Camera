package com.example.feature.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.camera.CameraCapabilitiesRepository
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.DefaultCameraCapabilitiesRepository
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.preferences.AppThemePreference
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.FlashMode
import com.example.core.preferences.GridMode
import com.example.core.preferences.HdOutputOption
import com.example.core.preferences.HdrMode
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.preferences.ProFocusMode
import com.example.core.preferences.ProRawFormat
import com.example.core.preferences.SlowMoSpeed
import com.example.core.preferences.TimeLapseSpeed
import com.example.core.preferences.TimerMode
import com.example.core.preferences.VideoStabilizationMode
import com.example.ui.components.SettingsCard
import com.example.ui.components.SettingsCardDivider
import com.example.ui.components.SettingsRow
import com.example.ui.components.SettingsSwitchRow
import com.example.ui.components.SingleChoiceDialog

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.core.preferences.VolumeButtonAction

/**
 * Google Pixel-inspired Camera Settings screen with grouped rounded cards,
 * clear visual hierarchy, subtle Material You dynamic tint, and zero decorative SVG icons.
 * Features device-aware video quality, HD mode, and Pro mode RAW & manual settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: CameraPreferences,
    onThemeChanged: (AppThemePreference) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAspectRatioChanged: (AspectRatioMode) -> Unit,
    onGridChanged: (GridMode) -> Unit,
    onTimerChanged: (TimerMode) -> Unit,
    onFlashChanged: (FlashMode) -> Unit,
    onSoundsChanged: (Boolean) -> Unit,
    onLocationChanged: (Boolean) -> Unit,
    onMirrorChanged: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    capabilitiesRepository: CameraCapabilitiesRepository = remember { DefaultCameraCapabilitiesRepository() },
    onVideoResolutionChanged: (VideoResolution) -> Unit = {},
    onVideoFpsChanged: (VideoFrameRate) -> Unit = {},
    onVideoStabilizationChanged: (VideoStabilizationMode) -> Unit = {},
    onHdOptionChanged: (HdOutputOption) -> Unit = {},
    onHdrModeChanged: (HdrMode) -> Unit = {},
    onPortraitRetouchChanged: (PortraitRetouch) -> Unit = {},
    onPortraitBlurLevelChanged: (PortraitBlurLevel) -> Unit = {},
    onNightSightExposureChanged: (NightSightExposure) -> Unit = {},
    onTimeLapseSpeedChanged: (TimeLapseSpeed) -> Unit = {},
    onSlowMoSpeedChanged: (SlowMoSpeed) -> Unit = {},
    onVolumeActionChanged: (VolumeButtonAction) -> Unit = {},
    onProIsoChanged: (String) -> Unit = {},
    onProShutterChanged: (String) -> Unit = {},
    onProFocusModeChanged: (ProFocusMode) -> Unit = {},
    onProFocusDistanceChanged: (Float) -> Unit = {},
    onProWbChanged: (String) -> Unit = {},
    onProEvChanged: (Float) -> Unit = {},
    onProRawFormatChanged: (ProRawFormat) -> Unit = {},
    onProHistogramToggled: (Boolean) -> Unit = {},
    onProFocusPeakingToggled: (Boolean) -> Unit = {},
    onCustomModeLayoutChanged: (visible: List<String>, hidden: List<String>) -> Unit = { _, _ -> },
    onResetModeLayout: () -> Unit = {}
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val capabilitiesState by capabilitiesRepository.capabilitiesState.collectAsState()
    val activeLens by capabilitiesRepository.activeLens.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        onLocationChanged(granted)
    }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showAspectRatioDialog by remember { mutableStateOf(false) }
    var showGridDialog by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showFlashDialog by remember { mutableStateOf(false) }
    var showVideoResDialog by remember { mutableStateOf(false) }
    var showVideoFpsDialog by remember { mutableStateOf(false) }
    var showStabilizationDialog by remember { mutableStateOf(false) }
    var showHdOptionDialog by remember { mutableStateOf(false) }
    var showHdrModeDialog by remember { mutableStateOf(false) }
    var showPortraitRetouchDialog by remember { mutableStateOf(false) }
    var showPortraitBlurDialog by remember { mutableStateOf(false) }
    var showNightExposureDialog by remember { mutableStateOf(false) }
    var showTimeLapseSpeedDialog by remember { mutableStateOf(false) }
    var showSlowMoSpeedDialog by remember { mutableStateOf(false) }
    var showProRawDialog by remember { mutableStateOf(false) }
    var showProFocusDialog by remember { mutableStateOf(false) }
    var showReorderModesDialog by remember { mutableStateOf(false) }
    var showVolumeDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.material3.Text(
                        text = "Camera Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Navigate back to Camera"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // APPEARANCE SECTION
                SettingsSectionHeader("Appearance")

                SettingsCard {
                    SettingsRow(
                        title = "App Theme",
                        subtitle = "Choose light, dark, or system default",
                        trailingText = preferences.themePreference.displayName,
                        showDropdownIndicator = true,
                        onClick = { showThemeDialog = true },
                        testTag = "setting_theme_row"
                    )

                    SettingsCardDivider()

                    val supportsMonet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    SettingsSwitchRow(
                        title = "Material You Dynamic Color",
                        subtitle = if (supportsMonet) {
                            "Use wallpaper colors when available"
                        } else {
                            "Requires Android 12 or newer"
                        },
                        checked = preferences.dynamicColor && supportsMonet,
                        onCheckedChange = onDynamicColorChanged,
                        enabled = supportsMonet,
                        testTag = "setting_dynamic_color_switch"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Reorder Camera Modes",
                        subtitle = "Customize mode selector order and visibility",
                        trailingText = "${preferences.visibleModes.size} visible",
                        showDropdownIndicator = true,
                        onClick = { showReorderModesDialog = true },
                        testTag = "setting_reorder_modes_row"
                    )
                }

                // PHOTO & HD CAPTURE SECTION
                SettingsSectionHeader("Photo & HD Capture")

                SettingsCard {
                    SettingsRow(
                        title = "Photo Aspect Ratio",
                        subtitle = "Framing ratio for preview and captures",
                        trailingText = preferences.aspectRatioMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showAspectRatioDialog = true },
                        testTag = "setting_aspect_ratio_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Grid Lines",
                        subtitle = "Framing reference lines on viewfinder",
                        trailingText = preferences.gridMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showGridDialog = true },
                        testTag = "setting_grid_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Timer Duration",
                        subtitle = "Countdown before shutter release",
                        trailingText = preferences.timerMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showTimerDialog = true },
                        testTag = "setting_timer_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "HD Mode Output",
                        subtitle = "Target full native sensor resolution without upscaling",
                        trailingText = preferences.hdOutputOption.displayName,
                        showDropdownIndicator = true,
                        onClick = { showHdOptionDialog = true },
                        testTag = "setting_hd_output_row"
                    )
                }

                // PRO MODE & RAW CAPTURE
                SettingsSectionHeader("Pro Mode & RAW")

                SettingsCard {
                    SettingsRow(
                        title = "RAW / DNG Output",
                        subtitle = "Save uncompressed DNG negatives alongside JPEG",
                        trailingText = preferences.proRawFormat.displayName,
                        showDropdownIndicator = true,
                        onClick = { showProRawDialog = true },
                        testTag = "setting_pro_raw_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Manual Focus Mode",
                        subtitle = "Auto Focus vs Manual Distance slider",
                        trailingText = preferences.proFocusMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showProFocusDialog = true },
                        testTag = "setting_pro_focus_row"
                    )

                    SettingsCardDivider()

                    SettingsSwitchRow(
                        title = "Live Viewfinder Histogram",
                        subtitle = "Overlay real-time luminance curve on Pro preview",
                        checked = preferences.proHistogramEnabled,
                        onCheckedChange = onProHistogramToggled,
                        testTag = "setting_pro_histogram_switch"
                    )

                    SettingsCardDivider()

                    SettingsSwitchRow(
                        title = "Focus Peaking Highlights",
                        subtitle = "Highlight high-contrast focus edges in viewfinder",
                        checked = preferences.proFocusPeakingEnabled,
                        onCheckedChange = onProFocusPeakingToggled,
                        testTag = "setting_pro_peaking_switch"
                    )
                }

                // VIDEO QUALITY & MOTION SECTION
                SettingsSectionHeader("Video & Motion")

                val verifiedLensCaps = (capabilitiesState as? CameraCapabilitiesState.Verified)?.currentLensCapabilities
                val effectiveVideoRes = verifiedLensCaps?.resolveSupportedResolution(preferences.videoResolution)
                    ?: preferences.videoResolution
                val effectiveVideoFps = verifiedLensCaps?.resolveSupportedFrameRate(effectiveVideoRes, preferences.videoFrameRate)
                    ?: preferences.videoFrameRate
                val effectiveVideoStab = verifiedLensCaps?.resolveSupportedStabilization(
                    effectiveVideoRes,
                    effectiveVideoFps,
                    preferences.videoStabilization
                ) ?: preferences.videoStabilization
                val effectiveFlash = verifiedLensCaps?.resolveSupportedFlashMode(preferences.flashMode)
                    ?: preferences.flashMode

                SettingsCard {
                    SettingsRow(
                        title = "Video Resolution",
                        subtitle = "720p, 1080p, 2K/1440p, or 4K UHD when supported",
                        trailingText = effectiveVideoRes.displayName,
                        showDropdownIndicator = true,
                        onClick = { showVideoResDialog = true },
                        testTag = "setting_video_resolution_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Video Frame Rate",
                        subtitle = "Framerate for recording",
                        trailingText = effectiveVideoFps.displayName,
                        showDropdownIndicator = true,
                        onClick = { showVideoFpsDialog = true },
                        testTag = "setting_video_fps_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Video Stabilization",
                        subtitle = "Standard EIS / Active stabilization",
                        trailingText = effectiveVideoStab.displayName,
                        showDropdownIndicator = true,
                        onClick = { showStabilizationDialog = true },
                        testTag = "setting_video_stabilization_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Slow Motion Speed",
                        subtitle = "High-frame-rate playback factor",
                        trailingText = preferences.slowMoSpeed.displayName,
                        showDropdownIndicator = true,
                        onClick = { showSlowMoSpeedDialog = true },
                        testTag = "setting_slow_mo_speed_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Flash / Torch Mode",
                        subtitle = "Illumination mode for photo and video",
                        trailingText = effectiveFlash.displayName,
                        showDropdownIndicator = true,
                        onClick = { showFlashDialog = true },
                        testTag = "setting_flash_row"
                    )
                }

                // MODE PREFERENCES
                SettingsSectionHeader("Mode Preferences")

                SettingsCard {
                    SettingsRow(
                        title = "HDR+ Computational Mode",
                        subtitle = "Multi-frame exposure bracket fusion for dynamic range",
                        trailingText = preferences.hdrMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showHdrModeDialog = true },
                        testTag = "setting_hdr_mode_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Portrait Background Blur",
                        subtitle = "Subject segmentation background bokeh intensity",
                        trailingText = "${preferences.portraitBlurLevel.displayName} (${preferences.portraitBlurLevel.apertureLabel})",
                        showDropdownIndicator = true,
                        onClick = { showPortraitBlurDialog = true },
                        testTag = "setting_portrait_blur_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Portrait Retouching",
                        subtitle = "Facial smoothing and portrait enhancements",
                        trailingText = preferences.portraitRetouch.displayName,
                        showDropdownIndicator = true,
                        onClick = { showPortraitRetouchDialog = true },
                        testTag = "setting_portrait_retouch_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Night Sight Exposure",
                        subtitle = "Low-light capture exposure duration",
                        trailingText = preferences.nightSightExposure.displayName,
                        showDropdownIndicator = true,
                        onClick = { showNightExposureDialog = true },
                        testTag = "setting_night_sight_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Time Lapse Speed",
                        subtitle = "Interval speed for time-lapse recording",
                        trailingText = preferences.timeLapseSpeed.displayName,
                        showDropdownIndicator = true,
                        onClick = { showTimeLapseSpeedDialog = true },
                        testTag = "setting_time_lapse_speed_row"
                    )
                }

                // GESTURES AND CONTROLS SECTION
                SettingsSectionHeader("Gestures and Controls")

                SettingsCard {
                    SettingsRow(
                        title = "Volume Key Action",
                        subtitle = "Action when pressing physical volume buttons",
                        trailingText = preferences.volumeButtonAction.displayName,
                        showDropdownIndicator = true,
                        onClick = { showVolumeDialog = true },
                        testTag = "setting_volume_action_row"
                    )

                    SettingsCardDivider()

                    SettingsSwitchRow(
                        title = "Camera Sounds",
                        subtitle = "Play shutter and recording sounds",
                        checked = preferences.cameraSoundsEnabled,
                        onCheckedChange = onSoundsChanged,
                        testTag = "setting_sounds_switch"
                    )

                    SettingsCardDivider()

                    SettingsSwitchRow(
                        title = "Save Location",
                        subtitle = "Include GPS location tags in media EXIF",
                        checked = preferences.saveLocation,
                        onCheckedChange = { checked ->
                            if (checked) {
                                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (hasFine || hasCoarse) {
                                    onLocationChanged(true)
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                    )
                                }
                            } else {
                                onLocationChanged(false)
                            }
                        },
                        testTag = "setting_location_switch"
                    )

                    SettingsCardDivider()

                    SettingsSwitchRow(
                        title = "Mirror Front Camera",
                        subtitle = "Save selfies as viewed in the preview",
                        checked = preferences.selfieMirror,
                        onCheckedChange = onMirrorChanged,
                        testTag = "setting_mirror_switch"
                    )
                }

                // HARDWARE CAPABILITY ARCHITECTURE
                SettingsSectionHeader("Hardware Capabilities")

                SettingsCard {
                    SettingsRow(
                        title = "Active Camera Lens",
                        subtitle = "Target lens for photo and video streams",
                        trailingText = activeLens.displayName,
                        onClick = {}
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Hardware Discovery Status",
                        subtitle = when (capabilitiesState) {
                            is CameraCapabilitiesState.PendingDiscovery -> {
                                "Pending camera engine bind (Phase 2). No artificial capabilities fabricated."
                            }
                            is CameraCapabilitiesState.Verified -> {
                                "Verified device hardware streams active."
                            }
                        },
                        trailingText = when (capabilitiesState) {
                            is CameraCapabilitiesState.PendingDiscovery -> "Pending"
                            is CameraCapabilitiesState.Verified -> "Verified"
                        },
                        onClick = {}
                    )
                }

                // ABOUT SECTION
                SettingsSectionHeader("About")

                SettingsCard {
                    SettingsRow(
                        title = "Pixel Camera",
                        subtitle = "Phase 1: UI, Monet Theme, 10 Modes & Pro Camera Engine",
                        trailingText = "v1.0.0",
                        onClick = {}
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Architecture",
                        subtitle = "Kotlin • Jetpack Compose • Material 3 • Clean MVI",
                        trailingText = "API 29+",
                        onClick = {}
                    )
                }
            }
        }
    }

    // Dialogs for selections
    if (showThemeDialog) {
        SingleChoiceDialog(
            title = "Choose Theme",
            options = AppThemePreference.entries,
            selectedOption = preferences.themePreference,
            onOptionSelected = onThemeChanged,
            onDismissRequest = { showThemeDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showAspectRatioDialog) {
        SingleChoiceDialog(
            title = "Aspect Ratio",
            options = AspectRatioMode.entries,
            selectedOption = preferences.aspectRatioMode,
            onOptionSelected = onAspectRatioChanged,
            onDismissRequest = { showAspectRatioDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showGridDialog) {
        SingleChoiceDialog(
            title = "Grid Overlay",
            options = GridMode.entries,
            selectedOption = preferences.gridMode,
            onOptionSelected = onGridChanged,
            onDismissRequest = { showGridDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showTimerDialog) {
        SingleChoiceDialog(
            title = "Shutter Timer",
            options = TimerMode.entries,
            selectedOption = preferences.timerMode,
            onOptionSelected = onTimerChanged,
            onDismissRequest = { showTimerDialog = false },
            optionLabel = { it.displayName }
        )
    }

    val verifiedCaps = (capabilitiesState as? CameraCapabilitiesState.Verified)?.currentLensCapabilities

    if (showFlashDialog) {
        val flashOptions = if (verifiedCaps != null && !verifiedCaps.isTorchSupported) {
            listOf(FlashMode.OFF)
        } else {
            FlashMode.entries
        }
        SingleChoiceDialog(
            title = "Flash Setting",
            options = flashOptions,
            selectedOption = if (preferences.flashMode in flashOptions) preferences.flashMode else FlashMode.OFF,
            onOptionSelected = onFlashChanged,
            onDismissRequest = { showFlashDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showVideoResDialog) {
        val resOptions = verifiedCaps?.getSupportedVideoModeResolutions()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P)
        SingleChoiceDialog(
            title = "Video Resolution",
            options = resOptions,
            selectedOption = verifiedCaps?.resolveSupportedResolution(preferences.videoResolution) ?: preferences.videoResolution,
            onOptionSelected = { newRes ->
                onVideoResolutionChanged(newRes)
                if (verifiedCaps != null) {
                    val resolvedFps = verifiedCaps.resolveSupportedFrameRate(newRes, preferences.videoFrameRate)
                    if (resolvedFps != preferences.videoFrameRate) {
                        onVideoFpsChanged(resolvedFps)
                    }
                    val resolvedStab = verifiedCaps.resolveSupportedStabilization(newRes, resolvedFps, preferences.videoStabilization)
                    if (resolvedStab != preferences.videoStabilization) {
                        onVideoStabilizationChanged(resolvedStab)
                    }
                }
            },
            onDismissRequest = { showVideoResDialog = false },
            optionLabel = { "${it.displayName} (${it.label})" }
        )
    }

    if (showVideoFpsDialog) {
        val effectiveRes = verifiedCaps?.resolveSupportedResolution(preferences.videoResolution) ?: preferences.videoResolution
        val fpsOptions = verifiedCaps?.getSupportedVideoModeFrameRates(effectiveRes)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(VideoFrameRate.FPS_30)
        SingleChoiceDialog(
            title = "Video Frame Rate",
            options = fpsOptions,
            selectedOption = verifiedCaps?.resolveSupportedFrameRate(effectiveRes, preferences.videoFrameRate) ?: preferences.videoFrameRate,
            onOptionSelected = { newFps ->
                onVideoFpsChanged(newFps)
                if (verifiedCaps != null) {
                    val resolvedStab = verifiedCaps.resolveSupportedStabilization(effectiveRes, newFps, preferences.videoStabilization)
                    if (resolvedStab != preferences.videoStabilization) {
                        onVideoStabilizationChanged(resolvedStab)
                    }
                }
            },
            onDismissRequest = { showVideoFpsDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showStabilizationDialog) {
        val effectiveRes = verifiedCaps?.resolveSupportedResolution(preferences.videoResolution) ?: preferences.videoResolution
        val effectiveFps = verifiedCaps?.resolveSupportedFrameRate(effectiveRes, preferences.videoFrameRate) ?: preferences.videoFrameRate
        val stabOptions = verifiedCaps?.getSupportedStabilizationModes(effectiveRes, effectiveFps)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD)
        SingleChoiceDialog(
            title = "Video Stabilization",
            options = stabOptions,
            selectedOption = verifiedCaps?.resolveSupportedStabilization(effectiveRes, effectiveFps, preferences.videoStabilization) ?: preferences.videoStabilization,
            onOptionSelected = onVideoStabilizationChanged,
            onDismissRequest = { showStabilizationDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showHdOptionDialog) {
        SingleChoiceDialog(
            title = "HD Still Capture Output",
            options = HdOutputOption.entries,
            selectedOption = preferences.hdOutputOption,
            onOptionSelected = onHdOptionChanged,
            onDismissRequest = { showHdOptionDialog = false },
            optionLabel = { "${it.displayName} — ${it.subtitle}" }
        )
    }

    if (showHdrModeDialog) {
        SingleChoiceDialog(
            title = "HDR+ Computational Mode",
            options = HdrMode.entries,
            selectedOption = preferences.hdrMode,
            onOptionSelected = onHdrModeChanged,
            onDismissRequest = { showHdrModeDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showProRawDialog) {
        SingleChoiceDialog(
            title = "RAW / DNG Format",
            options = ProRawFormat.entries,
            selectedOption = preferences.proRawFormat,
            onOptionSelected = onProRawFormatChanged,
            onDismissRequest = { showProRawDialog = false },
            optionLabel = { "${it.displayName} — ${it.description}" }
        )
    }

    if (showProFocusDialog) {
        SingleChoiceDialog(
            title = "Manual Focus Mode",
            options = ProFocusMode.entries,
            selectedOption = preferences.proFocusMode,
            onOptionSelected = onProFocusModeChanged,
            onDismissRequest = { showProFocusDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showPortraitBlurDialog) {
        SingleChoiceDialog(
            title = "Portrait Background Blur",
            options = PortraitBlurLevel.entries,
            selectedOption = preferences.portraitBlurLevel,
            onOptionSelected = onPortraitBlurLevelChanged,
            onDismissRequest = { showPortraitBlurDialog = false },
            optionLabel = { "${it.displayName} (${it.apertureLabel})" }
        )
    }

    if (showPortraitRetouchDialog) {
        SingleChoiceDialog(
            title = "Portrait Retouching",
            options = PortraitRetouch.entries,
            selectedOption = preferences.portraitRetouch,
            onOptionSelected = onPortraitRetouchChanged,
            onDismissRequest = { showPortraitRetouchDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showNightExposureDialog) {
        SingleChoiceDialog(
            title = "Night Sight Exposure",
            options = NightSightExposure.entries,
            selectedOption = preferences.nightSightExposure,
            onOptionSelected = onNightSightExposureChanged,
            onDismissRequest = { showNightExposureDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showTimeLapseSpeedDialog) {
        SingleChoiceDialog(
            title = "Time Lapse Speed",
            options = TimeLapseSpeed.entries,
            selectedOption = preferences.timeLapseSpeed,
            onOptionSelected = onTimeLapseSpeedChanged,
            onDismissRequest = { showTimeLapseSpeedDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showSlowMoSpeedDialog) {
        SingleChoiceDialog(
            title = "Slow Motion Speed",
            options = SlowMoSpeed.entries,
            selectedOption = preferences.slowMoSpeed,
            onOptionSelected = onSlowMoSpeedChanged,
            onDismissRequest = { showSlowMoSpeedDialog = false },
            optionLabel = { it.displayName }
        )
    }

    if (showVolumeDialog) {
        SingleChoiceDialog(
            title = "Volume Key Action",
            options = VolumeButtonAction.entries,
            selectedOption = preferences.volumeButtonAction,
            onOptionSelected = onVolumeActionChanged,
            onDismissRequest = { showVolumeDialog = false },
            optionLabel = { "${it.name} (${it.displayName})" }
        )
    }

    if (showReorderModesDialog) {
        com.example.ui.components.ReorderCameraModesDialog(
            visibleModeNames = preferences.visibleModeNames,
            hiddenModeNames = preferences.hiddenModeNames,
            onSaveLayout = onCustomModeLayoutChanged,
            onResetDefault = onResetModeLayout,
            onDismissRequest = { showReorderModesDialog = false }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    androidx.compose.material3.Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 4.dp)
    )
}
