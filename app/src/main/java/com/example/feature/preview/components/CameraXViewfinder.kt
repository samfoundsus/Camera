package com.example.feature.preview.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CaptureRequest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageProxy
import com.example.core.processing.AspectCropUtils
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.example.core.preferences.SlowMoSpeed
import com.example.core.preferences.TimeLapseDurationLimit
import com.example.core.preferences.TimeLapseSpeed
import com.example.core.preferences.PanoramaDirection
import androidx.camera.core.Preview
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.example.core.processing.FocusPeakingFrame
import com.example.core.processing.LiveHistogramData
import com.example.core.processing.PanoramaCapturePhase
import com.example.core.processing.PanoramaGuidanceStatus
import com.example.core.processing.PanoramaLiveAnalyzer
import com.example.core.processing.PanoramaLiveState
import com.example.core.processing.PanoramaProcessor
import com.example.core.processing.PanoramaStitchOutcome
import com.example.core.processing.ProImageAnalyzer
import java.util.concurrent.Executors
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.camera.CameraLens
import com.example.core.camera.LensCapabilities
import com.example.core.camera.LensFacing
import com.example.core.camera.ManualSensorCapabilities
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.ShutterProgressMode
import com.example.core.camera.ShutterProgressPhase
import com.example.core.camera.ShutterProgressState
import com.example.core.camera.TimeLapseRecordingService
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.camera.formatAstroCaptureCaption
import com.example.core.camera.formatShutterElapsedTime
import com.example.core.camera.formatTimeLapseCaptureCaption
import com.example.core.camera.toShutterProgressState
import com.example.core.preferences.FlashMode
import com.example.core.preferences.HdOutputOption
import com.example.core.preferences.AstroDuration
import com.example.core.preferences.AstroFocusMode
import com.example.core.preferences.AstroStrategy
import com.example.core.processing.AstroStreamingAccumulator
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.core.camera.FocusIndicatorStatus
import com.example.core.camera.FocusTouchRequest
import com.example.core.camera.ReconciledVideoSettings
import com.example.core.camera.buildCameraXFocusMeteringAction
import com.example.core.camera.buildNonProResetCamera2CaptureRequestOptions
import com.example.core.camera.buildProCamera2CaptureRequestOptions
import com.example.core.camera.buildAstroCamera2CaptureRequestOptions
import com.example.core.camera.clampExposureIndex
import com.example.core.camera.computeNormalizedMeteringCoordinate
import com.example.core.camera.evToExposureIndex
import com.example.core.camera.isFocusEvSliderEnabledForMode
import com.example.core.camera.isTapToFocusEnabledForMode
import com.example.core.camera.resolveModeExposureCompensationIndex
import com.example.core.camera.verifyCameraXFocusMeteringSupport
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.preferences.ProFocusMode
import com.example.core.preferences.ProRawFormat
import com.example.core.preferences.TimerMode
import com.example.core.preferences.VideoStabilizationMode
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.view.TextureView
import androidx.compose.ui.draw.alpha
import com.example.core.camera.Camera2DirectVideoRecorder
import com.example.core.camera.Camera2MaxResolutionStillCapturer
import com.example.core.camera.HdMaxPhotoCaptureSource
import com.example.core.camera.NightSightCapturePhase
import com.example.core.camera.NightSightDeviceSupport
import com.example.core.camera.NightSightProgressState
import com.example.core.camera.VideoSessionConfigVerifier
import com.example.core.camera.calculateTargetVideoBitrate
import com.example.core.camera.inspectAndLogSavedVideoMetadata
import com.example.core.camera.logSavedPhotoDimensions
import com.example.core.camera.resolveCameraLabelFromMetadata
import com.example.core.camera.selectBestVideoAeFpsRange
import com.example.core.processing.NightSightProcessingOutcome
import com.example.core.processing.NightSightProcessor
import com.example.core.processing.NightSightStreamingAccumulator
import com.example.core.processing.PortraitProcessingOutcome
import com.example.core.processing.PortraitProcessor
import java.util.concurrent.TimeUnit

private const val TAG = "CameraXViewfinder"

@OptIn(ExperimentalZeroShutterLag::class)
private fun resolveStillCaptureModeForExtension(
    resolvedStillCaptureMode: Int,
    candidateExtensionMode: Int
): Int = if (candidateExtensionMode != androidx.camera.extensions.ExtensionMode.NONE &&
    resolvedStillCaptureMode == ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
) {
    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
} else {
    resolvedStillCaptureMode
}

@OptIn(ExperimentalZeroShutterLag::class)
private fun isCameraInfoZslSupported(cameraInfo: CameraInfo): Boolean =
    runCatching { cameraInfo.isZslSupported }.getOrDefault(false)

fun VideoResolution.toCameraXQuality(): Quality = when (this) {
    VideoResolution.HD_720P -> Quality.HD
    VideoResolution.FHD_1080P -> Quality.FHD
    VideoResolution.QHD_2K -> Quality.FHD
    VideoResolution.UHD_4K -> Quality.UHD
}

fun Quality.toVideoResolution(): VideoResolution? = when (this) {
    Quality.SD -> VideoResolution.HD_720P
    Quality.HD -> VideoResolution.HD_720P
    Quality.FHD -> VideoResolution.FHD_1080P
    Quality.UHD -> VideoResolution.UHD_4K
    else -> null
}

/**
 * Selects the optimal hardware AE target FPS range for the requested video frame rate.
 */
fun selectBestTargetFpsRange(
    availableRanges: Array<Range<Int>>,
    targetFps: Int
): Range<Int> {
    if (availableRanges.isEmpty()) return Range(targetFps, targetFps)
    // 1. Prefer an exact fixed range [targetFps, targetFps] (e.g. [30, 30] or [60, 60])
    val exactFixed = availableRanges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
    if (exactFixed != null) return exactFixed

    // 2. Otherwise prefer range whose upper bound matches targetFps with highest lower bound
    val matchingUpper = availableRanges
        .filter { it.upper == targetFps }
        .maxByOrNull { it.lower }
    if (matchingUpper != null) return matchingUpper

    // 3. Otherwise pick range containing targetFps with narrowest span
    val containing = availableRanges
        .filter { targetFps in it.lower..it.upper }
        .minByOrNull { it.upper - it.lower }
    if (containing != null) return containing

    // 4. Otherwise pick the highest supported hardware FPS range so Camera2 never rejects an unsupported range
    val highestAvailable = availableRanges.maxWithOrNull(
        compareBy<Range<Int>> { it.upper }.thenBy { it.lower }
    )
    return highestAvailable ?: Range(targetFps, targetFps)
}

private data class BoundPipelineConfig(
    val lens: CameraLens,
    val isVideoMode: Boolean,
    val isSlowMotionMode: Boolean = false,
    val slowMoSpeed: SlowMoSpeed = SlowMoSpeed.SPEED_1_4X,
    val isTimeLapseMode: Boolean = false,
    val timeLapseSpeed: TimeLapseSpeed = TimeLapseSpeed.SPEED_30X,
    val isProMode: Boolean = false,
    val isPanoramaMode: Boolean = false,
    val resolution: VideoResolution,
    val frameRate: VideoFrameRate,
    val stabilization: VideoStabilizationMode,
    val activeExtensionMode: Int = androidx.camera.extensions.ExtensionMode.NONE,
    val captureMode: Int = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3,
    val hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE,
    val preferHighQualityIsp: Boolean = false
)

/**
 * LifecycleOwner wrapper for CameraX binding that mirrors [delegate] normally, except when
 * [keepAlive] is enabled during an active foreground-service-backed Time Lapse recording:
 * if the screen turns off or the app goes to the background briefly (`ON_PAUSE`/`ON_STOP`),
 * it holds the CameraX lifecycle at [Lifecycle.State.STARTED] so the recording is not torn down.
 * When [keepAlive] becomes false or the host Activity is destroyed, it immediately syncs back.
 */
