package com.example.feature.preview.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.example.core.processing.LiveHistogramData
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.camera.CameraLens
import com.example.core.camera.LensCapabilities
import com.example.core.camera.NightSightSupportPath
import com.example.core.camera.PortraitBlurDeviceSupport
import com.example.core.camera.PortraitDepthSupportPath
import com.example.core.camera.ReconciledVideoSettings
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.HdOutputOption
import com.example.core.preferences.AstroDuration
import com.example.core.preferences.AstroFocusMode
import com.example.core.preferences.AstroStrategy
import com.example.core.preferences.HdrMode
import com.example.core.preferences.GridMode
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.preferences.ProFocusMode
import com.example.core.preferences.ProRawFormat
import com.example.core.preferences.TimerMode
import com.example.core.preferences.TimeLapseSpeed
import com.example.core.preferences.PanoramaDirection
import com.example.core.preferences.VideoStabilizationMode
import com.example.core.preferences.SlowMoSpeed
import com.example.core.processing.PanoramaCapturePhase
import com.example.core.processing.PanoramaGuidanceStatus
import com.example.core.processing.PanoramaLiveState
import com.example.core.processing.PanoramaProcessor

import android.net.Uri
import com.example.core.preferences.FlashMode

import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Slider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.core.camera.FocusIndicatorStatus
import com.example.core.camera.FocusTouchRequest
import com.example.core.camera.TARGET_MAX_ZOOM_RATIO
import com.example.core.camera.calculatePinchZoomRatio
import com.example.core.camera.clampExposureIndex
import com.example.core.camera.clampZoomRatio
import com.example.core.camera.formatEvValue
import com.example.core.camera.formatZoomRatio
import com.example.core.camera.generateZoomShortcuts
import com.example.core.camera.isFocusEvSliderEnabledForMode
import com.example.core.camera.isTapToFocusEnabledForMode
import com.example.core.camera.resolveSupportedZoomRange
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SliderExpansionState {
    NONE,
    BLUR,
    ZOOM,
    TIME_LAPSE_SPEED
}

