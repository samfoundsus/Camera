package com.example.feature.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.example.core.camera.CameraHardwareDebugReport
import com.example.core.camera.collectCameraHardwareDebugReport
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
import com.example.core.preferences.PhotoCaptureSpeedMode
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
import com.example.ui.components.SettingsValueLayoutMode
import com.example.ui.components.SingleChoiceBottomSheet

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
    onPhotoCaptureSpeedModeChanged: (PhotoCaptureSpeedMode) -> Unit = {},
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
    onResetModeLayout: () -> Unit = {},
    onOpenReorderModes: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val capabilitiesState by capabilitiesRepository.capabilitiesState.collectAsState()
    val activeLens by capabilitiesRepository.activeLens.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        onLocationChanged(granted)
    }

    var showThemeSheet by remember { mutableStateOf(false) }
    var showAspectRatioSheet by remember { mutableStateOf(false) }
    var showGridSheet by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    var showFlashSheet by remember { mutableStateOf(false) }
    var showVideoResSheet by remember { mutableStateOf(false) }
    var showVideoFpsSheet by remember { mutableStateOf(false) }
    var showStabilizationSheet by remember { mutableStateOf(false) }
    var showHdOptionSheet by remember { mutableStateOf(false) }
    var showHdrModeSheet by remember { mutableStateOf(false) }
    var showCaptureSpeedModeSheet by remember { mutableStateOf(false) }
    var showPortraitRetouchSheet by remember { mutableStateOf(false) }
    var showPortraitBlurSheet by remember { mutableStateOf(false) }
    var showNightExposureSheet by remember { mutableStateOf(false) }
    var showTimeLapseSpeedSheet by remember { mutableStateOf(false) }
    var showSlowMoSpeedSheet by remember { mutableStateOf(false) }
    var showProRawSheet by remember { mutableStateOf(false) }
    var showProFocusSheet by remember { mutableStateOf(false) }
    var showInlineReorderModesScreen by remember { mutableStateOf(false) }
    var showVolumeSheet by remember { mutableStateOf(false) }
    var showHardwareDebugDialog by remember { mutableStateOf(false) }

    if (showInlineReorderModesScreen) {
        ReorderCameraModesScreen(
            visibleModeNames = preferences.visibleModeNames,
            hiddenModeNames = preferences.hiddenModeNames,
            onSaveLayout = onCustomModeLayoutChanged,
            onResetDefault = onResetModeLayout,
            onNavigateBack = { showInlineReorderModesScreen = false },
            modifier = modifier
        )
        return
    }

    BackHandler(onBack = onNavigateBack)

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
                        onClick = { showThemeSheet = true },
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
                        showNavigationArrow = true,
                        onClick = {
                            if (onOpenReorderModes != null) {
                                onOpenReorderModes()
                            } else {
                                showInlineReorderModesScreen = true
                            }
                        },
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
                        onClick = { showAspectRatioSheet = true },
                        testTag = "setting_aspect_ratio_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Grid Lines",
                        subtitle = "Framing reference lines on viewfinder",
                        trailingText = preferences.gridMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showGridSheet = true },
                        testTag = "setting_grid_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Timer Duration",
                        subtitle = "Countdown before shutter release",
                        trailingText = preferences.timerMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showTimerSheet = true },
                        testTag = "setting_timer_row"
                    )

                    SettingsCardDivider()

                    val hdVerifiedCaps = (capabilitiesState as? CameraCapabilitiesState.Verified)?.currentLensCapabilities

                    val hdSubtitle = hdVerifiedCaps?.hdRestrictedResolutionNote
                        ?: "Choose between the full native sensor resolution and standard binned output"

                    SettingsRow(
                        title = "HD Mode Output",
                        subtitle = hdSubtitle,
                        trailingText = if (hdVerifiedCaps != null) {
                            com.example.core.camera.CaptureResolutionResolver.formatHdModeButtonLabel(
                                lensCaps = hdVerifiedCaps,
                                aspectRatioMode = preferences.aspectRatioMode,
                                hdOutputOption = preferences.hdOutputOption
                            )
                        } else {
                            preferences.hdOutputOption.displayName
                        },
                        showDropdownIndicator = true,
                        onClick = { showHdOptionSheet = true },
                        testTag = "setting_hd_output_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Photo Capture Mode",
                        subtitle = preferences.photoCaptureSpeedMode.subtitle,
                        trailingText = preferences.photoCaptureSpeedMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showCaptureSpeedModeSheet = true },
                        testTag = "setting_capture_speed_mode_row"
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
                        onClick = { showProRawSheet = true },
                        testTag = "setting_pro_raw_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Manual Focus Mode",
                        subtitle = "Auto Focus vs Manual Distance slider",
                        trailingText = preferences.proFocusMode.displayName,
                        showDropdownIndicator = true,
                        onClick = { showProFocusSheet = true },
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
                        onClick = { showVideoResSheet = true },
                        testTag = "setting_video_resolution_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Video Frame Rate",
                        subtitle = "Framerate for recording",
                        trailingText = effectiveVideoFps.displayName,
                        showDropdownIndicator = true,
                        onClick = { showVideoFpsSheet = true },
                        testTag = "setting_video_fps_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Video Stabilization",
                        subtitle = "Standard EIS / Active stabilization",
                        trailingText = effectiveVideoStab.displayName,
                        showDropdownIndicator = true,
                        onClick = { showStabilizationSheet = true },
                        testTag = "setting_video_stabilization_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Slow Motion Speed",
                        subtitle = "High-frame-rate playback factor",
                        trailingText = preferences.slowMoSpeed.displayName,
                        showDropdownIndicator = true,
                        onClick = { showSlowMoSpeedSheet = true },
                        testTag = "setting_slow_mo_speed_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Flash / Torch Mode",
                        subtitle = "Illumination mode for photo and video",
                        trailingText = effectiveFlash.displayName,
                        showDropdownIndicator = true,
                        onClick = { showFlashSheet = true },
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
                        onClick = { showHdrModeSheet = true },
                        testTag = "setting_hdr_mode_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Portrait Background Blur",
                        subtitle = "Subject segmentation background bokeh intensity",
                        trailingText = "${preferences.portraitBlurLevel.displayName} (${preferences.portraitBlurLevel.apertureLabel})",
                        showDropdownIndicator = true,
                        onClick = { showPortraitBlurSheet = true },
                        testTag = "setting_portrait_blur_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Portrait Retouching",
                        subtitle = "Facial smoothing and portrait enhancements",
                        trailingText = preferences.portraitRetouch.displayName,
                        showDropdownIndicator = true,
                        onClick = { showPortraitRetouchSheet = true },
                        testTag = "setting_portrait_retouch_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Night Sight Exposure",
                        subtitle = "Low-light capture exposure duration",
                        trailingText = preferences.nightSightExposure.displayName,
                        showDropdownIndicator = true,
                        onClick = { showNightExposureSheet = true },
                        testTag = "setting_night_sight_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Time Lapse Speed",
                        subtitle = "Interval speed for time-lapse recording",
                        trailingText = preferences.timeLapseSpeed.displayName,
                        showDropdownIndicator = true,
                        onClick = { showTimeLapseSpeedSheet = true },
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
                        onClick = { showVolumeSheet = true },
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
                        trailingText = verifiedLensCaps?.resolvedHardwareLabel ?: activeLens.displayName,
                        onClick = {},
                        clickable = false,
                        layoutMode = SettingsValueLayoutMode.STACKED,
                        useSecondaryValueStyle = true,
                        testTag = "setting_active_camera_lens_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Hardware Discovery Status",
                        subtitle = when (capabilitiesState) {
                            is CameraCapabilitiesState.PendingDiscovery -> {
                                "Discovered Camera2 streams. Camera engine pending binding."
                            }
                            is CameraCapabilitiesState.Verified -> {
                                "Discovered and active hardware camera streams."
                            }
                        },
                        trailingText = when (capabilitiesState) {
                            is CameraCapabilitiesState.PendingDiscovery -> "Discovered"
                            is CameraCapabilitiesState.Verified -> "Active"
                        },
                        onClick = {},
                        clickable = false,
                        testTag = "setting_hardware_discovery_status_row"
                    )
                }

                // ABOUT SECTION
                SettingsSectionHeader("About")

                SettingsCard {
                    SettingsRow(
                        title = "Camera",
                        subtitle = "Hardware-Verified Computational Camera Engine",
                        trailingText = "v1.0.0",
                        onClick = {},
                        onLongClick = { showHardwareDebugDialog = true },
                        testTag = "settings_app_version_row"
                    )

                    SettingsCardDivider()

                    SettingsRow(
                        title = "Architecture",
                        subtitle = "Kotlin • Jetpack Compose • Material 3 • Camera2/CameraX",
                        trailingText = "API 29+",
                        onClick = {},
                        clickable = false,
                        testTag = "setting_architecture_row"
                    )
                }
            }
        }
    }

    // Material 3 Bottom Sheets for single-choice settings selections
    if (showThemeSheet) {
        SingleChoiceBottomSheet(
            title = "App Theme",
            options = AppThemePreference.entries,
            selectedOption = preferences.themePreference,
            onOptionSelected = onThemeChanged,
            onDismissRequest = { showThemeSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    AppThemePreference.SYSTEM -> "Follows your device light or dark theme"
                    AppThemePreference.LIGHT -> "Always uses the light interface"
                    AppThemePreference.DARK -> "Always uses the dark interface"
                }
            }
        )
    }

    if (showAspectRatioSheet) {
        SingleChoiceBottomSheet(
            title = "Photo Aspect Ratio",
            options = AspectRatioMode.entries,
            selectedOption = preferences.aspectRatioMode,
            onOptionSelected = onAspectRatioChanged,
            onDismissRequest = { showAspectRatioSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    AspectRatioMode.RATIO_4_3 -> "Full sensor frame for maximum photo resolution"
                    AspectRatioMode.RATIO_1_1 -> "Square framing ideal for social posts"
                    AspectRatioMode.RATIO_9_16 -> "Tall widescreen frame matching phone displays"
                }
            }
        )
    }

    if (showGridSheet) {
        SingleChoiceBottomSheet(
            title = "Grid Lines",
            options = GridMode.entries,
            selectedOption = preferences.gridMode,
            onOptionSelected = onGridChanged,
            onDismissRequest = { showGridSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    GridMode.OFF -> "Clean viewfinder with no overlay lines"
                    GridMode.GRID_3X3 -> "Classic rule-of-thirds composition guide"
                    GridMode.GRID_4X4 -> "Finer alignment grid for architecture and symmetry"
                    GridMode.GOLDEN_RATIO -> "Balanced 1:1.618 proportional guides"
                }
            }
        )
    }

    if (showTimerSheet) {
        SingleChoiceBottomSheet(
            title = "Timer Duration",
            options = TimerMode.entries,
            selectedOption = preferences.timerMode,
            onOptionSelected = onTimerChanged,
            onDismissRequest = { showTimerSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    TimerMode.OFF -> "Captures immediately when shutter is pressed"
                    TimerMode.SECONDS_3 -> "Short 3-second countdown to steady the shot"
                    TimerMode.SECONDS_5 -> "5-second countdown for quick group photos"
                    TimerMode.SECONDS_10 -> "10-second countdown for tripod and group shots"
                }
            }
        )
    }

    val verifiedCaps = (capabilitiesState as? CameraCapabilitiesState.Verified)?.currentLensCapabilities

    if (showFlashSheet) {
        val flashOptions = if (verifiedCaps != null && !verifiedCaps.isTorchSupported) {
            listOf(FlashMode.OFF)
        } else {
            FlashMode.entries
        }
        SingleChoiceBottomSheet(
            title = "Flash / Torch Mode",
            options = flashOptions,
            selectedOption = if (preferences.flashMode in flashOptions) preferences.flashMode else FlashMode.OFF,
            onOptionSelected = onFlashChanged,
            onDismissRequest = { showFlashSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    FlashMode.AUTO -> "Fires flash automatically in dark scenes"
                    FlashMode.ON -> "Always fires flash when taking a photo"
                    FlashMode.OFF -> "Never fires flash for natural lighting"
                    FlashMode.TORCH -> "Keeps LED light on continuously while framing"
                }
            }
        )
    }

    if (showVideoResSheet) {
        val resOptions = verifiedCaps?.getSupportedVideoModeResolutions()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P)
        SingleChoiceBottomSheet(
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
            onDismissRequest = { showVideoResSheet = false },
            optionLabel = { "${it.displayName} (${it.label})" },
            optionDescription = {
                when (it) {
                    VideoResolution.HD_720P -> "Compact 1280×720 clips using the least storage"
                    VideoResolution.FHD_1080P -> "Balanced 1920×1080 Full HD for everyday video"
                    VideoResolution.QHD_2K -> "Crisp 2560×1440 detail with moderate file sizes"
                    VideoResolution.UHD_4K -> "Ultra-sharp 3840×2160 video with larger files"
                }
            }
        )
    }

    if (showVideoFpsSheet) {
        val effectiveRes = verifiedCaps?.resolveSupportedResolution(preferences.videoResolution) ?: preferences.videoResolution
        val fpsOptions = verifiedCaps?.getSupportedVideoModeFrameRates(effectiveRes)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(VideoFrameRate.FPS_30)
        SingleChoiceBottomSheet(
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
            onDismissRequest = { showVideoFpsSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    VideoFrameRate.FPS_24 -> "Cinematic motion look at 24 frames per second"
                    VideoFrameRate.FPS_30 -> "Standard smooth recording for everyday clips"
                    VideoFrameRate.FPS_60 -> "Fluid high-motion video for action and panning"
                    else -> "High-speed frame rate for smooth motion"
                }
            }
        )
    }

    if (showStabilizationSheet) {
        val effectiveRes = verifiedCaps?.resolveSupportedResolution(preferences.videoResolution) ?: preferences.videoResolution
        val effectiveFps = verifiedCaps?.resolveSupportedFrameRate(effectiveRes, preferences.videoFrameRate) ?: preferences.videoFrameRate
        val stabOptions = verifiedCaps?.getSupportedStabilizationModes(effectiveRes, effectiveFps)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.STANDARD)
        SingleChoiceBottomSheet(
            title = "Video Stabilization",
            options = stabOptions,
            selectedOption = verifiedCaps?.resolveSupportedStabilization(effectiveRes, effectiveFps, preferences.videoStabilization) ?: preferences.videoStabilization,
            onOptionSelected = onVideoStabilizationChanged,
            onDismissRequest = { showStabilizationSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    VideoStabilizationMode.STANDARD -> "Smooths everyday handheld shake with minimal crop"
                    VideoStabilizationMode.ACTIVE -> "Stronger stabilization for walking or fast movement"
                    VideoStabilizationMode.OFF -> "Full field of view with no electronic cropping"
                }
            }
        )
    }

    if (showHdOptionSheet) {
        SingleChoiceBottomSheet(
            title = "HD Mode Output",
            options = HdOutputOption.entries,
            selectedOption = preferences.hdOutputOption,
            onOptionSelected = onHdOptionChanged,
            onDismissRequest = { showHdOptionSheet = false },
            optionLabel = { option ->
                val resolvedSize = com.example.core.camera.CaptureResolutionResolver.resolveActualCaptureSize(
                    lensCaps = verifiedCaps,
                    activeMode = com.example.core.model.CameraMode.HD,
                    aspectRatioMode = preferences.aspectRatioMode,
                    hdOutputOption = option
                )
                val mpLabel = com.example.core.camera.CaptureResolutionResolver.formatMegapixels(resolvedSize)
                if (mpLabel != null) "${option.displayName} ($mpLabel)" else option.displayName
            },
            optionDescription = { it.subtitle }
        )
    }

    if (showHdrModeSheet) {
        SingleChoiceBottomSheet(
            title = "HDR+ Computational Mode",
            options = HdrMode.entries,
            selectedOption = preferences.hdrMode,
            onOptionSelected = onHdrModeChanged,
            onDismissRequest = { showHdrModeSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    HdrMode.AUTO -> "Balances bright highlights and shadows automatically"
                    HdrMode.ON -> "Always applies high dynamic range processing"
                    HdrMode.OFF -> "Captures a single frame without HDR blending"
                }
            }
        )
    }

    if (showCaptureSpeedModeSheet) {
        SingleChoiceBottomSheet(
            title = "Photo Capture Mode",
            options = PhotoCaptureSpeedMode.entries,
            selectedOption = preferences.photoCaptureSpeedMode,
            onOptionSelected = onPhotoCaptureSpeedModeChanged,
            onDismissRequest = { showCaptureSpeedModeSheet = false },
            optionLabel = { it.displayName },
            optionDescription = { it.subtitle }
        )
    }

    if (showProRawSheet) {
        SingleChoiceBottomSheet(
            title = "RAW / DNG Output",
            options = ProRawFormat.entries,
            selectedOption = preferences.proRawFormat,
            onOptionSelected = onProRawFormatChanged,
            onDismissRequest = { showProRawSheet = false },
            optionLabel = { it.displayName },
            optionDescription = { it.description }
        )
    }

    if (showProFocusSheet) {
        SingleChoiceBottomSheet(
            title = "Manual Focus Mode",
            options = ProFocusMode.entries,
            selectedOption = preferences.proFocusMode,
            onOptionSelected = onProFocusModeChanged,
            onDismissRequest = { showProFocusSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    ProFocusMode.AUTO -> "Automatically locks focus on your subject"
                    ProFocusMode.MANUAL -> "Control focus distance directly with the slider"
                }
            }
        )
    }

    if (showPortraitBlurSheet) {
        SingleChoiceBottomSheet(
            title = "Portrait Background Blur",
            options = PortraitBlurLevel.entries,
            selectedOption = preferences.portraitBlurLevel,
            onOptionSelected = onPortraitBlurLevelChanged,
            onDismissRequest = { showPortraitBlurSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    PortraitBlurLevel.OFF -> "Keeps both subject and background sharp"
                    PortraitBlurLevel.LIGHT -> "Subtle natural background softening"
                    PortraitBlurLevel.MEDIUM -> "Balanced depth-of-field subject separation"
                    PortraitBlurLevel.DEEP -> "Strong shallow depth-of-field bokeh blur"
                }
            }
        )
    }

    if (showPortraitRetouchSheet) {
        SingleChoiceBottomSheet(
            title = "Portrait Retouching",
            options = PortraitRetouch.entries,
            selectedOption = preferences.portraitRetouch,
            onOptionSelected = onPortraitRetouchChanged,
            onDismissRequest = { showPortraitRetouchSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    PortraitRetouch.OFF -> "Preserves completely natural skin texture"
                    PortraitRetouch.NATURAL -> "Subtle blemish and skin tone refinement"
                    PortraitRetouch.SMOOTH -> "Softer skin smoothing for polished portraits"
                }
            }
        )
    }

    if (showNightExposureSheet) {
        SingleChoiceBottomSheet(
            title = "Night Sight Exposure",
            options = NightSightExposure.entries,
            selectedOption = preferences.nightSightExposure,
            onOptionSelected = onNightSightExposureChanged,
            onDismissRequest = { showNightExposureSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    NightSightExposure.AUTO -> "Adapts capture duration to scene brightness"
                    NightSightExposure.MAX -> "Longer handheld capture for very dark scenes"
                    NightSightExposure.OFF -> "Standard quick exposure without night stacking"
                }
            }
        )
    }

    if (showTimeLapseSpeedSheet) {
        SingleChoiceBottomSheet(
            title = "Time Lapse Speed",
            options = TimeLapseSpeed.entries,
            selectedOption = preferences.timeLapseSpeed,
            onOptionSelected = onTimeLapseSpeedChanged,
            onDismissRequest = { showTimeLapseSpeedSheet = false },
            optionLabel = { it.displayName },
            optionDescription = { it.description }
        )
    }

    if (showSlowMoSpeedSheet) {
        SingleChoiceBottomSheet(
            title = "Slow Motion Speed",
            options = SlowMoSpeed.entries,
            selectedOption = preferences.slowMoSpeed,
            onOptionSelected = onSlowMoSpeedChanged,
            onDismissRequest = { showSlowMoSpeedSheet = false },
            optionLabel = { it.displayName },
            optionDescription = { "Records at ${it.expectedFps} FPS for ${it.slowDownFactor.toInt()}× slower playback" }
        )
    }

    if (showVolumeSheet) {
        SingleChoiceBottomSheet(
            title = "Volume Key Action",
            options = VolumeButtonAction.entries,
            selectedOption = preferences.volumeButtonAction,
            onOptionSelected = onVolumeActionChanged,
            onDismissRequest = { showVolumeSheet = false },
            optionLabel = { it.displayName },
            optionDescription = {
                when (it) {
                    VolumeButtonAction.VOLUME -> "Keep volume keys controlling device audio"
                    VolumeButtonAction.SHUTTER -> "Take photos or start and stop video recording"
                    VolumeButtonAction.ZOOM -> "Zoom in and out with volume up and down"
                }
            }
        )
    }

    if (showHardwareDebugDialog) {
        val clipboardManager = LocalClipboardManager.current
        val debugReport by androidx.compose.runtime.produceState<CameraHardwareDebugReport?>(
            initialValue = null,
            key1 = context
        ) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                collectCameraHardwareDebugReport(context)
            }
        }
        var copiedBanner by remember { mutableStateOf(false) }
        val activeVerifiedLensCaps = (capabilitiesState as? CameraCapabilitiesState.Verified)?.currentLensCapabilities

        val activeResolvedPhoto = com.example.core.camera.CaptureResolutionResolver.formatMegapixelsWithDimensions(
            com.example.core.camera.CaptureResolutionResolver.resolveActualCaptureSize(
                lensCaps = activeVerifiedLensCaps,
                activeMode = com.example.core.model.CameraMode.PHOTO,
                aspectRatioMode = preferences.aspectRatioMode,
                hdOutputOption = preferences.hdOutputOption
            )
        )
        val activeResolvedHdNative = com.example.core.camera.CaptureResolutionResolver.formatMegapixelsWithDimensions(
            com.example.core.camera.CaptureResolutionResolver.resolveActualCaptureSize(
                lensCaps = activeVerifiedLensCaps,
                activeMode = com.example.core.model.CameraMode.HD,
                aspectRatioMode = preferences.aspectRatioMode,
                hdOutputOption = HdOutputOption.MAX_NATIVE
            )
        )
        val activeResolvedHdBinned = com.example.core.camera.CaptureResolutionResolver.formatMegapixelsWithDimensions(
            com.example.core.camera.CaptureResolutionResolver.resolveActualCaptureSize(
                lensCaps = activeVerifiedLensCaps,
                activeMode = com.example.core.model.CameraMode.HD,
                aspectRatioMode = preferences.aspectRatioMode,
                hdOutputOption = HdOutputOption.STANDARD_BINNED
            )
        )

        val activeExtensionsSummary = activeVerifiedLensCaps?.extensionAvailability?.supportedExtensionNames
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?: "None supported on this lens"
        val activeIspSummary = activeVerifiedLensCaps?.ispQualityCapabilities?.summaryLabel()
        val recentLatencies = remember(showHardwareDebugDialog) {
            com.example.core.camera.CaptureLatencyTracker.getRecentRecords()
        }
        val recentPortraitTimings = remember(showHardwareDebugDialog) {
            com.example.core.camera.PortraitStageTimingTracker.getRecentRecords()
        }
        val frameTimingSnapshot = remember(showHardwareDebugDialog) {
            com.example.core.camera.UiPerformanceMonitor.getSnapshot()
        }
        val filterVerificationSummary = remember(showHardwareDebugDialog) {
            com.example.core.camera.FilterDebugTracker.formatMultilineSummary()
        }

        CameraHardwareDebugDialog(
            report = debugReport,
            activePhotoResolvedSize = activeResolvedPhoto,
            activeHdNativeResolvedSize = activeResolvedHdNative,
            activeHdBinnedResolvedSize = activeResolvedHdBinned,
            activeExtensionsSummary = activeExtensionsSummary,
            activeIspSummary = activeIspSummary,
            recentLatencyRecords = recentLatencies,
            recentPortraitTimings = recentPortraitTimings,
            frameTimingSnapshot = frameTimingSnapshot,
            filterVerificationSummary = filterVerificationSummary,
            copied = copiedBanner,
            onCopyReport = {
                val loaded = debugReport
                if (loaded != null) {
                    clipboardManager.setText(AnnotatedString(formatHardwareDebugReportText(loaded)))
                    copiedBanner = true
                }
            },
            onDismiss = { showHardwareDebugDialog = false }
        )
    }
}

