package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Iso
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.PanoramaHorizontal
import androidx.compose.material.icons.outlined.RawOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.FlashMode
import com.example.core.preferences.GridMode
import com.example.core.preferences.HdOutputOption
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.preferences.ProFocusMode
import com.example.core.preferences.ProRawFormat
import com.example.core.preferences.SlowMoSpeed
import com.example.core.preferences.TimeLapseSpeed
import com.example.core.preferences.TimerMode
import com.example.core.preferences.VideoStabilizationMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraQuickControlsSheet(
    preferences: CameraPreferences,
    onFlashChanged: (FlashMode) -> Unit,
    onTimerChanged: (TimerMode) -> Unit,
    onAspectRatioChanged: (AspectRatioMode) -> Unit,
    onGridChanged: (GridMode) -> Unit,
    onOpenFullSettings: () -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    activeMode: CameraMode = CameraMode.PHOTO,
    capabilitiesState: CameraCapabilitiesState = CameraCapabilitiesState.PendingDiscovery(),
    onVideoResolutionChanged: (VideoResolution) -> Unit = {},
    onVideoFpsChanged: (VideoFrameRate) -> Unit = {},
    onVideoStabilizationChanged: (VideoStabilizationMode) -> Unit = {},
    onHdOptionChanged: (HdOutputOption) -> Unit = {},
    onPortraitRetouchChanged: (PortraitRetouch) -> Unit = {},
    onPortraitBlurLevelChanged: (PortraitBlurLevel) -> Unit = {},
    onNightSightExposureChanged: (NightSightExposure) -> Unit = {},
    onTimeLapseSpeedChanged: (TimeLapseSpeed) -> Unit = {},
    onSlowMoSpeedChanged: (SlowMoSpeed) -> Unit = {},
    onProIsoChanged: (String) -> Unit = {},
    onProShutterChanged: (String) -> Unit = {},
    onProFocusModeChanged: (ProFocusMode) -> Unit = {},
    onProFocusDistanceChanged: (Float) -> Unit = {},
    onProWbChanged: (String) -> Unit = {},
    onProEvChanged: (Float) -> Unit = {},
    onProRawFormatChanged: (ProRawFormat) -> Unit = {},
    onProHistogramToggled: (Boolean) -> Unit = {},
    onProFocusPeakingToggled: (Boolean) -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.testTag("quick_controls_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Title + Mode Badge + Settings button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Quick Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = activeMode.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                TextButton(
                    onClick = {
                        onDismissRequest()
                        onOpenFullSettings()
                    },
                    modifier = Modifier.testTag("full_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "More Settings",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("More settings")
                }
            }

            // Hardware Capability Notice
            when (capabilitiesState) {
                is CameraCapabilitiesState.PendingDiscovery -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Showing device-safe baseline profiles. Real hardware capabilities verify when the camera engine binds in Phase 2.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is CameraCapabilitiesState.Verified -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HighQuality,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "${capabilitiesState.activeLens.displayName} • Verified hardware capabilities active",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            val verifiedLensCaps = (capabilitiesState as? CameraCapabilitiesState.Verified)?.currentLensCapabilities
            val activeLens = when (capabilitiesState) {
                is CameraCapabilitiesState.Verified -> capabilitiesState.activeLens
                is CameraCapabilitiesState.PendingDiscovery -> capabilitiesState.activeLens
            }
            val isTorchHardwareSupported = when (capabilitiesState) {
                is CameraCapabilitiesState.Verified -> verifiedLensCaps?.isTorchSupported == true
                is CameraCapabilitiesState.PendingDiscovery -> activeLens.facing != com.example.core.camera.LensFacing.FRONT
            }

            // MODE-SPECIFIC SETTINGS
            when (activeMode) {
                CameraMode.PHOTO -> {
                    // Flash
                    val effectivePhotoFlash = if (isTorchHardwareSupported) {
                        preferences.flashMode
                    } else {
                        FlashMode.OFF
                    }
                    QuickControlPillSection(
                        label = "Flash",
                        icon = Icons.Outlined.FlashOn,
                        options = FlashMode.entries,
                        selectedOption = effectivePhotoFlash,
                        optionLabel = { it.displayName },
                        isOptionEnabled = { mode -> mode == FlashMode.OFF || isTorchHardwareSupported },
                        optionTestTag = { "quick_photo_flash_${it.name.lowercase()}" },
                        supportingNote = if (!isTorchHardwareSupported) {
                            "Flash hardware unavailable on ${activeLens.displayName}"
                        } else null,
                        onOptionSelected = onFlashChanged
                    )

                    // Timer
                    QuickControlPillSection(
                        label = "Timer",
                        icon = Icons.Outlined.Timer,
                        options = TimerMode.entries,
                        selectedOption = preferences.timerMode,
                        optionLabel = { it.displayName },
                        optionTestTag = { "quick_timer_${it.name.lowercase()}" },
                        onOptionSelected = onTimerChanged
                    )

                    // Ratio
                    QuickControlPillSection(
                        label = "Ratio",
                        icon = Icons.Outlined.AspectRatio,
                        options = AspectRatioMode.entries,
                        selectedOption = preferences.aspectRatioMode,
                        optionLabel = { it.displayName },
                        optionTestTag = { "quick_ratio_${it.name.lowercase()}" },
                        onOptionSelected = onAspectRatioChanged
                    )

                    // Grid
                    QuickControlPillSection(
                        label = "Grid",
                        icon = Icons.Outlined.GridOn,
                        options = GridMode.entries,
                        selectedOption = preferences.gridMode,
                        optionLabel = { it.displayName },
                        optionTestTag = { "quick_grid_${it.name.lowercase()}" },
                        onOptionSelected = onGridChanged
                    )
                }

                CameraMode.PRO -> {
                    // RAW Format
                    QuickControlPillSection(
                        label = "RAW / DNG Format",
                        icon = Icons.Outlined.RawOn,
                        options = ProRawFormat.entries,
                        selectedOption = preferences.proRawFormat,
                        optionLabel = { it.displayName },
                        onOptionSelected = onProRawFormatChanged
                    )

                    // ISO
                    val currentLensCaps = if (capabilitiesState is CameraCapabilitiesState.Verified) {
                        capabilitiesState.currentLensCapabilities
                    } else null
                    val hardwareIsoRange = currentLensCaps?.manualCapabilities?.isoRange
                    val isHardwareVerified = currentLensCaps?.manualCapabilities?.isManualIsoSupported == true && hardwareIsoRange != null
                    val supportsIso50 = isHardwareVerified && (hardwareIsoRange.start <= 50 && 50 <= hardwareIsoRange.endInclusive)
                    val effectiveIsoRange = hardwareIsoRange ?: (100..6400)

                    val isoOptions = mutableListOf("AUTO")
                    if (supportsIso50) isoOptions.add("50")
                    isoOptions.addAll(listOf("100", "200", "400", "800", "1600", "3200", "6400").filter { it.toInt() in (effectiveIsoRange.start..effectiveIsoRange.endInclusive) })

                    QuickControlPillSection(
                        label = "ISO Sensitivity",
                        icon = Icons.Outlined.Iso,
                        options = isoOptions,
                        selectedOption = preferences.proIso,
                        optionLabel = { it },
                        onOptionSelected = onProIsoChanged
                    )

                    // Shutter Speed
                    QuickControlPillSection(
                        label = "Shutter Speed",
                        icon = Icons.Outlined.ShutterSpeed,
                        options = listOf("AUTO", "1/1000s", "1/500s", "1/250s", "1/125s", "1/60s", "1/30s", "1s"),
                        selectedOption = preferences.proShutterSpeed,
                        optionLabel = { it },
                        onOptionSelected = onProShutterChanged
                    )

                    // Ratio
                    QuickControlPillSection(
                        label = "Ratio",
                        icon = Icons.Outlined.AspectRatio,
                        options = AspectRatioMode.entries,
                        selectedOption = preferences.aspectRatioMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onAspectRatioChanged
                    )

                    // Grid
                    QuickControlPillSection(
                        label = "Grid",
                        icon = Icons.Outlined.GridOn,
                        options = GridMode.entries,
                        selectedOption = preferences.gridMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onGridChanged
                    )
                }

                CameraMode.HD -> {
                    // HD Output Option
                    QuickControlPillSection(
                        label = "HD Capture Quality",
                        icon = Icons.Outlined.HighQuality,
                        options = HdOutputOption.entries,
                        selectedOption = preferences.hdOutputOption,
                        optionLabel = { it.displayName },
                        onOptionSelected = onHdOptionChanged
                    )

                    // Resolution Info Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Highest Supported Native Output",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (capabilitiesState) {
                                    is CameraCapabilitiesState.Verified -> {
                                        val highest = capabilitiesState.currentLensCapabilities?.highestNativeStillSize
                                        if (highest != null) {
                                            "Target output: ${highest.formattedMegapixels} (${highest.dimensionsLabel}) native unbinned sensor capture."
                                        } else {
                                            "Target output: Dynamic highest native output for ${capabilitiesState.activeLens.displayName}."
                                        }
                                    }
                                    is CameraCapabilitiesState.PendingDiscovery -> {
                                        "Target output will dynamically discover and select the camera lens's highest native resolution without artificial upscaling (Phase 2)."
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Ratio
                    QuickControlPillSection(
                        label = "Ratio",
                        icon = Icons.Outlined.AspectRatio,
                        options = listOf(AspectRatioMode.RATIO_4_3, AspectRatioMode.RATIO_9_16),
                        selectedOption = preferences.aspectRatioMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onAspectRatioChanged
                    )

                    // Timer
                    QuickControlPillSection(
                        label = "Timer",
                        icon = Icons.Outlined.Timer,
                        options = TimerMode.entries,
                        selectedOption = preferences.timerMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onTimerChanged
                    )
                }

                CameraMode.PORTRAIT -> {
                    // Background Blur Strength
                    QuickControlPillSection(
                        label = "Background Blur",
                        icon = Icons.Outlined.BlurOn,
                        options = PortraitBlurLevel.entries,
                        selectedOption = preferences.portraitBlurLevel,
                        optionLabel = { it.shortcutLabel },
                        onOptionSelected = onPortraitBlurLevelChanged
                    )

                    // Face Retouching
                    QuickControlPillSection(
                        label = "Face Retouching",
                        icon = Icons.Outlined.Face,
                        options = PortraitRetouch.entries,
                        selectedOption = preferences.portraitRetouch,
                        optionLabel = { it.displayName },
                        onOptionSelected = onPortraitRetouchChanged
                    )

                    // Timer
                    QuickControlPillSection(
                        label = "Timer",
                        icon = Icons.Outlined.Timer,
                        options = TimerMode.entries,
                        selectedOption = preferences.timerMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onTimerChanged
                    )

                    // Ratio
                    QuickControlPillSection(
                        label = "Ratio",
                        icon = Icons.Outlined.AspectRatio,
                        options = listOf(AspectRatioMode.RATIO_4_3, AspectRatioMode.RATIO_1_1, AspectRatioMode.RATIO_9_16),
                        selectedOption = preferences.aspectRatioMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onAspectRatioChanged
                    )

                    // Portrait Depth & Subject Segmentation Capability Card
                    val portraitPathLabel = verifiedLensCaps?.portraitDepthSupportPath?.displayName
                        ?: "Single-Lens On-Device Subject Matte"
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("portrait_depth_capability_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "$portraitPathLabel • Edge-refined background blur is applied to the captured photo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                CameraMode.VIDEO -> {
                    androidx.compose.runtime.LaunchedEffect(
                        capabilitiesState,
                        preferences.videoResolution,
                        preferences.videoFrameRate,
                        preferences.videoStabilization,
                        preferences.flashMode
                    ) {
                        if (verifiedLensCaps != null) {
                            val reconciled = verifiedLensCaps.reconcileVideoSettings(
                                requestedResolution = preferences.videoResolution,
                                requestedFrameRate = preferences.videoFrameRate,
                                requestedStabilization = preferences.videoStabilization,
                                requestedFlashMode = preferences.flashMode
                            )
                            if (reconciled.resolution != preferences.videoResolution) {
                                onVideoResolutionChanged(reconciled.resolution)
                            }
                            if (reconciled.frameRate != preferences.videoFrameRate) {
                                onVideoFpsChanged(reconciled.frameRate)
                            }
                            if (reconciled.stabilization != preferences.videoStabilization) {
                                onVideoStabilizationChanged(reconciled.stabilization)
                            }
                            if (reconciled.flashMode != preferences.flashMode) {
                                onFlashChanged(reconciled.flashMode)
                            }
                        }
                    }

                    val displayedResolutions = when (capabilitiesState) {
                        is CameraCapabilitiesState.Verified -> {
                            verifiedLensCaps?.getSupportedVideoModeResolutions()
                                ?.takeIf { it.isNotEmpty() }
                                ?: listOf(VideoResolution.HD_720P)
                        }
                        is CameraCapabilitiesState.PendingDiscovery -> {
                            listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P)
                        }
                    }

                    val effectiveResolution = when (capabilitiesState) {
                        is CameraCapabilitiesState.Verified -> {
                            verifiedLensCaps?.resolveSupportedResolution(preferences.videoResolution)
                                ?: displayedResolutions.last()
                        }
                        is CameraCapabilitiesState.PendingDiscovery -> {
                            if (preferences.videoResolution in displayedResolutions) {
                                preferences.videoResolution
                            } else {
                                VideoResolution.FHD_1080P
                            }
                        }
                    }

                    val unsupportedResolutions = VideoResolution.VIDEO_MODE_OPTIONS.filter { it !in displayedResolutions }
                    val resolutionNote = if (capabilitiesState is CameraCapabilitiesState.Verified && unsupportedResolutions.isNotEmpty()) {
                        "Supported on ${activeLens.displayName}: ${displayedResolutions.joinToString { it.displayName }}"
                    } else null

                    QuickControlPillSection(
                        label = "Video Resolution",
                        icon = Icons.Outlined.Videocam,
                        options = displayedResolutions,
                        selectedOption = effectiveResolution,
                        optionLabel = { it.displayName },
                        optionTestTag = { "video_res_option_${it.id}" },
                        supportingNote = resolutionNote,
                        onOptionSelected = { newRes ->
                            onVideoResolutionChanged(newRes)
                            if (verifiedLensCaps != null) {
                                val resolvedFps = verifiedLensCaps.resolveSupportedFrameRate(newRes, preferences.videoFrameRate)
                                if (resolvedFps != preferences.videoFrameRate) {
                                    onVideoFpsChanged(resolvedFps)
                                }
                                val resolvedStab = verifiedLensCaps.resolveSupportedStabilization(
                                    newRes,
                                    resolvedFps,
                                    preferences.videoStabilization
                                )
                                if (resolvedStab != preferences.videoStabilization) {
                                    onVideoStabilizationChanged(resolvedStab)
                                }
                            }
                        }
                    )

                    // Video Frame Rate: dynamically displays only FPS options supported for the selected resolution
                    val displayedFpsOptions = when (capabilitiesState) {
                        is CameraCapabilitiesState.Verified -> {
                            verifiedLensCaps?.getSupportedVideoModeFrameRates(effectiveResolution)
                                ?.takeIf { it.isNotEmpty() }
                                ?: listOf(VideoFrameRate.FPS_30)
                        }
                        is CameraCapabilitiesState.PendingDiscovery -> {
                            // Never display 24 or 60 FPS as verified before hardware capability inspection
                            listOf(VideoFrameRate.FPS_30)
                        }
                    }

                    val effectiveFps = when (capabilitiesState) {
                        is CameraCapabilitiesState.Verified -> {
                            verifiedLensCaps?.resolveSupportedFrameRate(effectiveResolution, preferences.videoFrameRate)
                                ?: displayedFpsOptions.first()
                        }
                        is CameraCapabilitiesState.PendingDiscovery -> {
                            if (preferences.videoFrameRate in displayedFpsOptions) {
                                preferences.videoFrameRate
                            } else {
                                VideoFrameRate.FPS_30
                            }
                        }
                    }

                    val fpsNote = if (capabilitiesState is CameraCapabilitiesState.Verified &&
                        VideoFrameRate.FPS_60 !in displayedFpsOptions
                    ) {
                        "60 FPS unavailable for ${effectiveResolution.displayName} on ${activeLens.displayName}"
                    } else null

                    QuickControlPillSection(
                        label = "Frame Rate",
                        icon = Icons.Outlined.Speed,
                        options = displayedFpsOptions,
                        selectedOption = effectiveFps,
                        optionLabel = { it.displayName },
                        optionTestTag = { "video_fps_option_${it.fps}" },
                        supportingNote = fpsNote,
                        onOptionSelected = { newFps ->
                            onVideoFpsChanged(newFps)
                            if (verifiedLensCaps != null) {
                                val resolvedStab = verifiedLensCaps.resolveSupportedStabilization(
                                    effectiveResolution,
                                    newFps,
                                    preferences.videoStabilization
                                )
                                if (resolvedStab != preferences.videoStabilization) {
                                    onVideoStabilizationChanged(resolvedStab)
                                }
                            }
                        }
                    )

                    // Video Stabilization: Standard, Active, Off
                    val isStabSupported: (VideoStabilizationMode) -> Boolean = { mode ->
                        when (capabilitiesState) {
                            is CameraCapabilitiesState.Verified -> {
                                verifiedLensCaps?.isStabilizationModeSupported(mode, effectiveResolution, effectiveFps)
                                    ?: (mode == VideoStabilizationMode.OFF)
                            }
                            is CameraCapabilitiesState.PendingDiscovery -> {
                                mode != VideoStabilizationMode.ACTIVE
                            }
                        }
                    }

                    val effectiveStabilization = when (capabilitiesState) {
                        is CameraCapabilitiesState.Verified -> {
                            verifiedLensCaps?.resolveSupportedStabilization(
                                effectiveResolution,
                                effectiveFps,
                                preferences.videoStabilization
                            ) ?: VideoStabilizationMode.OFF
                        }
                        is CameraCapabilitiesState.PendingDiscovery -> {
                            if (isStabSupported(preferences.videoStabilization)) {
                                preferences.videoStabilization
                            } else {
                                VideoStabilizationMode.STANDARD
                            }
                        }
                    }

                    val stabNote = if (capabilitiesState is CameraCapabilitiesState.Verified) {
                        when {
                            !isStabSupported(VideoStabilizationMode.STANDARD) && !isStabSupported(VideoStabilizationMode.ACTIVE) ->
                                "Hardware video stabilization unavailable on ${activeLens.displayName}"
                            !isStabSupported(VideoStabilizationMode.ACTIVE) ->
                                "Active stabilization requires OIS + EIS at 1080p/720p 30 FPS"
                            else -> null
                        }
                    } else null

                    QuickControlPillSection(
                        label = "Stabilization",
                        icon = Icons.Outlined.Videocam,
                        options = VideoStabilizationMode.entries,
                        selectedOption = effectiveStabilization,
                        optionLabel = { it.displayName },
                        isOptionEnabled = isStabSupported,
                        optionTestTag = { "video_stab_option_${it.name.lowercase()}" },
                        supportingNote = stabNote,
                        onOptionSelected = onVideoStabilizationChanged
                    )

                    // Flash / Torch: Off and Torch
                    val effectiveTorchMode = if (isTorchHardwareSupported && preferences.flashMode == FlashMode.TORCH) {
                        FlashMode.TORCH
                    } else {
                        FlashMode.OFF
                    }
                    val torchNote = if (capabilitiesState is CameraCapabilitiesState.Verified && !isTorchHardwareSupported) {
                        "Torch hardware unavailable on ${activeLens.displayName}"
                    } else null

                    QuickControlPillSection(
                        label = "Torch",
                        icon = Icons.Outlined.FlashOn,
                        options = listOf(FlashMode.OFF, FlashMode.TORCH),
                        selectedOption = effectiveTorchMode,
                        optionLabel = { it.displayName },
                        isOptionEnabled = { mode -> mode == FlashMode.OFF || isTorchHardwareSupported },
                        optionTestTag = { "video_torch_option_${it.name.lowercase()}" },
                        supportingNote = torchNote,
                        onOptionSelected = onFlashChanged
                    )
                }

                CameraMode.CINEMATIC -> {
                    // Cinematic Resolution (1080p / 4K)
                    QuickControlPillSection(
                        label = "Video Quality",
                        icon = Icons.Outlined.Videocam,
                        options = listOf(VideoResolution.FHD_1080P, VideoResolution.UHD_4K),
                        selectedOption = if (preferences.videoResolution == VideoResolution.UHD_4K) VideoResolution.UHD_4K else VideoResolution.FHD_1080P,
                        optionLabel = { it.displayName },
                        onOptionSelected = onVideoResolutionChanged
                    )

                    // Cinematic Frame Rate: 24 FPS / 30 FPS
                    QuickControlPillSection(
                        label = "Frame Rate",
                        icon = Icons.Outlined.Speed,
                        options = listOf(VideoFrameRate.FPS_24, VideoFrameRate.FPS_30),
                        selectedOption = if (preferences.videoFrameRate == VideoFrameRate.FPS_24) VideoFrameRate.FPS_24 else VideoFrameRate.FPS_30,
                        optionLabel = { it.displayName },
                        onOptionSelected = onVideoFpsChanged
                    )

                    // Stabilization
                    QuickControlPillSection(
                        label = "Stabilization",
                        icon = Icons.Outlined.Videocam,
                        options = listOf(VideoStabilizationMode.STANDARD, VideoStabilizationMode.ACTIVE),
                        selectedOption = preferences.videoStabilization,
                        optionLabel = { it.displayName },
                        onOptionSelected = onVideoStabilizationChanged
                    )
                }

                CameraMode.SLOW_MOTION -> {
                    // Slow Motion Speed
                    QuickControlPillSection(
                        label = "Slow Motion Speed",
                        icon = Icons.Outlined.SlowMotionVideo,
                        options = SlowMoSpeed.entries,
                        selectedOption = preferences.slowMoSpeed,
                        optionLabel = { it.displayName },
                        onOptionSelected = onSlowMoSpeedChanged
                    )

                    // Torch
                    QuickControlPillSection(
                        label = "Torch",
                        icon = Icons.Outlined.FlashOn,
                        options = listOf(FlashMode.OFF, FlashMode.TORCH),
                        selectedOption = if (isTorchHardwareSupported && preferences.flashMode == FlashMode.TORCH) FlashMode.TORCH else FlashMode.OFF,
                        optionLabel = { it.displayName },
                        isOptionEnabled = { mode -> mode == FlashMode.OFF || isTorchHardwareSupported },
                        onOptionSelected = onFlashChanged
                    )
                }

                CameraMode.NIGHT_SIGHT -> {
                    // Night sight exposure mode
                    QuickControlPillSection(
                        label = "Exposure Duration",
                        icon = Icons.Outlined.NightsStay,
                        options = NightSightExposure.entries,
                        selectedOption = preferences.nightSightExposure,
                        optionLabel = { it.displayName },
                        onOptionSelected = onNightSightExposureChanged
                    )

                    // Timer
                    QuickControlPillSection(
                        label = "Timer",
                        icon = Icons.Outlined.Timer,
                        options = TimerMode.entries,
                        selectedOption = preferences.timerMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onTimerChanged
                    )

                    // Grid
                    QuickControlPillSection(
                        label = "Grid",
                        icon = Icons.Outlined.GridOn,
                        options = GridMode.entries,
                        selectedOption = preferences.gridMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onGridChanged
                    )
                }

                CameraMode.TIME_LAPSE -> {
                    // Time Lapse Speed
                    QuickControlPillSection(
                        label = "Lapse Speed",
                        icon = Icons.Outlined.Speed,
                        options = TimeLapseSpeed.entries,
                        selectedOption = preferences.timeLapseSpeed,
                        optionLabel = { it.displayName },
                        onOptionSelected = onTimeLapseSpeedChanged
                    )

                    // Grid
                    QuickControlPillSection(
                        label = "Grid",
                        icon = Icons.Outlined.GridOn,
                        options = GridMode.entries,
                        selectedOption = preferences.gridMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onGridChanged
                    )
                }

                CameraMode.PANORAMA -> {
                    // Guidance indicator
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PanoramaHorizontal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Wide Angle Sweep",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Hold device vertically and pan smoothly along the horizon.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Grid
                    QuickControlPillSection(
                        label = "Reference Grid",
                        icon = Icons.Outlined.GridOn,
                        options = GridMode.entries,
                        selectedOption = preferences.gridMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onGridChanged
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun <T> QuickControlPillSection(
    label: String,
    icon: ImageVector,
    options: List<T>,
    selectedOption: T,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
    isOptionEnabled: (T) -> Boolean = { true },
    optionTestTag: ((T) -> String)? = null,
    supportingNote: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val isEnabled = isOptionEnabled(option)
                val isSelected = option == selectedOption && isEnabled
                Surface(
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        !isEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (optionTestTag != null) {
                                Modifier.testTag(optionTestTag(option))
                            } else {
                                Modifier
                            }
                        )
                        .clickable(enabled = isEnabled) {
                            if (isEnabled) {
                                onOptionSelected(option)
                            }
                        }
                ) {
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        if (supportingNote != null) {
            Text(
                text = supportingNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp)
            )
        }
    }
}