private class KeepAliveCameraLifecycleOwner(
    private val delegate: LifecycleOwner
) : LifecycleOwner {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    @Volatile
    var keepAlive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncStateOnMainThread()
        }

    private val observer = LifecycleEventObserver { _, _ ->
        syncStateOnMainThread()
    }

    init {
        syncStateOnMainThread()
        delegate.lifecycle.addObserver(observer)
    }

    private fun syncStateOnMainThread() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            syncStateInternal()
        } else {
            mainHandler.post { syncStateInternal() }
        }
    }

    private fun syncStateInternal() {
        val delegateState = delegate.lifecycle.currentState
        val targetState = if (keepAlive &&
            delegateState != Lifecycle.State.DESTROYED &&
            delegateState != Lifecycle.State.INITIALIZED &&
            !delegateState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            Lifecycle.State.STARTED
        } else {
            delegateState
        }
        if (registry.currentState != targetState) {
            runCatching { registry.currentState = targetState }
        }
    }

    fun release() {
        keepAlive = false
        delegate.lifecycle.removeObserver(observer)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                runCatching { registry.currentState = Lifecycle.State.DESTROYED }
            }
        } else {
            mainHandler.post {
                if (registry.currentState != Lifecycle.State.DESTROYED) {
                    runCatching { registry.currentState = Lifecycle.State.DESTROYED }
                }
            }
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraXViewfinder(
    activeLens: CameraLens,
    activeMode: CameraMode = CameraMode.PHOTO,
    flashMode: FlashMode = FlashMode.AUTO,
    timerMode: TimerMode = TimerMode.OFF,
    aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3,
    videoResolution: VideoResolution = VideoResolution.FHD_1080P,
    videoFrameRate: VideoFrameRate = VideoFrameRate.FPS_30,
    videoStabilization: VideoStabilizationMode = VideoStabilizationMode.STANDARD,
    zoomRatio: Float = 1.0f,
    manualIso: String = "AUTO",
    manualShutterSpeed: String = "AUTO",
    proFocusMode: ProFocusMode = ProFocusMode.AUTO,
    proFocusDistance: Float = 0.0f,
    proWhiteBalance: String = "AUTO",
    proRawFormat: ProRawFormat = ProRawFormat.JPEG_ONLY,
    focusTouchRequest: FocusTouchRequest? = null,
    autoExposureIndex: Int = 0,
    proEv: Float = 0.0f,
    isSelfieMirrorEnabled: Boolean = true,
    isSaveLocationEnabled: Boolean = false,
    isCameraSoundsEnabled: Boolean = true,
    portraitBlurLevel: PortraitBlurLevel = PortraitBlurLevel.MEDIUM,
    portraitBlurStrength: Float = portraitBlurLevel.blurStrength,
    portraitRetouch: PortraitRetouch = PortraitRetouch.NATURAL,
    nightSightExposure: NightSightExposure = NightSightExposure.AUTO,
    hdrMode: com.example.core.preferences.HdrMode = com.example.core.preferences.HdrMode.AUTO,
    photoCaptureSpeedMode: com.example.core.preferences.PhotoCaptureSpeedMode = com.example.core.preferences.PhotoCaptureSpeedMode.FAST,
    hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE,
    astroDuration: AstroDuration = AstroDuration.AUTO,
    astroFocus: AstroFocusMode = AstroFocusMode.AUTO,
    astroStrategy: AstroStrategy = AstroStrategy.AUTO,
    slowMoSpeed: SlowMoSpeed = SlowMoSpeed.SPEED_1_4X,
    timeLapseSpeed: TimeLapseSpeed = TimeLapseSpeed.SPEED_30X,
    timeLapseDurationLimit: TimeLapseDurationLimit = TimeLapseDurationLimit.UNLIMITED,
    panoramaDirection: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT,
    isPanoramaCapturingRequested: Boolean = false,
    panoramaFinishTrigger: Int = 0,
    panoramaCancelTrigger: Int = 0,
    panoramaStepTrigger: Int = 0,
    onPanoramaStateChanged: ((PanoramaLiveState) -> Unit)? = null,
    selectedFilter: com.example.core.preferences.CameraFilterPreset = com.example.core.preferences.CameraFilterPreset.ORIGINAL,
    photoCaptureTrigger: Int = 0,
    isRecordingRequested: Boolean = false,
    proHistogramEnabled: Boolean = false,
    proFocusPeakingEnabled: Boolean = false,
    onCapabilitiesDiscovered: ((Map<CameraLens, LensCapabilities>) -> Unit)? = null,
    onVideoConfigApplied: ((ReconciledVideoSettings) -> Unit)? = null,
    onZoomRatioChanged: ((Float) -> Unit)? = null,
    onFocusStatusChanged: ((Long, FocusIndicatorStatus) -> Unit)? = null,
    onExposureIndexApplied: ((Int) -> Unit)? = null,
    onCaptureSuccess: ((Uri) -> Unit)? = null,
    onInstantPreviewThumbnail: ((android.graphics.Bitmap?) -> Unit)? = null,
    onCaptureError: ((String) -> Unit)? = null,
    onRecordingStateChanged: ((Boolean) -> Unit)? = null,
    onRecordingDurationChanged: ((Long) -> Unit)? = null,
    onRecordingSuccess: ((Uri) -> Unit)? = null,
    onRecordingError: ((String) -> Unit)? = null,
    onHistogramUpdated: ((LiveHistogramData?) -> Unit)? = null,
    onFocusPeakingUpdated: ((FocusPeakingFrame?) -> Unit)? = null,
    nightSightCancelTrigger: Int = 0,
    onNightSightProgressChanged: ((NightSightProgressState) -> Unit)? = null,
    astroStopAndSaveTrigger: Int = 0,
    astroDiscardTrigger: Int = 0,
    onShutterProgressChanged: ((ShutterProgressState) -> Unit)? = null,
    onCameraError: ((String) -> Unit)? = null,
    onCountdownStateChanged: ((Boolean, Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraLifecycleOwner = remember(lifecycleOwner) { KeepAliveCameraLifecycleOwner(lifecycleOwner) }
    DisposableEffect(cameraLifecycleOwner) {
        onDispose {
            cameraLifecycleOwner.release()
        }
    }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var boundImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundVideoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var activeCamera2DirectRecorder by remember { mutableStateOf<Camera2DirectVideoRecorder?>(null) }
    var isDirectCamera2RecordingActive by remember { mutableStateOf(false) }
    var rebindTrigger by remember { mutableIntStateOf(0) }
    var lastBoundConfig by remember { mutableStateOf<BoundPipelineConfig?>(null) }
    var cachedCapabilities by remember { mutableStateOf<Map<CameraLens, LensCapabilities>>(emptyMap()) }
    var hasQueriedWithCameraProvider by remember { mutableStateOf(false) }
    val cachedAeRanges = remember { mutableMapOf<CameraLens, Array<Range<Int>>>() }
    var isCapturing by remember { mutableStateOf(false) }
    val isCaptureInFlight = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val nightSightCancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val astroStopAndSaveFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val astroDiscardFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var isCountingDown by remember { mutableStateOf(false) }
    var countdownRemainingSeconds by remember { mutableIntStateOf(0) }

    // Live Camera2 3A convergence (AE, AF, AWB) and face detection telemetry for smart precapture gating
    val latestAeState = remember { java.util.concurrent.atomic.AtomicInteger(CaptureResult.CONTROL_AE_STATE_INACTIVE) }
    val latestAfState = remember { java.util.concurrent.atomic.AtomicInteger(CaptureResult.CONTROL_AF_STATE_INACTIVE) }
    val latestAwbState = remember { java.util.concurrent.atomic.AtomicInteger(CaptureResult.CONTROL_AWB_STATE_INACTIVE) }
    val latestDetectedFaceRect = remember { java.util.concurrent.atomic.AtomicReference<Rect?>(null) }
    val latestSensorActiveArray = remember { java.util.concurrent.atomic.AtomicReference<Rect?>(null) }
    var activeExtensionMode by remember { mutableIntStateOf(androidx.camera.extensions.ExtensionMode.NONE) }
    val latestActiveExtensionMode by rememberUpdatedState(activeExtensionMode)

    LaunchedEffect(nightSightCancelTrigger) {
        if (nightSightCancelTrigger > 0) {
            nightSightCancelFlag.set(true)
        }
    }

    LaunchedEffect(astroStopAndSaveTrigger) {
        if (astroStopAndSaveTrigger > 0) {
            astroStopAndSaveFlag.set(true)
        }
    }

    LaunchedEffect(astroDiscardTrigger) {
        if (astroDiscardTrigger > 0) {
            astroDiscardFlag.set(true)
        }
    }

    LaunchedEffect(isCountingDown, countdownRemainingSeconds) {
        onCountdownStateChanged?.invoke(isCountingDown, countdownRemainingSeconds)
    }
    var showShutterFlashOverlay by remember { mutableStateOf(false) }
    var isHardwareAfLockActive by remember { mutableStateOf(false) }
    var isHardwareAeLockActive by remember { mutableStateOf(false) }
    val latestPortraitBlurLevel by rememberUpdatedState(portraitBlurLevel)
    val latestPortraitBlurStrength by rememberUpdatedState(portraitBlurStrength)
    val latestPortraitRetouch by rememberUpdatedState(portraitRetouch)
    val latestNightSightExposure by rememberUpdatedState(nightSightExposure)
    val latestHdrMode by rememberUpdatedState(hdrMode)
    val latestPhotoCaptureSpeedMode by rememberUpdatedState(photoCaptureSpeedMode)
    val latestHdOutputOption by rememberUpdatedState(hdOutputOption)
    val latestFocusTouchRequest by rememberUpdatedState(focusTouchRequest)
    val latestAutoExposureIndex by rememberUpdatedState(autoExposureIndex)
    val latestActiveMode by rememberUpdatedState(activeMode)
    val latestVideoResolution by rememberUpdatedState(videoResolution)
    val latestVideoFrameRate by rememberUpdatedState(videoFrameRate)
    val latestVideoStabilization by rememberUpdatedState(videoStabilization)
    val latestZoomRatio by rememberUpdatedState(zoomRatio)
    val latestFlashMode by rememberUpdatedState(flashMode)
    val latestSlowMoSpeed by rememberUpdatedState(slowMoSpeed)
    val latestTimeLapseSpeed by rememberUpdatedState(timeLapseSpeed)
    val latestTimeLapseDurationLimit by rememberUpdatedState(timeLapseDurationLimit)
    val latestPanoramaDirection by rememberUpdatedState(panoramaDirection)
    val latestIsPanoramaCapturingRequested by rememberUpdatedState(isPanoramaCapturingRequested)
    val latestOnPanoramaStateChanged by rememberUpdatedState(onPanoramaStateChanged)
    val latestOnNightSightProgressChanged by rememberUpdatedState(onNightSightProgressChanged)
    val latestOnShutterProgressChanged by rememberUpdatedState(onShutterProgressChanged)
    val latestOnInstantPreviewThumbnail by rememberUpdatedState(onInstantPreviewThumbnail)
    val latestSelectedFilter by rememberUpdatedState(selectedFilter)

    val emitNightSightProgress: (NightSightProgressState) -> Unit = { nsState ->
        latestOnNightSightProgressChanged?.invoke(nsState)
        latestOnShutterProgressChanged?.invoke(nsState.toShutterProgressState())
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val photoCaptureExecutor = remember { Executors.newSingleThreadExecutor() }
    val companionCaptureExecutor = remember { Executors.newSingleThreadExecutor() }
    val photoSaveQueue = remember { com.example.core.camera.BoundedPhotoSaveQueue(com.example.core.camera.BoundedPhotoSaveQueue.DEFAULT_MAX_IN_FLIGHT) }
    val portraitJobsInFlight = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val panoramaKeyframes = remember { java.util.Collections.synchronizedList(mutableListOf<android.graphics.Bitmap>()) }
    val isPanoramaProcessingInFlight = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var autoFinishSweepCounter by remember { mutableIntStateOf(0) }

    // Preload and warm up the Portrait segmentation model as soon as the user opens Portrait mode
    // so the very first Portrait shutter press does not incur cold model loading latency.
    LaunchedEffect(activeMode) {
        if (activeMode == CameraMode.PORTRAIT) {
            PortraitProcessor.warmUpSegmentationModelAsync()
        }
    }

    fun clearPanoramaKeyframes() {
        synchronized(panoramaKeyframes) {
            for (bmp in panoramaKeyframes) {
                if (!bmp.isRecycled) {
                    runCatching { bmp.recycle() }
                }
            }
            panoramaKeyframes.clear()
        }
    }

    val panoramaAnalyzer = remember {
        PanoramaLiveAnalyzer(
            onProgressUpdated = { progress, count, drift, speed, guidance ->
                ContextCompat.getMainExecutor(context).execute {
                    if (!isPanoramaProcessingInFlight.get() && latestIsPanoramaCapturingRequested) {
                        val effectiveCount = maxOf(count, panoramaKeyframes.size)
                        val coverage = if (effectiveCount <= 1) {
                            0f
                        } else {
                            ((effectiveCount - 1).toFloat() / (PanoramaProcessor.DEFAULT_TARGET_FRAMES - 1).coerceAtLeast(1).toFloat())
                                .coerceIn(0f, 1f)
                        }
                        val lagging = (progress - coverage) > 0.28f
                        latestOnPanoramaStateChanged?.invoke(
                            PanoramaLiveState(
                                phase = PanoramaCapturePhase.CAPTURING,
                                direction = latestPanoramaDirection,
                                progress = progress,
                                capturedCoverage = coverage,
                                capturedFrameCount = effectiveCount,
                                targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                                alignmentDriftNormalized = drift,
                                normalizedSpeed = speed,
                                guidanceStatus = if (lagging && guidance != PanoramaGuidanceStatus.WRONG_DIRECTION) {
                                    PanoramaGuidanceStatus.TOO_FAST
                                } else {
                                    guidance
                                },
                                isCaptureLagging = lagging
                            )
                        )
                    }
                }
            },
            onKeyframeCaptured = { bmp ->
                panoramaKeyframes.add(bmp)
            },
            onTargetSweepCompleted = {
                ContextCompat.getMainExecutor(context).execute {
                    autoFinishSweepCounter++
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            TimeLapseRecordingService.stop(context)
            panoramaAnalyzer.stopSession()
            clearPanoramaKeyframes()
            analysisExecutor.shutdown()
            photoCaptureExecutor.shutdown()
            companionCaptureExecutor.shutdown()
            photoSaveQueue.shutdown()
        }
    }

    var currentPeakingFrame by remember { mutableStateOf<FocusPeakingFrame?>(null) }
    var currentHistogramData by remember { mutableStateOf<LiveHistogramData?>(null) }

    val proAnalyzer = remember {
        ProImageAnalyzer(
            onHistogramComputed = { hist ->
                currentHistogramData = hist
                onHistogramUpdated?.invoke(hist)
            },
            onFocusPeakingComputed = { peaking ->
                currentPeakingFrame = peaking
                onFocusPeakingUpdated?.invoke(peaking)
            }
        )
    }

    DisposableEffect(proAnalyzer) {
        onDispose {
            proAnalyzer.release()
        }
    }

    LaunchedEffect(proHistogramEnabled, proFocusPeakingEnabled, activeLens, isSelfieMirrorEnabled, activeMode) {
        val isPro = activeMode == CameraMode.PRO
        proAnalyzer.isHistogramEnabled = isPro && proHistogramEnabled
        proAnalyzer.isFocusPeakingEnabled = isPro && proFocusPeakingEnabled
        proAnalyzer.isFrontCamera = activeLens.facing == LensFacing.FRONT
        proAnalyzer.isSelfieMirrorEnabled = isSelfieMirrorEnabled
        if (!isPro || !proFocusPeakingEnabled) {
            currentPeakingFrame = null
            onFocusPeakingUpdated?.invoke(null)
        }
        if (!isPro || !proHistogramEnabled) {
            currentHistogramData = null
            onHistogramUpdated?.invoke(null)
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            isClickable = false
            isFocusable = false
        }
    }

    val camera2PreviewTextureView = remember {
        TextureView(context).apply {
            isClickable = false
            isFocusable = false
        }
    }

    val effectivePreviewFilter = com.example.core.processing.CameraFilterProcessor.resolveEffectiveFilter(activeMode, selectedFilter)

    LaunchedEffect(effectivePreviewFilter) {
        val colorFilter = com.example.core.processing.CameraFilterProcessor.createColorMatrixColorFilter(effectivePreviewFilter)
        val desiredImplMode = if (colorFilter != null) {
            PreviewView.ImplementationMode.COMPATIBLE
        } else {
            PreviewView.ImplementationMode.PERFORMANCE
        }
        val implModeChanged = previewView.implementationMode != desiredImplMode
        if (implModeChanged) {
            previewView.implementationMode = desiredImplMode
        }
        if (colorFilter == null) {
            if (previewView.layerType != android.view.View.LAYER_TYPE_NONE) {
                previewView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previewView.setRenderEffect(null)
            }
        } else {
            val layerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.colorFilter = colorFilter
            }
            previewView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, layerPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previewView.setRenderEffect(
                    android.graphics.RenderEffect.createColorFilterEffect(colorFilter)
                )
            }
            previewView.invalidate()
        }
        if (implModeChanged && activeCamera != null) {
            rebindTrigger++
        }
    }

    // Observe lifecycle events to ensure countdown/panorama cancels and torch turns off on pause/stop,
    // while keeping active Time Lapse recording and in-flight Astro/Night Sight processing stable
    DisposableEffect(lifecycleOwner, activeCamera) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                isCountingDown = false
                countdownRemainingSeconds = 0
                val isMultiFrameOrTimeLapseActive = cameraLifecycleOwner.keepAlive ||
                    (isCaptureInFlight.get() && (latestActiveMode == CameraMode.ASTRO || latestActiveMode == CameraMode.NIGHT_SIGHT))
                if (!isMultiFrameOrTimeLapseActive) {
                    isCapturing = false
                    isCaptureInFlight.set(false)
                }
                if (panoramaAnalyzer.isCapturing) {
                    panoramaAnalyzer.stopSession()
                    clearPanoramaKeyframes()
                    isPanoramaProcessingInFlight.set(false)
                    latestOnPanoramaStateChanged?.invoke(
                        PanoramaLiveState(
                            phase = PanoramaCapturePhase.IDLE,
                            direction = latestPanoramaDirection,
                            progress = 0f,
                            capturedCoverage = 0f,
                            capturedFrameCount = 0,
                            targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                            alignmentDriftNormalized = 0f,
                            normalizedSpeed = 0f,
                            guidanceStatus = PanoramaGuidanceStatus.READY,
                            isCaptureLagging = false
                        )
                    )
                }
                if (!cameraLifecycleOwner.keepAlive) {
                    try {
                        activeCamera?.cameraControl?.enableTorch(false)
                    } catch (_: Exception) {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!cameraLifecycleOwner.keepAlive) {
                try {
                    activeCamera?.cameraControl?.enableTorch(false)
                } catch (_: Exception) {}
            }
        }
    }

    // Cancel any active self-timer countdown and clear stale focus locks if camera lens or mode changes
    LaunchedEffect(activeLens, activeMode) {
        if (isCountingDown) {
            isCountingDown = false
            countdownRemainingSeconds = 0
        }
        isHardwareAfLockActive = false
        val cam = activeCamera
        if (cam != null) {
            try {
                cam.cameraControl.cancelFocusAndMetering()
            } catch (_: Exception) {}
        }
    }

    // Rebind CameraX pipeline when lens, mode, video config, speed/quality toggle, extension mode, or aspect ratio changes
    LaunchedEffect(
        activeLens,
        activeMode,
        videoResolution,
        videoFrameRate,
        videoStabilization,
        slowMoSpeed,
        timeLapseSpeed,
        photoCaptureSpeedMode,
        hdrMode,
        aspectRatioMode,
        hdOutputOption,
        portraitRetouch,
        portraitBlurLevel,
        nightSightExposure,
        rebindTrigger
    ) {
        // Never interrupt an active direct Camera2 video recording
        if (isDirectCamera2RecordingActive) {
            Log.d(TAG, "Skipping CameraX rebind while direct Camera2 video recording is active")
            return@LaunchedEffect
        }
        // Never interrupt an active video recording just to refresh settings unless camera lens or video mode changed
        if (activeRecording != null) {
            if (lastBoundConfig?.lens == activeLens &&
                lastBoundConfig?.isSlowMotionMode == (activeMode == CameraMode.SLOW_MOTION) &&
                lastBoundConfig?.isTimeLapseMode == (activeMode == CameraMode.TIME_LAPSE) &&
                activeMode.isVideo
            ) {
                Log.d(TAG, "Skipping pipeline rebind while video recording is active on ${activeLens.displayName}")
                return@LaunchedEffect
            }
            try {
                activeRecording?.stop()
            } catch (_: Exception) {}
            activeRecording = null
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val cameraSelector = if (activeLens.facing == LensFacing.FRONT) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                if (!cameraProvider.hasCamera(cameraSelector)) {
                    Log.w(TAG, "Selected camera facing not available on device: ${activeLens.facing}")
                    onCameraError?.invoke("Camera lens ${activeLens.displayName} unavailable on this device.")
                    return@addListener
                }

                // Discover real capabilities across both rear and front cameras using Camera2 + CameraX Codec VideoCapabilities + Extensions
                val discoveredCapabilities = if (cachedCapabilities.isNotEmpty() && hasQueriedWithCameraProvider) {
                    cachedCapabilities
                } else {
                    val queried = queryDeviceCameraCapabilities(context, cameraProvider)
                    if (queried.isNotEmpty()) {
                        cachedCapabilities = queried
                        hasQueriedWithCameraProvider = true
                        onCapabilitiesDiscovered?.invoke(queried)
                    }
                    queried.ifEmpty { cachedCapabilities }
                }

                val activeLensCaps = discoveredCapabilities[activeLens]
                val reconciled = activeLensCaps?.reconcileVideoSettings(
                    requestedResolution = videoResolution,
                    requestedFrameRate = videoFrameRate,
                    requestedStabilization = videoStabilization,
                    requestedFlashMode = flashMode
                ) ?: ReconciledVideoSettings(
                    resolution = videoResolution,
                    frameRate = videoFrameRate,
                    stabilization = videoStabilization,
                    flashMode = flashMode
                )

                // Notify caller if any setting had to fall back to a valid supported option
                if (reconciled.resolution != videoResolution ||
                    reconciled.frameRate != videoFrameRate ||
                    reconciled.stabilization != videoStabilization ||
                    reconciled.flashMode != flashMode
                ) {
                    onVideoConfigApplied?.invoke(reconciled)
                }

                val slowMoTargetFrameRate = VideoFrameRate.entries.firstOrNull {
                    it.fps == slowMoSpeed.expectedFps
                } ?: VideoFrameRate.FPS_120
                val effectiveSlowMoResolution = activeLensCaps?.resolveSlowMoResolution(
                    rate = slowMoTargetFrameRate,
                    preferred = reconciled.resolution
                ) ?: VideoResolution.FHD_1080P

                val desiredExtensionMode = if (activeMode.isVideo) {
                    androidx.camera.extensions.ExtensionMode.NONE
                } else {
                    com.example.core.camera.CameraExtensionsHelper.resolveDesiredExtensionMode(
                        availability = activeLensCaps?.extensionAvailability ?: com.example.core.camera.CameraExtensionAvailability(),
                        activeMode = activeMode,
                        hdrMode = hdrMode,
                        portraitRetouch = portraitRetouch,
                        portraitBlurLevel = portraitBlurLevel,
                        nightSightExposure = nightSightExposure,
                        photoCaptureSpeedMode = photoCaptureSpeedMode
                    )
                }
                val resolvedStillCaptureMode = com.example.core.camera.CameraCaptureQualityPolicy.resolveCameraXCaptureMode(
                    activeMode = activeMode,
                    photoCaptureSpeedMode = photoCaptureSpeedMode,
                    isZslSupported = activeLensCaps?.ispQualityCapabilities?.isZslSupported == true,
                    activeExtensionMode = desiredExtensionMode,
                    flashMode = flashMode
                )
                val preferHighQualityIsp = com.example.core.camera.CameraCaptureQualityPolicy.shouldUseHighQualityIspForStillCapture(
                    activeMode = activeMode,
                    photoCaptureSpeedMode = photoCaptureSpeedMode
                )

                val desiredBindConfig = if (activeMode.isVideo) {
                    val isSlowMo = activeMode == CameraMode.SLOW_MOTION
                    val isTimeLapse = activeMode == CameraMode.TIME_LAPSE
                    BoundPipelineConfig(
                        lens = activeLens,
                        isVideoMode = true,
                        isSlowMotionMode = isSlowMo,
                        slowMoSpeed = if (isSlowMo) slowMoSpeed else SlowMoSpeed.SPEED_1_4X,
                        isTimeLapseMode = isTimeLapse,
                        timeLapseSpeed = if (isTimeLapse) timeLapseSpeed else TimeLapseSpeed.SPEED_30X,
                        isProMode = false,
                        resolution = if (isSlowMo) effectiveSlowMoResolution else reconciled.resolution,
                        frameRate = if (isSlowMo) slowMoTargetFrameRate else if (isTimeLapse) VideoFrameRate.FPS_30 else reconciled.frameRate,
                        stabilization = if (isSlowMo) VideoStabilizationMode.OFF else reconciled.stabilization,
                        activeExtensionMode = androidx.camera.extensions.ExtensionMode.NONE,
                        captureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                } else {
                    BoundPipelineConfig(
                        lens = activeLens,
                        isVideoMode = false,
                        isSlowMotionMode = false,
                        isProMode = activeMode == CameraMode.PRO,
                        isPanoramaMode = activeMode == CameraMode.PANORAMA,
                        resolution = VideoResolution.FHD_1080P,
                        frameRate = VideoFrameRate.FPS_30,
                        stabilization = VideoStabilizationMode.OFF,
                        activeExtensionMode = desiredExtensionMode,
                        captureMode = resolvedStillCaptureMode,
                        aspectRatioMode = aspectRatioMode,
                        hdOutputOption = hdOutputOption,
                        preferHighQualityIsp = preferHighQualityIsp
                    )
                }

                // Avoid duplicate camera bindings if the exact target pipeline is already bound
                if (lastBoundConfig == desiredBindConfig && activeCamera != null) {
                    return@addListener
                }

                cameraProvider.unbindAll()

                var boundCam: Camera? = null
                var boundImgCap: ImageCapture? = null
                var boundVidCap: VideoCapture<Recorder>? = null
                var effectiveBoundReconciled = reconciled
                var boundExtMode = androidx.camera.extensions.ExtensionMode.NONE

                if (activeMode.isVideo) {
                    val isSlowMo = activeMode == CameraMode.SLOW_MOTION
                    val aeRanges = cachedAeRanges.getOrPut(activeLens) {
                        queryLensAeFpsRanges(context, activeLens)
                    }
                    val effectiveVideoFps = if (isSlowMo) {
                        slowMoSpeed.expectedFps
                    } else {
                        reconciled.frameRate.fps
                    }
                    val targetFpsRange = selectBestTargetFpsRange(aeRanges, effectiveVideoFps)
                    val targetVideoRes = if (isSlowMo) effectiveSlowMoResolution else reconciled.resolution
                    val usePreviewStab = !isSlowMo && (
                        (reconciled.stabilization == VideoStabilizationMode.ACTIVE && activeLensCaps?.isPreviewStabilizationSupported == true) ||
                            (reconciled.stabilization == VideoStabilizationMode.STANDARD &&
                                activeLensCaps?.isPreviewStabilizationSupported == true &&
                                activeLensCaps.isDigitalVideoStabilizationSupported != true)
                        )
                    val useVideoStab = !isSlowMo && (
                        reconciled.stabilization == VideoStabilizationMode.STANDARD ||
                            reconciled.stabilization == VideoStabilizationMode.ACTIVE
                        ) && (activeLensCaps?.isStabilizationSupported == true)

                    val previewBuilder = Preview.Builder()
                        .setTargetFrameRate(targetFpsRange)
                    if (usePreviewStab) {
                        runCatching { previewBuilder.setPreviewStabilizationEnabled(true) }
                    }
                    val preview = previewBuilder.build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    fun buildConfiguredVideoCapture(useCustomQhdResolution: Boolean): VideoCapture<Recorder> {
                        val targetBitrate = calculateTargetVideoBitrate(targetVideoRes, effectiveVideoFps)
                        val recorderBuilder = Recorder.Builder()
                            .setVideoCapabilitiesSource(Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
                            .setTargetVideoEncodingBitRate(targetBitrate)
                        if (!useCustomQhdResolution) {
                            val targetQuality = when (targetVideoRes) {
                                VideoResolution.HD_720P -> Quality.HD
                                VideoResolution.FHD_1080P -> Quality.FHD
                                VideoResolution.QHD_2K -> Quality.UHD
                                VideoResolution.UHD_4K -> Quality.UHD
                            }
                            val qualitySelector = QualitySelector.from(
                                targetQuality,
                                FallbackStrategy.lowerQualityOrHigherThan(targetQuality)
                            )
                            recorderBuilder.setQualitySelector(qualitySelector)
                        }
                        val recorder = recorderBuilder.build()
                        val videoCaptureBuilder = VideoCapture.Builder(recorder)
                            .setTargetFrameRate(targetFpsRange)
                            .setVideoStabilizationEnabled(useVideoStab)

                        if (useCustomQhdResolution && targetVideoRes == VideoResolution.QHD_2K) {
                            runCatching {
                                val method = VideoCapture.Builder::class.java.getDeclaredMethod(
                                    "setCustomOrderedResolutions",
                                    List::class.java
                                )
                                method.isAccessible = true
                                method.invoke(
                                    videoCaptureBuilder,
                                    listOf(
                                        Size(VideoResolution.QHD_2K.width, VideoResolution.QHD_2K.height),
                                        Size(VideoResolution.FHD_1080P.width, VideoResolution.FHD_1080P.height)
                                    )
                                )
                            }
                        }

                        // Configure Camera2Interop options for exact FPS range and stabilization mode
                        val camera2Extender = Camera2Interop.Extender(videoCaptureBuilder)
                        camera2Extender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            targetFpsRange
                        )
                        when (if (isSlowMo) VideoStabilizationMode.OFF else reconciled.stabilization) {
                            VideoStabilizationMode.OFF -> {
                                camera2Extender.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                                )
                                if (activeLensCaps?.isOpticalStabilizationSupported == true) {
                                    camera2Extender.setCaptureRequestOption(
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                                    )
                                }
                            }
                            VideoStabilizationMode.STANDARD -> {
                                when {
                                    activeLensCaps?.isDigitalVideoStabilizationSupported == true -> {
                                        camera2Extender.setCaptureRequestOption(
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                        )
                                    }
                                    activeLensCaps?.isPreviewStabilizationSupported == true &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                                        camera2Extender.setCaptureRequestOption(
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                            2 // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                                        )
                                    }
                                    activeLensCaps?.isOpticalStabilizationSupported == true -> {
                                        camera2Extender.setCaptureRequestOption(
                                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                                        )
                                    }
                                }
                            }
                            VideoStabilizationMode.ACTIVE -> {
                                if (activeLensCaps?.isPreviewStabilizationSupported == true &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                ) {
                                    camera2Extender.setCaptureRequestOption(
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                        2 // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                                    )
                                } else if (activeLensCaps?.isDigitalVideoStabilizationSupported == true) {
                                    camera2Extender.setCaptureRequestOption(
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                    )
                                }
                                if (activeLensCaps?.isOpticalStabilizationSupported == true) {
                                    camera2Extender.setCaptureRequestOption(
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                                    )
                                }
                            }
                        }
                        return videoCaptureBuilder.build()
                    }

                    try {
                        val configuredVideoCapture = buildConfiguredVideoCapture(
                            useCustomQhdResolution = targetVideoRes == VideoResolution.QHD_2K
                        )
                        // In Video mode, prioritize Preview + VideoCapture so 4K UHD, 2K QHD, 60 FPS, and
                        // Stabilization are never throttled by an unused 3rd JPEG stream.
                        boundCam = cameraProvider.bindToLifecycle(
                            cameraLifecycleOwner,
                            cameraSelector,
                            preview,
                            configuredVideoCapture
                        )
                        boundVidCap = configuredVideoCapture
                        Log.d(
                            TAG,
                            "Bound Video pipeline (${reconciled.resolution.displayName} @ ${reconciled.frameRate.displayName}, Stab=${reconciled.stabilization.displayName}) on ${activeLens.displayName}"
                        )
                    } catch (excPrimary: Exception) {
                        Log.w(TAG, "Primary video configuration failed to bind, attempting standard QualitySelector fallback", excPrimary)
                        try {
                            val fallbackQualityCapture = buildConfiguredVideoCapture(useCustomQhdResolution = false)
                            boundCam = cameraProvider.bindToLifecycle(
                                cameraLifecycleOwner,
                                cameraSelector,
                                preview,
                                fallbackQualityCapture
                            )
                            boundVidCap = fallbackQualityCapture
                        } catch (excVideo: Exception) {
                            Log.w(TAG, "Secondary video configuration failed to bind, attempting safe 1080p/720p 30 FPS fallback", excVideo)
                            try {
                                val fallbackRecorder = Recorder.Builder()
                                    .setVideoCapabilitiesSource(Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
                                    .build()
                                val fallbackFpsRange = selectBestTargetFpsRange(aeRanges, 30)
                                val fallbackVideoCapture = VideoCapture.Builder(fallbackRecorder)
                                    .setTargetFrameRate(fallbackFpsRange)
                                    .setVideoStabilizationEnabled(false)
                                    .build()
                                boundCam = cameraProvider.bindToLifecycle(
                                    cameraLifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    fallbackVideoCapture
                                )
                                boundVidCap = fallbackVideoCapture
                                val fallbackRes = if (activeLensCaps?.isResolutionSupported(VideoResolution.FHD_1080P) == true) {
                                    VideoResolution.FHD_1080P
                                } else {
                                    VideoResolution.HD_720P
                                }
                                effectiveBoundReconciled = reconciled.copy(
                                    resolution = fallbackRes,
                                    frameRate = VideoFrameRate.FPS_30,
                                    stabilization = VideoStabilizationMode.OFF
                                )
                                onVideoConfigApplied?.invoke(effectiveBoundReconciled)
                            } catch (excFallback: Exception) {
                                Log.e(TAG, "Failed to bind fallback video pipeline", excFallback)
                                onCameraError?.invoke("Failed to initialize video stream: ${excFallback.localizedMessage}")
                            }
                        }
                    }
                } else {
                    val previewAspectRatioStrategy = when (aspectRatioMode) {
                        AspectRatioMode.RATIO_9_16 -> androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        AspectRatioMode.RATIO_1_1, AspectRatioMode.RATIO_4_3 -> androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    }
                    val previewResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(previewAspectRatioStrategy)
                        .build()
                    // Track sensor active array size for accurate face-to-metering-point projection in Portrait mode
                    activeLensCaps?.camera2CameraId?.let { cid ->
                        runCatching {
                            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                            latestSensorActiveArray.set(
                                cm?.getCameraCharacteristics(cid)?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                            )
                        }
                    }

                    val previewBuilder = Preview.Builder()
                        .setResolutionSelector(previewResolutionSelector)
                    val previewCamera2Extender = Camera2Interop.Extender(previewBuilder)
                    previewCamera2Extender.setSessionCaptureCallback(
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                result.get(CaptureResult.CONTROL_AE_STATE)?.let { latestAeState.set(it) }
                                result.get(CaptureResult.CONTROL_AF_STATE)?.let { latestAfState.set(it) }
                                result.get(CaptureResult.CONTROL_AWB_STATE)?.let { latestAwbState.set(it) }
                                val faces = result.get(CaptureResult.STATISTICS_FACES)
                                if (faces != null && faces.isNotEmpty()) {
                                    val largestFace = faces.maxByOrNull { f ->
                                        val b = f.bounds
                                        b.width().toLong() * b.height().toLong()
                                    }
                                    latestDetectedFaceRect.set(largestFace?.bounds)
                                } else {
                                    latestDetectedFaceRect.set(null)
                                }
                            }
                        }
                    )
                    // Keep preview ISP modes FAST for responsive continuous AF/AE without preview stutter
                    activeLensCaps?.ispQualityCapabilities?.let { ispCaps ->
                        if (ispCaps.supportsFastNoiseReduction) {
                            previewCamera2Extender.setCaptureRequestOption(
                                CaptureRequest.NOISE_REDUCTION_MODE,
                                CaptureRequest.NOISE_REDUCTION_MODE_FAST
                            )
                        }
                        if (ispCaps.supportsFastEdge) {
                            previewCamera2Extender.setCaptureRequestOption(
                                CaptureRequest.EDGE_MODE,
                                CaptureRequest.EDGE_MODE_FAST
                            )
                        }
                        if (ispCaps.supportsFastTonemap) {
                            previewCamera2Extender.setCaptureRequestOption(
                                CaptureRequest.TONEMAP_MODE,
                                CaptureRequest.TONEMAP_MODE_FAST
                            )
                        }
                        if (ispCaps.supportsFastAberration) {
                            previewCamera2Extender.setCaptureRequestOption(
                                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
                            )
                        }
                    }
                    val preview = previewBuilder.build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val preferMaxNative = activeMode == CameraMode.HD && hdOutputOption == HdOutputOption.MAX_NATIVE
                    val maxPhotoSize = com.example.core.camera.CaptureResolutionResolver.resolveHardwareCaptureStreamSize(
                        lensCaps = activeLensCaps,
                        activeMode = activeMode,
                        aspectRatioMode = aspectRatioMode,
                        hdOutputOption = hdOutputOption
                    )

                    // Derive ImageCapture AspectRatioStrategy from maxPhotoSize's REAL aspect ratio
                    // so choosing 16:9 or 1:1 NEVER forces CameraX onto a low-resolution stream when the
                    // sensor's highest-resolution stream is 4:3!
                    val captureAspectRatioStrategy = if (maxPhotoSize != null && maxPhotoSize.width > 0 && maxPhotoSize.height > 0) {
                        val maxLong = maxOf(maxPhotoSize.width, maxPhotoSize.height).toFloat()
                        val maxShort = minOf(maxPhotoSize.width, maxPhotoSize.height).toFloat()
                        val actualStreamRatio = maxLong / maxShort
                        if (kotlin.math.abs(actualStreamRatio - (16f / 9f)) < kotlin.math.abs(actualStreamRatio - (4f / 3f))) {
                            androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        } else {
                            androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                        }
                    } else {
                        previewAspectRatioStrategy
                    }

                    val resolutionSelectorBuilder = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(captureAspectRatioStrategy)
                    if (preferMaxNative || preferHighQualityIsp) {
                        resolutionSelectorBuilder.setAllowedResolutionMode(
                            androidx.camera.core.resolutionselector.ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                        )
                    }
                    if (maxPhotoSize != null && maxPhotoSize.width > 0 && maxPhotoSize.height > 0) {
                        resolutionSelectorBuilder.setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                Size(maxPhotoSize.width, maxPhotoSize.height),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        // Filter orders exact maxPhotoSize first and largest streams before smaller ones
                        // so CameraX never downgrades to a lower-resolution stream.
                        resolutionSelectorBuilder.setResolutionFilter { supportedSizes, _ ->
                            val targetLong = maxOf(maxPhotoSize.width, maxPhotoSize.height)
                            val targetShort = minOf(maxPhotoSize.width, maxPhotoSize.height)
                            val exact = supportedSizes.filter { sz ->
                                maxOf(sz.width, sz.height) == targetLong && minOf(sz.width, sz.height) == targetShort
                            }
                            val othersLargestFirst = supportedSizes
                                .filter { it !in exact }
                                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                            exact + othersLargestFirst
                        }
                    } else {
                        resolutionSelectorBuilder.setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                        )
                    }

                    fun buildImageCaptureForMode(captureModeToUse: Int, applyHighQualityIsp: Boolean): ImageCapture {
                        val builder = ImageCapture.Builder()
                            .setCaptureMode(captureModeToUse)
                            .setJpegQuality(com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY)
                            .setResolutionSelector(resolutionSelectorBuilder.build())
                        com.example.core.camera.CameraCaptureQualityPolicy.applyStillCaptureCamera2Interop(
                            imageCaptureBuilder = builder,
                            ispCaps = activeLensCaps?.ispQualityCapabilities,
                            preferHighQuality = applyHighQualityIsp
                        )
                        return builder.build()
                    }

                    val (resolvedSelector, candidateExtMode) = com.example.core.camera.CameraExtensionsHelper.resolveCameraSelectorWithExtension(
                        context = context,
                        cameraProvider = cameraProvider,
                        baseSelector = cameraSelector,
                        extensionMode = desiredExtensionMode
                    )
                    val initialCaptureMode = resolveStillCaptureModeForExtension(
                        resolvedStillCaptureMode = resolvedStillCaptureMode,
                        candidateExtensionMode = candidateExtMode
                    )
                    val imageCapture = buildImageCaptureForMode(
                        captureModeToUse = initialCaptureMode,
                        applyHighQualityIsp = preferHighQualityIsp
                    )

                    try {
                        if (activeMode == CameraMode.PRO) {
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .setTargetResolution(Size(320, 240))
                                .build()
                            imageAnalysis.setAnalyzer(analysisExecutor, proAnalyzer)

                            boundCam = cameraProvider.bindToLifecycle(
                                cameraLifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture,
                                imageAnalysis
                            )
                            boundImgCap = imageCapture
                            boundExtMode = androidx.camera.extensions.ExtensionMode.NONE
                            Log.d(TAG, "Successfully bound Preview + ImageCapture + ImageAnalysis for Pro Mode")
                        } else if (activeMode == CameraMode.PANORAMA) {
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .setTargetResolution(Size(960, 720))
                                .build()
                            imageAnalysis.setAnalyzer(analysisExecutor, panoramaAnalyzer)

                            boundCam = cameraProvider.bindToLifecycle(
                                cameraLifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture,
                                imageAnalysis
                            )
                            boundImgCap = imageCapture
                            boundExtMode = androidx.camera.extensions.ExtensionMode.NONE
                            Log.d(TAG, "Successfully bound Preview + ImageCapture + ImageAnalysis for Panorama Mode")
                        } else {
                            boundCam = cameraProvider.bindToLifecycle(
                                cameraLifecycleOwner,
                                resolvedSelector,
                                preview,
                                imageCapture
                            )
                            boundImgCap = imageCapture
                            boundExtMode = candidateExtMode
                            Log.d(
                                TAG,
                                "Successfully bound Preview + ImageCapture (captureMode=$initialCaptureMode, ext=${com.example.core.camera.CameraExtensionsHelper.extensionModeDisplayName(boundExtMode)}, hqIsp=$preferHighQualityIsp)"
                            )
                        }
                    } catch (exc2: Exception) {
                        Log.w(TAG, "Primary photo camera bind failed (ext=$candidateExtMode, captureMode=$initialCaptureMode), falling back cleanly", exc2)
                        try {
                            cameraProvider.unbindAll()
                            val safeFallbackMode = if (preferHighQualityIsp) {
                                ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                            } else {
                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                            }
                            val fallbackImageCapture = buildImageCaptureForMode(
                                captureModeToUse = safeFallbackMode,
                                applyHighQualityIsp = preferHighQualityIsp
                            )
                            boundCam = cameraProvider.bindToLifecycle(
                                cameraLifecycleOwner,
                                cameraSelector,
                                preview,
                                fallbackImageCapture
                            )
                            boundImgCap = fallbackImageCapture
                            boundExtMode = androidx.camera.extensions.ExtensionMode.NONE
                        } catch (excFallback: Exception) {
                            Log.e(TAG, "Failed to bind fallback camera", excFallback)
                            onCameraError?.invoke("Failed to initialize camera: ${excFallback.localizedMessage}")
                        }
                    }
                }

                activeCamera = boundCam
                boundImageCapture = boundImgCap
                boundVideoCapture = boundVidCap
                activeExtensionMode = boundExtMode

                // Verify actual bound VideoCapture stream resolution so we never falsely claim 2K or 4K if CameraX fell back
                val actualStreamSize = boundVidCap?.resolutionInfo?.resolution
                if (actualStreamSize != null) {
                    val maxDim = maxOf(actualStreamSize.width, actualStreamSize.height)
                    val actualResolution = when {
                        maxDim >= 3840 -> VideoResolution.UHD_4K
                        maxDim >= 2560 -> VideoResolution.QHD_2K
                        maxDim >= 1920 -> VideoResolution.FHD_1080P
                        else -> VideoResolution.HD_720P
                    }
                    if (actualResolution != effectiveBoundReconciled.resolution && activeMode != CameraMode.SLOW_MOTION) {
                        Log.w(
                            TAG,
                            "CameraX Recorder bound at $actualResolution (${actualStreamSize.width}x${actualStreamSize.height}) instead of ${effectiveBoundReconciled.resolution}"
                        )
                        effectiveBoundReconciled = effectiveBoundReconciled.copy(resolution = actualResolution)
                        onVideoConfigApplied?.invoke(effectiveBoundReconciled)
                    }
                }

                // Also verify hardware flash unit from bound CameraInfo
                if (boundCam != null && !boundCam.cameraInfo.hasFlashUnit() && flashMode != FlashMode.OFF) {
                    effectiveBoundReconciled = effectiveBoundReconciled.copy(flashMode = FlashMode.OFF)
                    onVideoConfigApplied?.invoke(effectiveBoundReconciled)
                }

                // Synchronize live CameraX ZoomState & ExposureState bounds and apply initial zoom ratio on bind
                if (boundCam != null) {
                    val liveZoomState = boundCam.cameraInfo.zoomState.value
                    val liveExposureState = boundCam.cameraInfo.exposureState
                    val currentLensCaps = cachedCapabilities[activeLens]
                    val minZ = liveZoomState?.minZoomRatio ?: currentLensCaps?.minZoomRatio ?: 1.0f
                    val maxZ = liveZoomState?.maxZoomRatio ?: currentLensCaps?.maxZoomRatio ?: 1.0f
                    val liveRange = com.example.core.camera.resolveSupportedZoomRange(minZ, maxZ)

                    val liveEvRange = liveExposureState.exposureCompensationRange.let { it.lower..it.upper }
                    val liveEvSupported = liveExposureState.isExposureCompensationSupported &&
                        (liveEvRange.start < 0 || liveEvRange.endInclusive > 0)
                    val liveEvStep = liveExposureState.exposureCompensationStep.let {
                        if (it.denominator != 0 && it.toFloat() > 0f) it.toFloat() else (1f / 3f)
                    }

                    val centerProbePoint = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                    val verifiedAfAe = verifyCameraXFocusMeteringSupport(
                        cameraInfo = boundCam.cameraInfo,
                        manualCapabilities = currentLensCaps?.manualCapabilities,
                        meteringPoint = centerProbePoint
                    )

                    if (currentLensCaps != null &&
                        (kotlin.math.abs(currentLensCaps.minZoomRatio - liveRange.start) > 0.05f ||
                            kotlin.math.abs(currentLensCaps.maxZoomRatio - liveRange.endInclusive) > 0.05f ||
                            currentLensCaps.manualCapabilities.evRange != liveEvRange ||
                            kotlin.math.abs(currentLensCaps.manualCapabilities.evStepRational - liveEvStep) > 0.001f ||
                            currentLensCaps.manualCapabilities.isExposureCompensationSupported != liveEvSupported ||
                            currentLensCaps.manualCapabilities.isAutoFocusSupported != verifiedAfAe.isAfSupported ||
                            currentLensCaps.manualCapabilities.isAutoExposureSupported != verifiedAfAe.isAeSupported ||
                            currentLensCaps.manualCapabilities.isFocusLockSupported != verifiedAfAe.isFocusLockSupported)
                    ) {
                        val updatedShortcuts = com.example.core.camera.generateZoomShortcuts(
                            liveRange.start,
                            liveRange.endInclusive
                        )
                        val updatedLensCaps = currentLensCaps.copy(
                            manualCapabilities = currentLensCaps.manualCapabilities.copy(
                                minZoomRatio = liveRange.start,
                                maxZoomRatio = liveRange.endInclusive,
                                availableZoomRatios = updatedShortcuts,
                                isAutoFocusSupported = verifiedAfAe.isAfSupported,
                                isAutoExposureSupported = verifiedAfAe.isAeSupported,
                                isFocusLockSupported = verifiedAfAe.isFocusLockSupported,
                                isExposureCompensationSupported = liveEvSupported,
                                evRange = liveEvRange,
                                evStepRational = liveEvStep
                            )
                        )
                        val updatedMap = cachedCapabilities.toMutableMap().apply {
                            put(activeLens, updatedLensCaps)
                        }
                        cachedCapabilities = updatedMap
                        onCapabilitiesDiscovered?.invoke(updatedMap)
                    }

                    val clampedZoom = com.example.core.camera.clampZoomRatio(
                        zoomRatio,
                        liveRange.start,
                        liveRange.endInclusive
                    )
                    try {
                        boundCam.cameraControl.setZoomRatio(clampedZoom)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to apply initial zoom ratio $clampedZoom on bind", e)
                    }
                    if (kotlin.math.abs(clampedZoom - zoomRatio) > 0.05f) {
                        onZoomRatioChanged?.invoke(clampedZoom)
                    }
                }

                if (boundCam != null) {
                    lastBoundConfig = if (activeMode.isVideo) {
                        val isSlowMo = activeMode == CameraMode.SLOW_MOTION
                        val isTimeLapse = activeMode == CameraMode.TIME_LAPSE
                        BoundPipelineConfig(
                            lens = activeLens,
                            isVideoMode = true,
                            isSlowMotionMode = isSlowMo,
                            slowMoSpeed = if (isSlowMo) slowMoSpeed else SlowMoSpeed.SPEED_1_4X,
                            isTimeLapseMode = isTimeLapse,
                            timeLapseSpeed = if (isTimeLapse) timeLapseSpeed else TimeLapseSpeed.SPEED_30X,
                            resolution = if (isSlowMo) effectiveSlowMoResolution else effectiveBoundReconciled.resolution,
                            frameRate = if (isSlowMo) slowMoTargetFrameRate else if (isTimeLapse) VideoFrameRate.FPS_30 else effectiveBoundReconciled.frameRate,
                            stabilization = if (isSlowMo) VideoStabilizationMode.OFF else effectiveBoundReconciled.stabilization
                        )
                    } else {
                        desiredBindConfig
                    }
                }

                Log.d(TAG, "CameraX successfully bound to lifecycle for ${activeLens.displayName}")
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to bind CameraX use cases", exc)
                onCameraError?.invoke("Failed to initialize camera stream: ${exc.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Observe live CameraX ZoomState so dynamic zoom bounds from the active camera stay synchronized
    DisposableEffect(activeCamera, activeLens) {
        val camera = activeCamera
        if (camera == null) {
            return@DisposableEffect onDispose {}
        }
        val zoomObserver = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            val liveRange = com.example.core.camera.resolveSupportedZoomRange(
                state.minZoomRatio,
                state.maxZoomRatio
            )
            val currentLensCaps = cachedCapabilities[activeLens]
            if (currentLensCaps != null &&
                (kotlin.math.abs(currentLensCaps.minZoomRatio - liveRange.start) > 0.05f ||
                    kotlin.math.abs(currentLensCaps.maxZoomRatio - liveRange.endInclusive) > 0.05f)
            ) {
                val updatedShortcuts = com.example.core.camera.generateZoomShortcuts(
                    liveRange.start,
                    liveRange.endInclusive
                )
                val updatedLensCaps = currentLensCaps.copy(
                    manualCapabilities = currentLensCaps.manualCapabilities.copy(
                        minZoomRatio = liveRange.start,
                        maxZoomRatio = liveRange.endInclusive,
                        availableZoomRatios = updatedShortcuts
                    )
                )
                val updatedMap = cachedCapabilities.toMutableMap().apply {
                    put(activeLens, updatedLensCaps)
                }
                cachedCapabilities = updatedMap
                onCapabilitiesDiscovered?.invoke(updatedMap)
            }
        }
        camera.cameraInfo.zoomState.observe(lifecycleOwner, zoomObserver)
        onDispose {
            camera.cameraInfo.zoomState.removeObserver(zoomObserver)
        }
    }

    // Apply real zoom ratio to active CameraX CameraControl (affects Preview, ImageCapture, and VideoCapture without rebinding)
    LaunchedEffect(activeCamera, zoomRatio, activeLens) {
        val camera = activeCamera ?: return@LaunchedEffect
        val liveZoomState = camera.cameraInfo.zoomState.value
        val lensCaps = cachedCapabilities[activeLens]
        val minZ = liveZoomState?.minZoomRatio ?: lensCaps?.minZoomRatio ?: 1.0f
        val maxZ = liveZoomState?.maxZoomRatio ?: lensCaps?.maxZoomRatio ?: 1.0f
        val clampedZoom = com.example.core.camera.clampZoomRatio(zoomRatio, minZ, maxZ)

        try {
            camera.cameraControl.setZoomRatio(clampedZoom)
            Log.d(
                TAG,
                "Applied real zoom ratio ${String.format(Locale.US, "%.1fx", clampedZoom)} (range ${minZ}x..${maxZ}x, target 20.0x) on ${activeLens.displayName}"
            )
        } catch (exc: Exception) {
            Log.w(TAG, "Failed to apply zoom ratio $clampedZoom on ${activeLens.displayName}", exc)
        }

        if (kotlin.math.abs(clampedZoom - zoomRatio) > 0.05f) {
            onZoomRatioChanged?.invoke(clampedZoom)
        }
    }

    // Synchronize flash mode and torch state safely checking hardware capabilities for both Photo and Video modes
    LaunchedEffect(flashMode, boundImageCapture, activeCamera, activeLens, activeMode) {
        val camera = activeCamera ?: return@LaunchedEffect
        val hasFlash = camera.cameraInfo.hasFlashUnit()

        if (!hasFlash) {
            boundImageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
            try {
                camera.cameraControl.enableTorch(false)
            } catch (e: Exception) {
                Log.w(TAG, "No torch unit available on active camera lens", e)
            }
            return@LaunchedEffect
        }

        if (activeMode.isVideo) {
            // Video mode: Strobe photo flash is inapplicable; only continuous Torch illumination is supported
            boundImageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
            val shouldTorch = flashMode == FlashMode.TORCH
            try {
                camera.cameraControl.enableTorch(shouldTorch)
            } catch (exc: Exception) {
                Log.w(TAG, "Failed to update torch state in video mode", exc)
            }
        } else {
            // Still photo & Pro modes:
            // - TORCH: enables continuous illumination before and during capture
            // - AUTO: auto strobe on capture
            // - ON: forced strobe on capture
            // - OFF: flash disabled
            val isTorchActive = flashMode == FlashMode.TORCH
            try {
                camera.cameraControl.enableTorch(isTorchActive)
            } catch (exc: Exception) {
                Log.w(TAG, "Failed to update torch state in still mode", exc)
            }

            boundImageCapture?.flashMode = when (flashMode) {
                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                FlashMode.OFF, FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    // Execute tap-to-focus (AF) and auto-exposure metering (AE) and tap-and-hold focus/AE lock on the active camera in all non-Pro modes
    LaunchedEffect(activeCamera, activeLens, activeMode, focusTouchRequest) {
        val camera = activeCamera ?: return@LaunchedEffect
        val request = focusTouchRequest
        if (request == null || !isTapToFocusEnabledForMode(activeMode)) {
            isHardwareAfLockActive = false
            isHardwareAeLockActive = false
            try {
                camera.cameraControl.cancelFocusAndMetering()
            } catch (_: Exception) {}
            return@LaunchedEffect
        }

        // Release any previous Camera2 AF/AE hold before initiating a new AF + AE metering sweep
        if (isHardwareAfLockActive) {
            isHardwareAfLockActive = false
        }
        if (isHardwareAeLockActive) {
            isHardwareAeLockActive = false
        }

        val expState = camera.cameraInfo.exposureState
        val evRange = expState.exposureCompensationRange.let { it.lower..it.upper }
        val isEvCompSupported = expState.isExposureCompensationSupported && (evRange.start < 0 || evRange.endInclusive > 0)
        val isModeFocusEvSupported = isEvCompSupported && isFocusEvSliderEnabledForMode(activeMode)

        val lensCaps = cachedCapabilities[activeLens]?.manualCapabilities
            ?: queryDeviceCameraCapabilities(context)[activeLens]?.manualCapabilities

        val normalizedCoord = computeNormalizedMeteringCoordinate(
            tapX = request.tapX,
            tapY = request.tapY,
            previewWidth = request.previewWidth,
            previewHeight = request.previewHeight,
            isFrontCamera = activeLens.facing == LensFacing.FRONT,
            isSelfieMirrorEnabled = isSelfieMirrorEnabled
        )

        val meteringPoint: MeteringPoint = if (previewView.width > 0 && previewView.height > 0) {
            val rawClampedX = request.tapX.coerceIn(0f, request.previewWidth.coerceAtLeast(1f))
            val rawClampedY = request.tapY.coerceIn(0f, request.previewHeight.coerceAtLeast(1f))
            val viewX = ((rawClampedX / request.previewWidth.coerceAtLeast(1f)) * previewView.width)
                .coerceIn(0f, previewView.width.toFloat())
            val viewY = ((rawClampedY / request.previewHeight.coerceAtLeast(1f)) * previewView.height)
                .coerceIn(0f, previewView.height.toFloat())
            previewView.meteringPointFactory.createPoint(viewX, viewY)
        } else {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            factory.createPoint(normalizedCoord.x, normalizedCoord.y)
        }

        // Verify AF (FLAG_AF) and AE (FLAG_AE) capabilities independently on the active camera
        val verifiedAfAe = verifyCameraXFocusMeteringSupport(
            cameraInfo = camera.cameraInfo,
            manualCapabilities = lensCaps,
            meteringPoint = meteringPoint
        )

        val action = buildCameraXFocusMeteringAction(
            meteringPoint = meteringPoint,
            capabilities = verifiedAfAe,
            isLockRequested = request.isLockRequested
        )

        if (action == null) {
            val fallbackStatus = if (isModeFocusEvSupported) {
                if (request.isLockRequested) FocusIndicatorStatus.LOCKED_METERING_ONLY else FocusIndicatorStatus.METERING_ONLY
            } else {
                FocusIndicatorStatus.UNSUPPORTED
            }
            onFocusStatusChanged?.invoke(request.requestId, fallbackStatus)
            return@LaunchedEffect
        }

        try {
            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({
                try {
                    val result = future.get()
                    if (request.isLockRequested) {
                        // Lock both AF and AE (when supported) after metering settles at the tapped subject point
                        val canLockAe = lensCaps?.isAeLockSupported == true
                        if (verifiedAfAe.isAfSupported && verifiedAfAe.isFocusLockSupported) {
                            isHardwareAfLockActive = true
                            if (canLockAe) isHardwareAeLockActive = true
                            onFocusStatusChanged?.invoke(
                                request.requestId,
                                FocusIndicatorStatus.LOCKED
                            )
                        } else if (verifiedAfAe.isAeSupported || isModeFocusEvSupported) {
                            if (canLockAe) isHardwareAeLockActive = true
                            onFocusStatusChanged?.invoke(request.requestId, FocusIndicatorStatus.LOCKED_METERING_ONLY)
                        } else {
                            onFocusStatusChanged?.invoke(request.requestId, FocusIndicatorStatus.UNSUPPORTED)
                        }
                    } else {
                        if (verifiedAfAe.isAfSupported) {
                            onFocusStatusChanged?.invoke(
                                request.requestId,
                                if (result.isFocusSuccessful) {
                                    FocusIndicatorStatus.FOCUSED
                                } else {
                                    FocusIndicatorStatus.FOCUS_UNSUCCESSFUL
                                }
                            )
                        } else if (verifiedAfAe.isAeSupported || isModeFocusEvSupported) {
                            onFocusStatusChanged?.invoke(request.requestId, FocusIndicatorStatus.METERING_ONLY)
                        } else {
                            onFocusStatusChanged?.invoke(request.requestId, FocusIndicatorStatus.UNSUPPORTED)
                        }
                    }
                } catch (_: Exception) {
                    // OperationCanceledException occurs normally if user taps a new point before previous sweep finishes
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.w(TAG, "Focus and metering request failed on ${activeLens.displayName}", e)
            val errorStatus = if (verifiedAfAe.isAeSupported || isModeFocusEvSupported) {
                if (request.isLockRequested) FocusIndicatorStatus.LOCKED_METERING_ONLY else FocusIndicatorStatus.METERING_ONLY
            } else {
                FocusIndicatorStatus.UNSUPPORTED
            }
            onFocusStatusChanged?.invoke(request.requestId, errorStatus)
        }
    }

    // Continuous Portrait subject face metering: when no manual tap-to-focus point is active in Portrait mode,
    // periodically steer AF + AE metering toward the detected face so exposure stays balanced on the subject.
    LaunchedEffect(activeCamera, activeMode, activeLens, focusTouchRequest) {
        if (activeMode != CameraMode.PORTRAIT || focusTouchRequest != null) return@LaunchedEffect
        val camera = activeCamera ?: return@LaunchedEffect
        while (true) {
            delay(1200L)
            if (latestFocusTouchRequest != null || latestActiveMode != CameraMode.PORTRAIT) break
            val faceRect = latestDetectedFaceRect.get() ?: continue
            val activeArray = latestSensorActiveArray.get() ?: continue
            if (activeArray.width() <= 0 || activeArray.height() <= 0) continue
            runCatching {
                val normX = ((faceRect.centerX() - activeArray.left).toFloat() / activeArray.width()).coerceIn(0.1f, 0.9f)
                val normY = ((faceRect.centerY() - activeArray.top).toFloat() / activeArray.height()).coerceIn(0.1f, 0.9f)
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val pt = factory.createPoint(normX, normY, 0.22f)
                val action = FocusMeteringAction.Builder(
                    pt,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                camera.cameraControl.startFocusAndMetering(action)
            }
        }
    }

    // Apply exposure compensation index to active CameraX CameraControl (keeping Pro mode EV and non-Pro EV strictly separate)
    LaunchedEffect(activeCamera, activeLens, activeMode, autoExposureIndex, proEv) {
        val camera = activeCamera ?: return@LaunchedEffect
        val expState = camera.cameraInfo.exposureState
        val evRange = expState.exposureCompensationRange.let { it.lower..it.upper }
        val isEvSupported = expState.isExposureCompensationSupported && (evRange.start < 0 || evRange.endInclusive > 0)

        if (!isEvSupported) {
            if (isFocusEvSliderEnabledForMode(activeMode) && autoExposureIndex != 0) {
                onExposureIndexApplied?.invoke(0)
            }
            return@LaunchedEffect
        }

        val step = expState.exposureCompensationStep.let {
            if (it.denominator != 0 && it.toFloat() > 0f) it.toFloat() else (1f / 3f)
        }

        val targetIndex = resolveModeExposureCompensationIndex(
            mode = activeMode,
            autoExposureIndex = autoExposureIndex,
            proEv = proEv,
            isExposureCompensationSupported = isEvSupported,
            evRange = evRange,
            evStep = step
        )

        try {
            val future = camera.cameraControl.setExposureCompensationIndex(targetIndex)
            future.addListener({
                try {
                    val appliedIndex = future.get()
                    if (isFocusEvSliderEnabledForMode(latestActiveMode) &&
                        targetIndex == clampExposureIndex(latestAutoExposureIndex, evRange)
                    ) {
                        onExposureIndexApplied?.invoke(appliedIndex)
                    }
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    val isCancelled = e is java.util.concurrent.CancellationException ||
                        cause is java.util.concurrent.CancellationException ||
                        cause is androidx.camera.core.CameraControl.OperationCanceledException
                    if (!isCancelled) {
                        Log.w(TAG, "Camera rejected exposure compensation index $targetIndex", e)
                        val actualIndex = camera.cameraInfo.exposureState.exposureCompensationIndex
                        if (isFocusEvSliderEnabledForMode(latestActiveMode) &&
                            targetIndex == clampExposureIndex(latestAutoExposureIndex, evRange)
                        ) {
                            onExposureIndexApplied?.invoke(actualIndex)
                        }
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply exposure compensation index $targetIndex on ${activeLens.displayName}", e)
        }
    }

    // Forward live zoom/torch/EV updates while direct Camera2 video recording is running
    LaunchedEffect(isDirectCamera2RecordingActive, zoomRatio, flashMode, autoExposureIndex) {
        if (isDirectCamera2RecordingActive) {
            activeCamera2DirectRecorder?.updateLiveControls(
                zoomRatio = zoomRatio,
                torchEnabled = flashMode == FlashMode.TORCH,
                exposureCompensationIndex = autoExposureIndex
            )
        }
    }

    // Apply manual Pro controls (ISO, Shutter, Focus, WB), Focus/AE Lock hold (non-Pro modes), or Video AE FPS & Stabilization options to Camera2 capture request
    LaunchedEffect(
        activeCamera,
        activeMode,
        manualIso,
        manualShutterSpeed,
        proFocusMode,
        proFocusDistance,
        proWhiteBalance,
        activeLens,
        videoFrameRate,
        videoStabilization,
        slowMoSpeed,
        isHardwareAfLockActive,
        isHardwareAeLockActive,
        focusTouchRequest,
        portraitBlurLevel,
        portraitBlurStrength,
        nightSightExposure,
        autoExposureIndex,
        proEv
    ) {
        if (isDirectCamera2RecordingActive) return@LaunchedEffect
        val camera = activeCamera ?: return@LaunchedEffect
        try {
            val camera2Control = Camera2CameraControl.from(camera.cameraControl)
            val lensCaps = cachedCapabilities[activeLens] ?: queryDeviceCameraCapabilities(context)[activeLens]
            val manualCaps = lensCaps?.manualCapabilities
            val supportsAfAutoHold = isTapToFocusEnabledForMode(activeMode) &&
                isHardwareAfLockActive &&
                manualCaps?.isAutoFocusSupported == true &&
                (manualCaps.availableAfModes.isEmpty() ||
                    manualCaps.availableAfModes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO))
            val effectiveAeLock = isTapToFocusEnabledForMode(activeMode) &&
                isHardwareAeLockActive &&
                manualCaps?.isAeLockSupported == true

            val expState = camera.cameraInfo.exposureState
            val liveEvRange = expState.exposureCompensationRange.let { it.lower..it.upper }
            val isLiveEvSupported = expState.isExposureCompensationSupported &&
                (liveEvRange.start < 0 || liveEvRange.endInclusive > 0)
            val liveEvStep = expState.exposureCompensationStep.let {
                if (it.denominator != 0 && it.toFloat() > 0f) it.toFloat() else (1f / 3f)
            }
            val effectiveNonProEvIndex = resolveModeExposureCompensationIndex(
                mode = activeMode,
                autoExposureIndex = autoExposureIndex,
                proEv = 0f,
                isExposureCompensationSupported = isLiveEvSupported,
                evRange = liveEvRange,
                evStep = liveEvStep
            )

            if (activeMode.isVideo && lensCaps != null) {
                val reconciled = lensCaps.reconcileVideoSettings(
                    requestedResolution = videoResolution,
                    requestedFrameRate = videoFrameRate,
                    requestedStabilization = videoStabilization,
                    requestedFlashMode = flashMode
                )
                val aeRanges = cachedAeRanges.getOrPut(activeLens) {
                    queryLensAeFpsRanges(context, activeLens)
                }
                val targetFps = if (activeMode == CameraMode.SLOW_MOTION) {
                    slowMoSpeed.expectedFps
                } else {
                    reconciled.frameRate.fps
                }
                val fpsRange = selectBestTargetFpsRange(aeRanges, targetFps)
                val optionsBuilder = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, effectiveAeLock)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                if (isLiveEvSupported) {
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        effectiveNonProEvIndex
                    )
                }

                when (reconciled.stabilization) {
                    VideoStabilizationMode.OFF -> {
                        optionsBuilder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                        if (lensCaps.isOpticalStabilizationSupported) {
                            optionsBuilder.setCaptureRequestOption(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                            )
                        }
                    }
                    VideoStabilizationMode.STANDARD -> {
                        when {
                            lensCaps.isDigitalVideoStabilizationSupported -> {
                                optionsBuilder.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                )
                            }
                            lensCaps.isPreviewStabilizationSupported &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                                optionsBuilder.setCaptureRequestOption(
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    2 // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                                )
                            }
                            lensCaps.isOpticalStabilizationSupported -> {
                                optionsBuilder.setCaptureRequestOption(
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                                )
                            }
                        }
                    }
                    VideoStabilizationMode.ACTIVE -> {
                        if (lensCaps.isPreviewStabilizationSupported &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            optionsBuilder.setCaptureRequestOption(
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                2 // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                            )
                        } else if (lensCaps.isDigitalVideoStabilizationSupported) {
                            optionsBuilder.setCaptureRequestOption(
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                            )
                        }
                        if (lensCaps.isOpticalStabilizationSupported) {
                            optionsBuilder.setCaptureRequestOption(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                            )
                        }
                    }
                }
                if (supportsAfAutoHold) {
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO
                    )
                } else if (focusTouchRequest == null) {
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }
                if (focusTouchRequest != null) {
                    camera2Control.addCaptureRequestOptions(optionsBuilder.build())
                } else {
                    camera2Control.clearCaptureRequestOptions()
                    camera2Control.setCaptureRequestOptions(optionsBuilder.build())
                }
                return@LaunchedEffect
            }

            if (activeMode == CameraMode.PRO) {
                val proOptions = buildProCamera2CaptureRequestOptions(
                    manualIso = manualIso,
                    manualShutterSpeed = manualShutterSpeed,
                    proFocusMode = proFocusMode,
                    proFocusDistance = proFocusDistance,
                    proWhiteBalance = proWhiteBalance,
                    manualCaps = manualCaps,
                    proEv = proEv
                )
                camera2Control.clearCaptureRequestOptions()
                camera2Control.setCaptureRequestOptions(proOptions)
                Log.d(TAG, "Applied Pro Mode Camera2 capture options on ${activeLens.displayName}")
                return@LaunchedEffect
            }

            if (activeMode == CameraMode.ASTRO) {
                val astroOptions = buildAstroCamera2CaptureRequestOptions(
                    astroFocus = astroFocus,
                    astroStrategy = astroStrategy,
                    manualCaps = manualCaps,
                    exposureCompensationIndex = effectiveNonProEvIndex
                )
                camera2Control.clearCaptureRequestOptions()
                camera2Control.setCaptureRequestOptions(astroOptions)
                Log.d(TAG, "Applied Astrophotography Camera2 capture options on ${activeLens.displayName}")
                return@LaunchedEffect
            }

            // Non-Pro still modes (Photo, Portrait, HD, Night Sight):
            // CRITICAL: When a tap-to-focus request is active, do NOT clear capture request options or overwrite
            // CONTROL_AF_MODE unless AF lock is requested — otherwise Camera2 wipes the active AF/AE metering
            // regions set by startFocusAndMetering() and prevents exposure from settling in Photo & Portrait.
            val hasActiveTapMetering = focusTouchRequest != null
            val hasActiveExtension = activeExtensionMode != androidx.camera.extensions.ExtensionMode.NONE
            val enableHardwareHdr = !hasActiveExtension &&
                activeMode == CameraMode.PHOTO &&
                hdrMode != com.example.core.preferences.HdrMode.OFF &&
                (lensCaps?.isHardwareHdrSceneModeSupported == true) &&
                effectiveNonProEvIndex == 0 &&
                !hasActiveTapMetering &&
                !effectiveAeLock
            val enableHardwareNightScene = !hasActiveExtension &&
                activeMode == CameraMode.NIGHT_SIGHT &&
                nightSightExposure != NightSightExposure.OFF &&
                (lensCaps?.isHardwareNightSceneModeSupported == true)
            val enableHardwarePortraitBokeh = !hasActiveExtension &&
                activeMode == CameraMode.PORTRAIT &&
                portraitBlurLevel != PortraitBlurLevel.OFF &&
                portraitBlurStrength > 0.0f &&
                (lensCaps?.isHardwareBokehSupported == true)
            val enableFaceDetect = activeMode == CameraMode.PORTRAIT &&
                ((lensCaps?.manualCapabilities?.maxFaceDetectCount ?: 0) > 0 ||
                    lensCaps?.manualCapabilities?.availableFaceDetectModes?.any { it != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF } == true)

            val resetOptions = buildNonProResetCamera2CaptureRequestOptions(
                isVideoMode = false,
                isHardwareAfLockActive = isHardwareAfLockActive,
                supportsAfAutoHold = supportsAfAutoHold,
                enableHardwareHdrSceneMode = enableHardwareHdr,
                enableHardwareNightSceneMode = enableHardwareNightScene,
                enableHardwarePortraitBokeh = enableHardwarePortraitBokeh,
                supportsExtendedBokehContinuous = lensCaps?.manualCapabilities?.isExtendedBokehContinuousSupported == true,
                supportsExtendedBokehStill = lensCaps?.manualCapabilities?.isExtendedBokehStillSupported == true,
                enableHardwareFaceDetect = enableFaceDetect,
                exposureCompensationIndex = effectiveNonProEvIndex,
                overrideAfMode = !hasActiveTapMetering || supportsAfAutoHold,
                lockAe = effectiveAeLock,
                ispQualityCapabilities = lensCaps?.ispQualityCapabilities,
                preferHighQualityIsp = false // Keep preview repeating stream FAST for smooth CAF/AE
            )
            if (hasActiveTapMetering) {
                camera2Control.addCaptureRequestOptions(resetOptions)
            } else {
                camera2Control.clearCaptureRequestOptions()
                camera2Control.setCaptureRequestOptions(resetOptions)
            }
            Log.d(
                TAG,
                "Applied non-Pro Camera2 settings for ${activeMode.name} on ${activeLens.displayName} " +
                    "(evIndex=$effectiveNonProEvIndex, aeLock=$effectiveAeLock, tapActive=$hasActiveTapMetering, ext=$activeExtensionMode)"
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Error applying Camera2 capture request options", exc)
        }
    }

    // Trigger photo capture when shutter is tapped, with robust self-timer countdown and cancellation support
    LaunchedEffect(photoCaptureTrigger) {
        if (photoCaptureTrigger <= 0) return@LaunchedEffect
        if (activeMode == CameraMode.PANORAMA) {
            Log.d(TAG, "Ignoring ordinary single-photo capture trigger while in Panorama mode")
            return@LaunchedEffect
        }
        if (isCapturing || isCaptureInFlight.get()) {
            Log.d(TAG, "Ignoring duplicate photo capture trigger while capture is already in flight")
            return@LaunchedEffect
        }

        // Repeated shutter tap while countdown is active cancels the countdown
        if (isCountingDown) {
            isCountingDown = false
            countdownRemainingSeconds = 0
            Log.d(TAG, "Countdown cancelled by repeated shutter tap")
            return@LaunchedEffect
        }

        val imageCapture = boundImageCapture
        if (imageCapture == null) {
            onCaptureError?.invoke("Camera engine is not connected or available for photo capture.")
            return@LaunchedEffect
        }

        val captureLens = activeLens
        val captureMode = activeMode
        val captureAspectRatio = aspectRatioMode
        val captureSelfieMirror = isSelfieMirrorEnabled && captureLens.facing == LensFacing.FRONT
        val captureProRawFormat = if (captureMode == CameraMode.PRO) proRawFormat else ProRawFormat.JPEG_ONLY
        val captureSaveLocation = isSaveLocationEnabled
        val captureSoundsEnabled = isCameraSoundsEnabled
        val capturePortraitBlurLevel = latestPortraitBlurLevel
        val capturePortraitBlurStrength = latestPortraitBlurStrength
        val capturePortraitRetouch = latestPortraitRetouch
        val captureNightSightExposure = latestNightSightExposure
        val captureAstroDuration = astroDuration
        val captureFocusReq = latestFocusTouchRequest

        if (timerMode.seconds > 0) {
            var completed = false
            try {
                isCountingDown = true
                var remaining = timerMode.seconds
                while (remaining > 0 && isCountingDown) {
                    countdownRemainingSeconds = remaining
                    delay(1000L)
                    remaining--
                    // Guard against camera lens or mode change during delay
                    if (activeLens != captureLens || activeMode != captureMode) {
                        Log.d(TAG, "Countdown cancelled due to lens or mode change")
                        return@LaunchedEffect
                    }
                }
                if (isCountingDown && remaining <= 0) {
                    completed = true
                }
            } finally {
                isCountingDown = false
                countdownRemainingSeconds = 0
            }

            if (!completed) {
                return@LaunchedEffect
            }
        }

        if (photoSaveQueue.isFull) {
            onCaptureError?.invoke("Saving previous photos — ready in a moment.")
            return@LaunchedEffect
        }

        if (!isCaptureInFlight.compareAndSet(false, true)) {
            return@LaunchedEffect
        }
        isCapturing = true

        val shutterPressedAtMs = System.currentTimeMillis()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val captureSelectedFilter = com.example.core.processing.CameraFilterProcessor.resolveEffectiveFilter(captureMode, latestSelectedFilter)
        val captureHdOption = latestHdOutputOption
        val captureSpeedMode = latestPhotoCaptureSpeedMode
        val captureActiveExtMode = latestActiveExtensionMode
        val captureActiveExtLabel = com.example.core.camera.CameraExtensionsHelper.extensionModeDisplayName(captureActiveExtMode)
        val captureLensCaps = cachedCapabilities[captureLens]

        // 1. HD MODE: When maximum-resolution stream (API 31+ SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION),
        // physical sub-camera ID, or higher-than-bound JPEG size is available, perform a dedicated single-surface
        // Camera2 still-capture session switch (no preview surface mixed with max-res stream) and restore CameraX afterward.
        val hdMaxSource = captureLensCaps?.hdMaxPhotoCaptureSource
        val boundCapSize = imageCapture.resolutionInfo?.resolution
        val boundCapPixels = if (boundCapSize != null) boundCapSize.width.toLong() * boundCapSize.height.toLong() else 0L
        val shouldUseDedicatedHdMaxResCapture = captureMode == CameraMode.HD &&
            captureHdOption == HdOutputOption.MAX_NATIVE &&
            hdMaxSource != null &&
            (hdMaxSource.requiresMaxResPixelMode ||
                hdMaxSource.isPhysicalSubCamera ||
                hdMaxSource.sourceTag.startsWith("YUV", ignoreCase = true) ||
                (boundCapPixels > 0L && hdMaxSource.totalPixels > (boundCapPixels * 105L) / 100L))

        if (shouldUseDedicatedHdMaxResCapture && hdMaxSource != null) {
            showShutterFlashOverlay = true
            val displayRot = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            val captureFlash = latestFlashMode
            val captureEvIdx = latestAutoExposureIndex
            try {
                // Unbind CameraX preview before opening the dedicated single-surface max-resolution Camera2 session
                val provider = ProcessCameraProvider.getInstance(context).get()
                provider.unbindAll()
                activeCamera = null
                boundImageCapture = null
                lastBoundConfig = null
            } catch (e: Exception) {
                Log.w(TAG, "Unable to unbind CameraX prior to HD max-res capture", e)
            }

            photoCaptureExecutor.execute {
                var hdResult: com.example.core.camera.Camera2StillCaptureResult? = null
                try {
                    hdResult = Camera2MaxResolutionStillCapturer.captureMaximumResolutionJpeg(
                        context = context,
                        source = hdMaxSource,
                        flashMode = captureFlash,
                        exposureCompensationIndex = captureEvIdx,
                        displayRotation = displayRot
                    )
                    val frameCapturedAtMs = System.currentTimeMillis()
                    if (hdResult != null && hdResult.jpegBytes.isNotEmpty()) {
                        if (captureSoundsEnabled) {
                            runCatching {
                                val sound = android.media.MediaActionSound()
                                sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                            }
                        }
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                        val displayName = "IMG_HD_$timeStamp"
                        val location = if (captureSaveLocation &&
                            (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        ) {
                            runCatching {
                                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                                lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                    ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                            }.getOrNull()
                        } else null

                        val processedPayload = AspectCropUtils.processCapturedJpegBytes(
                            sourceBytes = hdResult.jpegBytes,
                            rotationDegrees = hdResult.rotationDegrees,
                            aspectRatioMode = captureAspectRatio,
                            mirrorHorizontally = captureSelfieMirror,
                            jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                            allowExifOrientationBypass = true
                        )
                        val savedUri = if (processedPayload != null) {
                            com.example.core.storage.MediaStorePhotoSaver.savePhotoAtomically(
                                context = context,
                                outputBytes = processedPayload.jpegBytes,
                                sourceExifBytes = hdResult.jpegBytes,
                                displayName = displayName,
                                mimeType = "image/jpeg",
                                width = processedPayload.width,
                                height = processedPayload.height,
                                exifOrientation = processedPayload.exifOrientation,
                                location = location,
                                isDirectCameraJpeg = !processedPayload.wasRecompressed
                            )?.uri
                        } else null

                        if (savedUri != null) {
                            val savedAtMs = System.currentTimeMillis()
                            com.example.core.camera.CaptureLatencyTracker.record(
                                modeName = captureMode.displayName,
                                speedModeLabel = "MaxRes",
                                extensionLabel = "None",
                                capturePathLabel = if (processedPayload?.wasRecompressed == false) "HD-DirectJPEG" else "HD-MaxRes",
                                shutterPressedAtMs = shutterPressedAtMs,
                                frameCapturedAtMs = frameCapturedAtMs,
                                savedAtMs = savedAtMs,
                                width = processedPayload?.width ?: hdResult.width,
                                height = processedPayload?.height ?: hdResult.height
                            )
                            logSavedPhotoDimensions(context, savedUri, "HD_MAX_RES")
                            isCaptureInFlight.set(false)
                            mainExecutor.execute {
                                isCapturing = false
                                rebindTrigger++
                                onCaptureSuccess?.invoke(savedUri)
                            }
                            return@execute
                        }
                    }
                    isCaptureInFlight.set(false)
                    mainExecutor.execute {
                        isCapturing = false
                        rebindTrigger++
                        onCaptureError?.invoke("Unable to capture maximum-resolution HD photo on this sensor.")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Dedicated HD max-resolution capture failed", e)
                    isCaptureInFlight.set(false)
                    mainExecutor.execute {
                        isCapturing = false
                        rebindTrigger++
                        onCaptureError?.invoke("HD capture failed: ${e.localizedMessage ?: "unknown error"}")
                    }
                }
            }
            return@LaunchedEffect
        }

        // 2. SMART 3A (AE / AF / AWB) CONVERGENCE GATE:
        // Preview already runs continuous AF and AE. Only wait when flash is required, Portrait face-metering
        // is needed, tap-focus was just requested, or AE/AF/AWB has not yet converged!
        val camForPrecapture = activeCamera
        val needs3AWait = com.example.core.camera.CameraCaptureQualityPolicy.needsPrecaptureConvergenceWait(
            aeState = latestAeState.get(),
            afState = latestAfState.get(),
            awbState = latestAwbState.get(),
            flashMode = latestFlashMode,
            activeMode = captureMode,
            hasActiveTapFocus = captureFocusReq != null
        )
        if (camForPrecapture != null && needs3AWait) {
            if (captureMode == CameraMode.PORTRAIT && captureFocusReq == null) {
                val faceRect = latestDetectedFaceRect.get()
                val activeArray = latestSensorActiveArray.get()
                if (faceRect != null && activeArray != null && activeArray.width() > 0 && activeArray.height() > 0) {
                    runCatching {
                        val normX = ((faceRect.centerX() - activeArray.left).toFloat() / activeArray.width()).coerceIn(0.1f, 0.9f)
                        val normY = ((faceRect.centerY() - activeArray.top).toFloat() / activeArray.height()).coerceIn(0.1f, 0.9f)
                        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                        val pt = factory.createPoint(normX, normY, 0.22f)
                        val action = FocusMeteringAction.Builder(
                            pt,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        camForPrecapture.cameraControl.startFocusAndMetering(action)
                    }
                }
            }
            val waitBudgetMs = if (captureMode == CameraMode.PHOTO && captureSpeedMode == com.example.core.preferences.PhotoCaptureSpeedMode.FAST) {
                650L
            } else {
                1100L
            }
            val deadlineMs = System.currentTimeMillis() + waitBudgetMs
            while (System.currentTimeMillis() < deadlineMs) {
                val ae = latestAeState.get()
                val af = latestAfState.get()
                val awb = latestAwbState.get()
                val aeReady = com.example.core.camera.CameraCaptureQualityPolicy.isAeConverged(ae) ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                if (aeReady &&
                    com.example.core.camera.CameraCaptureQualityPolicy.isAfConverged(af) &&
                    com.example.core.camera.CameraCaptureQualityPolicy.isAwbConverged(awb)
                ) {
                    break
                }
                delay(25L)
            }
        }

        if (captureMode != CameraMode.NIGHT_SIGHT && captureMode != CameraMode.ASTRO) {
            showShutterFlashOverlay = true
        } else if (captureMode == CameraMode.NIGHT_SIGHT) {
            nightSightCancelFlag.set(false)
            emitNightSightProgress(
                NightSightProgressState(
                    phase = NightSightCapturePhase.CAPTURING,
                    currentFrame = 0,
                    totalFrames = 1,
                    progress = 0.05f,
                    statusLabel = "Starting…"
                )
            )
        } else if (captureMode == CameraMode.ASTRO) {
            astroStopAndSaveFlag.set(false)
            astroDiscardFlag.set(false)
            val plannedFrames = captureAstroDuration.targetFrames.coerceAtLeast(1)
            val estimatedTotalSec = captureAstroDuration.targetSeconds.toLong().coerceAtLeast(plannedFrames.toLong())
            latestOnShutterProgressChanged?.invoke(
                ShutterProgressState(
                    mode = ShutterProgressMode.ASTRO,
                    phase = ShutterProgressPhase.CAPTURING,
                    currentFrame = 0,
                    totalFrames = plannedFrames,
                    progress = 0.03f,
                    elapsedSeconds = 0L,
                    remainingSeconds = estimatedTotalSec,
                    statusLabel = "0/$plannedFrames",
                    detailLabel = formatAstroCaptureCaption(0, plannedFrames, 0L, estimatedTotalSec),
                    canStopAndSave = false
                )
            )
        }

        imageCapture.takePicture(
            photoCaptureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val rotationDegrees: Int
                    val bytes: ByteArray
                    try {
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val buffer = imageProxy.planes[0].buffer
                        bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                    } catch (e: Exception) {
                        try { imageProxy.close() } catch (_: Exception) {}
                        isCaptureInFlight.set(false)
                        mainExecutor.execute {
                            isCapturing = false
                            if (captureMode == CameraMode.NIGHT_SIGHT) {
                                emitNightSightProgress(NightSightProgressState())
                            } else if (captureMode == CameraMode.ASTRO) {
                                latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                            }
                            onCaptureError?.invoke("Failed to read captured image buffer: ${e.localizedMessage}")
                        }
                        return
                    } finally {
                        // Release camera buffer immediately before decoding/saving
                        try { imageProxy.close() } catch (_: Exception) {}
                    }

                    val frameCapturedAtMs = System.currentTimeMillis()
                    val captureHdrModeAtShot = latestHdrMode
                    val captureBaseEvIndex = latestAutoExposureIndex
                    val isSingleFrameFastHandoff = captureMode == CameraMode.HD ||
                        captureMode == CameraMode.PORTRAIT ||
                        captureMode == CameraMode.PRO ||
                        (captureMode == CameraMode.PHOTO &&
                            (captureActiveExtMode != androidx.camera.extensions.ExtensionMode.NONE ||
                                (captureSpeedMode == com.example.core.preferences.PhotoCaptureSpeedMode.FAST &&
                                    captureHdrModeAtShot != com.example.core.preferences.HdrMode.ON)))

                    // Unlock shutter immediately once frame bytes are copied for single-frame captures so the next
                    // shot can fire right away while this photo saves in the bounded background queue.
                    if (isSingleFrameFastHandoff) {
                        isCaptureInFlight.set(false)
                        mainExecutor.execute {
                            isCapturing = false
                        }
                    }

                    // Show an immediate preview thumbnail right after Portrait capture so the gallery button
                    // updates instantly before background bokeh processing finishes and replaces it.
                    if (captureMode == CameraMode.PORTRAIT) {
                        val instantThumb = PortraitProcessor.decodeFastPreviewThumbnail(
                            sourceBytes = bytes,
                            rotationDegrees = rotationDegrees,
                            aspectRatioMode = captureAspectRatio,
                            mirrorHorizontally = captureSelfieMirror
                        )
                        if (instantThumb != null) {
                            mainExecutor.execute {
                                latestOnInstantPreviewThumbnail?.invoke(instantThumb)
                            }
                        }
                    }

                    val runProcessingAndSave: () -> Unit = saveTask@{
                    var portraitCompletedSuccessfully = false
                    try {
                        if (captureSoundsEnabled) {
                            runCatching {
                                val sound = android.media.MediaActionSound()
                                sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                            }
                        }

                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
                        val displayName = "IMG_$timeStamp"
                        val isDngRequested = captureMode == CameraMode.PRO &&
                            (captureProRawFormat == ProRawFormat.RAW_DNG_ONLY || captureProRawFormat == ProRawFormat.RAW_PLUS_JPEG)
                        val isDngOnly = captureMode == CameraMode.PRO && captureProRawFormat == ProRawFormat.RAW_DNG_ONLY

                        val location = if (captureSaveLocation &&
                            (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        ) {
                            runCatching {
                                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                                lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                    ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                            }.getOrNull()
                        } else null

                        var portraitFallbackReason: String? = null
                        var portraitStageTimings: com.example.core.camera.PortraitStageTimings? = null
                        var capturePathLabel = "DirectJPEG"
                        val processedPayload = if (captureMode == CameraMode.PORTRAIT) {
                            capturePathLabel = "Portrait"
                            val captureElapsedMs = (frameCapturedAtMs - shutterPressedAtMs).coerceAtLeast(0L)
                            val normFocusX = if (captureFocusReq != null && captureFocusReq.previewWidth > 0f) {
                                (captureFocusReq.tapX / captureFocusReq.previewWidth).coerceIn(0.1f, 0.9f)
                            } else {
                                0.5f
                            }
                            val normFocusY = if (captureFocusReq != null && captureFocusReq.previewHeight > 0f) {
                                (captureFocusReq.tapY / captureFocusReq.previewHeight).coerceIn(0.1f, 0.9f)
                            } else {
                                0.48f
                            }
                            when (
                                val outcome = PortraitProcessor.processPortraitJpegBytes(
                                    sourceBytes = bytes,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    blurLevel = capturePortraitBlurLevel,
                                    blurStrengthOverride = capturePortraitBlurStrength,
                                    retouch = capturePortraitRetouch,
                                    focusNormX = normFocusX,
                                    focusNormY = normFocusY,
                                    depthSupportPath = cachedCapabilities[captureLens]?.portraitDepthSupportPath
                                        ?: com.example.core.camera.PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY,
                                    jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                    captureElapsedMs = captureElapsedMs,
                                    onStageProgress = { stageProgress, stageLabel ->
                                        val queuedNow = portraitJobsInFlight.get().coerceAtLeast(1)
                                        val caption = if (queuedNow > 1) {
                                            "$stageLabel ($queuedNow queued)"
                                        } else {
                                            "Portrait: $stageLabel"
                                        }
                                        val canTakeNext = !photoSaveQueue.isFull
                                        mainExecutor.execute {
                                            latestOnShutterProgressChanged?.invoke(
                                                ShutterProgressState(
                                                    mode = ShutterProgressMode.PORTRAIT,
                                                    phase = ShutterProgressPhase.PROCESSING,
                                                    progress = stageProgress.coerceIn(0.05f, 0.96f),
                                                    statusLabel = stageLabel,
                                                    detailLabel = caption,
                                                    allowConcurrentCapture = canTakeNext
                                                )
                                            )
                                        }
                                    }
                                )
                            ) {
                                is PortraitProcessingOutcome.ProcessedPortrait -> {
                                    portraitStageTimings = outcome.stageTimings
                                    com.example.core.processing.ProcessedPhotoPayload(
                                        jpegBytes = outcome.jpegBytes,
                                        width = outcome.width,
                                        height = outcome.height,
                                        exifOrientation = outcome.exifOrientation,
                                        wasRecompressed = true
                                    )
                                }
                                is PortraitProcessingOutcome.FallbackOriginal -> {
                                    portraitFallbackReason = outcome.reason
                                    portraitStageTimings = outcome.stageTimings
                                    com.example.core.processing.ProcessedPhotoPayload(
                                        jpegBytes = outcome.jpegBytes,
                                        width = outcome.width,
                                        height = outcome.height,
                                        exifOrientation = outcome.exifOrientation,
                                        wasRecompressed = true
                                    )
                                }
                                null -> null
                            }
                        } else if (captureMode == CameraMode.NIGHT_SIGHT) {
                            val nightStartMs = System.currentTimeMillis()
                            // Probe frame size from JPEG header (0 pixels allocated) to compute safe memory-bounded capture plan
                            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                            val frameW = if (boundsOpts.outWidth > 0) boundsOpts.outWidth else 4000
                            val frameH = if (boundsOpts.outHeight > 0) boundsOpts.outHeight else 3000
                            val maxSensorExpNanos = captureLensCaps?.manualCapabilities?.shutterSpeedRangeNanos?.endInclusive

                            val capturePlan = NightSightDeviceSupport.computeSafeCapturePlan(
                                context = context,
                                frameWidth = frameW,
                                frameHeight = frameH,
                                exposure = captureNightSightExposure,
                                maxSensorExposureNanos = maxSensorExpNanos
                            )
                            val totalPlannedFrames = capturePlan.frameCount.coerceAtLeast(1)
                            val decodeOpts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inSampleSize = capturePlan.recommendedDecodeSampleSize
                            }

                            var accumulator: NightSightStreamingAccumulator? = null
                            var nightSightWasCancelled = false
                            try {
                                val firstBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                                if (firstBitmap != null) {
                                    accumulator = NightSightStreamingAccumulator(firstBitmap.width, firstBitmap.height)
                                    accumulator.addFrame(firstBitmap, recycleBitmap = true)
                                    mainExecutor.execute {
                                        emitNightSightProgress(
                                            NightSightProgressState(
                                                phase = NightSightCapturePhase.CAPTURING,
                                                currentFrame = accumulator.framesMerged,
                                                totalFrames = totalPlannedFrames,
                                                progress = accumulator.framesMerged.toFloat() / totalPlannedFrames.toFloat(),
                                                statusLabel = "${accumulator.framesMerged}/$totalPlannedFrames"
                                            )
                                        )
                                    }

                                    // Stream subsequent frames one by one directly into the single running accumulator
                                    // — never hold all burst frames in memory!
                                    if (totalPlannedFrames > 1) {
                                        for (frameIndex in 1 until totalPlannedFrames) {
                                            if (nightSightCancelFlag.get() || activeLens != captureLens || activeMode != captureMode) {
                                                nightSightWasCancelled = true
                                                Log.d(TAG, "Night Sight streaming capture cancelled at frame $frameIndex/$totalPlannedFrames")
                                                break
                                            }
                                            if (accumulator.encounteredLowMemory) {
                                                Log.w(TAG, "Night Sight stopping burst early at ${accumulator.framesMerged} frames due to low memory")
                                                break
                                            }
                                            val latch = java.util.concurrent.CountDownLatch(1)
                                            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
                                            var nextFrameBytes: ByteArray? = null
                                            var frameFailed = false
                                            try {
                                                imageCapture.takePicture(
                                                    companionCaptureExecutor,
                                                    object : ImageCapture.OnImageCapturedCallback() {
                                                        override fun onCaptureSuccess(companionProxy: ImageProxy) {
                                                            try {
                                                                if (!timedOut.get() && !nightSightCancelFlag.get()) {
                                                                    val buf = companionProxy.planes[0].buffer
                                                                    val arr = ByteArray(buf.remaining())
                                                                    buf.get(arr)
                                                                    nextFrameBytes = arr
                                                                }
                                                            } catch (e: Throwable) {
                                                                frameFailed = true
                                                            } finally {
                                                                try { companionProxy.close() } catch (_: Exception) {}
                                                                latch.countDown()
                                                            }
                                                        }

                                                        override fun onError(exception: ImageCaptureException) {
                                                            frameFailed = true
                                                            latch.countDown()
                                                        }
                                                    }
                                                )
                                                val timeoutMs = maxOf(650L, capturePlan.targetPerFrameExposureMs + 500L)
                                                val completedInTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                                                if (!completedInTime) {
                                                    timedOut.set(true)
                                                    break
                                                }
                                                if (nightSightCancelFlag.get()) {
                                                    nightSightWasCancelled = true
                                                    break
                                                }
                                                val capturedArr = nextFrameBytes
                                                if (frameFailed || capturedArr == null || capturedArr.isEmpty()) {
                                                    break
                                                }
                                                // Decode, align at quarter-res, merge into running sum, and recycle immediately!
                                                val nextBmp = try {
                                                    BitmapFactory.decodeByteArray(capturedArr, 0, capturedArr.size, decodeOpts)
                                                } catch (oom: OutOfMemoryError) {
                                                    Log.w(TAG, "OOM decoding Night Sight frame ${frameIndex + 1}; finalizing ${accumulator.framesMerged} merged frames", oom)
                                                    null
                                                }
                                                if (nextBmp != null) {
                                                    val mergedOk = accumulator.addFrame(nextBmp, recycleBitmap = true)
                                                    if (!mergedOk && accumulator.encounteredLowMemory) {
                                                        break
                                                    }
                                                } else {
                                                    // Low memory during decode — stop burst and save what was merged so far
                                                    break
                                                }

                                                val curCount = accumulator.framesMerged
                                                mainExecutor.execute {
                                                    emitNightSightProgress(
                                                        NightSightProgressState(
                                                            phase = NightSightCapturePhase.CAPTURING,
                                                            currentFrame = curCount,
                                                            totalFrames = totalPlannedFrames,
                                                            progress = (curCount.toFloat() / totalPlannedFrames.toFloat()).coerceIn(0f, 1f),
                                                            statusLabel = "$curCount/$totalPlannedFrames"
                                                        )
                                                    )
                                                }
                                            } catch (e: Throwable) {
                                                Log.w(TAG, "Night Sight streaming frame $frameIndex skipped: ${e.localizedMessage}")
                                                break
                                            }
                                        }
                                    }
                                }

                                if (nightSightWasCancelled || nightSightCancelFlag.get()) {
                                    accumulator?.close()
                                    isCaptureInFlight.set(false)
                                    mainExecutor.execute {
                                        isCapturing = false
                                        emitNightSightProgress(NightSightProgressState())
                                    }
                                    return@saveTask
                                }

                                val mergedFramesCount = accumulator?.framesMerged ?: 0
                                val supportPath = captureLensCaps?.nightSightSupportPath
                                    ?: NightSightDeviceSupport.resolveNightSightSupportPath(
                                        isHardwareNightSceneSupported = false,
                                        context = context
                                    )

                                mainExecutor.execute {
                                    emitNightSightProgress(
                                        NightSightProgressState(
                                            phase = NightSightCapturePhase.PROCESSING,
                                            currentFrame = mergedFramesCount,
                                            totalFrames = totalPlannedFrames,
                                            progress = 0.05f,
                                            statusLabel = "Processing…"
                                        )
                                    )
                                }

                                if (accumulator != null && mergedFramesCount > 0) {
                                    val outcome = NightSightProcessor.finalizeStreamingAccumulator(
                                        accumulator = accumulator,
                                        rotationDegrees = rotationDegrees,
                                        aspectRatioMode = captureAspectRatio,
                                        mirrorHorizontally = captureSelfieMirror,
                                        exposure = captureNightSightExposure,
                                        supportPath = supportPath,
                                        jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                        startTimeMs = nightStartMs,
                                        onProcessingProgress = { p ->
                                            mainExecutor.execute {
                                                emitNightSightProgress(
                                                    NightSightProgressState(
                                                        phase = NightSightCapturePhase.PROCESSING,
                                                        currentFrame = mergedFramesCount,
                                                        totalFrames = totalPlannedFrames,
                                                        progress = p,
                                                        statusLabel = "${(p * 100f).toInt().coerceIn(1, 99)}%"
                                                    )
                                                )
                                            }
                                        }
                                    )
                                    capturePathLabel = "NightSight(${outcome.framesMerged}f)"
                                    if (outcome.wasPartialLowMemoryMerge) {
                                        portraitFallbackReason = "Saved Night Sight photo (${outcome.framesMerged} frames merged under low memory)."
                                    }
                                    com.example.core.processing.ProcessedPhotoPayload(
                                        jpegBytes = outcome.jpegBytes,
                                        width = outcome.width,
                                        height = outcome.height,
                                        exifOrientation = outcome.exifOrientation,
                                        wasRecompressed = true
                                    )
                                } else {
                                    capturePathLabel = "NightSight-1f"
                                    AspectCropUtils.processCapturedJpegBytes(
                                        sourceBytes = bytes,
                                        rotationDegrees = rotationDegrees,
                                        aspectRatioMode = captureAspectRatio,
                                        mirrorHorizontally = captureSelfieMirror,
                                        jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                        allowExifOrientationBypass = true
                                    )
                                }
                            } catch (oom: OutOfMemoryError) {
                                Log.e(TAG, "Night Sight OOM fallback: saving single frame safely", oom)
                                accumulator?.close()
                                portraitFallbackReason = "Low memory — saved single-frame Night Sight photo."
                                capturePathLabel = "NightSight-OOM-1f"
                                AspectCropUtils.processCapturedJpegBytes(
                                    sourceBytes = bytes,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                    allowExifOrientationBypass = true
                                )
                            } finally {
                                runCatching { accumulator?.close() }
                            }
                        } else if (captureMode == CameraMode.ASTRO) {
                            val astroStartAcquisitionMs = System.currentTimeMillis()
                            val lensCaps = cachedCapabilities[captureLens]
                            val manualCaps = lensCaps?.manualCapabilities
                            val isManualShutterSupported = manualCaps?.isManualShutterSupported == true
                            val hwShutterRange = manualCaps?.shutterSpeedRangeNanos
                            val maxExposureNanos = hwShutterRange?.endInclusive ?: 1_000_000_000L
                            val maxExposureSec = maxExposureNanos / 1_000_000_000f
                            val requestedSeconds = captureAstroDuration.targetSeconds

                            val perFrameExposureMs: Long
                            val desiredFrames: Int

                            if (isManualShutterSupported && maxExposureSec >= 0.5f) {
                                val singleFrameExpSec = minOf(maxExposureSec, 2.0f)
                                perFrameExposureMs = (singleFrameExpSec * 1000f).toLong()
                                desiredFrames = kotlin.math.round(requestedSeconds / singleFrameExpSec).toInt().coerceIn(1, 24)
                            } else {
                                perFrameExposureMs = 0L
                                desiredFrames = captureAstroDuration.targetFrames.coerceAtLeast(1)
                            }

                            Log.d(
                                TAG,
                                "Astro Streaming Capture Initiated: Requested=${captureAstroDuration.displayName} (${requestedSeconds}s), " +
                                "Lens=${captureLens.displayName}, ManualShutter=$isManualShutterSupported, " +
                                "MaxSensorExposureMs=${maxExposureNanos / 1_000_000L}ms, TargetFrames=$desiredFrames, " +
                                "PerFrameExpMs=${perFrameExposureMs}ms"
                            )

                            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                            val frameW = if (boundsOpts.outWidth > 0) boundsOpts.outWidth else 4000
                            val frameH = if (boundsOpts.outHeight > 0) boundsOpts.outHeight else 3000
                            val safeSampleSize = com.example.core.processing.AstroProcessor.computeSafeDecodeSampleSize(
                                context = context,
                                frameWidth = frameW,
                                frameHeight = frameH
                            )
                            val decodeOpts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inSampleSize = safeSampleSize
                            }

                            var accumulator: AstroStreamingAccumulator? = null
                            val capturedFramesAtomic = java.util.concurrent.atomic.AtomicInteger(0)
                            val isStillAcquiringAstro = java.util.concurrent.atomic.AtomicBoolean(true)
                            var astroTicker: java.util.concurrent.ScheduledExecutorService? = null

                            fun emitAstroCaptureState(curFrames: Int) {
                                val elapsedSec = ((System.currentTimeMillis() - astroStartAcquisitionMs) / 1000L).coerceAtLeast(0L)
                                val remainingSec = if (curFrames > 0 && curFrames < desiredFrames) {
                                    val avgMsPerFrame = maxOf(
                                        perFrameExposureMs.coerceAtLeast(600L),
                                        (System.currentTimeMillis() - astroStartAcquisitionMs) / curFrames.toLong()
                                    )
                                    (((desiredFrames - curFrames) * avgMsPerFrame) / 1000L).coerceAtLeast(1L)
                                } else if (curFrames == 0) {
                                    requestedSeconds.toLong().coerceAtLeast(1L)
                                } else {
                                    0L
                                }
                                val progressFraction = (curFrames.toFloat() / desiredFrames.toFloat()).coerceIn(0.03f, 1f)
                                mainExecutor.execute {
                                    if (isStillAcquiringAstro.get()) {
                                        latestOnShutterProgressChanged?.invoke(
                                            ShutterProgressState(
                                                mode = ShutterProgressMode.ASTRO,
                                                phase = ShutterProgressPhase.CAPTURING,
                                                currentFrame = curFrames,
                                                totalFrames = desiredFrames,
                                                progress = progressFraction,
                                                elapsedSeconds = elapsedSec,
                                                remainingSeconds = remainingSec,
                                                statusLabel = "$curFrames/$desiredFrames",
                                                detailLabel = formatAstroCaptureCaption(curFrames, desiredFrames, elapsedSec, remainingSec),
                                                canStopAndSave = curFrames >= 1
                                            )
                                        )
                                    }
                                }
                            }

                            try {
                                val firstBitmap = try {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                                } catch (oom: OutOfMemoryError) {
                                    Log.w(TAG, "OOM decoding first Astro frame; falling back safely", oom)
                                    null
                                }

                                if (firstBitmap != null) {
                                    accumulator = AstroStreamingAccumulator(firstBitmap.width, firstBitmap.height)
                                    accumulator.addFrame(firstBitmap, recycleBitmap = true)
                                    capturedFramesAtomic.set(accumulator.framesMerged)
                                    emitAstroCaptureState(accumulator.framesMerged)

                                    if (desiredFrames > 1) {
                                        // 1-second live elapsed / remaining time ticker while frames stream in
                                        astroTicker = Executors.newSingleThreadScheduledExecutor()
                                        astroTicker.scheduleAtFixedRate({
                                            if (isStillAcquiringAstro.get()) {
                                                emitAstroCaptureState(capturedFramesAtomic.get())
                                            }
                                        }, 1000L, 1000L, TimeUnit.MILLISECONDS)

                                        for (frameIndex in 1 until desiredFrames) {
                                            if (astroDiscardFlag.get()) {
                                                Log.d(TAG, "Astrophotography capture discarded by user at frame $frameIndex/$desiredFrames")
                                                break
                                            }
                                            if (astroStopAndSaveFlag.get() || activeLens != captureLens || activeMode != captureMode) {
                                                Log.d(TAG, "Astrophotography stop-and-save requested at ${accumulator.framesMerged}/$desiredFrames frames")
                                                break
                                            }
                                            if (accumulator.encounteredLowMemory) {
                                                Log.w(TAG, "Astrophotography stopping early at ${accumulator.framesMerged} frames due to low memory")
                                                break
                                            }

                                            val latch = java.util.concurrent.CountDownLatch(1)
                                            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
                                            var companionBytes: ByteArray? = null
                                            var companionFailed = false
                                            try {
                                                imageCapture.takePicture(
                                                    companionCaptureExecutor,
                                                    object : ImageCapture.OnImageCapturedCallback() {
                                                        override fun onCaptureSuccess(companionProxy: ImageProxy) {
                                                            try {
                                                                if (!timedOut.get() && !astroDiscardFlag.get()) {
                                                                    val buf = companionProxy.planes[0].buffer
                                                                    val arr = ByteArray(buf.remaining())
                                                                    buf.get(arr)
                                                                    companionBytes = arr
                                                                }
                                                            } catch (e: Throwable) {
                                                                companionFailed = true
                                                            } finally {
                                                                try { companionProxy.close() } catch (_: Exception) {}
                                                                latch.countDown()
                                                            }
                                                        }
                                                        override fun onError(exception: ImageCaptureException) {
                                                            companionFailed = true
                                                            latch.countDown()
                                                        }
                                                    }
                                                )
                                                val timeoutMs = maxOf(2500L, perFrameExposureMs + 1500L)
                                                val completedInTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                                                if (!completedInTime) {
                                                    timedOut.set(true)
                                                    break
                                                }
                                                if (astroDiscardFlag.get()) {
                                                    break
                                                }
                                                val capturedArr = companionBytes
                                                if (companionFailed || capturedArr == null || capturedArr.isEmpty()) {
                                                    break
                                                }

                                                // Decode, star-align, merge into running accumulator, and recycle immediately!
                                                val nextBmp = try {
                                                    BitmapFactory.decodeByteArray(capturedArr, 0, capturedArr.size, decodeOpts)
                                                } catch (oom: OutOfMemoryError) {
                                                    Log.w(TAG, "OOM decoding Astro frame ${frameIndex + 1}; stacking ${accumulator.framesMerged} frames so far", oom)
                                                    null
                                                }
                                                if (nextBmp != null) {
                                                    val mergedOk = accumulator.addFrame(nextBmp, recycleBitmap = true)
                                                    if (!mergedOk && accumulator.encounteredLowMemory) {
                                                        break
                                                    }
                                                } else {
                                                    break
                                                }

                                                capturedFramesAtomic.set(accumulator.framesMerged)
                                                emitAstroCaptureState(accumulator.framesMerged)

                                                if (astroStopAndSaveFlag.get()) {
                                                    break
                                                }
                                            } catch (e: Throwable) {
                                                Log.w(TAG, "Astro streaming frame $frameIndex skipped: ${e.localizedMessage}")
                                                break
                                            }
                                        }
                                    }
                                }

                                isStillAcquiringAstro.set(false)
                                runCatching { astroTicker?.shutdownNow() }
                                astroTicker = null

                                if (astroDiscardFlag.get()) {
                                    accumulator?.close()
                                    isCaptureInFlight.set(false)
                                    mainExecutor.execute {
                                        isCapturing = false
                                        latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                    }
                                    return@saveTask
                                }

                                val mergedFramesCount = accumulator?.framesMerged ?: 0
                                val astroTotalAcquisitionMs = maxOf(1L, System.currentTimeMillis() - astroStartAcquisitionMs)
                                val elapsedSecFinal = (astroTotalAcquisitionMs / 1000L).coerceAtLeast(0L)

                                // Switch shared shutter ring to distinct PROCESSING (aligning & stacking) state
                                mainExecutor.execute {
                                    latestOnShutterProgressChanged?.invoke(
                                        ShutterProgressState(
                                            mode = ShutterProgressMode.ASTRO,
                                            phase = ShutterProgressPhase.PROCESSING,
                                            currentFrame = mergedFramesCount,
                                            totalFrames = desiredFrames,
                                            progress = 0.05f,
                                            elapsedSeconds = elapsedSecFinal,
                                            statusLabel = "5%",
                                            detailLabel = "Aligning & stacking… 5%",
                                            canStopAndSave = false
                                        )
                                    )
                                }

                                if (accumulator != null && mergedFramesCount > 0) {
                                    val outcome = com.example.core.processing.AstroProcessor.finalizeStreamingAccumulator(
                                        accumulator = accumulator,
                                        rotationDegrees = rotationDegrees,
                                        aspectRatioMode = captureAspectRatio,
                                        mirrorHorizontally = captureSelfieMirror,
                                        duration = captureAstroDuration,
                                        perFrameExposureMs = if (perFrameExposureMs > 0) perFrameExposureMs else (astroTotalAcquisitionMs / maxOf(1, mergedFramesCount)),
                                        totalAcquisitionMs = astroTotalAcquisitionMs,
                                        jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                        onProcessingProgress = { p ->
                                            val pct = (p * 100f).toInt().coerceIn(1, 99)
                                            mainExecutor.execute {
                                                latestOnShutterProgressChanged?.invoke(
                                                    ShutterProgressState(
                                                        mode = ShutterProgressMode.ASTRO,
                                                        phase = ShutterProgressPhase.PROCESSING,
                                                        currentFrame = mergedFramesCount,
                                                        totalFrames = desiredFrames,
                                                        progress = p,
                                                        elapsedSeconds = elapsedSecFinal,
                                                        statusLabel = "$pct%",
                                                        detailLabel = "Aligning & stacking… $pct%",
                                                        canStopAndSave = false
                                                    )
                                                )
                                            }
                                        }
                                    )
                                    capturePathLabel = "Astro(${outcome.framesStacked}f)"
                                    if (astroStopAndSaveFlag.get() && outcome.framesStacked < desiredFrames) {
                                        portraitFallbackReason = "Stopped early — saved Astrophotography stack (${outcome.framesStacked} frames)."
                                    } else if (outcome.wasPartialLowMemoryMerge) {
                                        portraitFallbackReason = "Low memory — saved Astrophotography stack (${outcome.framesStacked} frames)."
                                    }
                                    com.example.core.processing.ProcessedPhotoPayload(
                                        jpegBytes = outcome.jpegBytes,
                                        width = outcome.width,
                                        height = outcome.height,
                                        exifOrientation = outcome.exifOrientation,
                                        wasRecompressed = true
                                    )
                                } else {
                                    capturePathLabel = "Astro-1f"
                                    portraitFallbackReason = "Saved captured Astrophotography frame."
                                    AspectCropUtils.processCapturedJpegBytes(
                                        sourceBytes = bytes,
                                        rotationDegrees = rotationDegrees,
                                        aspectRatioMode = captureAspectRatio,
                                        mirrorHorizontally = captureSelfieMirror,
                                        jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                        allowExifOrientationBypass = true
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "Astrophotography stacking failed — saving captured frame fallback", t)
                                isStillAcquiringAstro.set(false)
                                runCatching { astroTicker?.shutdownNow() }
                                runCatching { accumulator?.close() }
                                capturePathLabel = "Astro-Fallback"
                                portraitFallbackReason = "Astrophotography stacking failed — saved captured photo so nothing was lost."
                                AspectCropUtils.processCapturedJpegBytes(
                                    sourceBytes = bytes,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                    allowExifOrientationBypass = true
                                )
                            } finally {
                                isStillAcquiringAstro.set(false)
                                runCatching { astroTicker?.shutdownNow() }
                                runCatching { accumulator?.close() }
                            }
                        } else if (captureMode == CameraMode.PHOTO) {
                            val hasOemExtension = captureActiveExtMode != androidx.camera.extensions.ExtensionMode.NONE
                            val shouldRunSoftwareEvHdr = !hasOemExtension && (
                                captureHdrModeAtShot == com.example.core.preferences.HdrMode.ON ||
                                    (captureHdrModeAtShot == com.example.core.preferences.HdrMode.AUTO &&
                                        captureSpeedMode == com.example.core.preferences.PhotoCaptureSpeedMode.QUALITY)
                                )
                            val shouldRunQualityMultiFrame = !hasOemExtension &&
                                !shouldRunSoftwareEvHdr &&
                                captureSpeedMode == com.example.core.preferences.PhotoCaptureSpeedMode.QUALITY

                            if (shouldRunSoftwareEvHdr) {
                                // Real EV-bracketed 3-frame HDR (-1.5 EV, 0 EV, +1.5 EV)
                                val companionFrames = mutableListOf<ByteArray>()
                                val camControl = activeCamera?.cameraControl
                                val evSupported = captureLensCaps?.isExposureCompensationSupported == true && camControl != null
                                val evRange = captureLensCaps?.evIndexRange
                                val evStep = captureLensCaps?.evStep ?: (1f / 3f)
                                val evDeltaIdx = if (evSupported && evRange != null) {
                                    com.example.core.camera.evToExposureIndex(1.5f, evRange, evStep)
                                        .let { kotlin.math.abs(it).coerceAtLeast(1) }
                                } else 0
                                val bracketOffsets = listOf(-evDeltaIdx, evDeltaIdx)

                                try {
                                    for ((idx, evOffset) in bracketOffsets.withIndex()) {
                                        if (activeLens != captureLens || activeMode != captureMode) break
                                        if (evSupported && evRange != null && evOffset != 0) {
                                            val targetEv = com.example.core.camera.clampExposureIndex(
                                                captureBaseEvIndex + evOffset,
                                                evRange
                                            )
                                            runCatching {
                                                camControl?.setExposureCompensationIndex(targetEv)?.get(250L, TimeUnit.MILLISECONDS)
                                            }
                                            runCatching { Thread.sleep(85L) }
                                        }
                                        val latch = java.util.concurrent.CountDownLatch(1)
                                        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
                                        var companionBytes: ByteArray? = null
                                        var companionFailed = false
                                        try {
                                            imageCapture.takePicture(
                                                companionCaptureExecutor,
                                                object : ImageCapture.OnImageCapturedCallback() {
                                                    override fun onCaptureSuccess(companionProxy: ImageProxy) {
                                                        try {
                                                            if (!timedOut.get()) {
                                                                val buf = companionProxy.planes[0].buffer
                                                                val arr = ByteArray(buf.remaining())
                                                                buf.get(arr)
                                                                companionBytes = arr
                                                            }
                                                        } catch (_: Exception) {
                                                            companionFailed = true
                                                        } finally {
                                                            try { companionProxy.close() } catch (_: Exception) {}
                                                            latch.countDown()
                                                        }
                                                    }

                                                    override fun onError(exception: ImageCaptureException) {
                                                        companionFailed = true
                                                        latch.countDown()
                                                    }
                                                }
                                            )
                                            val completedInTime = latch.await(600L, TimeUnit.MILLISECONDS)
                                            if (!completedInTime) {
                                                timedOut.set(true)
                                                break
                                            }
                                            val capturedArr = companionBytes
                                            if (companionFailed || capturedArr == null || capturedArr.isEmpty()) break
                                            companionFrames.add(capturedArr)
                                        } catch (e: Throwable) {
                                            Log.w(TAG, "HDR EV bracket frame ${idx + 1} skipped: ${e.localizedMessage}")
                                            break
                                        }
                                    }
                                } finally {
                                    if (evSupported) {
                                        runCatching { camControl?.setExposureCompensationIndex(captureBaseEvIndex) }
                                    }
                                    isCaptureInFlight.set(false)
                                    mainExecutor.execute { isCapturing = false }
                                }

                                val supportPath = captureLensCaps?.hdrSupportPath
                                    ?: com.example.core.camera.HdrSupportPath.SOFTWARE_MULTI_FRAME_HDR

                                when (
                                    val outcome = com.example.core.processing.HdrProcessor.processHdrJpegBytes(
                                        sourceBytes = bytes,
                                        additionalFramesBytes = companionFrames,
                                        rotationDegrees = rotationDegrees,
                                        aspectRatioMode = captureAspectRatio,
                                        mirrorHorizontally = captureSelfieMirror,
                                        hdrMode = captureHdrModeAtShot,
                                        supportPath = supportPath,
                                        jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY
                                    )
                                ) {
                                    is com.example.core.processing.HdrProcessingOutcome.ProcessedHdr -> {
                                        capturePathLabel = "HDR-EV(${outcome.framesMerged}f)"
                                        com.example.core.processing.ProcessedPhotoPayload(
                                            jpegBytes = outcome.jpegBytes,
                                            width = outcome.width,
                                            height = outcome.height,
                                            exifOrientation = outcome.exifOrientation,
                                            wasRecompressed = true
                                        )
                                    }
                                    is com.example.core.processing.HdrProcessingOutcome.FallbackSingleFrame -> {
                                        capturePathLabel = "HDR-1f-Fallback"
                                        if (outcome.width > 0 && outcome.height > 0) {
                                            com.example.core.processing.ProcessedPhotoPayload(
                                                jpegBytes = outcome.jpegBytes,
                                                width = outcome.width,
                                                height = outcome.height,
                                                exifOrientation = outcome.exifOrientation,
                                                wasRecompressed = true
                                            )
                                        } else {
                                            AspectCropUtils.processCapturedJpegBytes(
                                                sourceBytes = bytes,
                                                rotationDegrees = rotationDegrees,
                                                aspectRatioMode = captureAspectRatio,
                                                mirrorHorizontally = captureSelfieMirror,
                                                jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                                allowExifOrientationBypass = true
                                            )
                                        }
                                    }
                                }
                            } else if (shouldRunQualityMultiFrame) {
                                // Quality Mode: Streaming 3-5 frame noise-reduction merge (frame-by-frame into running accumulator)
                                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                                val plannedCount = com.example.core.camera.StreamingQualityFrameMerger.resolveSafeQualityFrameCount(
                                    context = context,
                                    width = boundsOpts.outWidth,
                                    height = boundsOpts.outHeight
                                )
                                val merger = if (plannedCount >= 3) {
                                    com.example.core.camera.StreamingQualityFrameMerger.createFromFirstJpeg(bytes)
                                } else null

                                try {
                                    if (merger != null) {
                                        for (frameIdx in 2..plannedCount) {
                                            if (activeLens != captureLens || activeMode != captureMode) break
                                            val latch = java.util.concurrent.CountDownLatch(1)
                                            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
                                            var nextBytes: ByteArray? = null
                                            var failed = false
                                            try {
                                                imageCapture.takePicture(
                                                    companionCaptureExecutor,
                                                    object : ImageCapture.OnImageCapturedCallback() {
                                                        override fun onCaptureSuccess(companionProxy: ImageProxy) {
                                                            try {
                                                                if (!timedOut.get()) {
                                                                    val buf = companionProxy.planes[0].buffer
                                                                    val arr = ByteArray(buf.remaining())
                                                                    buf.get(arr)
                                                                    nextBytes = arr
                                                                }
                                                            } catch (_: Exception) {
                                                                failed = true
                                                            } finally {
                                                                try { companionProxy.close() } catch (_: Exception) {}
                                                                latch.countDown()
                                                            }
                                                        }

                                                        override fun onError(exception: ImageCaptureException) {
                                                            failed = true
                                                            latch.countDown()
                                                        }
                                                    }
                                                )
                                                if (!latch.await(600L, TimeUnit.MILLISECONDS)) {
                                                    timedOut.set(true)
                                                    break
                                                }
                                                val capturedArr = nextBytes
                                                if (failed || capturedArr == null || capturedArr.isEmpty()) break
                                                if (!com.example.core.camera.StreamingQualityFrameMerger.mergeNextJpeg(merger, capturedArr)) {
                                                    break
                                                }
                                            } catch (e: Throwable) {
                                                Log.w(TAG, "Quality burst frame $frameIdx skipped: ${e.localizedMessage}")
                                                break
                                            }
                                        }
                                    }
                                } finally {
                                    isCaptureInFlight.set(false)
                                    mainExecutor.execute { isCapturing = false }
                                }

                                val mergedPayload = if (merger != null && merger.framesMerged >= 2) {
                                    capturePathLabel = "QualityMerge(${merger.framesMerged}f)"
                                    com.example.core.camera.StreamingQualityFrameMerger.finalizeToPayload(
                                        merger = merger,
                                        rotationDegrees = rotationDegrees,
                                        aspectRatioMode = captureAspectRatio,
                                        mirrorHorizontally = captureSelfieMirror,
                                        jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY
                                    )
                                } else {
                                    null
                                }
                                mergedPayload ?: AspectCropUtils.processCapturedJpegBytes(
                                    sourceBytes = bytes,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                    allowExifOrientationBypass = true
                                ).also {
                                    capturePathLabel = if (it?.wasRecompressed == false) "Quality-DirectJPEG" else "Quality-1f"
                                }
                            } else {
                                // Fast Photo / OEM CameraX Extension path: zero Bitmap decode, write camera JPEG directly!
                                AspectCropUtils.processCapturedJpegBytes(
                                    sourceBytes = bytes,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                    allowExifOrientationBypass = true
                                ).also {
                                    capturePathLabel = when {
                                        hasOemExtension && it?.wasRecompressed == false -> "Ext($captureActiveExtLabel)-DirectJPEG"
                                        hasOemExtension -> "Ext($captureActiveExtLabel)"
                                        it?.wasRecompressed == false -> "DirectJPEG"
                                        else -> "AspectCrop-JPEG100"
                                    }
                                }
                            }
                        } else {
                            AspectCropUtils.processCapturedJpegBytes(
                                sourceBytes = bytes,
                                rotationDegrees = rotationDegrees,
                                aspectRatioMode = captureAspectRatio,
                                mirrorHorizontally = captureSelfieMirror,
                                jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY,
                                allowExifOrientationBypass = true
                            ).also {
                                capturePathLabel = if (it?.wasRecompressed == false) "${captureMode.displayName}-DirectJPEG" else captureMode.displayName
                            }
                        }

                        if (processedPayload == null) {
                            isCaptureInFlight.set(false)
                            mainExecutor.execute {
                                isCapturing = false
                                onCaptureError?.invoke("Failed to decode captured image.")
                            }
                            return@saveTask
                        }

                        val finalPhotoPayload = if (!isDngOnly && !captureSelectedFilter.isOriginal) {
                            com.example.core.processing.CameraFilterProcessor.applyFilterToPhotoPayload(
                                payload = processedPayload,
                                preset = captureSelectedFilter,
                                jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY
                            )
                        } else {
                            processedPayload
                        }

                        val saveStartMs = System.currentTimeMillis()
                        if (captureMode == CameraMode.PORTRAIT) {
                            val queuedNow = portraitJobsInFlight.get().coerceAtLeast(1)
                            val canTakeNext = !photoSaveQueue.isFull
                            mainExecutor.execute {
                                latestOnShutterProgressChanged?.invoke(
                                    ShutterProgressState(
                                        mode = ShutterProgressMode.PORTRAIT,
                                        phase = ShutterProgressPhase.PROCESSING,
                                        progress = 0.94f,
                                        statusLabel = "Saving…",
                                        detailLabel = if (queuedNow > 1) "Saving… ($queuedNow queued)" else "Portrait: Saving…",
                                        allowConcurrentCapture = canTakeNext
                                    )
                                )
                            }
                        }
                        val contentResolver = context.contentResolver
                        val savedUri: Uri? = if (isDngOnly) {
                            var pendingUri: Uri? = null
                            try {
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.dng")
                                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                                    put(MediaStore.MediaColumns.WIDTH, processedPayload.width)
                                    put(MediaStore.MediaColumns.HEIGHT, processedPayload.height)
                                    if (location != null) {
                                        put(MediaStore.Images.Media.LATITUDE, location.latitude)
                                        put(MediaStore.Images.Media.LONGITUDE, location.longitude)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                                        put(MediaStore.Images.Media.IS_PENDING, 1)
                                    }
                                }
                                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                pendingUri = uri
                                if (uri != null) {
                                    contentResolver.openOutputStream(uri)?.use { out ->
                                        out.write(bytes)
                                    } ?: throw java.io.IOException("Failed to open output stream for DNG")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        contentResolver.update(
                                            uri,
                                            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                                            null,
                                            null
                                        )
                                    }
                                }
                                uri
                            } catch (e: Exception) {
                                pendingUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                                throw e
                            }
                        } else {
                            com.example.core.storage.MediaStorePhotoSaver.savePhotoAtomically(
                                context = context,
                                outputBytes = finalPhotoPayload.jpegBytes,
                                sourceExifBytes = bytes,
                                displayName = displayName,
                                mimeType = "image/jpeg",
                                width = finalPhotoPayload.width,
                                height = finalPhotoPayload.height,
                                exifOrientation = finalPhotoPayload.exifOrientation,
                                location = location,
                                isDirectCameraJpeg = !finalPhotoPayload.wasRecompressed && captureSelectedFilter.isOriginal
                            )?.uri
                        }

                        if (savedUri != null) {
                            val savedAtMs = System.currentTimeMillis()
                            val saveMs = (savedAtMs - saveStartMs).coerceAtLeast(0L)
                            if (captureMode == CameraMode.PORTRAIT) {
                                portraitCompletedSuccessfully = true
                                val baseTimings = portraitStageTimings ?: com.example.core.camera.PortraitStageTimings(
                                    captureMs = (frameCapturedAtMs - shutterPressedAtMs).coerceAtLeast(0L),
                                    width = finalPhotoPayload.width,
                                    height = finalPhotoPayload.height
                                )
                                com.example.core.camera.PortraitStageTimingTracker.record(
                                    baseTimings.copy(
                                        saveMs = saveMs,
                                        totalMs = (savedAtMs - shutterPressedAtMs).coerceAtLeast(1L),
                                        width = finalPhotoPayload.width,
                                        height = finalPhotoPayload.height
                                    )
                                )
                            }
                            com.example.core.camera.CaptureLatencyTracker.record(
                                modeName = captureMode.displayName,
                                speedModeLabel = captureSpeedMode.displayName,
                                extensionLabel = captureActiveExtLabel,
                                capturePathLabel = capturePathLabel,
                                shutterPressedAtMs = shutterPressedAtMs,
                                frameCapturedAtMs = frameCapturedAtMs,
                                savedAtMs = savedAtMs,
                                width = finalPhotoPayload.width,
                                height = finalPhotoPayload.height
                            )
                            // If RAW+JPEG was requested in Pro mode, also write companion DNG asset atomically
                            if (isDngRequested && !isDngOnly) {
                                var pendingDngUri: Uri? = null
                                try {
                                    val dngValues = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, "RAW_$timeStamp.dng")
                                        put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                                        put(MediaStore.MediaColumns.WIDTH, processedPayload.width)
                                        put(MediaStore.MediaColumns.HEIGHT, processedPayload.height)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                                            put(MediaStore.Images.Media.IS_PENDING, 1)
                                        }
                                    }
                                    val dngUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, dngValues)
                                    pendingDngUri = dngUri
                                    if (dngUri != null) {
                                        contentResolver.openOutputStream(dngUri)?.use { out ->
                                            out.write(bytes)
                                        } ?: throw java.io.IOException("Failed to open companion DNG output stream")
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            contentResolver.update(
                                                dngUri,
                                                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                                                null,
                                                null
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    pendingDngUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                                    Log.w(TAG, "Companion DNG creation skipped or unsupported", e)
                                }
                            }

                            if (captureMode == CameraMode.HD) {
                                logSavedPhotoDimensions(context, savedUri, "HD")
                            }

                            if (!isSingleFrameFastHandoff) {
                                isCaptureInFlight.set(false)
                            }
                            mainExecutor.execute {
                                if (!isSingleFrameFastHandoff) {
                                    isCapturing = false
                                }
                                onCaptureSuccess?.invoke(savedUri)
                                if (captureMode == CameraMode.NIGHT_SIGHT) {
                                    emitNightSightProgress(
                                        NightSightProgressState(
                                            phase = NightSightCapturePhase.COMPLETED,
                                            progress = 1f,
                                            statusLabel = "Saved"
                                        )
                                    )
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        emitNightSightProgress(NightSightProgressState())
                                    }, 750L)
                                } else if (captureMode == CameraMode.ASTRO) {
                                    latestOnShutterProgressChanged?.invoke(
                                        ShutterProgressState(
                                            mode = ShutterProgressMode.ASTRO,
                                            phase = ShutterProgressPhase.COMPLETED,
                                            progress = 1f,
                                            statusLabel = "Saved",
                                            detailLabel = "Saved"
                                        )
                                    )
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                    }, 850L)
                                }
                                portraitFallbackReason?.let { reason ->
                                    val userMsg = if (reason.startsWith("No subject found", ignoreCase = true)) {
                                        "No subject found"
                                    } else {
                                        reason
                                    }
                                    onCaptureError?.invoke(userMsg)
                                }
                            }
                        } else {
                            if (!isSingleFrameFastHandoff) {
                                isCaptureInFlight.set(false)
                            }
                            mainExecutor.execute {
                                if (!isSingleFrameFastHandoff) {
                                    isCapturing = false
                                }
                                if (captureMode == CameraMode.NIGHT_SIGHT) {
                                    emitNightSightProgress(NightSightProgressState())
                                } else if (captureMode == CameraMode.ASTRO) {
                                    latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                }
                                onCaptureError?.invoke("Failed to create MediaStore entry.")
                            }
                        }
                    } catch (e: Exception) {
                        if (!isSingleFrameFastHandoff) {
                            isCaptureInFlight.set(false)
                        }
                        Log.e(TAG, "Error processing captured photo", e)
                        mainExecutor.execute {
                            if (!isSingleFrameFastHandoff) {
                                isCapturing = false
                            }
                            if (captureMode == CameraMode.NIGHT_SIGHT) {
                                emitNightSightProgress(NightSightProgressState())
                            } else if (captureMode == CameraMode.ASTRO) {
                                latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                            }
                            onCaptureError?.invoke("Photo processing failed: ${e.localizedMessage}")
                        }
                    } finally {
                        if (captureMode == CameraMode.PORTRAIT) {
                            val remaining = portraitJobsInFlight.decrementAndGet().coerceAtLeast(0)
                            if (remaining == 0) {
                                mainExecutor.execute {
                                    if (portraitCompletedSuccessfully) {
                                        latestOnShutterProgressChanged?.invoke(
                                            ShutterProgressState(
                                                mode = ShutterProgressMode.PORTRAIT,
                                                phase = ShutterProgressPhase.COMPLETED,
                                                progress = 1f,
                                                statusLabel = "Saved",
                                                detailLabel = "Saved",
                                                allowConcurrentCapture = true
                                            )
                                        )
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            if (portraitJobsInFlight.get() == 0) {
                                                latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                            }
                                        }, 750L)
                                    } else {
                                        latestOnInstantPreviewThumbnail?.invoke(null)
                                        latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                    }
                                }
                            } else {
                                val canTakeNext = !photoSaveQueue.isFull
                                mainExecutor.execute {
                                    latestOnShutterProgressChanged?.invoke(
                                        ShutterProgressState(
                                            mode = ShutterProgressMode.PORTRAIT,
                                            phase = ShutterProgressPhase.PROCESSING,
                                            progress = 0.25f,
                                            statusLabel = "Processing…",
                                            detailLabel = if (remaining == 1) "Portrait: Processing…" else "Portrait: Processing ($remaining queued)",
                                            allowConcurrentCapture = canTakeNext
                                        )
                                    )
                                }
                            }
                        }
                    }
                    }

                    // For single-frame captures, hand off saving/processing to the bounded background queue
                    // so photoCaptureExecutor stays free and consecutive shots fire without waiting for I/O!
                    if (isSingleFrameFastHandoff) {
                        if (captureMode == CameraMode.PORTRAIT) {
                            val queuedCount = portraitJobsInFlight.incrementAndGet()
                            val queued = photoSaveQueue.tryEnqueue(runProcessingAndSave)
                            if (!queued) {
                                val rem = portraitJobsInFlight.decrementAndGet().coerceAtLeast(0)
                                mainExecutor.execute {
                                    if (rem == 0) {
                                        latestOnInstantPreviewThumbnail?.invoke(null)
                                        latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                    }
                                    onCaptureError?.invoke("Save queue full — wait a moment before next shot.")
                                }
                            } else {
                                val canTakeNext = !photoSaveQueue.isFull
                                mainExecutor.execute {
                                    latestOnShutterProgressChanged?.invoke(
                                        ShutterProgressState(
                                            mode = ShutterProgressMode.PORTRAIT,
                                            phase = ShutterProgressPhase.PROCESSING,
                                            progress = 0.08f,
                                            statusLabel = "Processing…",
                                            detailLabel = if (queuedCount > 1) "Portrait: Processing ($queuedCount queued)" else "Portrait: Processing…",
                                            allowConcurrentCapture = canTakeNext
                                        )
                                    )
                                }
                            }
                        } else {
                            val queued = photoSaveQueue.tryEnqueue(runProcessingAndSave)
                            if (!queued) {
                                mainExecutor.execute {
                                    onCaptureError?.invoke("Save queue full — wait a moment before next shot.")
                                }
                            }
                        }
                    } else {
                        runProcessingAndSave()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCaptureInFlight.set(false)
                    Log.e(TAG, "Photo capture failed", exception)
                    mainExecutor.execute {
                        isCapturing = false
                        if (captureMode == CameraMode.NIGHT_SIGHT) {
                            emitNightSightProgress(NightSightProgressState())
                        } else if (captureMode == CameraMode.ASTRO) {
                            latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                        }
                        onCaptureError?.invoke("Photo capture failed: ${exception.localizedMessage}")
                    }
                }
            }
        )
    }

    // Synchronize PanoramaLiveAnalyzer direction and camera facing parameters
    LaunchedEffect(panoramaDirection, activeLens, isSelfieMirrorEnabled) {
        panoramaAnalyzer.direction = panoramaDirection
        panoramaAnalyzer.isFrontCamera = activeLens.facing == LensFacing.FRONT
        panoramaAnalyzer.isSelfieMirrorEnabled = isSelfieMirrorEnabled
        panoramaAnalyzer.motionTracker.isFrontCamera = activeLens.facing == LensFacing.FRONT
        panoramaAnalyzer.motionTracker.isSelfieMirrorEnabled = isSelfieMirrorEnabled
    }

    // Real-time Rotation-Vector + Gyroscope sensor tracking during active Panorama capture
    DisposableEffect(activeMode, isPanoramaCapturingRequested) {
        if (activeMode != CameraMode.PANORAMA || !isPanoramaCapturingRequested) {
            return@DisposableEffect onDispose {}
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            ?: return@DisposableEffect onDispose {}

        val rotationSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)
        val gyroSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)

        if (rotationSensor == null && gyroSensor == null) {
            return@DisposableEffect onDispose {}
        }

        var lastGyroTimestampNs = 0L
        var lastUiDispatchTimestampNs = 0L
        var lastDispatchedFrameCount = -1
        var lastDispatchedGuidance = PanoramaGuidanceStatus.READY
        var lastSensorKeyframeProgress = 0f

        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                if (event == null || !panoramaAnalyzer.isCapturing || isPanoramaProcessingInFlight.get()) return
                val currentDisplayRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                panoramaAnalyzer.displayRotation = currentDisplayRotation
                panoramaAnalyzer.motionTracker.displayRotation = currentDisplayRotation

                when (event.sensor.type) {
                    android.hardware.Sensor.TYPE_ROTATION_VECTOR,
                    android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        panoramaAnalyzer.onRotationVectorSample(event.values, event.timestamp)
                    }
                    android.hardware.Sensor.TYPE_GYROSCOPE -> {
                        if (lastGyroTimestampNs != 0L) {
                            val dtSec = (event.timestamp - lastGyroTimestampNs) * 1e-9f
                            if (dtSec in 0.001f..0.25f) {
                                panoramaAnalyzer.onGyroscopeSample(
                                    omegaX = event.values[0],
                                    omegaY = event.values[1],
                                    omegaZ = event.values[2],
                                    dtSeconds = dtSec
                                )
                            }
                        }
                        lastGyroTimestampNs = event.timestamp
                    }
                }

                // Coordinate sensor-measured progress with keyframe acquisition:
                // Request a keyframe from the ImageAnalysis stream (so all frames share identical 4:3 sensor FOV and resolution).
                val currentFrames = panoramaKeyframes.size
                if (panoramaAnalyzer.motionTracker.shouldAcquireNextKeyframe(currentFrames, lastSensorKeyframeProgress)) {
                    lastSensorKeyframeProgress = panoramaAnalyzer.motionTracker.currentProgress()
                    panoramaAnalyzer.requestManualStepCapture()
                }

                val updatedFrames = maxOf(1, panoramaKeyframes.size)
                val liveSnapshot = panoramaAnalyzer.motionTracker.snapshotLiveState(
                    phase = PanoramaCapturePhase.CAPTURING,
                    capturedFrameCount = updatedFrames
                )

                // Throttle UI updates to ~30 Hz (32ms) unless frame count or guidance status changed
                val shouldDispatchUi = (event.timestamp - lastUiDispatchTimestampNs >= 32_000_000L) ||
                    updatedFrames != lastDispatchedFrameCount ||
                    liveSnapshot.guidanceStatus != lastDispatchedGuidance

                if (shouldDispatchUi) {
                    lastUiDispatchTimestampNs = event.timestamp
                    lastDispatchedFrameCount = updatedFrames
                    lastDispatchedGuidance = liveSnapshot.guidanceStatus
                    latestOnPanoramaStateChanged?.invoke(liveSnapshot)
                }

                if (panoramaAnalyzer.motionTracker.isSweepReadyToAutoComplete(updatedFrames)) {
                    panoramaAnalyzer.stopSession()
                    autoFinishSweepCounter++
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(
                listener,
                rotationSensor,
                android.hardware.SensorManager.SENSOR_DELAY_GAME
            )
        }
        if (gyroSensor != null) {
            sensorManager.registerListener(
                listener,
                gyroSensor,
                android.hardware.SensorManager.SENSOR_DELAY_GAME
            )
        }

        onDispose {
            runCatching { sensorManager.unregisterListener(listener) }
        }
    }

    // Control Panorama session start / stop and Camera2 AE+AWB lock for uniform exposure across the sweep
    LaunchedEffect(isPanoramaCapturingRequested, activeMode, activeLens) {
        if (activeMode != CameraMode.PANORAMA) {
            panoramaAnalyzer.stopSession()
            clearPanoramaKeyframes()
            isPanoramaProcessingInFlight.set(false)
            latestOnPanoramaStateChanged?.invoke(
                PanoramaLiveState(
                    phase = PanoramaCapturePhase.IDLE,
                    direction = latestPanoramaDirection,
                    progress = 0f,
                    capturedCoverage = 0f,
                    capturedFrameCount = 0,
                    targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                    alignmentDriftNormalized = 0f,
                    normalizedSpeed = 0f,
                    guidanceStatus = PanoramaGuidanceStatus.READY,
                    isCaptureLagging = false
                )
            )
            return@LaunchedEffect
        }

        if (isPanoramaCapturingRequested) {
            if (isPanoramaProcessingInFlight.get()) return@LaunchedEffect
            clearPanoramaKeyframes()
            val currentDisplayRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            panoramaAnalyzer.startSession(
                selectedDirection = panoramaDirection,
                targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                screenRotation = currentDisplayRotation
            )
            // Request anchor frame #1 from ImageAnalysis; if unavailable within 120ms (e.g. unit test / preview-only), fall back to previewView.bitmap
            panoramaAnalyzer.requestManualStepCapture()
            val initialPreviewBmp = runCatching { previewView.bitmap }.getOrNull()
            if (panoramaKeyframes.isEmpty() && initialPreviewBmp != null && initialPreviewBmp.width > 16 && initialPreviewBmp.height > 16) {
                panoramaKeyframes.add(initialPreviewBmp)
            }
            latestOnPanoramaStateChanged?.invoke(
                PanoramaLiveState(
                    phase = PanoramaCapturePhase.CAPTURING,
                    direction = panoramaDirection,
                    progress = 0f,
                    capturedCoverage = 0f,
                    capturedFrameCount = maxOf(1, panoramaKeyframes.size),
                    targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                    alignmentDriftNormalized = 0f,
                    normalizedSpeed = 0f,
                    guidanceStatus = PanoramaGuidanceStatus.READY,
                    isCaptureLagging = false
                )
            )
            // Lock AE and AWB after anchor exposure settles so brightness/color stay uniform across sweep
            val cam = activeCamera
            if (cam != null) {
                delay(220L)
                if (isPanoramaCapturingRequested && activeMode == CameraMode.PANORAMA) {
                    runCatching {
                        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
                        val lockOptions = CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            .build()
                        camera2Control.addCaptureRequestOptions(lockOptions)
                    }
                }
            }
        } else if (!isPanoramaProcessingInFlight.get()) {
            panoramaAnalyzer.stopSession()
            clearPanoramaKeyframes()
            val cam = activeCamera
            if (cam != null) {
                runCatching {
                    val camera2Control = Camera2CameraControl.from(cam.cameraControl)
                    val unlockOptions = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                        .build()
                    camera2Control.addCaptureRequestOptions(unlockOptions)
                }
            }
            latestOnPanoramaStateChanged?.invoke(
                PanoramaLiveState(
                    phase = PanoramaCapturePhase.IDLE,
                    direction = panoramaDirection,
                    progress = 0f,
                    capturedCoverage = 0f,
                    capturedFrameCount = 0,
                    targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                    alignmentDriftNormalized = 0f,
                    normalizedSpeed = 0f,
                    guidanceStatus = PanoramaGuidanceStatus.READY,
                    isCaptureLagging = false
                )
            )
        }
    }

    // Handle explicit Panorama Cancel trigger
    LaunchedEffect(panoramaCancelTrigger) {
        if (panoramaCancelTrigger <= 0) return@LaunchedEffect
        panoramaAnalyzer.stopSession()
        clearPanoramaKeyframes()
        isPanoramaProcessingInFlight.set(false)
        latestOnPanoramaStateChanged?.invoke(
            PanoramaLiveState(
                phase = PanoramaCapturePhase.IDLE,
                direction = latestPanoramaDirection,
                progress = 0f,
                capturedCoverage = 0f,
                capturedFrameCount = 0,
                targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                alignmentDriftNormalized = 0f,
                normalizedSpeed = 0f,
                guidanceStatus = PanoramaGuidanceStatus.READY,
                isCaptureLagging = false
            )
        )
    }

    // Handle manual Panorama step capture trigger (useful on emulators or step-by-step sweeps)
    LaunchedEffect(panoramaStepTrigger) {
        if (panoramaStepTrigger <= 0 || activeMode != CameraMode.PANORAMA || !isPanoramaCapturingRequested) {
            return@LaunchedEffect
        }
        val beforeCount = panoramaKeyframes.size
        panoramaAnalyzer.requestManualStepCapture()
        delay(140L)
        if (panoramaKeyframes.size == beforeCount && panoramaKeyframes.size < PanoramaProcessor.DEFAULT_TARGET_FRAMES) {
            val previewBmp = runCatching { previewView.bitmap }.getOrNull()
            if (previewBmp != null && previewBmp.width > 16 && previewBmp.height > 16) {
                panoramaKeyframes.add(previewBmp)
                val newCount = panoramaKeyframes.size
                val newCoverage = ((newCount - 1).coerceAtLeast(0).toFloat() /
                    (PanoramaProcessor.DEFAULT_TARGET_FRAMES - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                val newProgress = maxOf(panoramaAnalyzer.motionTracker.currentProgress(), newCoverage)
                panoramaAnalyzer.recordExternalKeyframe(newProgress)
                latestOnPanoramaStateChanged?.invoke(
                    PanoramaLiveState(
                        phase = PanoramaCapturePhase.CAPTURING,
                        direction = latestPanoramaDirection,
                        progress = newProgress,
                        capturedCoverage = newCoverage,
                        capturedFrameCount = newCount,
                        targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                        alignmentDriftNormalized = 0f,
                        normalizedSpeed = 0.25f,
                        guidanceStatus = PanoramaGuidanceStatus.MOVING_STEADY,
                        isCaptureLagging = false
                    )
                )
                if (newCount >= PanoramaProcessor.DEFAULT_TARGET_FRAMES && newProgress >= 0.98f) {
                    autoFinishSweepCounter++
                }
            }
        }
    }

    // Handle Panorama Finish & Multi-Frame Stitching + Atomic MediaStore Saving
    LaunchedEffect(panoramaFinishTrigger, autoFinishSweepCounter) {
        if (panoramaFinishTrigger <= 0 && autoFinishSweepCounter <= 0) return@LaunchedEffect
        if (activeMode != CameraMode.PANORAMA) return@LaunchedEffect
        if (!isPanoramaProcessingInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Ignoring duplicate panorama finish trigger while stitching is already in flight")
            return@LaunchedEffect
        }

        panoramaAnalyzer.stopSession()

        val framesToStitch = synchronized(panoramaKeyframes) {
            panoramaKeyframes.mapNotNull { bmp ->
                if (!bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                    runCatching { bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
                } else null
            }
        }
        clearPanoramaKeyframes()

        val captureDirection = latestPanoramaDirection
        val captureSaveLocation = isSaveLocationEnabled
        val captureSoundsEnabled = isCameraSoundsEnabled
        val captureSelectedFilter = latestSelectedFilter
        val mainExecutor = ContextCompat.getMainExecutor(context)

        // Unlock Camera2 AE/AWB now that capture sweep has finished
        val cam = activeCamera
        if (cam != null) {
            runCatching {
                val camera2Control = Camera2CameraControl.from(cam.cameraControl)
                val unlockOptions = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                    .build()
                camera2Control.addCaptureRequestOptions(unlockOptions)
            }
        }

        // Requirement 5 & 6: Do not finalize or save if sufficient valid overlapping frames were not captured.
        if (framesToStitch.size < PanoramaProcessor.MIN_REQUIRED_FRAMES) {
            for (bmp in framesToStitch) {
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
            }
            isPanoramaProcessingInFlight.set(false)
            latestOnPanoramaStateChanged?.invoke(
                PanoramaLiveState(
                    phase = PanoramaCapturePhase.IDLE,
                    direction = captureDirection,
                    progress = 0f,
                    capturedCoverage = 0f,
                    capturedFrameCount = 0,
                    targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                    alignmentDriftNormalized = 0f,
                    normalizedSpeed = 0f,
                    guidanceStatus = PanoramaGuidanceStatus.READY,
                    isCaptureLagging = false
                )
            )
            onCaptureError?.invoke(
                "Panorama not saved: move your phone in the selected direction to capture at least ${PanoramaProcessor.MIN_REQUIRED_FRAMES} overlapping frames."
            )
            return@LaunchedEffect
        }

        latestOnPanoramaStateChanged?.invoke(
            PanoramaLiveState(
                phase = PanoramaCapturePhase.PROCESSING,
                direction = captureDirection,
                progress = 1.0f,
                capturedCoverage = 1.0f,
                capturedFrameCount = framesToStitch.size,
                targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                alignmentDriftNormalized = 0f,
                normalizedSpeed = 0f,
                guidanceStatus = PanoramaGuidanceStatus.STITCHING,
                isCaptureLagging = false
            )
        )

        photoCaptureExecutor.execute {
            try {
                if (captureSoundsEnabled) {
                    runCatching {
                        val sound = android.media.MediaActionSound()
                        sound.play(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
                    }
                }

                val outcome = PanoramaProcessor.stitchPanoramaBitmaps(
                    frames = framesToStitch,
                    direction = captureDirection,
                    maxFrameDimension = PanoramaProcessor.DEFAULT_MAX_FRAME_DIMENSION,
                    jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY
                )

                for (bmp in framesToStitch) {
                    if (!bmp.isRecycled) runCatching { bmp.recycle() }
                }

                when (outcome) {
                    is PanoramaStitchOutcome.Success -> {
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                        val displayName = "PANO_$timeStamp.jpg"
                        val location = if (captureSaveLocation &&
                            (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        ) {
                            runCatching {
                                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                                lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                    ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                            }.getOrNull()
                        } else null

                        val finalPanoramaBytes = if (!captureSelectedFilter.isOriginal) {
                            com.example.core.processing.CameraFilterProcessor.applyFilterToJpegBytes(
                                jpegBytes = outcome.jpegBytes,
                                preset = captureSelectedFilter,
                                jpegQuality = com.example.core.camera.CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY
                            )
                        } else {
                            outcome.jpegBytes
                        }

                        val savedItem = com.example.core.storage.MediaStorePhotoSaver.savePhotoAtomically(
                            context = context,
                            outputBytes = finalPanoramaBytes,
                            sourceExifBytes = outcome.jpegBytes,
                            displayName = displayName,
                            mimeType = "image/jpeg",
                            width = outcome.width,
                            height = outcome.height,
                            exifOrientation = outcome.exifOrientation,
                            location = location
                        )
                        if (!outcome.bitmap.isRecycled) {
                            runCatching { outcome.bitmap.recycle() }
                        }

                        isPanoramaProcessingInFlight.set(false)
                        mainExecutor.execute {
                            latestOnPanoramaStateChanged?.invoke(
                                PanoramaLiveState(
                                    phase = PanoramaCapturePhase.IDLE,
                                    direction = latestPanoramaDirection,
                                    progress = 0f,
                                    capturedFrameCount = 0,
                                    targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                                    alignmentDriftNormalized = 0f,
                                    normalizedSpeed = 0f,
                                    guidanceStatus = PanoramaGuidanceStatus.READY
                                )
                            )
                            if (savedItem != null) {
                                onCaptureSuccess?.invoke(savedItem.uri)
                            } else {
                                onCaptureError?.invoke("Failed to save stitched panorama to MediaStore.")
                            }
                        }
                    }

                    is PanoramaStitchOutcome.Failure -> {
                        isPanoramaProcessingInFlight.set(false)
                        mainExecutor.execute {
                            latestOnPanoramaStateChanged?.invoke(
                                PanoramaLiveState(
                                    phase = PanoramaCapturePhase.IDLE,
                                    direction = latestPanoramaDirection,
                                    progress = 0f,
                                    capturedFrameCount = 0,
                                    targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                                    alignmentDriftNormalized = 0f,
                                    normalizedSpeed = 0f,
                                    guidanceStatus = PanoramaGuidanceStatus.READY
                                )
                            )
                            onCaptureError?.invoke(outcome.reason)
                        }
                    }
                }
            } catch (e: Throwable) {
                for (bmp in framesToStitch) {
                    if (!bmp.isRecycled) runCatching { bmp.recycle() }
                }
                isPanoramaProcessingInFlight.set(false)
                mainExecutor.execute {
                    latestOnPanoramaStateChanged?.invoke(
                        PanoramaLiveState(
                            phase = PanoramaCapturePhase.IDLE,
                            direction = latestPanoramaDirection,
                            progress = 0f,
                            capturedFrameCount = 0,
                            targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                            alignmentDriftNormalized = 0f,
                            normalizedSpeed = 0f,
                            guidanceStatus = PanoramaGuidanceStatus.READY
                        )
                    )
                    onCaptureError?.invoke("Panorama stitching failed: ${e.localizedMessage ?: "unknown error"}")
                }
            }
        }
    }

    // Duration clock ticker while direct Camera2 MediaRecorder video recording is active
    LaunchedEffect(isDirectCamera2RecordingActive) {
        if (!isDirectCamera2RecordingActive) return@LaunchedEffect
        val startMs = System.currentTimeMillis()
        while (isDirectCamera2RecordingActive) {
            onRecordingDurationChanged?.invoke((System.currentTimeMillis() - startMs) / 1000L)
            delay(500L)
        }
    }

    // Control video recording lifecycle and state transitions
    // Standard VIDEO mode uses direct Camera2 + MediaRecorder when a TextureView surface & camera ID are available
    // so the saved MP4 strictly uses the exact selected resolution (720p/1080p/2K/4K), FPS (setVideoFrameRate +
    // setCaptureRate + AE FPS range), scaled bitrate, and per-resolution stabilization.
    LaunchedEffect(isRecordingRequested, boundVideoCapture) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        if (isRecordingRequested) {
            if (activeRecording != null || isDirectCamera2RecordingActive) return@LaunchedEffect

            val recordingMode = latestActiveMode
            val recLensCaps = cachedCapabilities[activeLens]
            val targetCameraId = recLensCaps?.camera2CameraId
            val directSurfaceTexture = camera2PreviewTextureView.surfaceTexture

            // Direct Camera2 + MediaRecorder path for standard Video mode
            if (recordingMode == CameraMode.VIDEO &&
                targetCameraId != null &&
                camera2PreviewTextureView.isAvailable &&
                directSurfaceTexture != null
            ) {
                val reconciled = recLensCaps.reconcileVideoSettings(
                    requestedResolution = latestVideoResolution,
                    requestedFrameRate = latestVideoFrameRate,
                    requestedStabilization = latestVideoStabilization,
                    requestedFlashMode = latestFlashMode
                )
                val aeRanges = cachedAeRanges.getOrPut(activeLens) {
                    queryLensAeFpsRanges(context, activeLens)
                }
                val aeRangePair = selectBestVideoAeFpsRange(
                    availableRanges = aeRanges.map { it.lower to it.upper },
                    targetFps = reconciled.frameRate.fps
                )
                val aeRange = Range(aeRangePair.first, aeRangePair.second)
                val displayRot = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                val tempRecordFile = java.io.File.createTempFile("rec_cam2_", ".mp4", context.cacheDir)
                val recorder = Camera2DirectVideoRecorder(context)

                try {
                    val provider = ProcessCameraProvider.getInstance(context).get()
                    provider.unbindAll()
                    activeCamera = null
                    boundVideoCapture = null
                    lastBoundConfig = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error unbinding CameraX before direct Camera2 video recording", e)
                }

                activeCamera2DirectRecorder = recorder
                isDirectCamera2RecordingActive = true

                companionCaptureExecutor.execute {
                    val started = recorder.startRecording(
                        cameraId = targetCameraId,
                        surfaceTexture = directSurfaceTexture,
                        resolution = reconciled.resolution,
                        frameRate = reconciled.frameRate,
                        aeFpsRange = aeRange,
                        stabilizationMode = reconciled.stabilization,
                        lensCaps = recLensCaps,
                        zoomRatio = latestZoomRatio,
                        torchEnabled = latestFlashMode == FlashMode.TORCH,
                        exposureCompensationIndex = latestAutoExposureIndex,
                        displayRotation = displayRot,
                        includeAudio = true,
                        outputFile = tempRecordFile,
                        onStarted = {
                            mainExecutor.execute {
                                onRecordingStateChanged?.invoke(true)
                            }
                        },
                        onError = { errMsg ->
                            runCatching { tempRecordFile.delete() }
                            mainExecutor.execute {
                                isDirectCamera2RecordingActive = false
                                activeCamera2DirectRecorder = null
                                onRecordingStateChanged?.invoke(false)
                                onRecordingError?.invoke(errMsg)
                                rebindTrigger++
                            }
                        }
                    )
                    if (!started) {
                        runCatching { tempRecordFile.delete() }
                        mainExecutor.execute {
                            isDirectCamera2RecordingActive = false
                            activeCamera2DirectRecorder = null
                            onRecordingStateChanged?.invoke(false)
                            rebindTrigger++
                        }
                    }
                }
                return@LaunchedEffect
            }

            // CameraX VideoCapture path (Slow Motion, Time Lapse, or fallback when TextureView is unavailable)
            val capture = boundVideoCapture
            if (capture == null) {
                onRecordingError?.invoke("Video capture is not supported or initialized on this lens.")
                onRecordingStateChanged?.invoke(false)
                return@LaunchedEffect
            }

            try {
                val recordingSlowMoSpeed = latestSlowMoSpeed
                val recordingTimeLapseSpeed = latestTimeLapseSpeed
                val recordingTimeLapseDurationLimit = latestTimeLapseDurationLimit
                val isTimeLapseSession = recordingMode == CameraMode.TIME_LAPSE
                val timeLapseTargetDurationSec = if (isTimeLapseSession) recordingTimeLapseDurationLimit.targetDurationSeconds else null
                val timeLapseIntervalMs = recordingTimeLapseSpeed.captureIntervalMs.coerceAtLeast(100L)
                val timeLapseTargetFrames = timeLapseTargetDurationSec?.let { durSec ->
                    ((durSec * 1000L) / timeLapseIntervalMs).toInt().coerceAtLeast(1)
                }
                val timeLapseFramesCaptured = java.util.concurrent.atomic.AtomicInteger(0)
                val timeLapsePulseCount = java.util.concurrent.atomic.AtomicInteger(0)
                val timeLapseAutoStopTriggered = java.util.concurrent.atomic.AtomicBoolean(false)

                val recordingRequestedRes = latestVideoResolution
                val recordingRequestedFps = if (recordingMode == CameraMode.SLOW_MOTION) {
                    recordingSlowMoSpeed.expectedFps
                } else {
                    latestVideoFrameRate.fps
                }
                val recordingSelectedFilter = com.example.core.processing.CameraFilterProcessor.resolveEffectiveFilter(recordingMode, latestSelectedFilter)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                val displayName = when (recordingMode) {
                    CameraMode.SLOW_MOTION -> "VID_SLOMO_${recordingSlowMoSpeed.expectedFps}FPS_$timeStamp.mp4"
                    CameraMode.TIME_LAPSE -> "VID_TIMELAPSE_${recordingTimeLapseSpeed.multiplier}X_$timeStamp.mp4"
                    else -> "VID_$timeStamp.mp4"
                }

                val tempRecordFile = java.io.File.createTempFile("rec_capture_", ".mp4", context.cacheDir)
                val fileOutputOptions = androidx.camera.video.FileOutputOptions.Builder(tempRecordFile).build()

                val pendingRecording = capture.output.prepareRecording(context, fileOutputOptions)

                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasAudioPermission &&
                    recordingMode != CameraMode.SLOW_MOTION &&
                    recordingMode != CameraMode.TIME_LAPSE
                ) {
                    try {
                        pendingRecording.withAudioEnabled()
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Audio permission missing when starting recording", e)
                    }
                }

                val recording = pendingRecording.start(mainExecutor) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            onRecordingStateChanged?.invoke(true)
                            if (isTimeLapseSession) {
                                cameraLifecycleOwner.keepAlive = true
                                TimeLapseRecordingService.start(
                                    context = context,
                                    statusText = "Recording ${recordingTimeLapseSpeed.shortLabel} Time Lapse"
                                )
                                latestOnShutterProgressChanged?.invoke(
                                    ShutterProgressState(
                                        mode = ShutterProgressMode.TIME_LAPSE,
                                        phase = ShutterProgressPhase.CAPTURING,
                                        currentFrame = 0,
                                        totalFrames = timeLapseTargetFrames ?: 0,
                                        progress = 0f,
                                        isIndeterminateCapture = (timeLapseTargetDurationSec == null),
                                        framePulseCount = 0,
                                        elapsedSeconds = 0L,
                                        remainingSeconds = timeLapseTargetDurationSec,
                                        statusLabel = "00:00",
                                        detailLabel = formatTimeLapseCaptureCaption(
                                            elapsedSeconds = 0L,
                                            framesCaptured = 0,
                                            targetDurationSeconds = timeLapseTargetDurationSec,
                                            targetFrames = timeLapseTargetFrames
                                        )
                                    )
                                )
                            }
                        }
                        is VideoRecordEvent.Status -> {
                            val nanos = recordEvent.recordingStats.recordedDurationNanos
                            val seconds = nanos / 1_000_000_000L
                            onRecordingDurationChanged?.invoke(seconds)
                            if (isTimeLapseSession) {
                                val elapsedMs = (nanos / 1_000_000L).coerceAtLeast(0L)
                                val frames = ((elapsedMs / timeLapseIntervalMs).toInt() + 1).coerceAtLeast(1)
                                val prevFrames = timeLapseFramesCaptured.get()
                                if (frames > prevFrames) {
                                    timeLapseFramesCaptured.set(frames)
                                    timeLapsePulseCount.incrementAndGet()
                                }
                                val progressFraction = if (timeLapseTargetDurationSec != null && timeLapseTargetDurationSec > 0L) {
                                    (seconds.toFloat() / timeLapseTargetDurationSec.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val remainingSec = timeLapseTargetDurationSec?.let { (it - seconds).coerceAtLeast(0L) }
                                latestOnShutterProgressChanged?.invoke(
                                    ShutterProgressState(
                                        mode = ShutterProgressMode.TIME_LAPSE,
                                        phase = ShutterProgressPhase.CAPTURING,
                                        currentFrame = frames,
                                        totalFrames = timeLapseTargetFrames ?: 0,
                                        progress = progressFraction,
                                        isIndeterminateCapture = (timeLapseTargetDurationSec == null),
                                        framePulseCount = timeLapsePulseCount.get(),
                                        elapsedSeconds = seconds,
                                        remainingSeconds = remainingSec,
                                        statusLabel = formatShutterElapsedTime(seconds),
                                        detailLabel = formatTimeLapseCaptureCaption(
                                            elapsedSeconds = seconds,
                                            framesCaptured = frames,
                                            targetDurationSeconds = timeLapseTargetDurationSec,
                                            targetFrames = timeLapseTargetFrames
                                        )
                                    )
                                )
                                if (timeLapseTargetDurationSec != null &&
                                    seconds >= timeLapseTargetDurationSec &&
                                    timeLapseAutoStopTriggered.compareAndSet(false, true)
                                ) {
                                    try {
                                        activeRecording?.stop()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error auto-stopping time-lapse at duration limit", e)
                                    }
                                }
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (isTimeLapseSession) {
                                cameraLifecycleOwner.keepAlive = false
                            }
                            onRecordingStateChanged?.invoke(false)
                            activeRecording = null

                            val hasValidTempFile = tempRecordFile.exists() && tempRecordFile.length() > 0L
                            if ((!recordEvent.hasError() || (isTimeLapseSession && hasValidTempFile)) && hasValidTempFile) {
                                val capturedFrameSnapshot = timeLapseFramesCaptured.get().coerceAtLeast(1)
                                if (isTimeLapseSession) {
                                    latestOnShutterProgressChanged?.invoke(
                                        ShutterProgressState(
                                            mode = ShutterProgressMode.TIME_LAPSE,
                                            phase = ShutterProgressPhase.PROCESSING,
                                            currentFrame = capturedFrameSnapshot,
                                            totalFrames = timeLapseTargetFrames ?: 0,
                                            progress = 0.05f,
                                            statusLabel = "5%",
                                            detailLabel = "Building time-lapse… 5%"
                                        )
                                    )
                                }
                                companionCaptureExecutor.execute {
                                    var finalVideoFile: java.io.File? = null
                                    var timeLapseFallbackNotice: String? = if (isTimeLapseSession && recordEvent.hasError()) {
                                        "Recording interrupted — saved captured time-lapse footage."
                                    } else {
                                        null
                                    }
                                    try {
                                        val modeProcessedFile = when (recordingMode) {
                                            CameraMode.SLOW_MOTION -> {
                                                val processedFile = processSlowMotionVideoFile(
                                                    context = context,
                                                    inputFile = tempRecordFile,
                                                    slowFactor = recordingSlowMoSpeed.slowDownFactor
                                                )
                                                runCatching { tempRecordFile.delete() }
                                                processedFile
                                            }
                                            CameraMode.TIME_LAPSE -> {
                                                val processedFile = try {
                                                    processTimeLapseVideoFile(
                                                        context = context,
                                                        inputFile = tempRecordFile,
                                                        speed = recordingTimeLapseSpeed,
                                                        onProgress = { p ->
                                                            val pct = (p * 100f).toInt().coerceIn(5, 99)
                                                            mainExecutor.execute {
                                                                latestOnShutterProgressChanged?.invoke(
                                                                    ShutterProgressState(
                                                                        mode = ShutterProgressMode.TIME_LAPSE,
                                                                        phase = ShutterProgressPhase.PROCESSING,
                                                                        currentFrame = capturedFrameSnapshot,
                                                                        totalFrames = timeLapseTargetFrames ?: 0,
                                                                        progress = p,
                                                                        statusLabel = "$pct%",
                                                                        detailLabel = "Building time-lapse… $pct%"
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    )
                                                } catch (t: Throwable) {
                                                    Log.e(TAG, "Time-lapse processing threw; falling back to raw recording", t)
                                                    null
                                                }
                                                if (processedFile != null && processedFile.exists() && processedFile.length() > 0L) {
                                                    runCatching { tempRecordFile.delete() }
                                                    processedFile
                                                } else {
                                                    // Keep raw recording instead of losing the session!
                                                    timeLapseFallbackNotice = "Time-lapse processing failed — saved raw recording so nothing was lost."
                                                    tempRecordFile
                                                }
                                            }
                                            else -> tempRecordFile
                                        }

                                        finalVideoFile = if (!recordingSelectedFilter.isOriginal && modeProcessedFile != null && modeProcessedFile.exists()) {
                                            val filteredVideo = com.example.core.processing.CameraFilterProcessor.processVideoFileWithFilter(
                                                context = context,
                                                inputFile = modeProcessedFile,
                                                preset = recordingSelectedFilter
                                            )
                                            if (filteredVideo != null && filteredVideo !== modeProcessedFile) {
                                                runCatching { modeProcessedFile.delete() }
                                                filteredVideo
                                            } else {
                                                modeProcessedFile
                                            }
                                        } else {
                                            modeProcessedFile
                                        }

                                        if (finalVideoFile == null || !finalVideoFile.exists() || finalVideoFile.length() == 0L) {
                                            if (isTimeLapseSession) {
                                                TimeLapseRecordingService.stop(context)
                                            }
                                            mainExecutor.execute {
                                                if (isTimeLapseSession) {
                                                    latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                                }
                                                val modeLabel = if (recordingMode == CameraMode.TIME_LAPSE) "time-lapse" else "slow-motion"
                                                onRecordingError?.invoke("Failed to finalize $modeLabel video output.")
                                            }
                                            return@execute
                                        }

                                        val (verifiedWidth, verifiedHeight, durationMs) = inspectAndLogSavedVideoMetadata(
                                            videoFile = finalVideoFile,
                                            requestedResolution = recordingRequestedRes,
                                            requestedFps = recordingRequestedFps,
                                            modeLabel = recordingMode.name
                                        )

                                        val savedMediaStoreUri = saveVideoFileToMediaStore(
                                            context = context,
                                            videoFile = finalVideoFile,
                                            displayName = displayName,
                                            width = verifiedWidth,
                                            height = verifiedHeight,
                                            durationMs = durationMs
                                        )
                                        runCatching { finalVideoFile.delete() }
                                        if (isTimeLapseSession) {
                                            TimeLapseRecordingService.stop(context)
                                        }

                                        mainExecutor.execute {
                                            if (savedMediaStoreUri != null && savedMediaStoreUri != Uri.EMPTY) {
                                                onRecordingSuccess?.invoke(savedMediaStoreUri)
                                                if (isTimeLapseSession) {
                                                    latestOnShutterProgressChanged?.invoke(
                                                        ShutterProgressState(
                                                            mode = ShutterProgressMode.TIME_LAPSE,
                                                            phase = ShutterProgressPhase.COMPLETED,
                                                            currentFrame = capturedFrameSnapshot,
                                                            totalFrames = timeLapseTargetFrames ?: 0,
                                                            progress = 1f,
                                                            statusLabel = "Saved",
                                                            detailLabel = "Saved"
                                                        )
                                                    )
                                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                        latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                                    }, 850L)
                                                }
                                                timeLapseFallbackNotice?.let { notice ->
                                                    onRecordingError?.invoke(notice)
                                                }
                                            } else {
                                                if (isTimeLapseSession) {
                                                    latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                                }
                                                onRecordingError?.invoke("Failed to save recorded video to MediaStore.")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error finalizing and saving video", e)
                                        // Last-chance fallback for Time Lapse: if tempRecordFile still exists, save it raw
                                        var rescuedUri: Uri? = null
                                        if (isTimeLapseSession && tempRecordFile.exists() && tempRecordFile.length() > 0L) {
                                            rescuedUri = runCatching {
                                                val (rw, rh, rDur) = inspectAndLogSavedVideoMetadata(
                                                    videoFile = tempRecordFile,
                                                    requestedResolution = recordingRequestedRes,
                                                    requestedFps = recordingRequestedFps,
                                                    modeLabel = "TIME_LAPSE_RAW_FALLBACK"
                                                )
                                                saveVideoFileToMediaStore(
                                                    context = context,
                                                    videoFile = tempRecordFile,
                                                    displayName = displayName,
                                                    width = rw,
                                                    height = rh,
                                                    durationMs = rDur
                                                )
                                            }.getOrNull()
                                        }
                                        runCatching { tempRecordFile.delete() }
                                        runCatching { finalVideoFile?.delete() }
                                        if (isTimeLapseSession) {
                                            TimeLapseRecordingService.stop(context)
                                        }
                                        mainExecutor.execute {
                                            if (rescuedUri != null && rescuedUri != Uri.EMPTY) {
                                                onRecordingSuccess?.invoke(rescuedUri)
                                                latestOnShutterProgressChanged?.invoke(
                                                    ShutterProgressState(
                                                        mode = ShutterProgressMode.TIME_LAPSE,
                                                        phase = ShutterProgressPhase.COMPLETED,
                                                        progress = 1f,
                                                        statusLabel = "Saved",
                                                        detailLabel = "Saved"
                                                    )
                                                )
                                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                    latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                                }, 850L)
                                                onRecordingError?.invoke("Time-lapse processing failed — saved raw recording so nothing was lost.")
                                            } else {
                                                if (isTimeLapseSession) {
                                                    latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                                }
                                                onRecordingError?.invoke("Failed to save recorded video: ${e.localizedMessage}")
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (isTimeLapseSession) {
                                    TimeLapseRecordingService.stop(context)
                                    latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                                }
                                runCatching { tempRecordFile.delete() }
                                Log.e(TAG, "Video recording finalize error code ${recordEvent.error}")
                                onRecordingError?.invoke("Video recording completed with error code ${recordEvent.error}")
                            }
                        }
                    }
                }
                activeRecording = recording
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video recording", e)
                if (recordingMode == CameraMode.TIME_LAPSE) {
                    cameraLifecycleOwner.keepAlive = false
                    TimeLapseRecordingService.stop(context)
                    latestOnShutterProgressChanged?.invoke(ShutterProgressState())
                }
                onRecordingError?.invoke("Failed to start video recording: ${e.localizedMessage}")
                onRecordingStateChanged?.invoke(false)
                activeRecording = null
            }
        } else {
            // Stop direct Camera2 recording if active, inspect metadata via MediaMetadataRetriever, and save to DCIM/Camera
            val directRec = activeCamera2DirectRecorder
            if (isDirectCamera2RecordingActive && directRec != null) {
                val recRes = latestVideoResolution
                val recFps = latestVideoFrameRate
                val recFilter = com.example.core.processing.CameraFilterProcessor.resolveEffectiveFilter(CameraMode.VIDEO, latestSelectedFilter)
                activeCamera2DirectRecorder = null
                isDirectCamera2RecordingActive = false
                onRecordingStateChanged?.invoke(false)

                companionCaptureExecutor.execute {
                    var finalVideoFile: java.io.File? = null
                    try {
                        val recordedFile = directRec.stopRecording()
                        // Resume CameraX live preview immediately once Camera2 device is released
                        mainExecutor.execute { rebindTrigger++ }

                        if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0L) {
                            finalVideoFile = if (!recFilter.isOriginal) {
                                val filtered = com.example.core.processing.CameraFilterProcessor.processVideoFileWithFilter(
                                    context = context,
                                    inputFile = recordedFile,
                                    preset = recFilter
                                )
                                if (filtered != null && filtered !== recordedFile) {
                                    runCatching { recordedFile.delete() }
                                    filtered
                                } else {
                                    recordedFile
                                }
                            } else {
                                recordedFile
                            }

                            val (verifiedW, verifiedH, durationMs) = inspectAndLogSavedVideoMetadata(
                                videoFile = finalVideoFile,
                                requestedResolution = recRes,
                                requestedFps = recFps.fps,
                                modeLabel = "VIDEO_CAMERA2"
                            )

                            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                            val savedUri = saveVideoFileToMediaStore(
                                context = context,
                                videoFile = finalVideoFile,
                                displayName = "VID_$timeStamp.mp4",
                                width = verifiedW,
                                height = verifiedH,
                                durationMs = durationMs
                            )
                            runCatching { finalVideoFile.delete() }

                            mainExecutor.execute {
                                if (savedUri != null && savedUri != Uri.EMPTY) {
                                    onRecordingSuccess?.invoke(savedUri)
                                } else {
                                    onRecordingError?.invoke("Failed to save recorded video to DCIM/Camera.")
                                }
                            }
                        } else {
                            mainExecutor.execute {
                                onRecordingError?.invoke("Video recording was too short or failed to save.")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error stopping/saving Camera2 direct video", e)
                        runCatching { finalVideoFile?.delete() }
                        mainExecutor.execute {
                            rebindTrigger++
                            onRecordingError?.invoke("Failed to finalize video: ${e.localizedMessage ?: "unknown error"}")
                        }
                    }
                }
            }

            if (activeRecording != null) {
                try {
                    activeRecording?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping active recording", e)
                } finally {
                    activeRecording = null
                }
            }
        }
    }

    // Dismiss flash animation overlay after brief delay
    LaunchedEffect(showShutterFlashOverlay) {
        if (showShutterFlashOverlay) {
            delay(120)
            showShutterFlashOverlay = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                activeCamera2DirectRecorder?.stopRecording()
                activeCamera2DirectRecorder = null
                isDirectCamera2RecordingActive = false
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping direct Camera2 recording on dispose", e)
            }
            try {
                activeRecording?.stop()
                activeRecording = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping active recording on dispose", e)
            }
            activeCamera = null
            boundImageCapture = null
            boundVideoCapture = null
            lastBoundConfig = null
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Error unbinding CameraX on dispose", exc)
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .testTag("camerax_preview_view")
        )

        // Dedicated TextureView surface for direct Camera2 + MediaRecorder video recording (exact resolution/FPS).
        // Mounted only in Video mode to avoid allocating an unused TextureView Gralloc buffer in photo modes.
        if (activeMode == CameraMode.VIDEO || isDirectCamera2RecordingActive) {
            AndroidView(
                factory = { camera2PreviewTextureView },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isDirectCamera2RecordingActive) 1f else 0f)
                    .testTag("camera2_video_texture_view")
            )
        }

        // Real Focus Peaking edge overlay directly over the viewfinder surface
        if (activeMode == CameraMode.PRO && proFocusPeakingEnabled && currentPeakingFrame != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pro_focus_peaking_overlay")
            ) {
                currentPeakingFrame?.let { frame ->
                    drawImage(
                        image = frame.bitmap.asImageBitmap(),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.Low
                    )
                }
            }
        }

        // Self-timer countdown visual feedback overlay with tap-to-cancel support
        AnimatedVisibility(
            visible = isCountingDown && countdownRemainingSeconds > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.50f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isCountingDown = false
                        countdownRemainingSeconds = 0
                    }
                    .testTag("countdown_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(110.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "$countdownRemainingSeconds",
                                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("countdown_number_text")
                            )
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .clickable {
                                isCountingDown = false
                                countdownRemainingSeconds = 0
                            }
                            .testTag("countdown_cancel_button")
                    ) {
                        Text(
                            text = "Tap to Cancel",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Night Sight multi-frame / low-light capture progress feedback
        AnimatedVisibility(
            visible = isCapturing && activeMode == CameraMode.NIGHT_SIGHT && nightSightExposure != NightSightExposure.OFF,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("night_sight_capturing_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Hold still • Night Sight…",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Astrophotography multi-frame capture progress feedback
        AnimatedVisibility(
            visible = isCapturing && activeMode == CameraMode.ASTRO,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("astro_capturing_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Hold still • Astrophotography…",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Subtle shutter flash feedback
        AnimatedVisibility(
            visible = showShutterFlashOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.65f))
                    .testTag("shutter_flash_overlay")
            )
        }
    }
}

/**
 * Queries the hardware AE target FPS ranges for a specific camera lens.
 */
fun queryLensAeFpsRanges(context: Context, targetLens: CameraLens): Array<Range<Int>> {
    try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyArray()
        val expectedFacing = if (targetLens.facing == LensFacing.FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == expectedFacing) {
                return characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Error querying AE FPS ranges for ${targetLens.displayName}", e)
    }
    return emptyArray()
}

/**
 * Computes supported video resolutions (720p, 1080p, 2K, 4K) and per-resolution frame rates
 * (24 FPS, 30 FPS, 60 FPS) from Camera2 output sizes, per-size minimum frame durations,
 * AE target FPS ranges, and CameraX VideoCapabilities.
 */
fun discoverVideoQualitiesFromHardware(
    videoOutputSizes: List<Size>,
    minFrameDurationNanosByResolution: Map<VideoResolution, Long>,
    aeFpsRanges: List<Range<Int>>,
    cameraXSupportedResolutions: Set<VideoResolution>? = null
): Map<VideoResolution, List<VideoFrameRate>> {
    val sensorMaxFps = aeFpsRanges.maxOfOrNull { it.upper } ?: 30
    val sensorSupports60Fps = sensorMaxFps >= 60
    val sensorSupports24Fps = aeFpsRanges.any { it.upper == 24 || (it.lower == 24 && it.upper == 24) }
    val sensorSupports30Fps = aeFpsRanges.isEmpty() || aeFpsRanges.any { it.upper == 30 || 30 in it.lower..it.upper }

    val result = linkedMapOf<VideoResolution, List<VideoFrameRate>>()

    for (res in VideoResolution.VIDEO_MODE_OPTIONS) {
        val matchesCamera2Size = if (videoOutputSizes.isEmpty()) {
            // Safe baseline if StreamConfigurationMap provides no explicit sizes: only 720p & 1080p, never claim 2K or 4K
            res == VideoResolution.HD_720P || res == VideoResolution.FHD_1080P
        } else {
            videoOutputSizes.any { size ->
                (size.width == res.width && size.height == res.height) ||
                    (size.width == res.height && size.height == res.width)
            }
        }

        val matchesCameraX = if (cameraXSupportedResolutions != null && cameraXSupportedResolutions.isNotEmpty()) {
            res in cameraXSupportedResolutions
        } else {
            // Without explicit CameraX profile verification, never claim QHD_2K since standard CameraX QualitySelector
            // only exposes SD/HD/FHD/UHD tokens unless a profile explicitly matches 2560x1440.
            res != VideoResolution.QHD_2K
        }

        if (!matchesCamera2Size || !matchesCameraX) continue

        val minDurationNanos = minFrameDurationNanosByResolution[res] ?: 0L
        val supportedRates = mutableListOf<VideoFrameRate>()

        // 24 FPS check (41.67ms frame duration = 41,666,667 ns; allow HAL tolerance up to 42.5ms)
        if (sensorSupports24Fps && (minDurationNanos == 0L || minDurationNanos <= 42_500_000L)) {
            supportedRates.add(VideoFrameRate.FPS_24)
        }

        // 30 FPS check (33.33ms frame duration = 33,333,333 ns; allow small HAL tolerance up to 34.5ms)
        if (sensorSupports30Fps && (minDurationNanos == 0L || minDurationNanos <= 34_500_000L)) {
            supportedRates.add(VideoFrameRate.FPS_30)
        }

        // 60 FPS check: requires sensor AE range >= 60 AND per-resolution minFrameDuration <= 17.0ms (16.67ms = 60fps).
        // For 2K QHD and 4K UHD, strictly require explicit minFrameDurationNanos in 1..17_000_000L so 2K/4K 60fps is never falsely assumed.
        val sizeSupports60Fps = when {
            !sensorSupports60Fps -> false
            minDurationNanos in 1L..17_000_000L -> true
            minDurationNanos > 17_000_000L -> false
            res == VideoResolution.UHD_4K || res == VideoResolution.QHD_2K -> false
            else -> aeFpsRanges.any { it.lower == 60 && it.upper == 60 } || sensorSupports60Fps
        }

        if (sizeSupports60Fps) {
            supportedRates.add(VideoFrameRate.FPS_60)
        }

        if (supportedRates.isNotEmpty()) {
            result[res] = supportedRates
        }
    }

    // Ensure at least 720p @ 30fps baseline if camera exists but reported unusual stream sizes
    if (result.isEmpty()) {
        result[VideoResolution.HD_720P] = listOf(VideoFrameRate.FPS_30)
    }

    return result
}

/**
 * Queries hardware MediaCodec H.264 (AVC) and HEVC video encoders to determine the maximum
 * supported frame rate at each candidate [VideoResolution].
 */
internal fun queryHardwareEncoderMaxFpsByResolution(): Map<VideoResolution, Double> {
    return com.example.core.camera.queryHardwareEncoderVideoCapabilities().maxFpsByResolution
}

/**
 * Queries Camera2 CameraCharacteristics, physical sub-camera characteristics, CamcorderProfile/EncoderProfiles,
 * MediaCodecList hardware encoder capabilities, and CameraX Codec VideoCapabilities to discover
 * genuine hardware capabilities separately for rear and front cameras without fabrication.
 */
fun queryDeviceCameraCapabilities(
    context: Context,
    cameraProvider: ProcessCameraProvider? = null
): Map<CameraLens, LensCapabilities> {
    val capabilitiesMap = mutableMapOf<CameraLens, LensCapabilities>()
    try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyMap()
        val cameraIdList = cameraManager.cameraIdList
        val codecMaxFpsByRes = queryHardwareEncoderMaxFpsByResolution()

        // Pre-collect focal lengths per facing so camera labels are derived purely from real metadata
        val backFocalLengths = mutableListOf<Float>()
        val frontFocalLengths = mutableListOf<Float>()
        for (cid in cameraIdList) {
            runCatching {
                val c = cameraManager.getCameraCharacteristics(cid)
                val f = c.get(CameraCharacteristics.LENS_FACING)
                val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                if (fl != null) {
                    if (f == CameraCharacteristics.LENS_FACING_FRONT) frontFocalLengths.add(fl)
                    else if (f == CameraCharacteristics.LENS_FACING_BACK) backFocalLengths.add(fl)
                }
            }
        }

        for (id in cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

            val lens = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> CameraLens.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> CameraLens.BACK_MAIN
                else -> continue
            }

            // Keep primary lens entry per facing (first discovered main back / front camera)
            if (capabilitiesMap.containsKey(lens)) continue

            // Physical sub-cameras belonging to this logical camera PLUS every other same-facing camera ID
            // in cameraIdList (some OEMs expose a dedicated full-res still camera ID alongside ID 0).
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { characteristics.physicalCameraIds.toList() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val sameFacingCameraIds = buildList {
                add(id)
                addAll(physicalIds)
                for (otherId in cameraIdList) {
                    if (otherId == id || otherId in physicalIds) continue
                    runCatching {
                        val otherChars = cameraManager.getCameraCharacteristics(otherId)
                        if (otherChars.get(CameraCharacteristics.LENS_FACING) == facing) {
                            add(otherId)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                runCatching { otherChars.physicalCameraIds.toList() }
                                    .getOrDefault(emptyList())
                                    .forEach { add(it) }
                            }
                        }
                    }
                }
            }.distinct()
            val stillInspectionChars = sameFacingCameraIds.mapNotNull { cid ->
                runCatching { cid to cameraManager.getCameraCharacteristics(cid) }.getOrNull()
            }

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            var pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                PhotoOutputSize(width = it.width, height = it.height, isSensorMaxResolution = true)
            }
            var activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                PhotoOutputSize(width = it.width(), height = it.height(), isSensorMaxResolution = true)
            }
            for ((cid, chars) in stillInspectionChars) {
                if (cid == id) continue
                chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { sz ->
                    val candidate = PhotoOutputSize(width = sz.width, height = sz.height, isSensorMaxResolution = true)
                    if (candidate.totalPixels > (pixelArraySize?.totalPixels ?: 0L)) {
                        pixelArraySize = candidate
                    }
                }
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rect ->
                    val candidate = PhotoOutputSize(width = rect.width(), height = rect.height(), isSensorMaxResolution = true)
                    if (candidate.totalPixels > (activeArraySize?.totalPixels ?: 0L)) {
                        activeArraySize = candidate
                    }
                }
            }

            // 1. HD MAX RESOLUTION DISCOVERY:
            // Inspect standard JPEG, YUV_420_888, and RAW_SENSOR, getHighResolutionOutputSizes, API 31+
            // SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION, and all same-facing & physical camera IDs.
            // Pick the single largest available JPEG/YUV capture source honestly (only adopting a size from a
            // secondary camera ID if it is strictly larger than the primary camera ID's largest exposed size).
            val rawPhotoSet = LinkedHashSet<Pair<Int, Int>>()
            var bestHdSource: HdMaxPhotoCaptureSource? = null
            var primaryMaxExposedPixels = 0L

            fun considerHdCandidate(
                candidateCamId: String,
                w: Int,
                h: Int,
                requiresMaxResMode: Boolean,
                sourceTag: String,
                isSecondaryCameraId: Boolean
            ) {
                if (w <= 0 || h <= 0) return
                val pixels = w.toLong() * h.toLong()
                if (!isSecondaryCameraId) {
                    rawPhotoSet.add(w to h)
                    if (pixels > primaryMaxExposedPixels) {
                        primaryMaxExposedPixels = pixels
                    }
                } else {
                    // Only adopt a size from a separate camera ID if it genuinely beats primary camera `id`
                    if (pixels <= primaryMaxExposedPixels) return
                    rawPhotoSet.add(w to h)
                }
                val curBest = bestHdSource
                val isBetter = curBest == null ||
                    pixels > curBest.totalPixels ||
                    (pixels == curBest.totalPixels && curBest.requiresMaxResPixelMode && !requiresMaxResMode) ||
                    (pixels == curBest.totalPixels && curBest.sourceTag.startsWith("YUV") && sourceTag.startsWith("JPEG"))
                if (isBetter) {
                    bestHdSource = HdMaxPhotoCaptureSource(
                        cameraId = candidateCamId,
                        width = w,
                        height = h,
                        requiresMaxResPixelMode = requiresMaxResMode,
                        isPhysicalSubCamera = candidateCamId != id,
                        sourceTag = sourceTag
                    )
                }
            }

            // Inspect primary camera `id` first so `primaryMaxExposedPixels` is established before checking secondary IDs
            val orderedInspectionChars = stillInspectionChars.sortedBy { if (it.first == id) 0 else 1 }
            for ((cid, chars) in orderedInspectionChars) {
                val isSecondary = cid != id
                val scMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (scMap != null) {
                    scMap.getOutputSizes(android.graphics.ImageFormat.JPEG)?.forEach { sz ->
                        considerHdCandidate(cid, sz.width, sz.height, false, "JPEG_STD", isSecondary)
                    }
                    scMap.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.forEach { sz ->
                        considerHdCandidate(cid, sz.width, sz.height, false, "YUV_STD", isSecondary)
                    }
                    runCatching {
                        scMap.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG)?.forEach { sz ->
                            considerHdCandidate(cid, sz.width, sz.height, false, "JPEG_HIRES", isSecondary)
                        }
                    }
                    runCatching {
                        scMap.getHighResolutionOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.forEach { sz ->
                            considerHdCandidate(cid, sz.width, sz.height, false, "YUV_HIRES", isSecondary)
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.let { maxResMap ->
                            maxResMap.getOutputSizes(android.graphics.ImageFormat.JPEG)?.forEach { sz ->
                                considerHdCandidate(cid, sz.width, sz.height, true, "JPEG_MAXRES_API31", isSecondary)
                            }
                            maxResMap.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG)?.forEach { sz ->
                                considerHdCandidate(cid, sz.width, sz.height, true, "JPEG_MAXRES_HIRES_API31", isSecondary)
                            }
                            maxResMap.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.forEach { sz ->
                                considerHdCandidate(cid, sz.width, sz.height, true, "YUV_MAXRES_API31", isSecondary)
                            }
                            maxResMap.getHighResolutionOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.forEach { sz ->
                                considerHdCandidate(cid, sz.width, sz.height, true, "YUV_MAXRES_HIRES_API31", isSecondary)
                            }
                        }
                    }
                }
            }
            val sortedPhotoDims = rawPhotoSet.sortedByDescending { it.first.toLong() * it.second.toLong() }
            val maxPhotoPixels = sortedPhotoDims.firstOrNull()?.let { it.first.toLong() * it.second.toLong() } ?: 0L
            val photoSizes = sortedPhotoDims.map { (w, h) ->
                PhotoOutputSize(
                    width = w,
                    height = h,
                    isSensorMaxResolution = (w.toLong() * h.toLong()) == maxPhotoPixels && maxPhotoPixels > 0L
                )
            }
            val highestNativeSize = photoSizes.firstOrNull()
            val sensorRefPixels = maxOf(pixelArraySize?.totalPixels ?: 0L, activeArraySize?.totalPixels ?: 0L)
            val isFullSensorExposed = sensorRefPixels > 0L && highestNativeSize != null &&
                highestNativeSize.totalPixels >= (sensorRefPixels * 85L) / 100L

            // 2. VIDEO STREAM SIZES & PER-SIZE MIN FRAME DURATION (STRICTLY FOR THIS CAMERA ID):
            // Query MediaRecorder output sizes primarily (and MediaCodec surface fallback) on camera `id` ONLY
            // so unrelated auxiliary lenses (macro/depth) never corrupt this camera's video list.
            val streamVideoSizes = LinkedHashSet<Pair<Int, Int>>()
            val minFrameDurationByRes = mutableMapOf<VideoResolution, Long>()
            if (map != null) {
                val recSizes = map.getOutputSizes(android.media.MediaRecorder::class.java) ?: emptyArray()
                val codecSizes = map.getOutputSizes(android.media.MediaCodec::class.java) ?: emptyArray()
                val combinedVideoSizes = if (recSizes.isNotEmpty()) {
                    (recSizes + codecSizes).distinct()
                } else {
                    val texSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
                    (codecSizes + texSizes).distinct()
                }
                for (sz in combinedVideoSizes) {
                    if (sz.width > 0 && sz.height > 0) {
                        streamVideoSizes.add(maxOf(sz.width, sz.height) to minOf(sz.width, sz.height))
                    }
                }

                for (res in VideoResolution.VIDEO_MODE_OPTIONS) {
                    val matchingSize = combinedVideoSizes.firstOrNull { sz ->
                        (sz.width == res.width && sz.height == res.height) ||
                            (sz.width == res.height && sz.height == res.width)
                    } ?: if (res == VideoResolution.FHD_1080P) {
                        combinedVideoSizes.firstOrNull { sz ->
                            (sz.width == 1920 && sz.height == 1088) ||
                                (sz.width == 1088 && sz.height == 1920)
                        }
                    } else null
                    if (matchingSize == null) continue

                    val recDur = runCatching { map.getOutputMinFrameDuration(android.media.MediaRecorder::class.java, matchingSize) }.getOrDefault(0L)
                    val codecDur = runCatching { map.getOutputMinFrameDuration(android.media.MediaCodec::class.java, matchingSize) }.getOrDefault(0L)
                    val bestDuration = listOf(recDur, codecDur).filter { it > 0L }.minOrNull() ?: 0L
                    if (bestDuration > 0L) {
                        minFrameDurationByRes[res] = bestDuration
                    }
                }
            }

            // 3. HIGH-SPEED VIDEO SIZES (STRICTLY FOR SLOW MOTION ONLY — NEVER MIXED INTO NORMAL VIDEO)
            val highSpeedSizeToMaxFps = mutableMapOf<Pair<Int, Int>, Int>()
            if (map != null) {
                val hsSizes = runCatching { map.highSpeedVideoSizes ?: emptyArray() }.getOrDefault(emptyArray())
                for (hsSize in hsSizes) {
                    val ranges = runCatching { map.getHighSpeedVideoFpsRangesFor(hsSize) ?: emptyArray() }.getOrDefault(emptyArray())
                    val maxFps = ranges.maxOfOrNull { it.upper } ?: 0
                    if (maxFps > 0) {
                        val key = maxOf(hsSize.width, hsSize.height) to minOf(hsSize.width, hsSize.height)
                        val prev = highSpeedSizeToMaxFps[key] ?: 0
                        if (maxFps > prev) highSpeedSizeToMaxFps[key] = maxFps
                    }
                }
            }

            // 4. STANDARD CamcorderProfile / EncoderProfiles AS SECOND SOURCE (MATCHED BY ACTUAL FRAME DIMENSIONS)
            val camcorderMaxFpsByRes = com.example.core.camera.queryCamcorderProfileMaxFpsByResolution(id)

            // 5. Query CameraX Recorder.getVideoCapabilities, PreviewCapabilities, ZSL, and Extensions
            val cameraXSupportedResolutions = mutableSetOf<VideoResolution>()
            var isCameraXVideoStabSupported = false
            var isCameraXPreviewStabSupported = false
            var isCameraXZslSupported = false
            var extensionAvailability = com.example.core.camera.CameraExtensionAvailability()
            try {
                if (cameraProvider != null) {
                    val selector = if (lens == CameraLens.FRONT) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    extensionAvailability = com.example.core.camera.CameraExtensionsHelper.queryAvailability(
                        context = context,
                        cameraProvider = cameraProvider,
                        baseCameraSelector = selector
                    )
                    val matchingCameraInfo = selector.filter(cameraProvider.availableCameraInfos).firstOrNull()
                    if (matchingCameraInfo != null) {
                        isCameraXZslSupported = isCameraInfoZslSupported(matchingCameraInfo)
                        isCameraXPreviewStabSupported = runCatching {
                            Preview.getPreviewCapabilities(matchingCameraInfo).isStabilizationSupported
                        }.getOrDefault(false)

                        // Verify the camera actually exposes at least one valid standard EncoderProfile before
                        // invoking Recorder.getVideoCapabilities (which logs E/CapabilitiesByQuality: No supported
                        // EncoderProfiles on devices/emulators that expose zero usable EncoderProfiles).
                        val camIdIntForProfile = id.toIntOrNull()
                        val hasAnyCamcorderProfile = camcorderMaxFpsByRes.isNotEmpty() || (
                            camIdIntForProfile != null && listOf(
                                android.media.CamcorderProfile.QUALITY_480P,
                                android.media.CamcorderProfile.QUALITY_720P,
                                android.media.CamcorderProfile.QUALITY_1080P,
                                android.media.CamcorderProfile.QUALITY_2160P
                            ).any { q ->
                                runCatching {
                                    if (!android.media.CamcorderProfile.hasProfile(camIdIntForProfile, q)) {
                                        false
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val ep = android.media.CamcorderProfile.getAll(id, q)
                                        ep != null && ep.videoProfiles.any { vp -> vp.width >= 640 && vp.height >= 480 }
                                    } else {
                                        true
                                    }
                                }.getOrDefault(false)
                            }
                        )

                        if (hasAnyCamcorderProfile) {
                            val codecVideoCaps = Recorder.getVideoCapabilities(
                                matchingCameraInfo,
                                Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES
                            )
                            val profileVideoCaps = Recorder.getVideoCapabilities(
                                matchingCameraInfo,
                                Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE
                            )
                            isCameraXVideoStabSupported = codecVideoCaps.isStabilizationSupported || profileVideoCaps.isStabilizationSupported

                            val qualities = (
                                codecVideoCaps.getSupportedQualities(DynamicRange.SDR) +
                                    profileVideoCaps.getSupportedQualities(DynamicRange.SDR)
                                ).distinct()
                            for (q in qualities) {
                                q.toVideoResolution()?.let { cameraXSupportedResolutions.add(it) }
                            }
                        } else {
                            // No CamcorderProfile/EncoderProfiles on this camera: derive supported resolutions
                            // directly from the verified Camera2 stream map + MediaCodec VideoCapabilities.
                            for (res in VideoResolution.entries) {
                                val inStreamMap = streamVideoSizes.any { (w, h) ->
                                    maxOf(w, h) == res.width && (minOf(w, h) == res.height || (res == VideoResolution.FHD_1080P && minOf(w, h) == 1088))
                                }
                                val codecCapable = (codecMaxFpsByRes[res] ?: 0.0) >= 23.0
                                if (inStreamMap && codecCapable) {
                                    cameraXSupportedResolutions.add(res)
                                }
                            }
                        }
                        val has2kStream = streamVideoSizes.any { (w, h) ->
                            maxOf(w, h) == VideoResolution.QHD_2K.width && minOf(w, h) == VideoResolution.QHD_2K.height
                        }
                        val is2kEncoderReady = (codecMaxFpsByRes[VideoResolution.QHD_2K] ?: 0.0) >= 29.0 ||
                            (camcorderMaxFpsByRes[VideoResolution.QHD_2K] ?: 0) > 0 ||
                            VideoResolution.UHD_4K in cameraXSupportedResolutions
                        if (has2kStream && is2kEncoderReady) {
                            cameraXSupportedResolutions.add(VideoResolution.QHD_2K)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query CameraX VideoCapabilities/Extensions for ${lens.displayName}", e)
            }
            val ispQualityCapabilities = com.example.core.camera.IspQualityCapabilities.fromCameraCharacteristics(
                characteristics = characteristics,
                cameraXZslSupported = isCameraXZslSupported
            )

            // 6. Read AE FPS ranges and stabilization modes strictly for camera `id`
            val aeFpsRangesList = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
            val aeFpsPairs = aeFpsRangesList.map { it.lower to it.upper }
            val videoStabModesList = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: emptyList()
            val oisModesList = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList()

            val rawVerifiedVideoProfile = com.example.core.camera.buildVerifiedVideoCapabilityProfile(
                streamVideoSizes = streamVideoSizes,
                minFrameDurationNanosByResolution = minFrameDurationByRes,
                aeFpsRanges = aeFpsPairs,
                highSpeedSizeToMaxFps = highSpeedSizeToMaxFps,
                cameraXSupportedResolutions = cameraXSupportedResolutions,
                camcorderMaxFpsByResolution = camcorderMaxFpsByRes,
                codecMaxFpsByResolution = codecMaxFpsByRes,
                availableVideoStabilizationModes = videoStabModesList,
                availableOpticalStabilizationModes = oisModesList,
                isCameraXVideoStabilizationSupported = isCameraXVideoStabSupported,
                isCameraXPreviewStabilizationSupported = isCameraXPreviewStabSupported
            )

            // Verify candidate video (resolution, FPS) session configs and cache per camera ID
            // Note: keep allowDeviceOpenProbe = false here to avoid racing CameraX startup camera open on API 29-34;
            // API 35+ still verifies CameraCharacteristics.isSessionConfigurationSupported without opening the device.
            val (sessionVerifiedQualities, _) = VideoSessionConfigVerifier.verifyAndCacheVideoCombinationsWithDiagnostics(
                context = context,
                cameraId = id,
                candidates = rawVerifiedVideoProfile.supportedVideoQualities,
                staticEvaluations = rawVerifiedVideoProfile.videoComboEvaluations,
                aeFpsRanges = aeFpsPairs,
                allowDeviceOpenProbe = false
            )

            val hasFlashUnit = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocusDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
            val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList() ?: emptyList()
            val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val maxAwbRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0
            val isAeLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
            val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
            val sceneModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList() ?: emptyList()
            val controlModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES)?.toList() ?: emptyList()
            val maxFaceCount = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
            val faceDetectModes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.toList() ?: emptyList()
            val isHardwareHdrSceneSupported = com.example.core.camera.isCamera2HardwareHdrSceneModeSupported(
                availableSceneModes = sceneModes,
                availableControlModes = controlModes
            )
            val isHardwareNightSceneSupported = com.example.core.camera.isCamera2HardwareNightSceneModeSupported(
                availableSceneModes = sceneModes,
                availableControlModes = controlModes
            )
            val availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
            val isLogicalMultiCam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                availableCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
                    physicalIds.size >= 2
            } else false
            val rawOutputSizes = map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            val isRawSupported = availableCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ||
                (rawOutputSizes != null && rawOutputSizes.isNotEmpty())

            val hasVariableAfMode = afModes.any {
                it == CameraCharacteristics.CONTROL_AF_MODE_AUTO ||
                    it == CameraCharacteristics.CONTROL_AF_MODE_MACRO ||
                    it == CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                    it == CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            }
            val isFixedFocusLens = (minFocusDist != null && minFocusDist == 0.0f) ||
                (afModes.isNotEmpty() && !hasVariableAfMode)
            val isAutoFocusSupported = !isFixedFocusLens &&
                (hasVariableAfMode || (minFocusDist != null && minFocusDist > 0f)) &&
                (maxAfRegions > 0 || afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO))
            val isFocusLockSupported = isAutoFocusSupported &&
                (afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) || maxAfRegions > 0)

            val hasActiveAeMode = aeModes.isEmpty() || aeModes.any {
                it == CameraCharacteristics.CONTROL_AE_MODE_ON ||
                    it == CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH ||
                    it == CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH ||
                    it == CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE
            }
            val isAutoExposureSupported = hasActiveAeMode && maxAeRegions > 0

            val aeCompRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeCompStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val isEvCompSupported = aeCompRange != null && (aeCompRange.lower < 0 || aeCompRange.upper > 0)
            val evStepFloat = if (aeCompStep != null && aeCompStep.denominator != 0 && aeCompStep.toFloat() > 0f) {
                aeCompStep.toFloat()
            } else {
                1f / 3f
            }

            val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()
            } else null
            val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f
            val hwMinZoom = (zoomRatioRange?.lower ?: 1.0f).coerceAtLeast(0.5f)
            val hwMaxZoom = (zoomRatioRange?.upper ?: maxDigitalZoom).coerceAtLeast(1.0f)
            val resolvedZoomRange = com.example.core.camera.resolveSupportedZoomRange(hwMinZoom, hwMaxZoom)
            val zoomShortcuts = com.example.core.camera.generateZoomShortcuts(
                resolvedZoomRange.start,
                resolvedZoomRange.endInclusive
            )

            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val primaryFocalMm = focalLengths?.firstOrNull()
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: if (lens == CameraLens.FRONT) 270 else 90

            // Derive camera label strictly from metadata (facing, focal length, physical IDs) — never hardcoded
            val metadataCameraLabel = resolveCameraLabelFromMetadata(
                facing = lens.facing,
                focalLengthMm = primaryFocalMm,
                cameraId = id,
                physicalCameraIds = physicalIds,
                allSameFacingFocalLengths = if (lens.facing == LensFacing.FRONT) frontFocalLengths else backFocalLengths
            )

            val manualCap = ManualSensorCapabilities(
                isManualIsoSupported = isoRange != null,
                isoRange = isoRange?.let { it.lower..it.upper },
                isManualShutterSupported = shutterRange != null,
                shutterSpeedRangeNanos = shutterRange?.let { it.lower..it.upper },
                isManualFocusSupported = minFocusDist != null && minFocusDist > 0f,
                minimumFocusDistanceDiopters = minFocusDist,
                isAutoFocusSupported = isAutoFocusSupported,
                isAutoExposureSupported = isAutoExposureSupported,
                isFocusLockSupported = isFocusLockSupported,
                maxAfRegions = maxAfRegions,
                maxAeRegions = maxAeRegions,
                maxAwbRegions = maxAwbRegions,
                isAeLockSupported = isAeLockSupported,
                availableAfModes = afModes,
                availableAeModes = aeModes,
                availableAwbModes = awbModes,
                availableSceneModes = sceneModes,
                availableControlModes = controlModes,
                isHardwareHdrSceneModeSupported = isHardwareHdrSceneSupported,
                isHardwareNightSceneModeSupported = isHardwareNightSceneSupported,
                isRawDngSupported = isRawSupported,
                isExposureCompensationSupported = isEvCompSupported,
                evRange = aeCompRange?.let { it.lower..it.upper },
                evStepRational = evStepFloat,
                minZoomRatio = resolvedZoomRange.start,
                maxZoomRatio = resolvedZoomRange.endInclusive,
                availableZoomRatios = zoomShortcuts,
                isLogicalMultiCamera = isLogicalMultiCam,
                physicalCameraIds = physicalIds,
                maxFaceDetectCount = maxFaceCount,
                availableFaceDetectModes = faceDetectModes
            )

            val lensCap = LensCapabilities(
                lens = lens,
                supportedPhotoSizes = photoSizes,
                highestNativeStillSize = highestNativeSize,
                supportedVideoQualities = sessionVerifiedQualities,
                supportedSlowMoRates = rawVerifiedVideoProfile.supportedSlowMoRates,
                supportedSlowMoResolutionsByRate = rawVerifiedVideoProfile.supportedSlowMoResolutionsByRate,
                supportedStabilizationModesByConfig = rawVerifiedVideoProfile.supportedStabilizationModesByConfig,
                isStabilizationSupported = rawVerifiedVideoProfile.isStabilizationSupported,
                isDigitalVideoStabilizationSupported = rawVerifiedVideoProfile.isDigitalVideoStabilizationSupported,
                isPreviewStabilizationSupported = rawVerifiedVideoProfile.isPreviewStabilizationSupported,
                isOpticalStabilizationSupported = rawVerifiedVideoProfile.isOpticalStabilizationSupported,
                isActiveStabilizationSupported = rawVerifiedVideoProfile.isActiveStabilizationSupported,
                isTorchSupported = hasFlashUnit,
                isFullSensorResolutionExposed = isFullSensorExposed,
                sensorPixelArraySize = pixelArraySize,
                sensorActiveArraySize = activeArraySize,
                hdMaxPhotoCaptureSource = bestHdSource,
                camera2CameraId = id,
                physicalCameraIds = physicalIds,
                focalLengthMm = primaryFocalMm,
                sensorOrientationDegrees = sensorOrientation,
                sensorDescription = metadataCameraLabel,
                manualCapabilities = manualCap,
                extensionAvailability = extensionAvailability,
                ispQualityCapabilities = ispQualityCapabilities
            )

            capabilitiesMap[lens] = lensCap
        }
    } catch (exc: Exception) {
        Log.e(TAG, "Error discovering Camera2 characteristics", exc)
    }
    return capabilitiesMap
}

/**
 * Remuxes a recorded video file into a genuine slow-motion MP4 container by scaling video sample
 * presentation timestamps by [slowFactor] (e.g. 4.0f for 120 FPS -> 1/4x speed, 8.0f for 240 FPS -> 1/8x speed).
 * Preserves video rotation metadata and omits 1x audio so the container duration and playback clock
 * strictly reflect the slow-motion timeline (e.g. a 10s capture at 120 FPS produces a 40s video).
 */
internal fun processSlowMotionVideoFile(
    context: Context,
    inputFile: java.io.File,
    slowFactor: Float
): java.io.File? {
    if (!inputFile.exists() || inputFile.length() == 0L) return null
    val effectiveSlowFactor = slowFactor.coerceAtLeast(1.0f)
    var extractor: android.media.MediaExtractor? = null
    var muxer: android.media.MediaMuxer? = null
    var muxerStarted = false
    val outputFile = java.io.File.createTempFile("slowmo_out_", ".mp4", context.cacheDir)
    try {
        var rotationDegrees = 0
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(inputFile.absolutePath)
            rotationDegrees = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        extractor = android.media.MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        var videoTrackIndex = -1
        var videoFormat: android.media.MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = format
                if (rotationDegrees == 0 && format.containsKey(android.media.MediaFormat.KEY_ROTATION)) {
                    rotationDegrees = runCatching {
                        format.getInteger(android.media.MediaFormat.KEY_ROTATION)
                    }.getOrDefault(0)
                }
                break
            }
        }

        if (videoTrackIndex < 0 || videoFormat == null) {
            outputFile.delete()
            return null
        }

        muxer = android.media.MediaMuxer(
            outputFile.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        if (rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            muxer.setOrientationHint(rotationDegrees)
        }

        val muxerVideoTrackIndex = muxer.addTrack(videoFormat)
        muxer.start()
        muxerStarted = true

        val maxInputSize = if (videoFormat.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { videoFormat.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        } else {
            0
        }
        val bufferCapacity = maxOf(maxInputSize, 4 * 1024 * 1024)
        val buffer = java.nio.ByteBuffer.allocateDirect(bufferCapacity)
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        extractor.selectTrack(videoTrackIndex)
        var firstSampleTimeUs = -1L
        var lastOutputPtsUs = -1L
        var sampleCount = 0

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            val rawSampleTimeUs = extractor.sampleTime
            if (rawSampleTimeUs < 0L) {
                extractor.advance()
                continue
            }
            if (firstSampleTimeUs < 0L) {
                firstSampleTimeUs = rawSampleTimeUs
            }
            val relativeTimeUs = (rawSampleTimeUs - firstSampleTimeUs).coerceAtLeast(0L)
            var scaledPtsUs = (relativeTimeUs * effectiveSlowFactor).toLong()
            if (scaledPtsUs <= lastOutputPtsUs) {
                scaledPtsUs = lastOutputPtsUs + 1000L
            }
            lastOutputPtsUs = scaledPtsUs

            val sampleFlags = extractor.sampleFlags
            var codecFlags = 0
            if ((sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                codecFlags = codecFlags or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if ((sampleFlags and android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) {
                codecFlags = codecFlags or android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }

            bufferInfo.set(0, sampleSize, scaledPtsUs, codecFlags)
            muxer.writeSampleData(muxerVideoTrackIndex, buffer, bufferInfo)
            sampleCount++
            extractor.advance()
        }

        extractor.unselectTrack(videoTrackIndex)

        if (sampleCount == 0) {
            outputFile.delete()
            return null
        }

        muxer.stop()
        muxerStarted = false
        muxer.release()
        muxer = null
        extractor.release()
        extractor = null

        return if (outputFile.exists() && outputFile.length() > 0L) outputFile else null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to remux slow-motion video with factor $slowFactor", e)
        runCatching { outputFile.delete() }
        return null
    } finally {
        runCatching {
            if (muxerStarted) muxer?.stop()
        }
        runCatching { muxer?.release() }
        runCatching { extractor?.release() }
    }
}

internal data class VideoSampleMeta(
    val index: Int,
    val ptsUs: Long,
    val isSync: Boolean
)

internal data class ScaledVideoSample(
    val sourceIndex: Int,
    val outputPtsUs: Long,
    val isSync: Boolean
)

/**
 * Computes the slow-motion output presentation timestamp schedule for a sequence of source video samples.
 * For genuine high-FPS footage (e.g. 120 FPS with [slowFactor] = 4.0f, 240 FPS with [slowFactor] = 8.0f),
 * every frame is preserved and its presentation timestamp is scaled by [slowFactor] so a 10s capture
 * produces a 40s (120 FPS) or 80s (240 FPS) slow-motion output timeline.
 */
internal fun computeSlowMotionSampleSchedule(
    samples: List<VideoSampleMeta>,
    slowFactor: Float
): List<ScaledVideoSample> {
    if (samples.isEmpty()) return emptyList()
    val effectiveSlowFactor = slowFactor.coerceAtLeast(1.0f)
    val firstPtsUs = samples.first().ptsUs
    val result = ArrayList<ScaledVideoSample>(samples.size)
    var lastOutputPtsUs = -1L

    for (sample in samples) {
        val relativeTimeUs = (sample.ptsUs - firstPtsUs).coerceAtLeast(0L)
        var scaledPtsUs = (relativeTimeUs * effectiveSlowFactor).toLong()
        if (scaledPtsUs <= lastOutputPtsUs) {
            scaledPtsUs = lastOutputPtsUs + 1000L
        }
        lastOutputPtsUs = scaledPtsUs
        result.add(
            ScaledVideoSample(
                sourceIndex = sample.index,
                outputPtsUs = scaledPtsUs,
                isSync = sample.isSync
            )
        )
    }
    return result
}

/**
 * Computes the time-lapse frame subsampling and compressed presentation timestamp schedule for [speed].
 * Subsamples sync frames at `captureIntervalUs = (speed.multiplier * 1_000_000L) / targetOutputFps`
 * (retaining the first and last sync frames, or retaining GOP frames if fewer than 2 sync frames exist)
 * and compresses each kept frame's presentation timestamp by `1.0 / speed.multiplier`.
 */
internal fun computeTimeLapseSampleSchedule(
    samples: List<VideoSampleMeta>,
    speed: TimeLapseSpeed,
    targetOutputFps: Int = TimeLapseSpeed.TARGET_OUTPUT_FPS
): List<ScaledVideoSample> {
    if (samples.isEmpty()) return emptyList()
    val multiplier = speed.multiplier.coerceAtLeast(1)
    val safeTargetFps = targetOutputFps.coerceAtLeast(1)
    val firstPtsUs = samples.first().ptsUs
    val syncSamples = samples.filter { it.isSync }
    val captureIntervalUs = (multiplier.toLong() * 1_000_000L) / safeTargetFps.toLong()

    val selectedSamples = if (syncSamples.size >= 2) {
        val picked = ArrayList<VideoSampleMeta>()
        picked.add(syncSamples.first())
        var lastSelectedPtsUs = syncSamples.first().ptsUs
        for (k in 1 until syncSamples.size) {
            val candidate = syncSamples[k]
            if (candidate.ptsUs - lastSelectedPtsUs >= captureIntervalUs) {
                picked.add(candidate)
                lastSelectedPtsUs = candidate.ptsUs
            }
        }
        if (picked.last().index != syncSamples.last().index) {
            picked.add(syncSamples.last())
        }
        picked
    } else {
        samples
    }

    val result = ArrayList<ScaledVideoSample>(selectedSamples.size)
    var lastOutputPtsUs = -1L
    for (sample in selectedSamples) {
        val relativeTimeUs = (sample.ptsUs - firstPtsUs).coerceAtLeast(0L)
        var scaledPtsUs = (relativeTimeUs.toDouble() / multiplier.toDouble()).toLong()
        if (scaledPtsUs <= lastOutputPtsUs) {
            scaledPtsUs = lastOutputPtsUs + 100L
        }
        lastOutputPtsUs = scaledPtsUs
        result.add(
            ScaledVideoSample(
                sourceIndex = sample.index,
                outputPtsUs = scaledPtsUs,
                isSync = sample.isSync
            )
        )
    }
    return result
}

/**
 * Remuxes a recorded video file into a genuine time-lapse MP4 container by subsampling video frames
 * at the interval required for [speed] and compressing presentation timestamps by `1.0 / speed.multiplier`
 * (e.g. 30x -> 1/30 duration, 60x -> 1/60 duration, ..., 6000x -> 1/6000 duration at 30 FPS output).
 * Preserves video rotation metadata and omits 1x audio so playback timing strictly reflects the
 * selected time-lapse speed multiplier.
 */
internal fun processTimeLapseVideoFile(
    context: Context,
    inputFile: java.io.File,
    speed: TimeLapseSpeed,
    targetOutputFps: Int = TimeLapseSpeed.TARGET_OUTPUT_FPS,
    onProgress: ((Float) -> Unit)? = null
): java.io.File? {
    if (!inputFile.exists() || inputFile.length() == 0L) return null
    val multiplier = speed.multiplier.coerceAtLeast(1)
    var extractor: android.media.MediaExtractor? = null
    var muxer: android.media.MediaMuxer? = null
    var muxerStarted = false
    val outputFile = java.io.File.createTempFile("timelapse_out_${multiplier}x_", ".mp4", context.cacheDir)
    try {
        onProgress?.invoke(0.06f)
        var rotationDegrees = 0
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(inputFile.absolutePath)
            rotationDegrees = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        extractor = android.media.MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        var videoTrackIndex = -1
        var videoFormat: android.media.MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = format
                if (rotationDegrees == 0 && format.containsKey(android.media.MediaFormat.KEY_ROTATION)) {
                    rotationDegrees = runCatching {
                        format.getInteger(android.media.MediaFormat.KEY_ROTATION)
                    }.getOrDefault(0)
                }
                break
            }
        }

        if (videoTrackIndex < 0 || videoFormat == null) {
            outputFile.delete()
            return null
        }

        // Pass 1: Inspect sample timestamps and sync flags to determine interval-subsampled frames
        extractor.selectTrack(videoTrackIndex)
        val sampleMetas = mutableListOf<VideoSampleMeta>()
        var metaIdx = 0
        while (true) {
            val ptsUs = extractor.sampleTime
            if (ptsUs < 0L) break
            val flags = extractor.sampleFlags
            val isSync = (flags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            sampleMetas.add(VideoSampleMeta(index = metaIdx, ptsUs = ptsUs, isSync = isSync))
            metaIdx++
            if (metaIdx % 120 == 0) {
                onProgress?.invoke((0.10f + minOf(0.28f, metaIdx / 4000f)).coerceIn(0.10f, 0.38f))
            }
            if (!extractor.advance()) break
        }
        onProgress?.invoke(0.40f)

        val schedule = computeTimeLapseSampleSchedule(
            samples = sampleMetas,
            speed = speed,
            targetOutputFps = targetOutputFps
        )
        if (schedule.isEmpty()) {
            outputFile.delete()
            return null
        }
        val scheduleBySourceIndex = schedule.associateBy { it.sourceIndex }
        val totalSourceSamples = sampleMetas.size.coerceAtLeast(1)

        // Pass 2: Remux selected samples with compressed time-lapse presentation timestamps
        extractor.seekTo(0L, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        muxer = android.media.MediaMuxer(
            outputFile.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        if (rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            muxer.setOrientationHint(rotationDegrees)
        }

        val muxerVideoTrackIndex = muxer.addTrack(videoFormat)
        muxer.start()
        muxerStarted = true

        val maxInputSize = if (videoFormat.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { videoFormat.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        } else {
            0
        }
        val bufferCapacity = maxOf(maxInputSize, 4 * 1024 * 1024)
        val buffer = java.nio.ByteBuffer.allocateDirect(bufferCapacity)
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        var currentIndex = 0
        var writtenCount = 0
        var lastReportedPct = 40

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val scheduled = scheduleBySourceIndex[currentIndex]
            if (scheduled != null) {
                val sampleFlags = extractor.sampleFlags
                var codecFlags = 0
                if ((sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags = codecFlags or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if ((sampleFlags and android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) {
                    codecFlags = codecFlags or android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }

                bufferInfo.set(0, sampleSize, scheduled.outputPtsUs, codecFlags)
                muxer.writeSampleData(muxerVideoTrackIndex, buffer, bufferInfo)
                writtenCount++
            }

            currentIndex++
            val nextPct = 40 + ((currentIndex * 54) / totalSourceSamples).coerceIn(0, 54)
            if (nextPct >= lastReportedPct + 3) {
                lastReportedPct = nextPct
                onProgress?.invoke(nextPct / 100f)
            }
            if (!extractor.advance()) break
        }

        extractor.unselectTrack(videoTrackIndex)

        if (writtenCount == 0) {
            outputFile.delete()
            return null
        }

        onProgress?.invoke(0.96f)
        muxer.stop()
        muxerStarted = false
        muxer.release()
        muxer = null
        extractor.release()
        extractor = null
        onProgress?.invoke(0.99f)

        return if (outputFile.exists() && outputFile.length() > 0L) outputFile else null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to remux time-lapse video with speed ${speed.shortLabel}", e)
        runCatching { outputFile.delete() }
        return null
    } finally {
        runCatching {
            if (muxerStarted) muxer?.stop()
        }
        runCatching { muxer?.release() }
        runCatching { extractor?.release() }
    }
}

/**
 * Publishes a finalized MP4 file to [MediaStore.Video.Media.EXTERNAL_CONTENT_URI] using the
 * pending-publication flow (`IS_PENDING = 1` -> stream write -> `IS_PENDING = 0`) and returns
 * the verified `content://` URI only if saving genuinely succeeded.
 */
internal fun saveVideoFileToMediaStore(
    context: Context,
    videoFile: java.io.File,
    displayName: String,
    width: Int = 0,
    height: Int = 0,
    durationMs: Long = 0L
): Uri? {
    if (!videoFile.exists() || videoFile.length() == 0L) return null
    val contentResolver = context.contentResolver
    val nowMs = System.currentTimeMillis()
    val nowSec = nowMs / 1000L

    val videoRotation = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0)

    val insertValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.DATE_ADDED, nowSec)
        put(MediaStore.Video.Media.DATE_MODIFIED, nowSec)
        put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
        put(MediaStore.Video.Media.SIZE, videoFile.length())
        if (durationMs > 0L) {
            put(MediaStore.Video.Media.DURATION, durationMs)
        }
        if (width > 0 && height > 0) {
            put(MediaStore.MediaColumns.WIDTH, width)
            put(MediaStore.MediaColumns.HEIGHT, height)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.ORIENTATION, videoRotation)
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }

    var insertedUri: Uri? = null
    return try {
        insertedUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, insertValues)
            ?: return null

        contentResolver.openOutputStream(insertedUri)?.use { outStream ->
            videoFile.inputStream().use { inStream ->
                inStream.copyTo(outStream, bufferSize = 256 * 1024)
            }
            outStream.flush()
        } ?: throw java.io.IOException("Unable to open MediaStore output stream for $insertedUri")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Clear IS_PENDING in a dedicated update so no read-only column can ever block publication
            val publishValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            contentResolver.update(insertedUri, publishValues, null, null)
            if (durationMs > 0L) {
                runCatching {
                    val durationUpdate = ContentValues().apply {
                        put(MediaStore.Video.Media.DURATION, durationMs)
                    }
                    contentResolver.update(insertedUri, durationUpdate, null, null)
                }
            }
        }

        val isReadable = runCatching {
            contentResolver.openInputStream(insertedUri)?.use { stream ->
                stream.read() != -1
            } ?: false
        }.getOrDefault(false)

        if (isReadable) {
            insertedUri
        } else {
            runCatching { contentResolver.delete(insertedUri, null, null) }
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save video file to MediaStore", e)
        insertedUri?.let { uri ->
            runCatching { contentResolver.delete(uri, null, null) }
        }
        null
    }
}