@Composable
internal fun CameraHardwareDebugDialog(
    report: CameraHardwareDebugReport?,
    activePhotoResolvedSize: String?,
    activeHdNativeResolvedSize: String?,
    activeHdBinnedResolvedSize: String?,
    activeExtensionsSummary: String,
    activeIspSummary: String?,
    recentLatencyRecords: List<com.example.core.camera.CaptureLatencyRecord>,
    recentPortraitTimings: List<com.example.core.camera.PortraitStageTimings> = emptyList(),
    frameTimingSnapshot: com.example.core.camera.UiFrameTimingSnapshot = com.example.core.camera.UiPerformanceMonitor.getSnapshot(),
    filterVerificationSummary: String = com.example.core.camera.FilterDebugTracker.formatMultilineSummary(),
    copied: Boolean,
    onCopyReport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("hardware_debug_dialog"),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Camera2 Hardware Debug",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (report != null) {
                        "${report.deviceModel} (${report.androidVersionLabel}) • ${report.cameras.size} camera ID(s)"
                    } else {
                        "Loading Camera2 hardware telemetry…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (activePhotoResolvedSize != null || activeHdNativeResolvedSize != null || activeHdBinnedResolvedSize != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Active Resolved Capture Sizes",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            activePhotoResolvedSize?.let {
                                Text(
                                    text = "Photo: $it",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                            activeHdNativeResolvedSize?.let {
                                Text(
                                    text = "HD (Highest Native): $it",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                            activeHdBinnedResolvedSize?.let {
                                Text(
                                    text = "HD (Standard Binned): $it",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                            Text(
                                text = "CameraX Extensions: $activeExtensionsSummary",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            activeIspSummary?.let {
                                Text(
                                    text = "ISP / ZSL: $it",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hardware_debug_frame_timing_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "UI Frame Timing & Main-Thread Stalls (>16ms)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = frameTimingSnapshot.formatMultilineSummary(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hardware_debug_filter_status_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Filter Pipeline Verification (Preview & Saved Files)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = filterVerificationSummary,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hardware_debug_latency_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Capture Latency (Shutter → Saved)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (recentLatencyRecords.isEmpty()) {
                            Text(
                                text = "No photos captured yet in this session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            recentLatencyRecords.forEach { rec ->
                                Text(
                                    text = "• ${rec.formatSummaryLine()}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hardware_debug_portrait_timings_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Portrait Pipeline Timings (Per Stage)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (recentPortraitTimings.isEmpty()) {
                            Text(
                                text = "No Portrait photos captured yet in this session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            recentPortraitTimings.forEach { pt ->
                                Text(
                                    text = pt.formatMultilineBreakdown(),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }
                if (report == null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Querying Camera2 stream maps and MediaCodec capabilities on background thread…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else if (report.cameras.isEmpty()) {
                    Text(
                        text = "No Camera2 camera IDs reported by CameraManager on this environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                report?.cameras?.forEach { cam ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Camera ID ${cam.cameraId} — ${cam.metadataLabel} (${cam.facingLabel})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Focal: ${cam.focalLengthsMm.joinToString(", ") { "${it}mm" }.ifBlank { "n/a" }}" +
                                    if (cam.isPhysicalOnly) " | Physical of ${cam.parentLogicalId ?: "logical"}"
                                    else if (cam.physicalCameraIds.isNotEmpty()) " | Physical IDs: ${cam.physicalCameraIds.joinToString(", ")}"
                                    else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Sensor Pixel Array: ${cam.pixelArraySize} | Active Array: ${cam.activeArraySize}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Text(
                                text = "Ultra-High-Res Sensor Cap (API 31+): ${if (cam.supportsUltraHighResSensorCapability) "YES" else "NO"}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Text(
                                text = "CameraX Extensions: ${cam.supportedExtensions.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: cam.extensionsStatusLabel}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            if (cam.ispQualitySummary.isNotBlank()) {
                                Text(
                                    text = "ISP Quality / ZSL: ${cam.ispQualitySummary}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Text(
                                text = "Video Resolution + FPS Evaluation (PASS/FAIL):",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = cam.videoComboEvaluations.joinToString("\n") {
                                    "  • ${it.formatDebugLine()}"
                                }.ifBlank { "  (none)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            if (cam.encoderCapabilitiesSummary.isNotEmpty()) {
                                Text(
                                    text = "Video Encoder Limits (getSupportedFrameRatesFor):",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = cam.encoderCapabilitiesSummary.joinToString("\n") { "  • $it" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }

                            Text(
                                text = "Video Sizes & Max FPS:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = cam.videoSizesWithMaxFps.joinToString("\n") {
                                    val fpsText = if (it.maxFps > 0.0) String.format(java.util.Locale.US, "%.1f", it.maxFps) else "n/a"
                                    "  • ${it.width}x${it.height} — max $fpsText FPS"
                                }.ifBlank { "  (none)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            if (cam.highSpeedVideoEntries.isNotEmpty()) {
                                Text(
                                    text = "High-Speed Video (Slow-Mo Only):",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = cam.highSpeedVideoEntries.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }

                            Text(
                                text = "JPEG Standard Sizes:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = cam.standardJpegSizes.joinToString(", ").ifBlank { "(none)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            Text(
                                text = "JPEG High-Resolution Sizes:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = cam.highResJpegSizes.joinToString(", ").ifBlank { "(none)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            Text(
                                text = "JPEG Maximum-Resolution Sizes (API 31+):",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = cam.maxResJpegSizes.joinToString(", ").ifBlank { "(none)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            Text(
                                text = "YUV_420_888 Sizes (Std / HiRes / MaxRes API 31+):",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Std: ${cam.standardYuvSizes.joinToString(", ").ifBlank { "(none)" }}\n" +
                                    "HiRes: ${cam.highResYuvSizes.joinToString(", ").ifBlank { "(none)" }}\n" +
                                    "MaxRes: ${cam.maxResYuvSizes.joinToString(", ").ifBlank { "(none)" }}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            Text(
                                text = "RAW_SENSOR Sizes (Std / HiRes / MaxRes API 31+):",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Std: ${cam.standardRawSizes.joinToString(", ").ifBlank { "(none)" }}\n" +
                                    "HiRes: ${cam.highResRawSizes.joinToString(", ").ifBlank { "(none)" }}\n" +
                                    "MaxRes: ${cam.maxResRawSizes.joinToString(", ").ifBlank { "(none)" }}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            Text(
                                text = "Stabilization Modes:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Video: ${cam.videoStabilizationModes.joinToString(", ").ifBlank { "NONE" }}\nOptical: ${cam.opticalStabilizationModes.joinToString(", ").ifBlank { "NONE" }}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )

                            Text(
                                text = "AE Target FPS Ranges:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = cam.aeFpsRanges.joinToString(", ").ifBlank { "(none)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            TextButton(onClick = onCopyReport, enabled = report != null) {
                Text(if (copied) "Copied" else "Copy Report")
            }
        }
    )
}

internal fun formatHardwareDebugReportText(report: CameraHardwareDebugReport): String = buildString {
    append(report.formatPlainText())
    appendLine()
    appendLine("=== UI Frame Timing & Main-Thread Stalls (>16ms) ===")
    appendLine(com.example.core.camera.UiPerformanceMonitor.getSnapshot().formatMultilineSummary())
    appendLine()
    appendLine("=== Filter Pipeline Verification (Preview & Saved Files) ===")
    appendLine(com.example.core.camera.FilterDebugTracker.formatMultilineSummary())
    val latencyRecords = com.example.core.camera.CaptureLatencyTracker.getRecentRecords()
    if (latencyRecords.isNotEmpty()) {
        appendLine()
        appendLine("=== Capture Latency (Shutter -> Saved) ===")
        latencyRecords.forEach { rec ->
            appendLine("  • ${rec.formatSummaryLine()}")
        }
    }
    val portraitRecords = com.example.core.camera.PortraitStageTimingTracker.getRecentRecords()
    if (portraitRecords.isNotEmpty()) {
        appendLine()
        appendLine("=== Portrait Pipeline Timings (Per Stage) ===")
        portraitRecords.forEach { pt ->
            appendLine("  • ${pt.formatSummaryLine()} (slowest=${pt.slowestStageLabel})")
        }
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
