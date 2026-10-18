package com.example.feature.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.camera.CameraCapabilitiesRepository
import com.example.core.camera.CameraLens
import com.example.core.camera.DefaultCameraCapabilitiesRepository
import com.example.core.model.CameraMode
import com.example.core.model.SystemDeviceInfo
import com.example.core.preferences.CameraPreferences
import com.example.core.preferences.CameraPreferencesRepository
import com.example.feature.preview.components.CameraModeSelector
import com.example.feature.preview.components.CameraPreviewPlaceholder
import com.example.feature.preview.components.CameraProControlBar
import com.example.feature.preview.components.CameraShutterBar
import com.example.feature.preview.components.CameraTopBar
import com.example.ui.components.CameraQuickControlsSheet
import kotlinx.coroutines.launch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.feature.preview.components.queryDeviceCameraCapabilities

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraFoundationScreen(
    preferences: CameraPreferences,
    preferencesRepository: CameraPreferencesRepository,
    onNavigateToSettings: () -> Unit,
    deviceInfo: SystemDeviceInfo = remember { SystemDeviceInfo.current() },
    capabilitiesRepository: CameraCapabilitiesRepository = remember { DefaultCameraCapabilitiesRepository() },
    hardwareShutterTrigger: Int = 0,
    hardwareZoomDelta: Float = 0f,
    modifier: Modifier = Modifier
) {
    var selectedMode by remember { mutableStateOf(CameraMode.PHOTO) }
    var selectedProControl by rememberSaveable { mutableStateOf<String?>("ISO") }
    var isRecordingState by remember { mutableStateOf(false) }
    var recordingDurationSeconds by remember { mutableLongStateOf(0L) }
    var photoCaptureTrigger by remember { mutableIntStateOf(0) }
    var lastCapturedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var showQuickControlsSheet by remember { mutableStateOf(false) }
    var showMoreModesSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val context = LocalContext.current

    LaunchedEffect(hardwareShutterTrigger) {
        if (hardwareShutterTrigger > 0) {
            if (selectedMode.isVideo) {
                isRecordingState = !isRecordingState
                if (!isRecordingState) {
                    recordingDurationSeconds = 0L
                }
            } else {
                photoCaptureTrigger++
            }
        }
    }

    LaunchedEffect(hardwareZoomDelta) {
        if (hardwareZoomDelta != 0f) {
            val currentZoom = preferences.proZoomRatio
            val updatedZoom = (currentZoom + hardwareZoomDelta).coerceIn(1.0f, 10.0f)
            preferencesRepository.setProZoomRatio(updatedZoom)
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    LaunchedEffect(Unit) {
        val discovered = queryDeviceCameraCapabilities(context)
        if (discovered.isNotEmpty()) {
            capabilitiesRepository.setDiscoveredCapabilities(discovered)
        }
    }

    // Reset recording state when switching modes and ensure Pro mode has active control tab
    LaunchedEffect(selectedMode) {
        isRecordingState = false
        recordingDurationSeconds = 0L
        if (selectedMode == CameraMode.PRO && selectedProControl == null) {
            selectedProControl = "ISO"
        }
        if (selectedMode.isVideo && !hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Back button closes open Pro control panel if active
    BackHandler(enabled = selectedMode == CameraMode.PRO && selectedProControl != null) {
        selectedProControl = null
    }

    val capabilitiesState by capabilitiesRepository.capabilitiesState.collectAsState()
    val activeLens by capabilitiesRepository.activeLens.collectAsState()

    val verifiedLensCaps = (capabilitiesState as? com.example.core.camera.CameraCapabilitiesState.Verified)?.currentLensCapabilities
    val isFlashSupportedOnActiveLens = when (capabilitiesState) {
        is com.example.core.camera.CameraCapabilitiesState.Verified -> verifiedLensCaps?.isTorchSupported == true
        is com.example.core.camera.CameraCapabilitiesState.PendingDiscovery -> activeLens.facing != com.example.core.camera.LensFacing.FRONT
    }

    val minZoomRatio = verifiedLensCaps?.minZoomRatio ?: 1.0f
    val maxZoomRatio = verifiedLensCaps?.maxZoomRatio ?: 4.0f
    val zoomShortcuts = verifiedLensCaps?.zoomShortcuts
        ?: com.example.core.camera.generateZoomShortcuts(minZoomRatio, maxZoomRatio)
    val isTarget20xZoomSupported = verifiedLensCaps?.isTarget20xZoomSupported == true
    val clampedZoomRatio = com.example.core.camera.clampZoomRatio(
        preferences.proZoomRatio,
        minZoomRatio,
        maxZoomRatio
    )
    val isAutoFocusSupported = verifiedLensCaps?.isAutoFocusSupported ?: true
    val isAutoExposureSupported = verifiedLensCaps?.isAutoExposureSupported ?: true
    val isFocusLockSupported = verifiedLensCaps?.isFocusLockSupported ?: true
    val isExposureCompensationSupported = verifiedLensCaps?.isExposureCompensationSupported ?: true
    val evIndexRange = if (verifiedLensCaps != null) {
        verifiedLensCaps.evIndexRange
    } else {
        -6..6
    }
    val evStep = verifiedLensCaps?.evStep ?: (1f / 3f)
    val portraitDepthSupportPath = verifiedLensCaps?.portraitDepthSupportPath
        ?: com.example.core.camera.PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY

    // Reconcile video resolution, frame rate, stabilization, torch/flash, and zoom whenever active camera, mode, or capabilities change
    LaunchedEffect(
        capabilitiesState,
        activeLens,
        selectedMode,
        isRecordingState,
        preferences.videoResolution,
        preferences.videoFrameRate,
        preferences.videoStabilization,
        preferences.flashMode,
        preferences.proZoomRatio
    ) {
        val caps = (capabilitiesState as? com.example.core.camera.CameraCapabilitiesState.Verified)?.currentLensCapabilities
        if (caps != null) {
            val clampedZoom = caps.clampZoom(preferences.proZoomRatio)
            if (kotlin.math.abs(clampedZoom - preferences.proZoomRatio) > 0.05f) {
                preferencesRepository.setProZoomRatio(clampedZoom)
            }
        }
        if (isRecordingState) return@LaunchedEffect
        if (caps != null) {
            val reconciled = caps.reconcileVideoSettings(
                requestedResolution = preferences.videoResolution,
                requestedFrameRate = preferences.videoFrameRate,
                requestedStabilization = preferences.videoStabilization,
                requestedFlashMode = preferences.flashMode
            )
            if (reconciled.resolution != preferences.videoResolution) {
                preferencesRepository.setVideoResolution(reconciled.resolution)
            }
            if (reconciled.frameRate != preferences.videoFrameRate) {
                preferencesRepository.setVideoFrameRate(reconciled.frameRate)
            }
            if (reconciled.stabilization != preferences.videoStabilization) {
                preferencesRepository.setVideoStabilization(reconciled.stabilization)
            }
            if (reconciled.flashMode != preferences.flashMode) {
                preferencesRepository.setFlashMode(reconciled.flashMode)
            }
        } else if (activeLens.facing == com.example.core.camera.LensFacing.FRONT &&
            preferences.flashMode != com.example.core.preferences.FlashMode.OFF
        ) {
            preferencesRepository.setFlashMode(com.example.core.preferences.FlashMode.OFF)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("camera_foundation_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Clean minimal camera top bar with proper insets
            CameraTopBar(
                flashMode = preferences.flashMode,
                onFlashModeSelected = { mode ->
                    if (mode == com.example.core.preferences.FlashMode.OFF || isFlashSupportedOnActiveLens) {
                        preferencesRepository.setFlashMode(mode)
                    }
                },
                onCycleFlash = {
                    if (isFlashSupportedOnActiveLens) {
                        preferencesRepository.cycleFlashMode()
                    } else {
                        preferencesRepository.setFlashMode(com.example.core.preferences.FlashMode.OFF)
                    }
                },
                onQuickControlsClick = { showQuickControlsSheet = true },
                onSettingsClick = onNavigateToSettings,
                isFlashSupported = isFlashSupportedOnActiveLens,
                isVideoMode = selectedMode.isVideo,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Outer camera preview container - fills available content width edge-to-edge
            CameraPreviewPlaceholder(
                activeMode = selectedMode,
                aspectRatioMode = preferences.aspectRatioMode,
                gridMode = preferences.gridMode,
                timerMode = preferences.timerMode,
                flashMode = preferences.flashMode,
                videoResolution = preferences.videoResolution,
                videoFrameRate = preferences.videoFrameRate,
                videoStabilization = preferences.videoStabilization,
                zoomRatio = clampedZoomRatio,
                minZoomRatio = minZoomRatio,
                maxZoomRatio = maxZoomRatio,
                zoomShortcuts = zoomShortcuts,
                isTarget20xZoomSupported = isTarget20xZoomSupported,
                isAutoFocusSupported = isAutoFocusSupported,
                isAutoExposureSupported = isAutoExposureSupported,
                isFocusLockSupported = isFocusLockSupported,
                isExposureCompensationSupported = isExposureCompensationSupported,
                evIndexRange = evIndexRange,
                evStep = evStep,
                proEv = if (selectedMode == CameraMode.PRO) preferences.proEv else 0f,
                isSelfieMirrorEnabled = preferences.selfieMirror,
                isSaveLocationEnabled = preferences.saveLocation,
                isCameraSoundsEnabled = preferences.cameraSoundsEnabled,
                proHistogramEnabled = selectedMode == CameraMode.PRO && preferences.proHistogramEnabled,
                proFocusPeakingEnabled = selectedMode == CameraMode.PRO && preferences.proFocusPeakingEnabled,
                isRecording = isRecordingState,
                recordingDurationSeconds = recordingDurationSeconds,
                activeLens = activeLens,
                manualIso = if (selectedMode == CameraMode.PRO) preferences.proIso else "AUTO",
                manualShutterSpeed = if (selectedMode == CameraMode.PRO) preferences.proShutterSpeed else "AUTO",
                proFocusMode = if (selectedMode == CameraMode.PRO) preferences.proFocusMode else com.example.core.preferences.ProFocusMode.AUTO,
                proFocusDistance = if (selectedMode == CameraMode.PRO) preferences.proFocusDistance else 0f,
                proWhiteBalance = if (selectedMode == CameraMode.PRO) preferences.proWhiteBalance else "AUTO",
                proRawFormat = if (selectedMode == CameraMode.PRO) preferences.proRawFormat else com.example.core.preferences.ProRawFormat.JPEG_ONLY,
                portraitBlurLevel = preferences.portraitBlurLevel,
                portraitBlurStrength = preferences.portraitBlurStrength,
                portraitRetouch = preferences.portraitRetouch,
                portraitDepthSupportPath = portraitDepthSupportPath,
                onPortraitBlurLevelChanged = { preferencesRepository.setPortraitBlurLevel(it) },
                onPortraitBlurStrengthChanged = { preferencesRepository.setPortraitBlurStrength(it) },
                photoCaptureTrigger = photoCaptureTrigger,
                onCapabilitiesDiscovered = { discovered ->
                    capabilitiesRepository.setDiscoveredCapabilities(discovered)
                },
                onVideoConfigApplied = { reconciled ->
                    if (reconciled.resolution != preferences.videoResolution) {
                        preferencesRepository.setVideoResolution(reconciled.resolution)
                    }
                    if (reconciled.frameRate != preferences.videoFrameRate) {
                        preferencesRepository.setVideoFrameRate(reconciled.frameRate)
                    }
                    if (reconciled.stabilization != preferences.videoStabilization) {
                        preferencesRepository.setVideoStabilization(reconciled.stabilization)
                    }
                    if (reconciled.flashMode != preferences.flashMode) {
                        preferencesRepository.setFlashMode(reconciled.flashMode)
                    }
                },
                onZoomRatioChanged = { updatedZoom ->
                    val clamped = com.example.core.camera.clampZoomRatio(updatedZoom, minZoomRatio, maxZoomRatio)
                    if (kotlin.math.abs(clamped - preferences.proZoomRatio) > 0.01f) {
                        preferencesRepository.setProZoomRatio(clamped)
                    }
                },
                onCaptureSuccess = { uri ->
                    lastCapturedPhotoUri = uri
                },
                onCaptureError = { errorMsg ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(errorMsg)
                    }
                },
                onRecordingStateChanged = { recording ->
                    isRecordingState = recording
                    if (!recording) {
                        recordingDurationSeconds = 0L
                    }
                },
                onRecordingDurationChanged = { seconds ->
                    recordingDurationSeconds = seconds
                },
                onRecordingSuccess = { uri ->
                    lastCapturedPhotoUri = uri
                },
                onRecordingError = { errorMsg ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(errorMsg)
                    }
                },
                onPreviewClick = {
                    if (selectedMode == CameraMode.PRO && selectedProControl != null) {
                        selectedProControl = null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Bottom camera controls section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Interactive Pro Mode Control Bar
                if (selectedMode == CameraMode.PRO) {
                    CameraProControlBar(
                        preferences = preferences,
                        capabilitiesState = capabilitiesState,
                        selectedProControl = selectedProControl,
                        onSelectProControl = { selectedProControl = it },
                        onIsoChanged = { preferencesRepository.setProIso(it) },
                        onShutterChanged = { preferencesRepository.setProShutterSpeed(it) },
                        onFocusModeChanged = { preferencesRepository.setProFocusMode(it) },
                        onFocusDistanceChanged = { preferencesRepository.setProFocusDistance(it) },
                        onWbChanged = { preferencesRepository.setProWhiteBalance(it) },
                        onEvChanged = { preferencesRepository.setProEv(it) },
                        onRawFormatChanged = { preferencesRepository.setProRawFormat(it) },
                        onZoomRatioChanged = { preferencesRepository.setProZoomRatio(it) },
                        onHistogramToggled = { preferencesRepository.setProHistogramEnabled(it) },
                        onFocusPeakingToggled = { preferencesRepository.setProFocusPeakingEnabled(it) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Pixel mode carousel with auto-centering for all modes
                CameraModeSelector(
                    selectedMode = selectedMode,
                    visibleModes = preferences.visibleModes,
                    hiddenModes = preferences.hiddenModes,
                    onModeSelected = { mode ->
                        selectedMode = mode
                    },
                    onMoreClick = {
                        showMoreModesSheet = true
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                CameraShutterBar(
                    activeMode = selectedMode,
                    isRecording = isRecordingState,
                    lastCapturedPhotoUri = lastCapturedPhotoUri,
                    onShutterClick = {
                        if (selectedMode.isVideo) {
                            if (!isRecordingState && !hasAudioPermission) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            isRecordingState = !isRecordingState
                        } else {
                            // Photo, Portrait, Night Sight, HD, Pro, Panorama modes execute real image capture
                            photoCaptureTrigger++
                        }
                    },
                    onGalleryClick = {
                        val uri = lastCapturedPhotoUri
                        if (uri != null) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Failed to open gallery")
                                }
                            }
                        } else {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    type = "image/*"
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("No gallery application available")
                                }
                            }
                        }
                    },
                    onFlipCameraClick = {
                        if (isRecordingState) {
                            isRecordingState = false
                            recordingDurationSeconds = 0L
                        }
                        val nextLens = if (activeLens == CameraLens.BACK_MAIN) {
                            CameraLens.FRONT
                        } else {
                            CameraLens.BACK_MAIN
                        }
                        val nextLensCaps = (capabilitiesState as? com.example.core.camera.CameraCapabilitiesState.Verified)
                            ?.capabilitiesByLens?.get(nextLens)
                        val defaultNextZoom = nextLensCaps?.clampZoom(1.0f) ?: 1.0f
                        preferencesRepository.setProZoomRatio(defaultNextZoom)
                        if (nextLensCaps?.isManualFocusSupported == false && preferences.proFocusMode == com.example.core.preferences.ProFocusMode.MANUAL) {
                            preferencesRepository.setProFocusMode(com.example.core.preferences.ProFocusMode.AUTO)
                        }
                        capabilitiesRepository.setActiveLens(nextLens)
                    }
                )
            }
        }
    }

    // Quick controls bottom sheet with mode-specific and device-aware capabilities
    if (showQuickControlsSheet) {
        CameraQuickControlsSheet(
            activeMode = selectedMode,
            capabilitiesState = capabilitiesState,
            preferences = preferences,
            onFlashChanged = { preferencesRepository.setFlashMode(it) },
            onTimerChanged = { preferencesRepository.setTimerMode(it) },
            onAspectRatioChanged = { preferencesRepository.setAspectRatioMode(it) },
            onGridChanged = { preferencesRepository.setGridMode(it) },
            onVideoResolutionChanged = { preferencesRepository.setVideoResolution(it) },
            onVideoFpsChanged = { preferencesRepository.setVideoFrameRate(it) },
            onVideoStabilizationChanged = { preferencesRepository.setVideoStabilization(it) },
            onHdOptionChanged = { preferencesRepository.setHdOutputOption(it) },
            onPortraitRetouchChanged = { preferencesRepository.setPortraitRetouch(it) },
            onPortraitBlurLevelChanged = { preferencesRepository.setPortraitBlurLevel(it) },
            onNightSightExposureChanged = { preferencesRepository.setNightSightExposure(it) },
            onTimeLapseSpeedChanged = { preferencesRepository.setTimeLapseSpeed(it) },
            onSlowMoSpeedChanged = { preferencesRepository.setSlowMoSpeed(it) },
            onProIsoChanged = { preferencesRepository.setProIso(it) },
            onProShutterChanged = { preferencesRepository.setProShutterSpeed(it) },
            onProFocusModeChanged = { preferencesRepository.setProFocusMode(it) },
            onProFocusDistanceChanged = { preferencesRepository.setProFocusDistance(it) },
            onProWbChanged = { preferencesRepository.setProWhiteBalance(it) },
            onProEvChanged = { preferencesRepository.setProEv(it) },
            onProRawFormatChanged = { preferencesRepository.setProRawFormat(it) },
            onProHistogramToggled = { preferencesRepository.setProHistogramEnabled(it) },
            onProFocusPeakingToggled = { preferencesRepository.setProFocusPeakingEnabled(it) },
            onOpenFullSettings = onNavigateToSettings,
            onDismissRequest = { showQuickControlsSheet = false },
            sheetState = sheetState
        )
    }

    // More modes bottom sheet for selecting hidden modes
    if (showMoreModesSheet) {
        val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        com.example.ui.components.MoreCameraModesSheet(
            hiddenModes = preferences.hiddenModes,
            onModeSelected = { mode -> selectedMode = mode },
            onDismissRequest = { showMoreModesSheet = false },
            sheetState = moreSheetState
        )
    }
}