fun zoomShortcutTag(ratio: Float): String {
    val rounded = (ratio * 10f).roundToInt() / 10f
    return if (abs(rounded - rounded.roundToInt()) < 0.05f) {
        "zoom_shortcut_${rounded.roundToInt()}x"
    } else {
        "zoom_shortcut_${String.format(Locale.US, "%.1f", rounded).replace('.', '_')}x"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraPreviewPlaceholder(
    activeMode: CameraMode,
    aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3,
    gridMode: GridMode = GridMode.GRID_3X3,
    timerMode: TimerMode = TimerMode.OFF,
    flashMode: FlashMode = FlashMode.AUTO,
    videoResolution: VideoResolution = VideoResolution.FHD_1080P,
    videoFrameRate: VideoFrameRate = VideoFrameRate.FPS_30,
    videoStabilization: VideoStabilizationMode = VideoStabilizationMode.STANDARD,
    zoomRatio: Float = 1.0f,
    minZoomRatio: Float = 1.0f,
    maxZoomRatio: Float = 4.0f,
    zoomShortcuts: List<Float> = listOf(1.0f, 2.0f, 4.0f),
    isTarget20xZoomSupported: Boolean = false,
    isAutoFocusSupported: Boolean = true,
    isAutoExposureSupported: Boolean = true,
    isFocusLockSupported: Boolean = true,
    isExposureCompensationSupported: Boolean = true,
    evIndexRange: IntRange = -6..6,
    evStep: Float = 1f / 3f,
    proEv: Float = 0.0f,
    isSelfieMirrorEnabled: Boolean = true,
    isSaveLocationEnabled: Boolean = false,
    isCameraSoundsEnabled: Boolean = true,
    proHistogramEnabled: Boolean = false,
    proFocusPeakingEnabled: Boolean = false,
    isRecording: Boolean = false,
    recordingDurationSeconds: Long = 0L,
    activeLens: CameraLens = CameraLens.BACK_MAIN,
    manualIso: String = "AUTO",
    manualShutterSpeed: String = "AUTO",
    proFocusMode: ProFocusMode = ProFocusMode.AUTO,
    proFocusDistance: Float = 0.0f,
    proWhiteBalance: String = "AUTO",
    proRawFormat: ProRawFormat = ProRawFormat.JPEG_ONLY,
    portraitBlurLevel: PortraitBlurLevel = PortraitBlurLevel.MEDIUM,
    portraitBlurStrength: Float = portraitBlurLevel.blurStrength,
    portraitRetouch: PortraitRetouch = PortraitRetouch.NATURAL,
    portraitDepthSupportPath: PortraitDepthSupportPath = PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY,
    onPortraitBlurLevelChanged: ((PortraitBlurLevel) -> Unit)? = null,
    onPortraitBlurStrengthChanged: ((Float) -> Unit)? = null,
    nightSightExposure: NightSightExposure = NightSightExposure.AUTO,
    astroDuration: AstroDuration = AstroDuration.AUTO,
    astroFocus: AstroFocusMode = AstroFocusMode.AUTO,
    astroStrategy: AstroStrategy = AstroStrategy.AUTO,
    hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE,
    slowMoSpeed: SlowMoSpeed = SlowMoSpeed.SPEED_1_4X,
    timeLapseSpeed: TimeLapseSpeed = TimeLapseSpeed.SPEED_30X,
    onTimeLapseSpeedChanged: ((TimeLapseSpeed) -> Unit)? = null,
    panoramaDirection: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT,
    onPanoramaDirectionChanged: ((PanoramaDirection) -> Unit)? = null,
    panoramaLiveState: PanoramaLiveState = PanoramaLiveState(direction = panoramaDirection),
    isPanoramaCapturingRequested: Boolean = false,
    panoramaFinishTrigger: Int = 0,
    panoramaCancelTrigger: Int = 0,
    panoramaStepTrigger: Int = 0,
    onPanoramaStateChanged: ((PanoramaLiveState) -> Unit)? = null,
    onStartPanoramaCapture: (() -> Unit)? = null,
    onFinishPanoramaCapture: (() -> Unit)? = null,
    onCancelPanoramaCapture: (() -> Unit)? = null,
    onStepPanoramaCapture: (() -> Unit)? = null,
    hdrMode: com.example.core.preferences.HdrMode = com.example.core.preferences.HdrMode.AUTO,
    selectedFilter: com.example.core.preferences.CameraFilterPreset = com.example.core.preferences.CameraFilterPreset.ORIGINAL,
    onFilterBadgeClick: (() -> Unit)? = null,
    nightSightSupportPath: NightSightSupportPath = NightSightSupportPath.MULTI_FRAME_LOW_LIGHT_FUSION,
    onNightSightExposureChanged: ((NightSightExposure) -> Unit)? = null,
    photoCaptureTrigger: Int = 0,
    onCapabilitiesDiscovered: ((Map<CameraLens, LensCapabilities>) -> Unit)? = null,
    onVideoConfigApplied: ((ReconciledVideoSettings) -> Unit)? = null,
    onZoomRatioChanged: ((Float) -> Unit)? = null,
    onFocusTapRequested: ((FocusTouchRequest) -> Unit)? = null,
    onExposureIndexChanged: ((Int) -> Unit)? = null,
    onCaptureSuccess: ((Uri) -> Unit)? = null,
    onCaptureError: ((String) -> Unit)? = null,
    onRecordingStateChanged: ((Boolean) -> Unit)? = null,
    onRecordingDurationChanged: ((Long) -> Unit)? = null,
    onRecordingSuccess: ((Uri) -> Unit)? = null,
    onRecordingError: ((String) -> Unit)? = null,
    onCountdownStateChanged: ((Boolean, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onPreviewClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var expandedSliderState by remember { mutableStateOf(SliderExpansionState.NONE) }
    var previewPixelSize by remember { mutableStateOf(IntSize.Zero) }

    // Non-Pro tap-to-focus, tap-and-hold focus lock, and exposure compensation state
    var activeFocusRequest by remember { mutableStateOf<FocusTouchRequest?>(null) }
    var focusIndicatorStatus by remember { mutableStateOf(FocusIndicatorStatus.IDLE) }
    var autoExposureIndex by remember { mutableIntStateOf(0) }
    var autoExposureSliderFloat by remember { mutableStateOf(0f) }
    var focusRequestSequence by remember { mutableLongStateOf(0L) }
    var exposureInteractionTick by remember { mutableLongStateOf(0L) }
    var liveHistogramData by remember { mutableStateOf<LiveHistogramData?>(null) }

    val safeEvMin = minOf(evIndexRange.first, evIndexRange.last)
    val safeEvMax = maxOf(evIndexRange.first, evIndexRange.last)
    val isFocusEvEnabledForMode = isFocusEvSliderEnabledForMode(activeMode)
    val effectiveEvSupported = isExposureCompensationSupported && (safeEvMin < 0 || safeEvMax > 0)
    val safeEvRange = safeEvMin..safeEvMax
    val effectiveFilter = com.example.core.processing.CameraFilterProcessor.resolveEffectiveFilter(activeMode, selectedFilter)

    // Clear stale focus regions, focus lock, and automatic exposure state when switching camera lens or mode
    LaunchedEffect(activeLens, activeMode) {
        expandedSliderState = SliderExpansionState.NONE
        activeFocusRequest = null
        focusIndicatorStatus = FocusIndicatorStatus.IDLE
        autoExposureSliderFloat = 0f
        if (autoExposureIndex != 0) {
            autoExposureIndex = 0
            onExposureIndexChanged?.invoke(0)
        }
        if (activeMode != CameraMode.PRO) {
            liveHistogramData = null
        }
    }

    // Clamp active exposure compensation index if the active camera's EV range changes
    LaunchedEffect(safeEvMin, safeEvMax, effectiveEvSupported) {
        if (!effectiveEvSupported) {
            autoExposureSliderFloat = 0f
            if (autoExposureIndex != 0) {
                autoExposureIndex = 0
                onExposureIndexChanged?.invoke(0)
            }
        } else {
            val clamped = clampExposureIndex(autoExposureIndex, safeEvRange)
            autoExposureSliderFloat = autoExposureSliderFloat.coerceIn(safeEvMin.toFloat(), safeEvMax.toFloat())
            if (clamped != autoExposureIndex) {
                autoExposureIndex = clamped
                autoExposureSliderFloat = clamped.toFloat()
                onExposureIndexChanged?.invoke(clamped)
            }
        }
    }

    // Transition FOCUSING -> FOCUSED if camera hardware callback hasn't fired yet (e.g. in preview/JVM test mode)
    // and auto-dismiss unlocked focus indicator after 6 seconds of inactivity
    LaunchedEffect(activeFocusRequest, focusIndicatorStatus, exposureInteractionTick) {
        val req = activeFocusRequest ?: return@LaunchedEffect
        if (focusIndicatorStatus == FocusIndicatorStatus.FOCUSING && !req.isLockRequested) {
            delay(320L)
            if (activeFocusRequest?.requestId == req.requestId &&
                focusIndicatorStatus == FocusIndicatorStatus.FOCUSING
            ) {
                focusIndicatorStatus = when {
                    isAutoFocusSupported -> FocusIndicatorStatus.FOCUSED
                    isAutoExposureSupported || (effectiveEvSupported && isFocusEvEnabledForMode) -> FocusIndicatorStatus.METERING_ONLY
                    else -> FocusIndicatorStatus.UNSUPPORTED
                }
            }
        }
        if (!req.isLockRequested && !focusIndicatorStatus.isLockedState) {
            delay(6000L)
            if (activeFocusRequest?.requestId == req.requestId && !req.isLockRequested) {
                activeFocusRequest = null
                focusIndicatorStatus = FocusIndicatorStatus.IDLE
            }
        }
    }

    val safeRange = resolveSupportedZoomRange(minZoomRatio, maxZoomRatio, TARGET_MAX_ZOOM_RATIO)
    val effectiveMin = safeRange.start
    val effectiveMax = safeRange.endInclusive
    val clampedZoom = clampZoomRatio(zoomRatio, effectiveMin, effectiveMax)
    val latestClampedZoom by rememberUpdatedState(clampedZoom)
    val latestOnZoomRatioChanged by rememberUpdatedState(onZoomRatioChanged)
    val latestOnPreviewClick by rememberUpdatedState(onPreviewClick)
    val latestOnFocusTapRequested by rememberUpdatedState(onFocusTapRequested)
    val latestOnExposureIndexChanged by rememberUpdatedState(onExposureIndexChanged)
    val latestIsAutoFocusSupported by rememberUpdatedState(isAutoFocusSupported)
    val latestIsAutoExposureSupported by rememberUpdatedState(isAutoExposureSupported)
    val latestIsFocusLockSupported by rememberUpdatedState(isFocusLockSupported)
    val latestEffectiveEvSupported by rememberUpdatedState(effectiveEvSupported)
    val latestPreviewPixelSize by rememberUpdatedState(previewPixelSize)

    val latestIsFocusEvEnabledForMode by rememberUpdatedState(isFocusEvEnabledForMode)

    // Tracks whether a pointer gesture began inside a child control overlay (zoom bar, zoom slider, exposure panel)
    val childOverlayTouchTracker = remember {
        object {
            var isTouchInsideChild: Boolean = false
        }
    }

    val triggerFocusAction: (Offset, Boolean) -> Unit = { tapOffset, isLock ->
        if (isTapToFocusEnabledForMode(activeMode)) {
            val widthPx = if (latestPreviewPixelSize.width > 0) latestPreviewPixelSize.width.toFloat() else 1080f
            val heightPx = if (latestPreviewPixelSize.height > 0) latestPreviewPixelSize.height.toFloat() else 1440f
            val nextId = focusRequestSequence + 1L
            focusRequestSequence = nextId
            val req = FocusTouchRequest(
                requestId = nextId,
                tapX = tapOffset.x.coerceIn(0f, widthPx),
                tapY = tapOffset.y.coerceIn(0f, heightPx),
                previewWidth = widthPx,
                previewHeight = heightPx,
                isLockRequested = isLock,
                isAfRequested = latestIsAutoFocusSupported,
                isAeRequested = latestIsAutoExposureSupported
            )
            // Immediately reset exposure compensation to neutral (0 EV) on every new focus point or focus-lock gesture
            autoExposureSliderFloat = 0f
            autoExposureIndex = 0
            latestOnExposureIndexChanged?.invoke(0)

            val canUseEvFallback = latestEffectiveEvSupported && latestIsFocusEvEnabledForMode
            activeFocusRequest = req
            focusIndicatorStatus = if (isLock) {
                when {
                    latestIsAutoFocusSupported && latestIsFocusLockSupported -> FocusIndicatorStatus.LOCKED
                    latestIsAutoExposureSupported || canUseEvFallback -> FocusIndicatorStatus.LOCKED_METERING_ONLY
                    else -> FocusIndicatorStatus.UNSUPPORTED
                }
            } else {
                when {
                    latestIsAutoFocusSupported -> FocusIndicatorStatus.FOCUSING
                    latestIsAutoExposureSupported || canUseEvFallback -> FocusIndicatorStatus.METERING_ONLY
                    else -> FocusIndicatorStatus.UNSUPPORTED
                }
            }
            latestOnFocusTapRequested?.invoke(req)
        }
    }
    val latestTriggerFocusAction by rememberUpdatedState(triggerFocusAction)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Auto-request camera permission on first display if not yet granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val availWidth = maxWidth
        val availHeight = maxHeight

        val frameModifier = when (aspectRatioMode) {
            AspectRatioMode.RATIO_4_3 -> {
                val calcHeight = availWidth / (3f / 4f)
                if (calcHeight <= availHeight) {
                    Modifier
                        .fillMaxWidth()
                        .height(calcHeight)
                } else {
                    Modifier.fillMaxSize()
                }
            }
            AspectRatioMode.RATIO_1_1 -> {
                val calcHeight = availWidth
                if (calcHeight <= availHeight) {
                    Modifier
                        .fillMaxWidth()
                        .height(calcHeight)
                } else {
                    Modifier.fillMaxSize()
                }
            }
            AspectRatioMode.RATIO_9_16 -> Modifier.fillMaxSize()
        }

        Box(
            modifier = frameModifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D0E10))
                .onSizeChanged { previewPixelSize = it }
                .pointerInput(activeLens, activeMode, effectiveMin, effectiveMax) {
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis.coerceAtLeast(300L)
                    awaitEachGesture {
                        childOverlayTouchTracker.isTouchInsideChild = false
                        val firstDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val initialDownPos = firstDown.position
                        val downUptimeMillis = firstDown.uptimeMillis
                        var latestUptimeMillis = downUptimeMillis
                        var pinchZoomAccumulator = latestClampedZoom
                        var isMultiFingerPinch = false
                        var exceededSingleTapSlop = false
                        var consumedByChild = false
                        var longPressTriggered = false

                        // Wait for Main pass of the initial DOWN event so any child control overlay marks isTouchInsideChild first
                        val initialMainEvent = awaitPointerEvent(PointerEventPass.Main)
                        if (childOverlayTouchTracker.isTouchInsideChild || initialMainEvent.changes.any { it.isConsumed }) {
                            consumedByChild = true
                        }
                        val initialPressed = initialMainEvent.changes.filter { it.pressed }
                        if (initialPressed.size >= 2 && activeMode != CameraMode.PANORAMA && effectiveMax > effectiveMin) {
                            isMultiFingerPinch = true
                            pinchZoomAccumulator = latestClampedZoom
                        }

                        var anyPressed = initialPressed.isNotEmpty()

                        // If single finger landed on open preview in a non-Pro mode, monitor for long-press hold timeout
                        if (anyPressed &&
                            !consumedByChild &&
                            !isMultiFingerPinch &&
                            isTapToFocusEnabledForMode(activeMode)
                        ) {
                            val finishedBeforeLongPress = withTimeoutOrNull(longPressTimeoutMs) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val pressedChanges = event.changes.filter { it.pressed }
                                    event.changes.firstOrNull()?.let { latestUptimeMillis = it.uptimeMillis }

                                    if (childOverlayTouchTracker.isTouchInsideChild || event.changes.any { it.isConsumed }) {
                                        consumedByChild = true
                                        break
                                    }

                                    if (pressedChanges.size >= 2) {
                                        if (activeMode != CameraMode.PANORAMA && effectiveMax > effectiveMin) {
                                            isMultiFingerPinch = true
                                            pinchZoomAccumulator = latestClampedZoom
                                            val zoomFactor = event.calculateZoom()
                                            if (zoomFactor.isFinite() && zoomFactor > 0f && abs(zoomFactor - 1.0f) > 0.0005f) {
                                                pinchZoomAccumulator = calculatePinchZoomRatio(
                                                    currentZoomRatio = pinchZoomAccumulator,
                                                    pinchScaleFactor = zoomFactor,
                                                    minZoomRatio = effectiveMin,
                                                    maxZoomRatio = effectiveMax
                                                )
                                                latestOnZoomRatioChanged?.invoke(
                                                    clampZoomRatio(pinchZoomAccumulator, effectiveMin, effectiveMax)
                                                )
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                        break
                                    }

                                    for (change in event.changes) {
                                        if ((change.position - initialDownPos).getDistance() > touchSlop) {
                                            exceededSingleTapSlop = true
                                        }
                                    }
                                    if (exceededSingleTapSlop) {
                                        break
                                    }

                                    if (pressedChanges.isEmpty()) {
                                        anyPressed = false
                                        break
                                    }
                                }
                                true
                            }

                            if (finishedBeforeLongPress == null &&
                                !consumedByChild &&
                                !isMultiFingerPinch &&
                                !exceededSingleTapSlop
                            ) {
                                longPressTriggered = true
                                if (expandedSliderState != SliderExpansionState.NONE) {
                                    expandedSliderState = SliderExpansionState.NONE
                                } else {
                                    latestTriggerFocusAction(initialDownPos, true)
                                }
                            }
                        }

                        // Continue tracking remaining gesture until all pointers are lifted
                        while (anyPressed) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressedChanges = event.changes.filter { it.pressed }
                            event.changes.firstOrNull()?.let { latestUptimeMillis = it.uptimeMillis }

                            if (pressedChanges.size >= 2 && activeMode != CameraMode.PANORAMA && effectiveMax > effectiveMin) {
                                if (!isMultiFingerPinch) {
                                    isMultiFingerPinch = true
                                    pinchZoomAccumulator = latestClampedZoom
                                }
                                val zoomFactor = event.calculateZoom()
                                if (zoomFactor.isFinite() && zoomFactor > 0f && abs(zoomFactor - 1.0f) > 0.0005f) {
                                    pinchZoomAccumulator = calculatePinchZoomRatio(
                                        currentZoomRatio = pinchZoomAccumulator,
                                        pinchScaleFactor = zoomFactor,
                                        minZoomRatio = effectiveMin,
                                        maxZoomRatio = effectiveMax
                                    )
                                    val updatedZoom = clampZoomRatio(
                                        pinchZoomAccumulator,
                                        effectiveMin,
                                        effectiveMax
                                    )
                                    latestOnZoomRatioChanged?.invoke(updatedZoom)
                                }
                                // Consume multi-touch pointer events so pinch never triggers single-tap or child controls
                                event.changes.forEach { change ->
                                    change.consume()
                                }
                            } else if (!isMultiFingerPinch) {
                                if (childOverlayTouchTracker.isTouchInsideChild) {
                                    consumedByChild = true
                                }
                                for (change in event.changes) {
                                    if (change.isConsumed) {
                                        consumedByChild = true
                                    }
                                    if ((change.position - initialDownPos).getDistance() > touchSlop) {
                                        exceededSingleTapSlop = true
                                    }
                                }
                            }

                            anyPressed = pressedChanges.isNotEmpty()
                        }

                        if (!isMultiFingerPinch && !exceededSingleTapSlop && !consumedByChild) {
                            val heldDurationMs = latestUptimeMillis - downUptimeMillis
                            if (longPressTriggered) {
                                // Long-press focus lock already fired during hold; do not also fire short tap
                            } else if (heldDurationMs >= longPressTimeoutMs && isTapToFocusEnabledForMode(activeMode)) {
                                if (expandedSliderState != SliderExpansionState.NONE) {
                                    expandedSliderState = SliderExpansionState.NONE
                                } else {
                                    latestTriggerFocusAction(initialDownPos, true)
                                }
                            } else {
                                if (expandedSliderState != SliderExpansionState.NONE) {
                                    // Tapping outside dismisses either expanded slider and returns to compact shortcuts
                                    expandedSliderState = SliderExpansionState.NONE
                                } else {
                                    if (isTapToFocusEnabledForMode(activeMode)) {
                                        latestTriggerFocusAction(initialDownPos, false)
                                    }
                                    latestOnPreviewClick?.invoke()
                                }
                            }
                        }
                    }
                }
                .testTag("preview_placeholder"),
            contentAlignment = Alignment.Center
        ) {
            // Live CameraX Viewfinder stream when permission is granted
            if (hasCameraPermission) {
                CameraXViewfinder(
                    activeLens = activeLens,
                    activeMode = activeMode,
                    flashMode = flashMode,
                    timerMode = timerMode,
                    aspectRatioMode = aspectRatioMode,
                    videoResolution = videoResolution,
                    videoFrameRate = videoFrameRate,
                    videoStabilization = videoStabilization,
                    zoomRatio = zoomRatio,
                    manualIso = manualIso,
                    manualShutterSpeed = manualShutterSpeed,
                    proFocusMode = proFocusMode,
                    proFocusDistance = proFocusDistance,
                    proWhiteBalance = proWhiteBalance,
                    proRawFormat = proRawFormat,
                    portraitBlurLevel = portraitBlurLevel,
                    portraitBlurStrength = portraitBlurStrength,
                    portraitRetouch = portraitRetouch,
                    nightSightExposure = nightSightExposure,
                    astroDuration = astroDuration,
                    astroFocus = astroFocus,
                    astroStrategy = astroStrategy,
                    hdOutputOption = hdOutputOption,
                    slowMoSpeed = slowMoSpeed,
                    timeLapseSpeed = timeLapseSpeed,
                    panoramaDirection = panoramaDirection,
                    isPanoramaCapturingRequested = isPanoramaCapturingRequested,
                    panoramaFinishTrigger = panoramaFinishTrigger,
                    panoramaCancelTrigger = panoramaCancelTrigger,
                    panoramaStepTrigger = panoramaStepTrigger,
                    onPanoramaStateChanged = onPanoramaStateChanged,
                    selectedFilter = effectiveFilter,
                    hdrMode = hdrMode,
                    focusTouchRequest = if (isTapToFocusEnabledForMode(activeMode)) activeFocusRequest else null,
                    autoExposureIndex = if (isFocusEvEnabledForMode) autoExposureIndex else 0,
                    proEv = proEv,
                    isSelfieMirrorEnabled = isSelfieMirrorEnabled,
                    isSaveLocationEnabled = isSaveLocationEnabled,
                    isCameraSoundsEnabled = isCameraSoundsEnabled,
                    photoCaptureTrigger = photoCaptureTrigger,
                    isRecordingRequested = isRecording,
                    proHistogramEnabled = proHistogramEnabled,
                    proFocusPeakingEnabled = proFocusPeakingEnabled,
                    onCapabilitiesDiscovered = onCapabilitiesDiscovered,
                    onVideoConfigApplied = onVideoConfigApplied,
                    onZoomRatioChanged = onZoomRatioChanged,
                    onHistogramUpdated = { liveHistogramData = it },
                    onFocusStatusChanged = { reqId, updatedStatus ->
                        if (activeFocusRequest?.requestId == reqId) {
                            focusIndicatorStatus = updatedStatus
                        }
                    },
                    onExposureIndexApplied = { appliedIdx ->
                        if (isFocusEvEnabledForMode && appliedIdx != autoExposureIndex) {
                            autoExposureIndex = appliedIdx
                            autoExposureSliderFloat = appliedIdx.toFloat()
                            onExposureIndexChanged?.invoke(appliedIdx)
                        }
                    },
                    onCaptureSuccess = onCaptureSuccess,
                    onCaptureError = onCaptureError,
                    onRecordingStateChanged = onRecordingStateChanged,
                    onRecordingDurationChanged = onRecordingDurationChanged,
                    onRecordingSuccess = onRecordingSuccess,
                    onRecordingError = onRecordingError,
                    onCountdownStateChanged = onCountdownStateChanged,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Permission rationale overlay inside preview bounds
                Surface(
                    color = Color(0xDD0D0E10),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .padding(24.dp)
                        .testTag("permission_rationale_card")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PhotoCamera,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxSize()
                            )
                        }

                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Grant camera permission to display the live viewfinder preview.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.testTag("grant_camera_permission_button")
                        ) {
                            Text("Grant Access")
                        }
                    }
                }
            }

            // Viewfinder reticles and grid canvas aligned precisely to outer preview bounds
            val gridColor = Color.White.copy(alpha = 0.30f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gridStroke = 1.dp.toPx()

                when (gridMode) {
                    GridMode.GRID_3X3 -> {
                        drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = gridStroke)
                    }
                    GridMode.GRID_4X4 -> {
                        drawLine(gridColor, Offset(w / 4f, 0f), Offset(w / 4f, h), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(2f * w / 4f, 0f), Offset(2f * w / 4f, h), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(3f * w / 4f, 0f), Offset(3f * w / 4f, h), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(0f, h / 4f), Offset(w, h / 4f), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(0f, 2f * h / 4f), Offset(w, 2f * h / 4f), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(0f, 3f * h / 4f), Offset(w, 3f * h / 4f), strokeWidth = gridStroke)
                    }
                    GridMode.GOLDEN_RATIO -> {
                        val g1 = 0.382f
                        val g2 = 0.618f
                        drawLine(gridColor, Offset(w * g1, 0f), Offset(w * g1, h), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(w * g2, 0f), Offset(w * g2, h), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(0f, h * g1), Offset(w, h * g1), strokeWidth = gridStroke)
                        drawLine(gridColor, Offset(0f, h * g2), Offset(w, h * g2), strokeWidth = gridStroke)
                    }
                    GridMode.OFF -> {}
                }
            }

            // Video recording timer indicator
            if (isRecording) {
                Surface(
                    color = Color.Red.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
                        .testTag("recording_indicator")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        val minutes = recordingDurationSeconds / 60
                        val seconds = recordingDurationSeconds % 60
                        val recText = when (activeMode) {
                            CameraMode.SLOW_MOTION -> {
                                val slowOutTotalSeconds = (recordingDurationSeconds * slowMoSpeed.slowDownFactor).toLong()
                                val outMin = slowOutTotalSeconds / 60
                                val outSec = slowOutTotalSeconds % 60
                                String.format(
                                    Locale.US,
                                    "REC • %02d:%02d → %02d:%02d (%d FPS)",
                                    minutes,
                                    seconds,
                                    outMin,
                                    outSec,
                                    slowMoSpeed.expectedFps
                                )
                            }
                            CameraMode.TIME_LAPSE -> {
                                val tlOutSeconds = recordingDurationSeconds / timeLapseSpeed.multiplier.toLong().coerceAtLeast(1L)
                                val outMin = tlOutSeconds / 60
                                val outSec = tlOutSeconds % 60
                                String.format(
                                    Locale.US,
                                    "REC • %02d:%02d → %02d:%02d (%s)",
                                    minutes,
                                    seconds,
                                    outMin,
                                    outSec,
                                    timeLapseSpeed.shortLabel
                                )
                            }
                            else -> {
                                String.format(Locale.US, "REC • %02d:%02d", minutes, seconds)
                            }
                        }
                        Text(
                            text = recText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }

            // Shutter timer active indicator
            if (timerMode != TimerMode.OFF) {
                Surface(
                    color = Color.Black.copy(alpha = 0.70f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "⏱ ${timerMode.seconds}s",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Active color filter indicator pill in live preview (enabled strictly in PHOTO and VIDEO modes)
            if (activeMode.supportsFilters && !effectiveFilter.isOriginal) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = onFilterBadgeClick != null) {
                            childOverlayTouchTracker.isTouchInsideChild = true
                            onFilterBadgeClick?.invoke()
                        }
                        .testTag("preview_active_filter_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = selectedFilter.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                }
            }

            // Mode-specific Viewfinder Guidance
            when (activeMode) {
                CameraMode.PANORAMA -> {
                    val phase = panoramaLiveState.phase
                    val activeDir = panoramaLiveState.direction
                    val guidance = panoramaLiveState.guidanceStatus
                    val isWarning = guidance.isWarning || panoramaLiveState.isCaptureLagging
                    val driftNorm = if (phase == PanoramaCapturePhase.IDLE) {
                        0f
                    } else {
                        panoramaLiveState.alignmentDriftNormalized.coerceIn(-1f, 1f)
                    }
                    val progressFraction = if (phase == PanoramaCapturePhase.IDLE) {
                        0f
                    } else {
                        panoramaLiveState.progress.coerceIn(0f, 1f)
                    }
                    val coverageFraction = if (phase == PanoramaCapturePhase.IDLE) {
                        0f
                    } else {
                        panoramaLiveState.capturedCoverage.coerceIn(0f, 1f)
                    }
                    val trackOffset = PanoramaProcessor.computeArrowTrackOffset(activeDir, progressFraction)
                    val progressPercent = (progressFraction * 100f).roundToInt()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("panorama_overlay_container")
                    ) {
                        // Top instruction & live warning pill
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = if (isWarning) {
                                    Color(0xFFD84315).copy(alpha = 0.92f)
                                } else {
                                    Color.Black.copy(alpha = 0.74f)
                                },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isWarning) Color(0xFFFFAB91) else Color.White.copy(alpha = 0.22f)
                                ),
                                modifier = Modifier.testTag(
                                    if (isWarning) "panorama_warning_badge" else "panorama_instruction_card"
                                )
                            ) {
                                val displayInstruction = when {
                                    phase == PanoramaCapturePhase.IDLE -> "Move your phone slowly in one direction"
                                    phase == PanoramaCapturePhase.PROCESSING -> "Stitching panorama…"
                                    panoramaLiveState.isCaptureLagging && guidance != PanoramaGuidanceStatus.WRONG_DIRECTION ->
                                        PanoramaGuidanceStatus.TOO_FAST.instructionText
                                    else -> guidance.instructionText
                                }
                                Text(
                                    text = displayInstruction,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                        .testTag("panorama_instruction_text")
                                )
                            }

                            if (phase == PanoramaCapturePhase.CAPTURING) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.68f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("panorama_progress_indicator")
                                ) {
                                    Text(
                                        text = "${panoramaLiveState.capturedFrameCount} / ${panoramaLiveState.targetFrameCount} frames • $progressPercent% (${activeDir.shortLabel})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color(0xFF80CBC4),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // Direction-Aware Progress Track & Moving Arrow Indicator
                        val arrowIconVector = when (activeDir) {
                            PanoramaDirection.LEFT_TO_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
                            PanoramaDirection.RIGHT_TO_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
                            PanoramaDirection.TOP_TO_BOTTOM -> Icons.Filled.ArrowDownward
                            PanoramaDirection.BOTTOM_TO_TOP -> Icons.Filled.ArrowUpward
                        }
                        val isAligned = abs(driftNorm) <= PanoramaProcessor.MAX_DRIFT_WARNING_THRESHOLD && !isWarning
                        val arrowContainerColor = if (isAligned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color(0xFFFF7043)
                        }
                        val arrowContentColor = if (isAligned) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            Color.White
                        }

                        if (activeDir.isHorizontal) {
                            // HORIZONTAL PROGRESS TRACK (LEFT_TO_RIGHT or RIGHT_TO_LEFT)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxWidth(0.88f)
                                    .testTag("panorama_alignment_guide"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.58f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isWarning) Color(0xFFFF7043) else Color.White.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("panorama_track_horizontal")
                                ) {
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        val density = LocalDensity.current
                                        val arrowSizeDp = 36.dp
                                        val arrowSizePx = with(density) { arrowSizeDp.toPx() }
                                        val trackWidthPx = constraints.maxWidth.toFloat()
                                        val trackHeightPx = constraints.maxHeight.toFloat()
                                        val usableSpanPx = (trackWidthPx - arrowSizePx).coerceAtLeast(0f)
                                        val arrowXpx = (usableSpanPx * trackOffset).roundToInt()
                                        val maxDriftOffsetPx = ((trackHeightPx - arrowSizePx) * 0.5f).coerceAtLeast(0f)
                                        val arrowYpx = (((trackHeightPx - arrowSizePx) * 0.5f) + driftNorm * maxDriftOffsetPx)
                                            .coerceIn(0f, (trackHeightPx - arrowSizePx).coerceAtLeast(0f))
                                            .roundToInt()

                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val midY = size.height / 2f

                                            // Swept progress highlight from active starting endpoint
                                            val fillW = (size.width * progressFraction).coerceIn(0f, size.width)
                                            val fillStartX = if (activeDir == PanoramaDirection.RIGHT_TO_LEFT) {
                                                size.width - fillW
                                            } else {
                                                0f
                                            }
                                            if (fillW > 1f) {
                                                drawRect(
                                                    color = arrowContainerColor.copy(alpha = 0.24f),
                                                    topLeft = Offset(fillStartX, 0f),
                                                    size = androidx.compose.ui.geometry.Size(fillW, size.height)
                                                )
                                            }

                                            // Usable frame coverage sub-bar along bottom edge
                                            val covW = (size.width * coverageFraction).coerceIn(0f, size.width)
                                            val covStartX = if (activeDir == PanoramaDirection.RIGHT_TO_LEFT) {
                                                size.width - covW
                                            } else {
                                                0f
                                            }
                                            if (covW > 1f) {
                                                drawRect(
                                                    color = Color(0xFF80CBC4).copy(alpha = 0.55f),
                                                    topLeft = Offset(covStartX, size.height - 3.dp.toPx()),
                                                    size = androidx.compose.ui.geometry.Size(covW, 3.dp.toPx())
                                                )
                                            }

                                            // Center horizontal alignment rail
                                            drawLine(
                                                color = Color.White.copy(alpha = 0.55f),
                                                start = Offset(0f, midY),
                                                end = Offset(size.width, midY),
                                                strokeWidth = 2.dp.toPx()
                                            )

                                            // Start and End tick marks
                                            val tickHalfH = 7.dp.toPx()
                                            drawLine(
                                                color = Color.White.copy(alpha = 0.75f),
                                                start = Offset(2.dp.toPx(), midY - tickHalfH),
                                                end = Offset(2.dp.toPx(), midY + tickHalfH),
                                                strokeWidth = 2.dp.toPx()
                                            )
                                            drawLine(
                                                color = Color.White.copy(alpha = 0.75f),
                                                start = Offset(size.width - 2.dp.toPx(), midY - tickHalfH),
                                                end = Offset(size.width - 2.dp.toPx(), midY + tickHalfH),
                                                strokeWidth = 2.dp.toPx()
                                            )
                                        }

                                        // Directional Arrow Indicator (never starts in the center)
                                        Surface(
                                            color = arrowContainerColor,
                                            shape = CircleShape,
                                            border = BorderStroke(1.5.dp, Color.White),
                                            shadowElevation = 4.dp,
                                            modifier = Modifier
                                                .offset { IntOffset(arrowXpx, arrowYpx) }
                                                .size(arrowSizeDp)
                                                .testTag("panorama_arrow_indicator")
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = arrowIconVector,
                                                    contentDescription = "Panorama arrow pointing ${activeDir.arrowDirectionName}",
                                                    tint = arrowContentColor,
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .testTag("panorama_arrow_direction_${activeDir.arrowDirectionName.lowercase()}")
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "Sweep Direction: ${activeDir.shortLabel}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color.White.copy(alpha = 0.90f)
                                )
                            }
                        } else {
                            // VERTICAL PROGRESS TRACK (TOP_TO_BOTTOM or BOTTOM_TO_TOP)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(vertical = 8.dp)
                                    .testTag("panorama_alignment_guide"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.58f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isWarning) Color(0xFFFF7043) else Color.White.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(168.dp)
                                        .testTag("panorama_track_vertical")
                                ) {
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 6.dp, vertical = 10.dp)
                                    ) {
                                        val density = LocalDensity.current
                                        val arrowSizeDp = 36.dp
                                        val arrowSizePx = with(density) { arrowSizeDp.toPx() }
                                        val trackWidthPx = constraints.maxWidth.toFloat()
                                        val trackHeightPx = constraints.maxHeight.toFloat()
                                        val usableSpanPx = (trackHeightPx - arrowSizePx).coerceAtLeast(0f)
                                        val arrowYpx = (usableSpanPx * trackOffset).roundToInt()
                                        val maxDriftOffsetPx = ((trackWidthPx - arrowSizePx) * 0.5f).coerceAtLeast(0f)
                                        val arrowXpx = (((trackWidthPx - arrowSizePx) * 0.5f) + driftNorm * maxDriftOffsetPx)
                                            .coerceIn(0f, (trackWidthPx - arrowSizePx).coerceAtLeast(0f))
                                            .roundToInt()

                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val midX = size.width / 2f

                                            // Swept progress highlight from active vertical endpoint
                                            val fillH = (size.height * progressFraction).coerceIn(0f, size.height)
                                            val fillStartY = if (activeDir == PanoramaDirection.BOTTOM_TO_TOP) {
                                                size.height - fillH
                                            } else {
                                                0f
                                            }
                                            if (fillH > 1f) {
                                                drawRect(
                                                    color = arrowContainerColor.copy(alpha = 0.24f),
                                                    topLeft = Offset(0f, fillStartY),
                                                    size = androidx.compose.ui.geometry.Size(size.width, fillH)
                                                )
                                            }

                                            // Usable frame coverage sub-bar along right edge
                                            val covH = (size.height * coverageFraction).coerceIn(0f, size.height)
                                            val covStartY = if (activeDir == PanoramaDirection.BOTTOM_TO_TOP) {
                                                size.height - covH
                                            } else {
                                                0f
                                            }
                                            if (covH > 1f) {
                                                drawRect(
                                                    color = Color(0xFF80CBC4).copy(alpha = 0.55f),
                                                    topLeft = Offset(size.width - 3.dp.toPx(), covStartY),
                                                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), covH)
                                                )
                                            }

                                            // Center vertical alignment rail
                                            drawLine(
                                                color = Color.White.copy(alpha = 0.55f),
                                                start = Offset(midX, 0f),
                                                end = Offset(midX, size.height),
                                                strokeWidth = 2.dp.toPx()
                                            )

                                            // Top and Bottom endpoint tick marks
                                            val tickHalfW = 7.dp.toPx()
                                            drawLine(
                                                color = Color.White.copy(alpha = 0.75f),
                                                start = Offset(midX - tickHalfW, 2.dp.toPx()),
                                                end = Offset(midX + tickHalfW, 2.dp.toPx()),
                                                strokeWidth = 2.dp.toPx()
                                            )
                                            drawLine(
                                                color = Color.White.copy(alpha = 0.75f),
                                                start = Offset(midX - tickHalfW, size.height - 2.dp.toPx()),
                                                end = Offset(midX + tickHalfW, size.height - 2.dp.toPx()),
                                                strokeWidth = 2.dp.toPx()
                                            )
                                        }

                                        // Directional Arrow Indicator (never starts in the center)
                                        Surface(
                                            color = arrowContainerColor,
                                            shape = CircleShape,
                                            border = BorderStroke(1.5.dp, Color.White),
                                            shadowElevation = 4.dp,
                                            modifier = Modifier
                                                .offset { IntOffset(arrowXpx, arrowYpx) }
                                                .size(arrowSizeDp)
                                                .testTag("panorama_arrow_indicator")
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = arrowIconVector,
                                                    contentDescription = "Panorama arrow pointing ${activeDir.arrowDirectionName}",
                                                    tint = arrowContentColor,
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .testTag("panorama_arrow_direction_${activeDir.arrowDirectionName.lowercase()}")
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "Sweep Direction: ${activeDir.shortLabel}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color.White.copy(alpha = 0.90f)
                                )
                            }
                        }

                        // Processing overlay when stitching frames
                        if (phase == PanoramaCapturePhase.PROCESSING) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.86f),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .testTag("panorama_processing_indicator")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = "Stitching panorama…",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // Bottom Panorama Controls (Direction selector in IDLE; Cancel / Capture Slice / Finish in CAPTURING)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                                        childOverlayTouchTracker.isTouchInsideChild = true
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (phase == PanoramaCapturePhase.IDLE) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                                    modifier = Modifier.testTag("panorama_direction_selector_row")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PanoramaDirection.entries.forEach { dir ->
                                            val isSelected = dir == activeDir
                                            Box(
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary
                                                        else Color.Transparent
                                                    )
                                                    .clickable {
                                                        onPanoramaDirectionChanged?.invoke(dir)
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                                    .testTag("panorama_direction_${dir.name.lowercase()}"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = dir.shortLabel,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = 11.sp
                                                    ),
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.onPrimary
                                                    } else {
                                                        Color.White.copy(alpha = 0.85f)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else if (phase == PanoramaCapturePhase.CAPTURING) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Cancel panorama button
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(22.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable {
                                                onCancelPanoramaCapture?.invoke()
                                            }
                                            .testTag("panorama_cancel_button")
                                    ) {
                                        Text(
                                            text = "Cancel",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                        )
                                    }

                                    // Capture next overlapping slice button
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(22.dp),
                                        border = BorderStroke(1.dp, Color(0xFF80CBC4).copy(alpha = 0.55f)),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable {
                                                onStepPanoramaCapture?.invoke()
                                            }
                                            .testTag("panorama_step_button")
                                    ) {
                                        Text(
                                            text = "Capture Slice (${panoramaLiveState.capturedFrameCount}/${panoramaLiveState.targetFrameCount})",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = Color(0xFF80CBC4),
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                        )
                                    }

                                    // Finish & stitch button
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(22.dp),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable {
                                                onFinishPanoramaCapture?.invoke()
                                            }
                                            .testTag("panorama_finish_button")
                                    ) {
                                        Text(
                                            text = "Finish",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                CameraMode.PORTRAIT -> {
                    // Portrait blur controls and preset indicators moved to bottom control area above zoom
                }

                CameraMode.PRO -> {
                    if (proFocusPeakingEnabled) {
                        Surface(
                            color = Color(0xCC002816),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(14.dp)
                                .testTag("pro_focus_peaking_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(Color(0xFF00E676), CircleShape)
                                )
                                Text(
                                    text = "PEAKING",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFF00E676)
                                )
                            }
                        }
                    }

                    if (proHistogramEnabled) {
                        ProLiveHistogramCard(
                            data = liveHistogramData,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(14.dp)
                        )
                    }
                }

                else -> {}
            }

            // Phase 2.5: Minimal Pixel-style Focus Circle + Adjacent Vertical EV Line Slider (Non-Pro modes only)
            val currentFocusReq = activeFocusRequest
            if (isTapToFocusEnabledForMode(activeMode) &&
                currentFocusReq != null &&
                focusIndicatorStatus != FocusIndicatorStatus.IDLE
            ) {
                val ringSizeDp = 56.dp
                val sliderWidthDp = 32.dp
                val sliderHeightDp = 112.dp
                val sliderGapDp = 6.dp

                val ringSizePx = with(density) { ringSizeDp.toPx() }
                val sliderWidthPx = with(density) { sliderWidthDp.toPx() }
                val sliderHeightPx = with(density) { sliderHeightDp.toPx() }
                val sliderGapPx = with(density) { sliderGapDp.toPx() }
                val leftMarginPx = with(density) { 14.dp.toPx() }
                val rightMarginPx = with(density) { 14.dp.toPx() }
                val topMarginPx = with(density) { 28.dp.toPx() }
                val bottomMarginPx = with(density) { 64.dp.toPx() }

                val previewW = if (previewPixelSize.width > 0) previewPixelSize.width.toFloat() else currentFocusReq.previewWidth
                val previewH = if (previewPixelSize.height > 0) previewPixelSize.height.toFloat() else currentFocusReq.previewHeight

                val minCenterX = ringSizePx / 2f + leftMarginPx
                val maxCenterX = (previewW - (ringSizePx / 2f + sliderGapPx + sliderWidthPx + rightMarginPx)).coerceAtLeast(minCenterX)
                val clampedCenterX = currentFocusReq.tapX.coerceIn(minCenterX, maxCenterX)

                val minCenterY = sliderHeightPx / 2f + topMarginPx
                val maxCenterY = (previewH - sliderHeightPx / 2f - bottomMarginPx).coerceAtLeast(minCenterY)
                val clampedCenterY = currentFocusReq.tapY.coerceIn(minCenterY, maxCenterY)

                val ringOffsetX = (clampedCenterX - ringSizePx / 2f).roundToInt()
                val ringOffsetY = (clampedCenterY - ringSizePx / 2f).roundToInt()
                val sliderOffsetX = (clampedCenterX + ringSizePx / 2f + sliderGapPx).roundToInt()
                val sliderOffsetY = (clampedCenterY - sliderHeightPx / 2f).roundToInt()

                val isLocked = focusIndicatorStatus.isLockedState
                val monetAccent = MaterialTheme.colorScheme.primary
                val activeAccentColor = if (isLocked) Color(0xFFFFC107) else monetAccent
                val ringColor = when (focusIndicatorStatus) {
                    FocusIndicatorStatus.LOCKED, FocusIndicatorStatus.LOCKED_METERING_ONLY -> Color(0xFFFFC107)
                    FocusIndicatorStatus.FOCUSED -> Color.White
                    FocusIndicatorStatus.FOCUS_UNSUCCESSFUL -> Color.White.copy(alpha = 0.75f)
                    FocusIndicatorStatus.FOCUSING -> monetAccent
                    FocusIndicatorStatus.METERING_ONLY -> Color.White.copy(alpha = 0.85f)
                    FocusIndicatorStatus.UNSUPPORTED -> Color.White.copy(alpha = 0.55f)
                    FocusIndicatorStatus.IDLE -> Color.Transparent
                }

                // 1. Focus / Focus-Lock Circle at tapped coordinate
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(ringOffsetX, ringOffsetY) }
                        .size(ringSizeDp)
                        .testTag(if (isLocked) "focus_lock_indicator_ring" else "focus_indicator_ring"),
                    contentAlignment = Alignment.Center
                ) {
                    // Always expose "focus_indicator_ring" semantic tag so both tap and lock queries can locate the active focus point
                    if (isLocked) {
                        Box(modifier = Modifier.fillMaxSize().testTag("focus_indicator_ring"))
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val mainStroke = if (isLocked) 2.2.dp.toPx() else 1.75.dp.toPx()
                        drawCircle(
                            color = ringColor,
                            radius = r - mainStroke,
                            center = center,
                            style = Stroke(width = mainStroke)
                        )
                        if (isLocked) {
                            val tickLen = 5.dp.toPx()
                            drawLine(ringColor, Offset(center.x, 0f), Offset(center.x, tickLen), strokeWidth = mainStroke)
                            drawLine(ringColor, Offset(center.x, size.height - tickLen), Offset(center.x, size.height), strokeWidth = mainStroke)
                            drawLine(ringColor, Offset(0f, center.y), Offset(tickLen, center.y), strokeWidth = mainStroke)
                            drawLine(ringColor, Offset(size.width - tickLen, center.y), Offset(size.width, center.y), strokeWidth = mainStroke)
                        } else {
                            drawCircle(
                                color = ringColor.copy(alpha = 0.8f),
                                radius = 2.5.dp.toPx(),
                                center = center
                            )
                        }
                    }

                    if (isLocked) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Focus Locked",
                            tint = ringColor,
                            modifier = Modifier
                                .size(16.dp)
                                .testTag("focus_lock_icon")
                        )
                    }
                }

                // 2. Minimal Vertical Exposure (EV) Line Slider immediately to the right of the focus circle
                if (effectiveEvSupported && safeEvMax > safeEvMin) {
                    val evSpan = (safeEvMax - safeEvMin).toFloat().coerceAtLeast(1f)
                    val normalizedEv = ((autoExposureSliderFloat.coerceIn(safeEvMin.toFloat(), safeEvMax.toFloat()) - safeEvMin.toFloat()) / evSpan)
                        .coerceIn(0f, 1f)
                    val neutralNormalizedEv = ((0f - safeEvMin.toFloat()) / evSpan).coerceIn(0f, 1f)

                    // Small readable EV value & reset tap target directly above the vertical line (no background card)
                    val evLabelOffsetY = (sliderOffsetY - with(density) { 20.dp.toPx() }).roundToInt()
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(sliderOffsetX - with(density) { 8.dp.toPx() }.roundToInt(), evLabelOffsetY) }
                            .testTag("focus_exposure_reset_button")
                            .clickable {
                                childOverlayTouchTracker.isTouchInsideChild = true
                                exposureInteractionTick++
                                autoExposureSliderFloat = 0f
                                autoExposureIndex = 0
                                onExposureIndexChanged?.invoke(0)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatEvValue(autoExposureIndex, evStep, includeUnit = true),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (autoExposureIndex != 0) activeAccentColor else Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.testTag("focus_exposure_value_label")
                        )
                    }

                    // Thin Vertical Exposure Line + Draggable Sun Indicator
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(sliderOffsetX, sliderOffsetY) }
                            .size(width = sliderWidthDp, height = sliderHeightDp)
                            .pointerInput(safeEvMin, safeEvMax) {
                                val trackPadPx = 12.dp.toPx()
                                fun updateEvFromLocalY(localY: Float) {
                                    val trackTop = trackPadPx
                                    val trackBottom = (size.height.toFloat() - trackPadPx).coerceAtLeast(trackTop + 1f)
                                    val fractionFromBottom = (1f - ((localY - trackTop) / (trackBottom - trackTop))).coerceIn(0f, 1f)
                                    val rawFloat = safeEvMin.toFloat() + fractionFromBottom * (safeEvMax - safeEvMin).toFloat()
                                    exposureInteractionTick++
                                    autoExposureSliderFloat = rawFloat.coerceIn(safeEvMin.toFloat(), safeEvMax.toFloat())
                                    val clampedIdx = clampExposureIndex(autoExposureSliderFloat.roundToInt(), safeEvRange)
                                    if (clampedIdx != autoExposureIndex) {
                                        autoExposureIndex = clampedIdx
                                        onExposureIndexChanged?.invoke(clampedIdx)
                                    }
                                }

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    childOverlayTouchTracker.isTouchInsideChild = true
                                    updateEvFromLocalY(down.position.y)

                                    var pressed = true
                                    while (pressed) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        childOverlayTouchTracker.isTouchInsideChild = true
                                        val activeChange = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                        if (activeChange != null && activeChange.pressed) {
                                            updateEvFromLocalY(activeChange.position.y)
                                            activeChange.consume()
                                        }
                                        pressed = event.changes.any { it.pressed }
                                    }
                                }
                            }
                            .testTag("focus_exposure_slider")
                    ) {
                        val trackPadDp = 12.dp
                        val sunIconSizeDp = 16.dp
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = size.width / 2f
                            val trackTop = trackPadDp.toPx()
                            val trackBottom = (size.height - trackPadDp.toPx()).coerceAtLeast(trackTop + 1f)
                            val trackLen = trackBottom - trackTop

                            val sunCenterY = trackBottom - normalizedEv * trackLen
                            val neutralCenterY = trackBottom - neutralNormalizedEv * trackLen
                            val sunGapHalf = (sunIconSizeDp.toPx() / 2f) + 3.dp.toPx()
                            val baseStroke = 1.5.dp.toPx()
                            val activeStroke = 2.2.dp.toPx()
                            val inactiveLineColor = Color.White.copy(alpha = 0.45f)

                            // Upper vertical line segment above sun icon
                            if (sunCenterY - sunGapHalf > trackTop) {
                                drawLine(
                                    color = inactiveLineColor,
                                    start = Offset(cx, trackTop),
                                    end = Offset(cx, sunCenterY - sunGapHalf),
                                    strokeWidth = baseStroke
                                )
                            }
                            // Lower vertical line segment below sun icon
                            if (sunCenterY + sunGapHalf < trackBottom) {
                                drawLine(
                                    color = inactiveLineColor,
                                    start = Offset(cx, sunCenterY + sunGapHalf),
                                    end = Offset(cx, trackBottom),
                                    strokeWidth = baseStroke
                                )
                            }

                            // Active Monet-accent segment between neutral (0 EV) and current sun position
                            val segStart = minOf(neutralCenterY, sunCenterY)
                            val segEnd = maxOf(neutralCenterY, sunCenterY)
                            if (segEnd - segStart > sunGapHalf) {
                                val clampedStart = if (sunCenterY < neutralCenterY) {
                                    (sunCenterY + sunGapHalf).coerceAtMost(neutralCenterY)
                                } else {
                                    neutralCenterY
                                }
                                val clampedEnd = if (sunCenterY > neutralCenterY) {
                                    (sunCenterY - sunGapHalf).coerceAtLeast(neutralCenterY)
                                } else {
                                    neutralCenterY
                                }
                                if (clampedEnd > clampedStart) {
                                    drawLine(
                                        color = activeAccentColor,
                                        start = Offset(cx, clampedStart),
                                        end = Offset(cx, clampedEnd),
                                        strokeWidth = activeStroke
                                    )
                                }
                            }

                            // Subtle horizontal tick marks at top (+max), neutral (0 EV), and bottom (-min)
                            val tickHalfWidth = 2.5.dp.toPx()
                            val tickStroke = 1.dp.toPx()
                            val tickColor = Color.White.copy(alpha = 0.55f)
                            drawLine(tickColor, Offset(cx - tickHalfWidth, trackTop), Offset(cx + tickHalfWidth, trackTop), strokeWidth = tickStroke)
                            drawLine(tickColor, Offset(cx - tickHalfWidth * 1.3f, neutralCenterY), Offset(cx + tickHalfWidth * 1.3f, neutralCenterY), strokeWidth = tickStroke)
                            drawLine(tickColor, Offset(cx - tickHalfWidth, trackBottom), Offset(cx + tickHalfWidth, trackBottom), strokeWidth = tickStroke)
                        }

                        // Sun indicator positioned along the vertical line at the current exposure level
                        val usableTrackDp = sliderHeightDp - (trackPadDp * 2)
                        val sunTopOffsetDp = trackPadDp + usableTrackDp * (1f - normalizedEv) - (sunIconSizeDp / 2)
                        Icon(
                            imageVector = Icons.Outlined.WbSunny,
                            contentDescription = "Exposure Compensation",
                            tint = activeAccentColor,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = sunTopOffsetDp)
                                .size(sunIconSizeDp)
                                .testTag("focus_exposure_sun_indicator")
                        )
                    }
                } else {
                    Text(
                        text = "EV unsupported",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(sliderOffsetX, ringOffsetY) }
                            .testTag("focus_exposure_unsupported_note")
                    )
                }

                // 3. Minimal Status & Lock-Release Caption below Focus Circle (no background card)
                val statusText = when (focusIndicatorStatus) {
                    FocusIndicatorStatus.LOCKED -> if (isAutoExposureSupported) "AF/AE Locked" else "AF Locked"
                    FocusIndicatorStatus.LOCKED_METERING_ONLY -> "AE Locked • Fixed Focus"
                    FocusIndicatorStatus.FOCUSING -> if (isAutoExposureSupported) "Focusing & Metering…" else "Focusing…"
                    FocusIndicatorStatus.FOCUSED -> if (isAutoExposureSupported) "AF/AE • Focused" else "AF • Focused"
                    FocusIndicatorStatus.FOCUS_UNSUCCESSFUL -> if (isAutoExposureSupported) "AE Metered • Focus Unchanged" else "Focus Unchanged"
                    FocusIndicatorStatus.METERING_ONLY -> "Fixed Focus • Exposure"
                    FocusIndicatorStatus.UNSUPPORTED -> "AF/AE Unsupported"
                    FocusIndicatorStatus.IDLE -> ""
                }
                val captionOffsetY = (ringOffsetY + ringSizePx + with(density) { 4.dp.toPx() }).roundToInt()
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(ringOffsetX - with(density) { 8.dp.toPx() }.roundToInt(), captionOffsetY) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (isLocked) Color(0xFFFFC107) else Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.testTag("focus_status_badge")
                    )
                    if (isLocked) {
                        Box(
                            modifier = Modifier
                                .testTag("focus_unlock_button")
                                .clickable {
                                    childOverlayTouchTracker.isTouchInsideChild = true
                                    activeFocusRequest = null
                                    focusIndicatorStatus = FocusIndicatorStatus.IDLE
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LockOpen,
                                contentDescription = "Unlock Focus",
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            // Pixel Camera Zoom Shortcuts & Long-Press Continuous Zoom Slider Overlay
            if (activeMode != CameraMode.PANORAMA) {
                val effectiveShortcuts = remember(zoomShortcuts, effectiveMin, effectiveMax) {
                    val filtered = zoomShortcuts
                        .filter { it in (effectiveMin - 0.05f)..(effectiveMax + 0.05f) }
                        .distinct()
                        .sorted()
                    filtered.ifEmpty { generateZoomShortcuts(effectiveMin, effectiveMax) }
                }

                // Determine which shortcut pill represents the active zoom bucket
                val activeBucketIndex = remember(clampedZoom, effectiveShortcuts) {
                    val exactIdx = effectiveShortcuts.indexOfFirst { abs(it - clampedZoom) < 0.08f }
                    if (exactIdx >= 0) {
                        exactIdx
                    } else {
                        val lowerOrEqualIdx = effectiveShortcuts.indexOfLast { it <= clampedZoom }
                        if (lowerOrEqualIdx >= 0) lowerOrEqualIdx else 0
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                                childOverlayTouchTracker.isTouchInsideChild = true
                            }
                        }
                        .testTag("bottom_preview_controls_container"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Portrait Blur Controls (Quick Presets by default, Slider on long-press) integrated near zoom controls
                    if (activeMode == CameraMode.PORTRAIT) {
                        val effectiveBlurStrength = remember(portraitBlurLevel, portraitBlurStrength) {
                            if (portraitBlurLevel == PortraitBlurLevel.OFF && portraitBlurStrength <= PortraitBlurLevel.OFF_THRESHOLD) {
                                0.0f
                            } else {
                                PortraitBlurLevel.normalizeBlurStrength(portraitBlurStrength)
                            }
                        }
                        val activePresetBucket = remember(effectiveBlurStrength) {
                            PortraitBlurLevel.fromBlurStrength(effectiveBlurStrength)
                        }
                        val simulatedApertureLabel = remember(effectiveBlurStrength) {
                            PortraitBlurLevel.simulatedApertureLabelForStrength(effectiveBlurStrength)
                        }

                        // Continuous Blur Slider Panel revealed on long-press (Mutually exclusive with zoom slider)
                        if (expandedSliderState == SliderExpansionState.BLUR) {
                            val maxSupportedBlur = remember(context) {
                                PortraitBlurDeviceSupport.getMaxSupportedBlurStrength(context)
                            }
                            val currentSliderValue = if (effectiveBlurStrength <= 0.05f) {
                                1.0f
                            } else {
                                effectiveBlurStrength.coerceIn(1.0f, maxSupportedBlur)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 360.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black.copy(alpha = 0.78f))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), RoundedCornerShape(20.dp))
                                    .testTag("portrait_blur_continuous_slider_panel")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Current selected value on the left (updates live)
                                    val leftDisplay = if (effectiveBlurStrength <= 0.05f) "Off" else String.format(Locale.US, "f/%.1f", effectiveBlurStrength)
                                    Text(
                                        text = leftDisplay,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        ),
                                        color = if (effectiveBlurStrength > 0.05f) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.White.copy(alpha = 0.72f)
                                        },
                                        modifier = Modifier
                                            .widthIn(min = 36.dp)
                                            .testTag("portrait_blur_aperture_label")
                                    )

                                    // Smooth continuous slider
                                    Slider(
                                        value = currentSliderValue,
                                        onValueChange = { rawValue ->
                                            childOverlayTouchTracker.isTouchInsideChild = true
                                            val clamped = rawValue.coerceIn(1.0f, maxSupportedBlur)
                                            val rounded = ((clamped * 10f).roundToInt() / 10f)
                                            onPortraitBlurStrengthChanged?.invoke(rounded)
                                            val mappedPreset = PortraitBlurLevel.fromBlurStrength(rounded)
                                            if (mappedPreset != portraitBlurLevel) {
                                                onPortraitBlurLevelChanged?.invoke(mappedPreset)
                                            }
                                        },
                                        valueRange = 1.0f..maxSupportedBlur,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .testTag("portrait_blur_slider")
                                    )

                                    // Maximum supported value on the right, beside the Done button
                                    Text(
                                        text = String.format(Locale.US, "f/%.1f", maxSupportedBlur),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = Color.White.copy(alpha = 0.75f),
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.testTag("portrait_blur_percentage_label")
                                    )

                                    // Done button
                                    Box(
                                        modifier = Modifier
                                            .testTag("portrait_blur_slider_close_button")
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.White.copy(alpha = 0.16f))
                                            .clickable {
                                                childOverlayTouchTracker.isTouchInsideChild = true
                                                expandedSliderState = SliderExpansionState.NONE
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Done",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 10.sp
                                            ),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        // Compact Portrait Blur Shortcut Bar ("Off", "4", "2", "1")
                        // Background wraps tightly around the shortcut row with consistent small horizontal padding
                        Box(
                            modifier = Modifier
                                .testTag("portrait_blur_selector_bar")
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.68f))
                                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), RoundedCornerShape(20.dp))
                                .combinedClickable(
                                    onClick = {
                                        childOverlayTouchTracker.isTouchInsideChild = true
                                    },
                                    onLongClick = {
                                        childOverlayTouchTracker.isTouchInsideChild = true
                                        expandedSliderState = SliderExpansionState.BLUR
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PortraitBlurLevel.entries.forEach { level ->
                                    val isSelected = level == activePresetBucket
                                    Box(
                                        modifier = Modifier
                                            .testTag("portrait_blur_pill_${level.name.lowercase(Locale.US)}")
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    Color.Transparent
                                                }
                                            )
                                            .combinedClickable(
                                                onClick = {
                                                    childOverlayTouchTracker.isTouchInsideChild = true
                                                    onPortraitBlurLevelChanged?.invoke(level)
                                                    onPortraitBlurStrengthChanged?.invoke(level.blurStrength)
                                                },
                                                onLongClick = {
                                                    childOverlayTouchTracker.isTouchInsideChild = true
                                                    onPortraitBlurLevelChanged?.invoke(level)
                                                    onPortraitBlurStrengthChanged?.invoke(level.blurStrength)
                                                    expandedSliderState = SliderExpansionState.BLUR
                                                }
                                            )
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = level.shortcutLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                Color.White.copy(alpha = 0.85f)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Night Sight Exposure Controls (Off, Auto, Max) integrated cleanly above zoom controls
                    if (activeMode == CameraMode.NIGHT_SIGHT) {
                        Box(
                            modifier = Modifier
                                .testTag("night_sight_exposure_selector_bar")
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), RoundedCornerShape(24.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                NightSightExposure.entries.forEach { exposureOption ->
                                    val isSelected = nightSightExposure == exposureOption
                                    Box(
                                        modifier = Modifier
                                            .testTag("night_sight_exposure_pill_${exposureOption.name.lowercase(Locale.US)}")
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    Color.Transparent
                                                }
                                            )
                                            .clickable {
                                                childOverlayTouchTracker.isTouchInsideChild = true
                                                onNightSightExposureChanged?.invoke(exposureOption)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = exposureOption.displayName,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                Color.White.copy(alpha = 0.85f)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Time Lapse Compact Speed Shortcut & Anchored Popup Menu immediately above Zoom Shortcuts
                    if (activeMode == CameraMode.TIME_LAPSE) {
                        val isPopupOpen = expandedSliderState == SliderExpansionState.TIME_LAPSE_SPEED

                        if (isPopupOpen) {
                            val speedRows = remember { TimeLapseSpeed.entries.chunked(4) }
                            val intervalFormatted = if (timeLapseSpeed.intervalSeconds >= 1f && timeLapseSpeed.intervalSeconds % 1f == 0f) {
                                "${timeLapseSpeed.intervalSeconds.toInt()}s"
                            } else {
                                String.format(Locale.US, "%.1fs", timeLapseSpeed.intervalSeconds)
                            }

                            Surface(
                                color = Color.Black.copy(alpha = 0.84f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                                modifier = Modifier
                                    .widthIn(max = 340.dp)
                                    .testTag("time_lapse_speed_popup")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "${timeLapseSpeed.description} • 1 frame / $intervalFormatted",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = Color.White.copy(alpha = 0.78f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.testTag("time_lapse_speed_popup_subtitle")
                                    )

                                    speedRows.forEach { rowSpeeds ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            rowSpeeds.forEach { option ->
                                                val isSelected = option == timeLapseSpeed
                                                Box(
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(
                                                            if (isSelected) {
                                                                MaterialTheme.colorScheme.primaryContainer
                                                            } else {
                                                                Color.White.copy(alpha = 0.10f)
                                                            }
                                                        )
                                                        .clickable {
                                                            childOverlayTouchTracker.isTouchInsideChild = true
                                                            onTimeLapseSpeedChanged?.invoke(option)
                                                            expandedSliderState = SliderExpansionState.NONE
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                                        .testTag("time_lapse_speed_option_${option.multiplier}x"),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = option.shortLabel,
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                        ),
                                                        color = if (isSelected) {
                                                            MaterialTheme.colorScheme.onPrimaryContainer
                                                        } else {
                                                            Color.White
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Compact single Time Lapse Speed Selector pill immediately above zoom shortcuts
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (isPopupOpen) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Black.copy(alpha = 0.68f)
                                    }
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (isPopupOpen) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        } else {
                                            Color.White.copy(alpha = 0.18f)
                                        }
                                    ),
                                    RoundedCornerShape(22.dp)
                                )
                                .clickable {
                                    childOverlayTouchTracker.isTouchInsideChild = true
                                    expandedSliderState = if (isPopupOpen) {
                                        SliderExpansionState.NONE
                                    } else {
                                        SliderExpansionState.TIME_LAPSE_SPEED
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("time_lapse_speed_selector_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Speed,
                                    contentDescription = "Time Lapse Speed",
                                    tint = if (isPopupOpen) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (isPopupOpen) "Speed • ${timeLapseSpeed.shortLabel}" else timeLapseSpeed.shortLabel,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (isPopupOpen) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        Color.White
                                    },
                                    modifier = Modifier.testTag("time_lapse_speed_selected_label")
                                )
                                Icon(
                                    imageVector = if (isPopupOpen) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                                    contentDescription = if (isPopupOpen) "Collapse Time Lapse speeds" else "Expand Time Lapse speeds",
                                    tint = if (isPopupOpen) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        Color.White.copy(alpha = 0.85f)
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Continuous Zoom Slider revealed on long-press of any zoom shortcut (Mutually exclusive with blur slider)
                    if (expandedSliderState == SliderExpansionState.ZOOM && effectiveMax > effectiveMin) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.78f))
                                .testTag("zoom_continuous_slider_panel")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatZoomRatio(clampedZoom, asCompactShortcut = false),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("zoom_slider_current_value")
                                    )

                                    val rangeLabel = if (isTarget20xZoomSupported || effectiveMax >= TARGET_MAX_ZOOM_RATIO) {
                                        "${formatZoomRatio(effectiveMin)} – ${formatZoomRatio(effectiveMax)}"
                                    } else {
                                        "Max ${formatZoomRatio(effectiveMax)} (Hardware limit)"
                                    }
                                    Text(
                                        text = rangeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.testTag("zoom_slider_range_note")
                                    )

                                    Box(
                                        modifier = Modifier
                                            .testTag("zoom_slider_close_button")
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.16f))
                                            .clickable {
                                                childOverlayTouchTracker.isTouchInsideChild = true
                                                expandedSliderState = SliderExpansionState.NONE
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Done",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = Color.White
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatZoomRatio(effectiveMin, asCompactShortcut = true),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Slider(
                                        value = clampedZoom.coerceIn(effectiveMin, effectiveMax),
                                        onValueChange = { rawVal ->
                                            childOverlayTouchTracker.isTouchInsideChild = true
                                            val rounded = (rawVal * 10f).roundToInt() / 10f
                                            onZoomRatioChanged?.invoke(
                                                clampZoomRatio(rounded, effectiveMin, effectiveMax)
                                            )
                                        },
                                        valueRange = effectiveMin..effectiveMax.coerceAtLeast(effectiveMin + 0.1f),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .padding(horizontal = 8.dp)
                                            .testTag("zoom_continuous_slider")
                                    )
                                    Text(
                                        text = formatZoomRatio(effectiveMax, asCompactShortcut = true),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }

                    // Zoom Shortcut Pill Bar (short tap -> jump to zoom; long press -> open continuous slider)
                    Box(
                        modifier = Modifier
                            .testTag("zoom_shortcut_bar")
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .combinedClickable(
                                onClick = {
                                    childOverlayTouchTracker.isTouchInsideChild = true
                                },
                                onLongClick = {
                                    childOverlayTouchTracker.isTouchInsideChild = true
                                    if (effectiveMax > effectiveMin) {
                                        expandedSliderState = SliderExpansionState.ZOOM
                                    }
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            effectiveShortcuts.forEachIndexed { index, shortcutValue ->
                                val isSelected = index == activeBucketIndex
                                val labelText = if (isSelected) {
                                    formatZoomRatio(clampedZoom, asCompactShortcut = false)
                                } else {
                                    formatZoomRatio(shortcutValue, asCompactShortcut = true)
                                }
                                Box(
                                    modifier = Modifier
                                        .testTag(zoomShortcutTag(shortcutValue))
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                childOverlayTouchTracker.isTouchInsideChild = true
                                                val target = clampZoomRatio(shortcutValue, effectiveMin, effectiveMax)
                                                onZoomRatioChanged?.invoke(target)
                                            },
                                            onLongClick = {
                                                childOverlayTouchTracker.isTouchInsideChild = true
                                                if (effectiveMax > effectiveMin) {
                                                    expandedSliderState = SliderExpansionState.ZOOM
                                                }
                                            }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = labelText,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            Color.White
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact live luminance histogram overlay for Pro mode preview.
 * Displays normalized 256-bin curve, 25%/50%/75% exposure guidelines,
 * and highlights/shadow clipping alerts.
 */
@Composable
fun ProLiveHistogramCard(
    data: LiveHistogramData?,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xCC000000),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        modifier = modifier
            .size(width = 112.dp, height = 62.dp)
            .testTag("pro_live_histogram_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LUMA",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White.copy(alpha = 0.75f)
                )

                if (data?.isHighlightClipping == true) {
                    Text(
                        text = "CLIP",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = Color(0xFFFF5252)
                    )
                } else if (data?.isShadowClipping == true) {
                    Text(
                        text = "SHADOW",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color(0xFF448AFF)
                    )
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .testTag("histogram_canvas")
            ) {
                val w = size.width
                val h = size.height

                // Guide lines for 25%, 50%, 75% stops
                val gridColor = Color.White.copy(alpha = 0.12f)
                drawLine(gridColor, Offset(w * 0.25f, 0f), Offset(w * 0.25f, h), strokeWidth = 1f)
                drawLine(gridColor, Offset(w * 0.50f, 0f), Offset(w * 0.50f, h), strokeWidth = 1f)
                drawLine(gridColor, Offset(w * 0.75f, 0f), Offset(w * 0.75f, h), strokeWidth = 1f)

                val bins = data?.normalizedBins
                if (bins != null && bins.isNotEmpty()) {
                    val path = Path()
                    val fillPath = Path()

                    fillPath.moveTo(0f, h)

                    val numBins = bins.size
                    for (i in 0 until numBins) {
                        val x = (i.toFloat() / (numBins - 1)) * w
                        val normalizedHeight = bins[i].coerceIn(0f, 1f)
                        val y = h - (normalizedHeight * (h - 2f))

                        if (i == 0) {
                            path.moveTo(x, y)
                            fillPath.lineTo(x, y)
                        } else {
                            path.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }

                    fillPath.lineTo(w, h)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0.15f)
                            ),
                            startY = 0f,
                            endY = h
                        )
                    )

                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.95f),
                        style = Stroke(width = 1.25.dp.toPx())
                    )

                    if (data.isShadowClipping) {
                        drawLine(
                            color = Color(0xFF448AFF),
                            start = Offset(0f, 0f),
                            end = Offset(0f, h),
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    if (data.isHighlightClipping) {
                        drawLine(
                            color = Color(0xFFFF5252),
                            start = Offset(w, 0f),
                            end = Offset(w, h),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                } else {
                    drawLine(
                        color = Color.White.copy(alpha = 0.3f),
                        start = Offset(0f, h - 1f),
                        end = Offset(w, h - 1f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }
    }
}
