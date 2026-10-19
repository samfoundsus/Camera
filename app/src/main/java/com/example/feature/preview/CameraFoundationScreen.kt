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
import com.example.core.preferences.AstroDuration
import com.example.core.preferences.AstroFocusMode
import com.example.core.preferences.AstroStrategy
import com.example.feature.preview.components.CameraModeSelector
import com.example.feature.preview.components.CameraPreviewPlaceholder
import com.example.feature.preview.components.CameraProControlBar
import com.example.feature.preview.components.CameraShutterBar
import com.example.feature.preview.components.CameraTopBar
import com.example.core.processing.PanoramaCapturePhase
import com.example.core.processing.PanoramaGuidanceStatus
import com.example.core.processing.PanoramaLiveState
import com.example.core.processing.PanoramaProcessor
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
    var captureConfirmationTrigger by remember { mutableIntStateOf(0) }
    var inAppViewerUri by remember { mutableStateOf<Uri?>(null) }
    var isCountingDown by remember { mutableStateOf(false) }
    var countdownRemainingSeconds by remember { mutableIntStateOf(0) }
    var panoramaLiveState by remember {
        mutableStateOf(PanoramaLiveState(direction = preferences.panoramaDirection))
    }
    var isPanoramaCapturingRequested by remember { mutableStateOf(false) }
    var panoramaFinishTrigger by remember { mutableIntStateOf(0) }
    var panoramaCancelTrigger by remember { mutableIntStateOf(0) }
    var panoramaStepTrigger by remember { mutableIntStateOf(0) }
    var showQuickControlsSheet by remember { mutableStateOf(false) }
    var showMoreModesSheet by remember { mutableStateOf(false) }
    var showFilterTray by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BackHandler(enabled = showFilterTray) {
        showFilterTray = false
    }

    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun startPanoramaCaptureSession() {
        if (panoramaLiveState.phase != PanoramaCapturePhase.IDLE) return
        snackbarHostState.currentSnackbarData?.dismiss()
        isPanoramaCapturingRequested = true
        panoramaLiveState = PanoramaLiveState(
            phase = PanoramaCapturePhase.CAPTURING,
            direction = panoramaLiveState.direction,
            progress = 0f,
            capturedCoverage = 0f,
            capturedFrameCount = 1,
            targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
            alignmentDriftNormalized = 0f,
            normalizedSpeed = 0f,
            guidanceStatus = PanoramaGuidanceStatus.READY,
            isCaptureLagging = false
        )
    }

    fun cancelPanoramaCaptureSession() {
        snackbarHostState.currentSnackbarData?.dismiss()
        isPanoramaCapturingRequested = false
        panoramaCancelTrigger++
        panoramaLiveState = PanoramaLiveState(
            phase = PanoramaCapturePhase.IDLE,
            direction = panoramaLiveState.direction,
            progress = 0f,
            capturedCoverage = 0f,
            capturedFrameCount = 0,
            targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
            alignmentDriftNormalized = 0f,
            normalizedSpeed = 0f,
            guidanceStatus = PanoramaGuidanceStatus.READY,
            isCaptureLagging = false
        )
    }

    fun finishPanoramaCaptureSession() {
        if (panoramaLiveState.phase != PanoramaCapturePhase.CAPTURING) return
        if (panoramaLiveState.capturedFrameCount < PanoramaProcessor.MIN_REQUIRED_FRAMES) {
            cancelPanoramaCaptureSession()
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    "Panorama not saved: move your phone in the selected direction to capture overlapping frames."
                )
            }
            return
        }
        isPanoramaCapturingRequested = false
        panoramaLiveState = panoramaLiveState.copy(
            phase = PanoramaCapturePhase.PROCESSING,
            progress = 1.0f,
            capturedCoverage = 1.0f,
            guidanceStatus = PanoramaGuidanceStatus.STITCHING,
            isCaptureLagging = false
        )
        panoramaFinishTrigger++
    }

    fun stepPanoramaCaptureSession() {
        if (panoramaLiveState.phase != PanoramaCapturePhase.CAPTURING) return
        val nextCount = (panoramaLiveState.capturedFrameCount + 1)
            .coerceAtMost(panoramaLiveState.targetFrameCount)
        val nextCoverage = ((nextCount - 1).coerceAtLeast(0).toFloat() /
            (panoramaLiveState.targetFrameCount - 1).coerceAtLeast(1).toFloat())
            .coerceIn(0f, 1.0f)
        val nextProgress = maxOf(panoramaLiveState.progress, nextCoverage).coerceIn(0f, 1.0f)
        panoramaLiveState = panoramaLiveState.copy(
            capturedFrameCount = nextCount,
            capturedCoverage = nextCoverage,
            progress = nextProgress,
            guidanceStatus = PanoramaGuidanceStatus.MOVING_STEADY,
            isCaptureLagging = false
        )
        panoramaStepTrigger++
    }

    fun updatePanoramaDirectionWhileIdle(dir: com.example.core.preferences.PanoramaDirection) {
        if (panoramaLiveState.phase != PanoramaCapturePhase.IDLE) return
        preferencesRepository.setPanoramaDirection(dir)
        panoramaLiveState = PanoramaLiveState(
            phase = PanoramaCapturePhase.IDLE,
            direction = dir,
            progress = 0f,
            capturedCoverage = 0f,
            capturedFrameCount = 0,
            targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
            alignmentDriftNormalized = 0f,
            normalizedSpeed = 0f,
            guidanceStatus = PanoramaGuidanceStatus.READY,
            isCaptureLagging = false
        )
    }

    LaunchedEffect(preferences.panoramaDirection) {
        if (panoramaLiveState.phase == PanoramaCapturePhase.IDLE &&
            panoramaLiveState.direction != preferences.panoramaDirection
        ) {
            panoramaLiveState = panoramaLiveState.copy(direction = preferences.panoramaDirection)
        }
    }

    LaunchedEffect(selectedMode) {
        if (!selectedMode.supportsFilters && showFilterTray) {
            showFilterTray = false
        }
        if (selectedMode != CameraMode.PANORAMA && panoramaLiveState.phase != PanoramaCapturePhase.IDLE) {
            cancelPanoramaCaptureSession()
        }
    }

    LaunchedEffect(hardwareShutterTrigger) {
        if (hardwareShutterTrigger > 0) {
            when {
                selectedMode.isVideo -> {
                    isRecordingState = !isRecordingState
                    if (!isRecordingState) {
                        recordingDurationSeconds = 0L
                    }
                }
                selectedMode == CameraMode.PANORAMA -> {
                    when (panoramaLiveState.phase) {
                        PanoramaCapturePhase.IDLE -> startPanoramaCaptureSession()
                        PanoramaCapturePhase.CAPTURING -> finishPanoramaCaptureSession()
                        PanoramaCapturePhase.PROCESSING -> {}
                    }
                }
                else -> {
                    photoCaptureTrigger++
                }
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

    // Back button closes open Pro control panel if active, or cancels active Panorama capture
    BackHandler(
        enabled = (selectedMode == CameraMode.PRO && selectedProControl != null) ||
            (selectedMode == CameraMode.PANORAMA && panoramaLiveState.phase == PanoramaCapturePhase.CAPTURING)
    ) {
        if (selectedMode == CameraMode.PANORAMA && panoramaLiveState.phase == PanoramaCapturePhase.CAPTURING) {
            cancelPanoramaCaptureSession()
        } else {
            selectedProControl = null
        }
    }

    val capabilitiesState by capabilitiesRepository.capabilitiesState.collectAsState()
    val activeLens by capabilitiesRepository.activeLens.collectAsState()

    var lastResetMode by remember { mutableStateOf<CameraMode?>(null) }
    var lastResetLens by remember { mutableStateOf<CameraLens?>(null) }

    LaunchedEffect(selectedMode) {
        if (selectedMode != CameraMode.PANORAMA && panoramaLiveState.phase != PanoramaCapturePhase.IDLE) {
            cancelPanoramaCaptureSession()
        }
    }

    // Reset recording state and zoom when switching modes or active lens
    LaunchedEffect(selectedMode, activeLens, capabilitiesState) {
        if (selectedMode != lastResetMode || activeLens != lastResetLens) {
            lastResetMode = selectedMode
            lastResetLens = activeLens
            isRecordingState = false
            recordingDurationSeconds = 0L
            if (isPanoramaCapturingRequested || panoramaLiveState.phase != PanoramaCapturePhase.IDLE) {
                cancelPanoramaCaptureSession()
            }
            if (selectedMode == CameraMode.PRO && selectedProControl == null) {
                selectedProControl = "ISO"
            }
            if (selectedMode.isVideo && !hasAudioPermission) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            val caps = (capabilitiesState as? com.example.core.camera.CameraCapabilitiesState.Verified)?.currentLensCapabilities
            val minZ = caps?.minZoomRatio ?: 1.0f
            val maxZ = caps?.maxZoomRatio ?: 4.0f
            val defaultZoom = 1.0f.coerceIn(minZ, maxZ)
            if (kotlin.math.abs(preferences.proZoomRatio - defaultZoom) > 0.01f) {
                preferencesRepository.setProZoomRatio(defaultZoom)
            }
        }
    }

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
    val nightSightSupportPath = verifiedLensCaps?.nightSightSupportPath
        ?: com.example.core.camera.NightSightDeviceSupport.resolveNightSightSupportPath(
            isHardwareNightSceneSupported = false,
            context = context
        )

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
        preferences.proZoomRatio,
        preferences.slowMoSpeed
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
            val requestedSlowMoFr = com.example.core.camera.VideoFrameRate.entries.firstOrNull {
                it.fps == preferences.slowMoSpeed.expectedFps
            }
            if (caps.supportedSlowMoRates.isNotEmpty() &&
                requestedSlowMoFr != null &&
                requestedSlowMoFr !in caps.supportedSlowMoRates
            ) {
                val fallbackFr = caps.supportedSlowMoRates.first()
                val fallbackSpeed = com.example.core.preferences.SlowMoSpeed.entries.firstOrNull {
                    it.expectedFps == fallbackFr.fps
                } ?: com.example.core.preferences.SlowMoSpeed.SPEED_1_4X
                if (fallbackSpeed != preferences.slowMoSpeed) {
                    preferencesRepository.setSlowMoSpeed(fallbackSpeed)
                }
            }
        } else if (activeLens.facing == com.example.core.camera.LensFacing.FRONT &&
            preferences.flashMode != com.example.core.preferences.FlashMode.OFF
        ) {
            preferencesRepository.setFlashMode(com.example.core.preferences.FlashMode.OFF)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("camera_foundation_screen"),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 260.dp)
            )
        },
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
                hdrMode = preferences.hdrMode,
                onCycleHdr = { preferencesRepository.cycleHdrMode() },
                onQuickControlsClick = { showQuickControlsSheet = true },
                onSettingsClick = onNavigateToSettings,
                isFlashSupported = isFlashSupportedOnActiveLens,
                isVideoMode = selectedMode.isVideo,
                selectedFilter = if (selectedMode.supportsFilters) preferences.selectedFilter else com.example.core.preferences.CameraFilterPreset.ORIGINAL,
                isFilterTrayOpen = selectedMode.supportsFilters && showFilterTray,
                isFiltersSupported = selectedMode.supportsFilters,
                onFiltersClick = {
                    if (selectedMode.supportsFilters) {
                        showFilterTray = !showFilterTray
                    } else {
                        showFilterTray = false
                    }
                },
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
                hdrMode = preferences.hdrMode,
                selectedFilter = if (selectedMode.supportsFilters) preferences.selectedFilter else com.example.core.preferences.CameraFilterPreset.ORIGINAL,
                onFilterBadgeClick = {
                    if (selectedMode.supportsFilters) {
                        showFilterTray = !showFilterTray
                    } else {
                        showFilterTray = false
                    }
                },
                videoResolution = preferences.videoResolution,
                videoFrameRate = preferences.videoFrameRate,
                videoStabilization = preferences.videoStabilization,
                slowMoSpeed = preferences.slowMoSpeed,
                timeLapseSpeed = preferences.timeLapseSpeed,
                onTimeLapseSpeedChanged = { preferencesRepository.setTimeLapseSpeed(it) },
                panoramaDirection = panoramaLiveState.direction,
                onPanoramaDirectionChanged = { dir ->
                    updatePanoramaDirectionWhileIdle(dir)
                },
                panoramaLiveState = panoramaLiveState,
                isPanoramaCapturingRequested = isPanoramaCapturingRequested,
                panoramaFinishTrigger = panoramaFinishTrigger,
                panoramaCancelTrigger = panoramaCancelTrigger,
                panoramaStepTrigger = panoramaStepTrigger,
                onPanoramaStateChanged = { updatedState ->
                    if (selectedMode == CameraMode.PANORAMA && isPanoramaCapturingRequested) {
                        panoramaLiveState = updatedState
                        if (updatedState.phase == PanoramaCapturePhase.IDLE) {
                            isPanoramaCapturingRequested = false
                        }
                    } else if (updatedState.phase == PanoramaCapturePhase.IDLE) {
                        panoramaLiveState = updatedState
                        isPanoramaCapturingRequested = false
                    }
                },
                onStartPanoramaCapture = { startPanoramaCaptureSession() },
                onFinishPanoramaCapture = { finishPanoramaCaptureSession() },
                onCancelPanoramaCapture = { cancelPanoramaCaptureSession() },
                onStepPanoramaCapture = { stepPanoramaCaptureSession() },
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
                nightSightExposure = preferences.nightSightExposure,
                astroDuration = preferences.astroDuration,
                astroFocus = preferences.astroFocus,
                astroStrategy = preferences.astroStrategy,
                hdOutputOption = preferences.hdOutputOption,
                nightSightSupportPath = nightSightSupportPath,
                onNightSightExposureChanged = { preferencesRepository.setNightSightExposure(it) },
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
                    captureConfirmationTrigger++
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
                onCountdownStateChanged = { counting, remaining ->
                    isCountingDown = counting
                    countdownRemainingSeconds = remaining
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
                // Compact, horizontally browsable Filter Selector Tray (PHOTO & VIDEO modes only)
                androidx.compose.animation.AnimatedVisibility(
                    visible = selectedMode.supportsFilters && showFilterTray,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = com.example.ui.theme.CameraMotionTokens.panelTween()
                    ) + androidx.compose.animation.expandVertically(
                        animationSpec = com.example.ui.theme.CameraMotionTokens.panelTween()
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = com.example.ui.theme.CameraMotionTokens.panelTween()
                    ) + androidx.compose.animation.shrinkVertically(
                        animationSpec = com.example.ui.theme.CameraMotionTokens.panelTween()
                    )
                ) {
                    com.example.feature.preview.components.CameraFilterTray(
                        selectedFilter = preferences.selectedFilter,
                        onFilterSelected = { preset ->
                            preferencesRepository.setSelectedFilter(preset)
                        },
                        onResetFilter = {
                            preferencesRepository.resetFilter()
                        },
                        onDismiss = {
                            showFilterTray = false
                        }
                    )
                }

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
                        if (selectedMode == CameraMode.PANORAMA && mode != CameraMode.PANORAMA) {
                            cancelPanoramaCaptureSession()
                        }
                        selectedMode = mode
                    },
                    onMoreClick = {
                        showMoreModesSheet = true
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                CameraShutterBar(
                    activeMode = selectedMode,
                    isRecording = if (selectedMode == CameraMode.PANORAMA) {
                        panoramaLiveState.phase == PanoramaCapturePhase.CAPTURING
                    } else {
                        isRecordingState
                    },
                    isCountingDown = isCountingDown,
                    countdownSeconds = countdownRemainingSeconds,
                    lastCapturedPhotoUri = lastCapturedPhotoUri,
                    captureConfirmationTrigger = captureConfirmationTrigger,
                    onShutterClick = {
                        when {
                            selectedMode.isVideo -> {
                                if (!isRecordingState && !hasAudioPermission) {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                                isRecordingState = !isRecordingState
                            }
                            selectedMode == CameraMode.PANORAMA -> {
                                when (panoramaLiveState.phase) {
                                    PanoramaCapturePhase.IDLE -> startPanoramaCaptureSession()
                                    PanoramaCapturePhase.CAPTURING -> finishPanoramaCaptureSession()
                                    PanoramaCapturePhase.PROCESSING -> {
                                        // Ignore duplicate taps while panorama stitching is in flight
                                    }
                                }
                            }
                            else -> {
                                // Photo, Portrait, Night Sight, HD, Pro, Astro modes execute real image capture
                                photoCaptureTrigger++
                            }
                        }
                    },
                    onGalleryClick = {
                        val uri = lastCapturedPhotoUri
                        if (uri != null) {
                            when (openCapturedMediaUri(context, uri)) {
                                MediaOpenResult.Opened -> {}
                                MediaOpenResult.NoCompatibleViewer -> {
                                    inAppViewerUri = uri
                                }
                                MediaOpenResult.MissingOrInvalidUri -> {
                                    lastCapturedPhotoUri = null
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Saved media file is no longer available")
                                    }
                                }
                                MediaOpenResult.Error -> {
                                    inAppViewerUri = uri
                                }
                            }
                        } else {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    type = if (selectedMode.isVideo) "video/*" else "image/*"
                                    if (context !is android.app.Activity) {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
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
            onHdrModeChanged = { preferencesRepository.setHdrMode(it) },
            onPortraitRetouchChanged = { preferencesRepository.setPortraitRetouch(it) },
            onPortraitBlurLevelChanged = { preferencesRepository.setPortraitBlurLevel(it) },
            onNightSightExposureChanged = { preferencesRepository.setNightSightExposure(it) },
            onTimeLapseSpeedChanged = { preferencesRepository.setTimeLapseSpeed(it) },
            onSlowMoSpeedChanged = { preferencesRepository.setSlowMoSpeed(it) },
            onPanoramaDirectionChanged = { dir ->
                updatePanoramaDirectionWhileIdle(dir)
            },
            onProIsoChanged = { preferencesRepository.setProIso(it) },
            onProShutterChanged = { preferencesRepository.setProShutterSpeed(it) },
            onProFocusModeChanged = { preferencesRepository.setProFocusMode(it) },
            onProFocusDistanceChanged = { preferencesRepository.setProFocusDistance(it) },
            onProWbChanged = { preferencesRepository.setProWhiteBalance(it) },
            onProEvChanged = { preferencesRepository.setProEv(it) },
            onProRawFormatChanged = { preferencesRepository.setProRawFormat(it) },
            onProHistogramToggled = { preferencesRepository.setProHistogramEnabled(it) },
            onProFocusPeakingToggled = { preferencesRepository.setProFocusPeakingEnabled(it) },
            onAstroDurationChanged = { preferencesRepository.setAstroDuration(it) },
            onAstroFocusChanged = { preferencesRepository.setAstroFocus(it) },
            onAstroStrategyChanged = { preferencesRepository.setAstroStrategy(it) },
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

    val activeViewerUri = inAppViewerUri
    if (activeViewerUri != null) {
        InAppMediaViewerDialog(
            uri = activeViewerUri,
            onDismiss = { inAppViewerUri = null }
        )
    }
}

enum class MediaOpenResult {
    Opened,
    NoCompatibleViewer,
    MissingOrInvalidUri,
    Error
}

internal fun isMediaUriVideo(context: android.content.Context, uri: Uri): Boolean {
    val resolverType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    val uriString = uri.toString()
    return resolverType?.startsWith("video/", ignoreCase = true) == true ||
        uriString.contains("video", ignoreCase = true) ||
        uri.lastPathSegment?.endsWith(".mp4", ignoreCase = true) == true
}

internal fun openCapturedMediaUri(context: android.content.Context, uri: Uri): MediaOpenResult {
    if (uri == Uri.EMPTY) return MediaOpenResult.MissingOrInvalidUri
    val scheme = uri.scheme?.lowercase(java.util.Locale.US)
    if (scheme == "file") {
        // file:// URIs cannot be shared externally on Android N+; verify file exists
        val path = uri.path ?: return MediaOpenResult.MissingOrInvalidUri
        val f = java.io.File(path)
        if (!f.exists() || f.length() == 0L) return MediaOpenResult.MissingOrInvalidUri
        return MediaOpenResult.NoCompatibleViewer
    }
    if (scheme == "content") {
        val isReadable = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize != 0L
            } ?: (context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.read() != -1
            } ?: false)
        }.getOrDefault(false)
        if (!isReadable) return MediaOpenResult.MissingOrInvalidUri
    }

    val isVideo = isMediaUriVideo(context, uri)
    val resolvedType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    val primaryMimeType = when {
        isVideo -> resolvedType?.takeIf { it.startsWith("video/", ignoreCase = true) } ?: "video/mp4"
        else -> resolvedType?.takeIf { it.startsWith("image/", ignoreCase = true) } ?: "image/*"
    }

    val primaryIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, primaryMimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    return try {
        context.startActivity(primaryIntent)
        MediaOpenResult.Opened
    } catch (_: android.content.ActivityNotFoundException) {
        val fallbackMimeType = if (isVideo) "video/*" else "image/*"
        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, fallbackMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(fallbackIntent)
            MediaOpenResult.Opened
        } catch (_: android.content.ActivityNotFoundException) {
            MediaOpenResult.NoCompatibleViewer
        } catch (_: Exception) {
            MediaOpenResult.Error
        }
    } catch (_: Exception) {
        MediaOpenResult.Error
    }
}

@Composable
private fun InAppMediaViewerDialog(
    uri: Uri,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isVideo = remember(uri) { isMediaUriVideo(context, uri) }
    var durationLabel by remember(uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(uri, isVideo) {
        if (isVideo) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                if (durationMs > 0L) {
                    val totalSecs = (durationMs + 500L) / 1000L
                    val mins = totalSecs / 60L
                    val secs = totalSecs % 60L
                    val resPart = if (width > 0 && height > 0) " • ${width}×${height}" else ""
                    durationLabel = String.format(java.util.Locale.US, "%02d:%02d%s", mins, secs, resPart)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                text = if (isVideo) {
                    if (durationLabel != null) "Recorded Video ($durationLabel)" else "Recorded Video"
                } else {
                    "Captured Photo"
                }
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .testTag("in_app_media_viewer"),
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                val controller = android.widget.MediaController(ctx)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoURI(uri)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    coil.compose.AsyncImage(
                        model = uri,
                        contentDescription = "Captured photo preview",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Close")
            }
        }
    )
}

