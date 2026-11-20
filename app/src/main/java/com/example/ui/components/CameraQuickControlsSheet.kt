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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.core.camera.CameraCapabilitiesState
import com.example.core.camera.CameraLens
import com.example.core.camera.CaptureResolutionResolver
import com.example.core.camera.LensCapabilities
import com.example.core.camera.LensFacing
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.FlashMode
import com.example.core.preferences.GridMode
import com.example.core.preferences.HdOutputOption
import com.example.core.preferences.AstroDuration
import com.example.core.preferences.AstroFocusMode
import com.example.core.preferences.AstroStrategy
import com.example.core.preferences.HdrMode
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.preferences.ProFocusMode
import com.example.core.preferences.ProRawFormat
import com.example.core.preferences.SlowMoSpeed
import com.example.core.preferences.TimeLapseSpeed
import com.example.core.preferences.PanoramaDirection
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
    onHdrModeChanged: (HdrMode) -> Unit = {},
    onPortraitRetouchChanged: (PortraitRetouch) -> Unit = {},
    onPortraitBlurLevelChanged: (PortraitBlurLevel) -> Unit = {},
    onNightSightExposureChanged: (NightSightExposure) -> Unit = {},
    onTimeLapseSpeedChanged: (TimeLapseSpeed) -> Unit = {},
    onSlowMoSpeedChanged: (SlowMoSpeed) -> Unit = {},
    onPanoramaDirectionChanged: (PanoramaDirection) -> Unit = {},
    onProIsoChanged: (String) -> Unit = {},
    onProShutterChanged: (String) -> Unit = {},
    onProFocusModeChanged: (ProFocusMode) -> Unit = {},
    onProFocusDistanceChanged: (Float) -> Unit = {},
    onProWbChanged: (String) -> Unit = {},
    onProEvChanged: (Float) -> Unit = {},
    onProRawFormatChanged: (ProRawFormat) -> Unit = {},
    onProHistogramToggled: (Boolean) -> Unit = {},
    onProFocusPeakingToggled: (Boolean) -> Unit = {},
    onAstroDurationChanged: (AstroDuration) -> Unit = {},
    onAstroFocusChanged: (AstroFocusMode) -> Unit = {},
    onAstroStrategyChanged: (AstroStrategy) -> Unit = {}
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
                    val lensCaps = capabilitiesState.currentLensCapabilities
                    val detailsLine = if (lensCaps != null) {
                        buildQuickSettingsCameraDetailsLine(
                            lensCaps = lensCaps,
                            activeMode = activeMode,
                            preferences = preferences
                        )
                    } else {
                        if (capabilitiesState.activeLens.facing == LensFacing.FRONT) {
                            "Front Camera"
                        } else {
                            "Rear Camera"
                        }
                    }
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
                                text = detailsLine,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.testTag("quick_settings_camera_details_line")
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
            val activeHardwareName = verifiedLensCaps?.let { formatCameraBaseLabelWithoutFocalLength(it) }
                ?: activeLens.displayName
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
                            "Flash hardware unavailable on $activeHardwareName"
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

                    // HDR+ (shown only when CameraX HDR Extension, Camera2 Hardware Scene HDR, or real EV-bracketed HDR is available)
                    val isHdrAvailableOnLens = verifiedLensCaps == null || verifiedLensCaps.isRealHdrSupported
                    if (isHdrAvailableOnLens) {
                        val hdrPathNote = when (verifiedLensCaps?.hdrSupportPath) {
                            com.example.core.camera.HdrSupportPath.COMPATIBLE_EXTENSION ->
                                "Using device CameraX HDR Extension"
                            com.example.core.camera.HdrSupportPath.HARDWARE_SCENE_MODE_HDR ->
                                "Using Camera2 Hardware HDR + EV fusion"
                            com.example.core.camera.HdrSupportPath.SOFTWARE_MULTI_FRAME_HDR ->
                                "Using real 3-frame EV-bracketed HDR fusion"
                            else -> null
                        }
                        QuickControlPillSection(
                            label = "HDR+",
                            icon = Icons.Outlined.HighQuality,
                            options = HdrMode.entries,
                            selectedOption = preferences.hdrMode,
                            optionLabel = { it.displayName },
                            optionTestTag = { "quick_hdr_${it.name.lowercase()}" },
                            supportingNote = hdrPathNote,
                            onOptionSelected = onHdrModeChanged
                        )
                    }

                    // Show genuinely available CameraX OEM Extensions for this lens (never list unsupported extensions)
                    val availableExtNames = verifiedLensCaps?.extensionAvailability?.supportedExtensionNames.orEmpty()
                    if (availableExtNames.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_extensions_info_card")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "CameraX Extensions available: ${availableExtNames.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

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
                    val hdActiveCaptureSize = CaptureResolutionResolver.resolveActualCaptureSize(
                        lensCaps = verifiedLensCaps,
                        activeMode = CameraMode.HD,
                        aspectRatioMode = preferences.aspectRatioMode,
                        hdOutputOption = preferences.hdOutputOption
                    )
                    val hdMaxCaptureSize = CaptureResolutionResolver.resolveActualCaptureSize(
                        lensCaps = verifiedLensCaps,
                        activeMode = CameraMode.HD,
                        aspectRatioMode = preferences.aspectRatioMode,
                        hdOutputOption = HdOutputOption.MAX_NATIVE
                    )
                    val hdMaxFormattedWithDims = CaptureResolutionResolver.formatMegapixelsWithDimensions(hdMaxCaptureSize)

                    // HD Output Option
                    QuickControlPillSection(
                        label = "HD Capture Quality",
                        icon = Icons.Outlined.HighQuality,
                        options = HdOutputOption.entries,
                        selectedOption = preferences.hdOutputOption,
                        optionLabel = { it.displayName },
                        supportingNote = if (verifiedLensCaps?.isSensorFullResolutionRestricted == true) {
                            if (hdMaxFormattedWithDims != null) {
                                "This device doesn't expose full sensor resolution to apps. Max available: $hdMaxFormattedWithDims."
                            } else {
                                "This device doesn't expose full sensor resolution to apps."
                            }
                        } else null,
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
                                text = if (verifiedLensCaps != null) {
                                    CaptureResolutionResolver.formatHdModeButtonLabel(
                                        lensCaps = verifiedLensCaps,
                                        aspectRatioMode = preferences.aspectRatioMode,
                                        hdOutputOption = preferences.hdOutputOption
                                    )
                                } else {
                                    "Highest Supported Native Output"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("hd_resolution_card_title")
                            )
                            Text(
                                text = when (capabilitiesState) {
                                    is CameraCapabilitiesState.Verified -> {
                                        val caps = capabilitiesState.currentLensCapabilities
                                        val formattedOutput = CaptureResolutionResolver.formatMegapixelsWithDimensions(hdActiveCaptureSize)
                                        val sensorArrayMp = CaptureResolutionResolver.formatMegapixels(caps?.sensorPixelArraySize)
                                        if (formattedOutput != null) {
                                            when {
                                                preferences.hdOutputOption == HdOutputOption.STANDARD_BINNED ->
                                                    "Target output: $formattedOutput standard binned capture."
                                                caps?.isSensorFullResolutionRestricted == true && sensorArrayMp != null ->
                                                    "Target output: $formattedOutput. This device doesn't expose full sensor resolution to apps ($sensorArrayMp sensor)."
                                                else ->
                                                    "Target output: $formattedOutput native sensor capture."
                                            }
                                        } else {
                                            "Target output: Dynamic native output for $activeHardwareName."
                                        }
                                    }
                                    is CameraCapabilitiesState.PendingDiscovery -> {
                                        "Target output will dynamically discover and select the camera lens's highest native resolution without artificial upscaling."
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("hd_resolution_card_body")
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
                        "Supported on $activeHardwareName: ${displayedResolutions.joinToString { it.displayName }}"
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
                        "60 FPS unavailable for ${effectiveResolution.displayName} on $activeHardwareName"
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

                    // Video Stabilization: show toggle only when EIS or OIS is actually available on this camera,
                    // checking per resolution/FPS so unsupported single resolutions don't falsely mark the whole camera unsupported.
                    val showStabilizationSection = when (capabilitiesState) {
                        is CameraCapabilitiesState.Verified -> verifiedLensCaps?.isStabilizationAvailableForAnyResolution == true
                        is CameraCapabilitiesState.PendingDiscovery -> true
                    }

                    if (showStabilizationSection) {
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
                                    "Stabilization unavailable at ${effectiveResolution.displayName} ${effectiveFps.fps} FPS"
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
                    }

                    // Flash / Torch: Off and Torch
                    val effectiveTorchMode = if (isTorchHardwareSupported && preferences.flashMode == FlashMode.TORCH) {
                        FlashMode.TORCH
                    } else {
                        FlashMode.OFF
                    }
                    val torchNote = if (capabilitiesState is CameraCapabilitiesState.Verified && !isTorchHardwareSupported) {
                        "Torch hardware unavailable on $activeHardwareName"
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

                CameraMode.ASTRO -> {
                    QuickControlPillSection(
                        label = "Astrophotography Duration",
                        icon = Icons.Outlined.NightsStay,
                        options = AstroDuration.entries,
                        selectedOption = preferences.astroDuration,
                        optionLabel = { it.displayName },
                        onOptionSelected = onAstroDurationChanged
                    )
                    QuickControlPillSection(
                        label = "Astro Focus",
                        icon = Icons.Outlined.NightsStay,
                        options = AstroFocusMode.entries,
                        selectedOption = preferences.astroFocus,
                        optionLabel = { it.displayName },
                        onOptionSelected = onAstroFocusChanged
                    )
                    QuickControlPillSection(
                        label = "Capture Strategy",
                        icon = Icons.Outlined.ShutterSpeed,
                        options = AstroStrategy.entries,
                        selectedOption = preferences.astroStrategy,
                        optionLabel = { it.displayName },
                        onOptionSelected = onAstroStrategyChanged
                    )
                    QuickControlPillSection(
                        label = "Timer",
                        icon = Icons.Outlined.Timer,
                        options = TimerMode.entries,
                        selectedOption = preferences.timerMode,
                        optionLabel = { it.displayName },
                        optionTestTag = { "astro_timer_${it.name.lowercase()}" },
                        onOptionSelected = onTimerChanged
                    )
                    QuickControlPillSection(
                        label = "Aspect Ratio",
                        icon = Icons.Outlined.AspectRatio,
                        options = AspectRatioMode.entries,
                        selectedOption = preferences.aspectRatioMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onAspectRatioChanged
                    )
                    QuickControlPillSection(
                        label = "Grid",
                        icon = Icons.Outlined.GridOn,
                        options = GridMode.entries,
                        selectedOption = preferences.gridMode,
                        optionLabel = { it.displayName },
                        onOptionSelected = onGridChanged
                    )
                }

                CameraMode.SLOW_MOTION -> {
                    val activeSlowMoFr = VideoFrameRate.entries.firstOrNull {
                        it.fps == preferences.slowMoSpeed.expectedFps
                    } ?: VideoFrameRate.FPS_120
                    val resolvedSlowMoRes = verifiedLensCaps?.resolveSlowMoResolution(
                        rate = activeSlowMoFr,
                        preferred = preferences.videoResolution
                    ) ?: VideoResolution.FHD_1080P
                    val slowMoSectionNote = "${preferences.slowMoSpeed.displayName} • ${resolvedSlowMoRes.displayName} output (10s capture → ${(10 * preferences.slowMoSpeed.slowDownFactor).toInt()}s playback)"

                    // Slow Motion Speed
                    QuickControlPillSection(
                        label = "Slow Motion Speed",
                        icon = Icons.Outlined.SlowMotionVideo,
                        options = SlowMoSpeed.entries,
                        selectedOption = preferences.slowMoSpeed,
                        optionLabel = { it.displayName },
                        optionTestTag = { "slow_mo_speed_${it.expectedFps}" },
                        isOptionEnabled = { speed ->
                            val matchingFr = VideoFrameRate.entries.firstOrNull { it.fps == speed.expectedFps }
                            verifiedLensCaps == null || matchingFr == null || matchingFr in verifiedLensCaps.supportedSlowMoRates
                        },
                        supportingNote = slowMoSectionNote,
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
                        optionTestTag = { "night_sight_exposure_option_${it.name.lowercase()}" },
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

                    // Aspect Ratio
                    QuickControlPillSection(
                        label = "Aspect Ratio",
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

                    // Night Sight Hardware & Processing Capability Card
                    val nightSightPathLabel = verifiedLensCaps?.nightSightSupportPath?.displayName
                        ?: com.example.core.camera.NightSightDeviceSupport.resolveNightSightSupportPath(
                            isHardwareNightSceneSupported = false
                        ).displayName
                    val maxBurstFrames = com.example.core.camera.NightSightDeviceSupport.getMaxSupportedFrames()
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("night_sight_capability_card")
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
                                text = "$nightSightPathLabel • Up to ${maxBurstFrames}-frame low-light alignment & shadow tone mapping.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                CameraMode.TIME_LAPSE -> {
                    val activeSpeed = preferences.timeLapseSpeed
                    val intervalLabel = if (activeSpeed.intervalSeconds >= 1f && activeSpeed.intervalSeconds % 1f == 0f) {
                        "${activeSpeed.intervalSeconds.toInt()}s"
                    } else {
                        String.format(java.util.Locale.US, "%.1fs", activeSpeed.intervalSeconds)
                    }
                    val timeLapseNote = "${activeSpeed.shortLabel} (${activeSpeed.description}) • 1 frame every $intervalLabel → ${activeSpeed.multiplier}s capture = 1s video"

                    // Time Lapse Speed
                    QuickControlPillSection(
                        label = "Lapse Speed",
                        icon = Icons.Outlined.Speed,
                        options = TimeLapseSpeed.entries,
                        selectedOption = preferences.timeLapseSpeed,
                        optionLabel = { it.displayName },
                        optionTestTag = { "time_lapse_quick_speed_${it.multiplier}x" },
                        supportingNote = timeLapseNote,
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
                                    text = "Move your phone slowly in one direction along the guide line.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Sweep Direction
                    QuickControlPillSection(
                        label = "Sweep Direction",
                        icon = Icons.Outlined.PanoramaHorizontal,
                        options = PanoramaDirection.entries,
                        selectedOption = preferences.panoramaDirection,
                        optionLabel = { it.shortLabel },
                        optionTestTag = { "panorama_quick_dir_${it.name.lowercase()}" },
                        onOptionSelected = onPanoramaDirectionChanged
                    )

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

                val animatedBgColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        !isEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    animationSpec = com.example.ui.theme.CameraMotionTokens.controlTween(),
                    label = "pill_bg_color"
                )
                val animatedTextColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = com.example.ui.theme.CameraMotionTokens.controlTween(),
                    label = "pill_text_color"
                )

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed && isEnabled) 0.95f else 1.0f,
                    animationSpec = com.example.ui.theme.CameraMotionTokens.touchSpring(),
                    label = "pill_scale"
                )

                val baseModifier = Modifier
                    .weight(1f)
                    .scale(scale)
                val taggedModifier = if (optionTestTag != null) {
                    baseModifier.testTag(optionTestTag(option))
                } else {
                    baseModifier
                }

                Surface(
                    color = animatedBgColor,
                    shape = RoundedCornerShape(12.dp),
                    modifier = taggedModifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = ripple(bounded = true, radius = 24.dp),
                            enabled = isEnabled
                        ) {
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
                        color = animatedTextColor,
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

/**
 * Formats the camera facing and optional lens role without any focal length (mm).
 */
internal fun formatCameraBaseLabelWithoutFocalLength(lensCaps: LensCapabilities): String {
    val facingPrefix = if (lensCaps.lens.facing == LensFacing.FRONT) "Front Camera" else "Rear Camera"
    val desc = lensCaps.sensorDescription.orEmpty()
    val roleSuffix = when {
        lensCaps.lens == CameraLens.BACK_ULTRAWIDE || desc.contains("Ultrawide", ignoreCase = true) -> "Ultrawide"
        lensCaps.lens == CameraLens.BACK_TELEPHOTO || desc.contains("Telephoto", ignoreCase = true) -> "Telephoto"
        else -> null
    }
    return if (roleSuffix != null && lensCaps.lens.facing == LensFacing.BACK) {
        "$facingPrefix · $roleSuffix"
    } else {
        facingPrefix
    }
}

internal fun formatShortVideoResolutionLabel(resolution: VideoResolution): String = when (resolution) {
    VideoResolution.HD_720P -> "720p"
    VideoResolution.FHD_1080P -> "1080p"
    VideoResolution.QHD_2K -> "2K"
    VideoResolution.UHD_4K -> "4K"
}

/**
 * Resolves the highest detected video resolution and the highest FPS available at that resolution.
 * For Slow Motion, prefers the real detected high-speed Slow Motion table.
 */
internal fun resolveVideoCapabilitySummary(
    lensCaps: LensCapabilities,
    activeMode: CameraMode
): String? {
    if (activeMode == CameraMode.SLOW_MOTION && lensCaps.supportedSlowMoResolutionsByRate.isNotEmpty()) {
        val maxFpsBySlowMoRes = LinkedHashMap<VideoResolution, Int>()
        for ((rate, resolutions) in lensCaps.supportedSlowMoResolutionsByRate) {
            for (res in resolutions) {
                val existing = maxFpsBySlowMoRes[res] ?: 0
                if (rate.fps > existing) {
                    maxFpsBySlowMoRes[res] = rate.fps
                }
            }
        }
        val highestSlowMoRes = maxFpsBySlowMoRes.keys.maxByOrNull { it.width.toLong() * it.height.toLong() }
        if (highestSlowMoRes != null) {
            val maxFpsAtRes = maxFpsBySlowMoRes[highestSlowMoRes]
            if (maxFpsAtRes != null && maxFpsAtRes > 0) {
                return "${formatShortVideoResolutionLabel(highestSlowMoRes)} up to ${maxFpsAtRes}fps"
            }
        }
    }

    val supportedResolutions = lensCaps.getSupportedVideoModeResolutions()
    val highestRes = supportedResolutions.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: return null
    val maxFpsAtHighest = lensCaps.getSupportedVideoModeFrameRates(highestRes)
        .maxOfOrNull { it.fps }
        ?.takeIf { it > 0 }
        ?: return null
    return "${formatShortVideoResolutionLabel(highestRes)} up to ${maxFpsAtHighest}fps"
}

/**
 * Reports EIS and/or OIS strictly when the selected camera actually reports them in hardware capabilities.
 * In Video mode, also reflects whether stabilization is available at the currently selected resolution & FPS.
 */
internal fun resolveStabilizationSummary(
    lensCaps: LensCapabilities,
    activeMode: CameraMode,
    preferences: CameraPreferences
): String? {
    val reportsEis = lensCaps.isDigitalVideoStabilizationSupported || lensCaps.isPreviewStabilizationSupported
    val reportsOis = lensCaps.isOpticalStabilizationSupported

    val showEis: Boolean
    val showOis: Boolean
    if (activeMode == CameraMode.VIDEO) {
        val effectiveRes = lensCaps.resolveSupportedResolution(preferences.videoResolution)
        val effectiveFps = lensCaps.resolveSupportedFrameRate(effectiveRes, preferences.videoFrameRate)
        val comboModes = lensCaps.getSupportedStabilizationModes(effectiveRes, effectiveFps)
        val comboHasStab = comboModes.any { it != VideoStabilizationMode.OFF }
        val eisEligibleAtCombo = when (effectiveRes) {
            VideoResolution.HD_720P -> true
            VideoResolution.FHD_1080P -> effectiveFps.fps < 120
            VideoResolution.QHD_2K, VideoResolution.UHD_4K -> effectiveFps.fps <= 30
        }
        showEis = reportsEis && comboHasStab && eisEligibleAtCombo
        showOis = reportsOis && comboHasStab
    } else {
        showEis = reportsEis
        showOis = reportsOis
    }

    return when {
        showEis && showOis -> "EIS+OIS"
        showEis -> "EIS"
        showOis -> "OIS"
        else -> null
    }
}

/**
 * Builds the mode-aware camera details line displayed at the top of Quick Settings.
 * Still modes read strictly from [CaptureResolutionResolver.resolveActualCaptureSize] and
 * [CaptureResolutionResolver.formatMegapixels] so the header always matches the HD card,
 * HD button, Settings, and Debug screen.
 *
 * Examples:
 * - Still modes (Photo, HD, Portrait, Night Sight, Pro, Panorama, Astrophotography):
 *   "Rear Camera · ID 0 · 2.6 MP · EIS" or "Rear Camera · ID 0 · 108 MP · EIS+OIS"
 * - Video modes (Video, Slow Motion, Time Lapse):
 *   "Rear Camera · ID 0 · 2K up to 60fps · EIS+OIS"
 */
internal fun buildQuickSettingsCameraDetailsLine(
    lensCaps: LensCapabilities,
    activeMode: CameraMode,
    preferences: CameraPreferences
): String {
    val parts = mutableListOf<String>()
    parts.add(formatCameraBaseLabelWithoutFocalLength(lensCaps))
    lensCaps.camera2CameraId?.takeIf { it.isNotBlank() }?.let { camId ->
        parts.add("ID $camId")
    }
    if (activeMode.isVideo) {
        resolveVideoCapabilitySummary(lensCaps, activeMode)?.let { parts.add(it) }
    } else {
        val captureSize = CaptureResolutionResolver.resolveActualCaptureSize(
            lensCaps = lensCaps,
            activeMode = activeMode,
            aspectRatioMode = preferences.aspectRatioMode,
            hdOutputOption = preferences.hdOutputOption
        )
        CaptureResolutionResolver.formatMegapixels(captureSize)?.let { parts.add(it) }
    }
    resolveStabilizationSummary(lensCaps, activeMode, preferences)?.let { parts.add(it) }
    return parts.joinToString(" · ")
}
