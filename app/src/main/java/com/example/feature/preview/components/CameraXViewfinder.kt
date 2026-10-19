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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.example.core.preferences.SlowMoSpeed
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.camera.CameraLens
import com.example.core.camera.LensCapabilities
import com.example.core.camera.LensFacing
import com.example.core.camera.ManualSensorCapabilities
import com.example.core.camera.PhotoOutputSize
import com.example.core.camera.VideoFrameRate
import com.example.core.camera.VideoResolution
import com.example.core.preferences.FlashMode
import com.example.core.preferences.HdOutputOption
import com.example.core.preferences.AstroDuration
import com.example.core.preferences.AstroFocusMode
import com.example.core.preferences.AstroStrategy
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
import com.example.core.processing.NightSightProcessingOutcome
import com.example.core.processing.NightSightProcessor
import com.example.core.processing.PortraitProcessingOutcome
import com.example.core.processing.PortraitProcessor
import java.util.concurrent.TimeUnit

private const val TAG = "CameraXViewfinder"

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
    val stabilization: VideoStabilizationMode
)

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
    hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE,
    astroDuration: AstroDuration = AstroDuration.AUTO,
    astroFocus: AstroFocusMode = AstroFocusMode.AUTO,
    astroStrategy: AstroStrategy = AstroStrategy.AUTO,
    slowMoSpeed: SlowMoSpeed = SlowMoSpeed.SPEED_1_4X,
    timeLapseSpeed: TimeLapseSpeed = TimeLapseSpeed.SPEED_30X,
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
    onCaptureError: ((String) -> Unit)? = null,
    onRecordingStateChanged: ((Boolean) -> Unit)? = null,
    onRecordingDurationChanged: ((Long) -> Unit)? = null,
    onRecordingSuccess: ((Uri) -> Unit)? = null,
    onRecordingError: ((String) -> Unit)? = null,
    onHistogramUpdated: ((LiveHistogramData?) -> Unit)? = null,
    onFocusPeakingUpdated: ((FocusPeakingFrame?) -> Unit)? = null,
    onCameraError: ((String) -> Unit)? = null,
    onCountdownStateChanged: ((Boolean, Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var boundImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundVideoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var lastBoundConfig by remember { mutableStateOf<BoundPipelineConfig?>(null) }
    var cachedCapabilities by remember { mutableStateOf<Map<CameraLens, LensCapabilities>>(emptyMap()) }
    val cachedAeRanges = remember { mutableMapOf<CameraLens, Array<Range<Int>>>() }
    var isCapturing by remember { mutableStateOf(false) }
    val isCaptureInFlight = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var isCountingDown by remember { mutableStateOf(false) }
    var countdownRemainingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(isCountingDown, countdownRemainingSeconds) {
        onCountdownStateChanged?.invoke(isCountingDown, countdownRemainingSeconds)
    }
    var showShutterFlashOverlay by remember { mutableStateOf(false) }
    var isHardwareAfLockActive by remember { mutableStateOf(false) }
    val latestPortraitBlurLevel by rememberUpdatedState(portraitBlurLevel)
    val latestPortraitBlurStrength by rememberUpdatedState(portraitBlurStrength)
    val latestPortraitRetouch by rememberUpdatedState(portraitRetouch)
    val latestNightSightExposure by rememberUpdatedState(nightSightExposure)
    val latestHdrMode by rememberUpdatedState(hdrMode)
    val latestFocusTouchRequest by rememberUpdatedState(focusTouchRequest)
    val latestAutoExposureIndex by rememberUpdatedState(autoExposureIndex)
    val latestActiveMode by rememberUpdatedState(activeMode)
    val latestSlowMoSpeed by rememberUpdatedState(slowMoSpeed)
    val latestTimeLapseSpeed by rememberUpdatedState(timeLapseSpeed)
    val latestPanoramaDirection by rememberUpdatedState(panoramaDirection)
    val latestIsPanoramaCapturingRequested by rememberUpdatedState(isPanoramaCapturingRequested)
    val latestOnPanoramaStateChanged by rememberUpdatedState(onPanoramaStateChanged)
    val latestSelectedFilter by rememberUpdatedState(selectedFilter)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val photoCaptureExecutor = remember { Executors.newSingleThreadExecutor() }
    val companionCaptureExecutor = remember { Executors.newSingleThreadExecutor() }
    val panoramaKeyframes = remember { java.util.Collections.synchronizedList(mutableListOf<android.graphics.Bitmap>()) }
    val isPanoramaProcessingInFlight = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var autoFinishSweepCounter by remember { mutableIntStateOf(0) }

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
            panoramaAnalyzer.stopSession()
            clearPanoramaKeyframes()
            analysisExecutor.shutdown()
            photoCaptureExecutor.shutdown()
            companionCaptureExecutor.shutdown()
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
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            isClickable = false
            isFocusable = false
        }
    }

    val effectivePreviewFilter = com.example.core.processing.CameraFilterProcessor.resolveEffectiveFilter(activeMode, selectedFilter)

    LaunchedEffect(effectivePreviewFilter) {
        val colorFilter = com.example.core.processing.CameraFilterProcessor.createColorMatrixColorFilter(effectivePreviewFilter)
        if (colorFilter == null) {
            previewView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
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
        }
        previewView.invalidate()
    }

    // Observe lifecycle events to ensure countdown/panorama cancels and torch turns off on pause/stop
    DisposableEffect(lifecycleOwner, activeCamera) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                isCountingDown = false
                countdownRemainingSeconds = 0
                isCapturing = false
                isCaptureInFlight.set(false)
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
                try {
                    activeCamera?.cameraControl?.enableTorch(false)
                } catch (_: Exception) {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                activeCamera?.cameraControl?.enableTorch(false)
            } catch (_: Exception) {}
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

    // Rebind CameraX pipeline when activeLens, activeMode, or video/slow-motion/time-lapse pipeline config changes
    LaunchedEffect(
        activeLens,
        activeMode,
        videoResolution,
        videoFrameRate,
        videoStabilization,
        slowMoSpeed,
        timeLapseSpeed
    ) {
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

                // Discover real capabilities across both rear and front cameras once using Camera2 + CameraX VideoCapabilities
                val discoveredCapabilities = if (cachedCapabilities.isNotEmpty()) {
                    cachedCapabilities
                } else {
                    val queried = queryDeviceCameraCapabilities(context, cameraProvider)
                    if (queried.isNotEmpty()) {
                        cachedCapabilities = queried
                        onCapabilitiesDiscovered?.invoke(queried)
                    }
                    queried
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
                        stabilization = if (isSlowMo) VideoStabilizationMode.OFF else reconciled.stabilization
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
                        stabilization = VideoStabilizationMode.OFF
                    )
                }

                // Avoid duplicate camera bindings if the exact target pipeline is already bound
                if (lastBoundConfig == desiredBindConfig && activeCamera != null) {
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                cameraProvider.unbindAll()

                var boundCam: Camera? = null
                var boundImgCap: ImageCapture? = null
                var boundVidCap: VideoCapture<Recorder>? = null
                var effectiveBoundReconciled = reconciled

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
                    val targetQuality = targetVideoRes.toCameraXQuality()
                    val qualitySelector = QualitySelector.from(
                        targetQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(targetQuality)
                    )

                    val recorder = Recorder.Builder()
                        .setQualitySelector(qualitySelector)
                        .build()

                    val videoCaptureBuilder = VideoCapture.Builder(recorder)
                        .setTargetFrameRate(targetFpsRange)
                        .setVideoStabilizationEnabled(
                            reconciled.stabilization == VideoStabilizationMode.STANDARD ||
                                reconciled.stabilization == VideoStabilizationMode.ACTIVE
                        )

                    // Configure Camera2Interop options for exact FPS range and stabilization mode
                    val camera2Extender = Camera2Interop.Extender(videoCaptureBuilder)
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        targetFpsRange
                    )
                    when (reconciled.stabilization) {
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
                            if (activeLensCaps?.isStabilizationSupported == true) {
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
                        VideoStabilizationMode.ACTIVE -> {
                            if (activeLensCaps?.isStabilizationSupported == true) {
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

                    val configuredVideoCapture = videoCaptureBuilder.build()

                    try {
                        // In Video mode, prioritize Preview + VideoCapture so 4K UHD, 60 FPS, and
                        // Stabilization are never throttled by an unused 3rd JPEG stream.
                        boundCam = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            configuredVideoCapture
                        )
                        boundVidCap = configuredVideoCapture
                        Log.d(
                            TAG,
                            "Bound Video pipeline (${reconciled.resolution.displayName} @ ${reconciled.frameRate.displayName}, Stab=${reconciled.stabilization.displayName}) on ${activeLens.displayName}"
                        )
                    } catch (excVideo: Exception) {
                        Log.w(TAG, "Primary video configuration failed to bind, attempting safe 1080p/720p 30 FPS fallback", excVideo)
                        try {
                            val fallbackRecorder = Recorder.Builder().build()
                            val fallbackFpsRange = selectBestTargetFpsRange(aeRanges, 30)
                            val fallbackVideoCapture = VideoCapture.Builder(fallbackRecorder)
                                .setTargetFrameRate(fallbackFpsRange)
                                .setVideoStabilizationEnabled(false)
                                .build()
                            boundCam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
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
                } else {
                    val preferMaxNative = activeMode == CameraMode.HD && hdOutputOption == HdOutputOption.MAX_NATIVE
                    val maxPhotoSize = if (preferMaxNative) {
                        activeLensCaps?.highestNativeStillSize ?: activeLensCaps?.selectOptimalPhotoOutputSize(aspectRatioMode, preferMaxNative = true)
                    } else {
                        activeLensCaps?.selectOptimalPhotoOutputSize(aspectRatioMode, preferMaxNative = activeMode == CameraMode.HD)
                    }
                    val resolutionSelectorBuilder = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                        )
                    if (maxPhotoSize != null && maxPhotoSize.width > 0 && maxPhotoSize.height > 0) {
                        resolutionSelectorBuilder.setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                Size(maxPhotoSize.width, maxPhotoSize.height),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                    } else {
                        resolutionSelectorBuilder.setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                        )
                    }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(if (activeMode == CameraMode.HD) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(95)
                        .setResolutionSelector(resolutionSelectorBuilder.build())
                        .build()

                    try {
                        if (activeMode == CameraMode.PRO) {
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .setTargetResolution(Size(320, 240))
                                .build()
                            imageAnalysis.setAnalyzer(analysisExecutor, proAnalyzer)

                            boundCam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture,
                                imageAnalysis
                            )
                            boundImgCap = imageCapture
                            Log.d(TAG, "Successfully bound Preview + ImageCapture + ImageAnalysis for Pro Mode")
                        } else if (activeMode == CameraMode.PANORAMA) {
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .setTargetResolution(Size(960, 720))
                                .build()
                            imageAnalysis.setAnalyzer(analysisExecutor, panoramaAnalyzer)

                            boundCam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture,
                                imageAnalysis
                            )
                            boundImgCap = imageCapture
                            Log.d(TAG, "Successfully bound Preview + ImageCapture + ImageAnalysis for Panorama Mode")
                        } else {
                            boundCam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                            boundImgCap = imageCapture
                            Log.d(TAG, "Successfully bound Preview + ImageCapture")
                        }
                    } catch (exc2: Exception) {
                        Log.e(TAG, "Failed to bind photo camera use cases, attempting fallback", exc2)
                        try {
                            boundCam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                            boundImgCap = imageCapture
                        } catch (excFallback: Exception) {
                            Log.e(TAG, "Failed to bind fallback camera", excFallback)
                            onCameraError?.invoke("Failed to initialize camera: ${excFallback.localizedMessage}")
                        }
                    }
                }

                activeCamera = boundCam
                boundImageCapture = boundImgCap
                boundVideoCapture = boundVidCap

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

    // Execute tap-to-focus (AF) and auto-exposure metering (AE) and tap-and-hold focus lock on the active camera in all non-Pro modes
    LaunchedEffect(activeCamera, activeLens, activeMode, focusTouchRequest) {
        val camera = activeCamera ?: return@LaunchedEffect
        val request = focusTouchRequest
        if (request == null || !isTapToFocusEnabledForMode(activeMode)) {
            isHardwareAfLockActive = false
            try {
                camera.cameraControl.cancelFocusAndMetering()
            } catch (_: Exception) {}
            return@LaunchedEffect
        }

        // Release any previous Camera2 AF hold before initiating a new AF + AE metering sweep
        if (isHardwareAfLockActive) {
            isHardwareAfLockActive = false
        }

        // Reset hardware exposure compensation to neutral (index 0) on every new non-Pro focus or focus-lock point
        val expState = camera.cameraInfo.exposureState
        val evRange = expState.exposureCompensationRange.let { it.lower..it.upper }
        val isEvCompSupported = expState.isExposureCompensationSupported && (evRange.start < 0 || evRange.endInclusive > 0)
        val isModeFocusEvSupported = isEvCompSupported && isFocusEvSliderEnabledForMode(activeMode)
        if (expState.isExposureCompensationSupported && 0 in evRange) {
            try {
                val evResetFuture = camera.cameraControl.setExposureCompensationIndex(0)
                evResetFuture.addListener({
                    try {
                        val appliedIdx = evResetFuture.get()
                        if (isFocusEvSliderEnabledForMode(latestActiveMode) && latestAutoExposureIndex == 0) {
                            onExposureIndexApplied?.invoke(appliedIdx)
                        }
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset exposure compensation to 0 on new focus request", e)
            }
        }

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
            val viewX = ((normalizedCoord.clampedPixelX / request.previewWidth.coerceAtLeast(1f)) * previewView.width)
                .coerceIn(0f, previewView.width.toFloat())
            val viewY = ((normalizedCoord.clampedPixelY / request.previewHeight.coerceAtLeast(1f)) * previewView.height)
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
                        // Perform AF and AE metering at the selected point first; then apply focus-lock behavior
                        if (verifiedAfAe.isAfSupported && verifiedAfAe.isFocusLockSupported) {
                            isHardwareAfLockActive = true
                            onFocusStatusChanged?.invoke(
                                request.requestId,
                                if (result.isFocusSuccessful) FocusIndicatorStatus.LOCKED else FocusIndicatorStatus.LOCKED
                            )
                        } else if (verifiedAfAe.isAeSupported || isModeFocusEvSupported) {
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

    // Apply manual Pro controls (ISO, Shutter, Focus, WB), Focus Lock AF hold (non-Pro modes), or Video AE FPS & Stabilization options to Camera2 capture request
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
        portraitBlurLevel,
        portraitBlurStrength,
        nightSightExposure,
        autoExposureIndex,
        proEv
    ) {
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
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
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
                    }
                    VideoStabilizationMode.STANDARD, VideoStabilizationMode.ACTIVE -> {
                        if (lensCaps.isStabilizationSupported) {
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
                } else {
                    optionsBuilder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }
                camera2Control.clearCaptureRequestOptions()
                camera2Control.setCaptureRequestOptions(optionsBuilder.build())
                return@LaunchedEffect
            }

            if (activeMode == CameraMode.PRO) {
                // Apply manual ISO, shutter speed, manual focus distance, white balance, and EV
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

            // Non-Pro still modes: restore continuous automatic AE/AF/AWB and synchronize active exposureCompensationIndex
            val enableHardwareHdr = activeMode == CameraMode.PHOTO &&
                hdrMode != com.example.core.preferences.HdrMode.OFF &&
                (lensCaps?.isHardwareHdrSceneModeSupported == true) &&
                effectiveNonProEvIndex == 0
            val enableHardwareNightScene = activeMode == CameraMode.NIGHT_SIGHT &&
                nightSightExposure != NightSightExposure.OFF &&
                (lensCaps?.isHardwareNightSceneModeSupported == true)
            val enableHardwarePortraitBokeh = activeMode == CameraMode.PORTRAIT &&
                portraitBlurLevel != PortraitBlurLevel.OFF &&
                portraitBlurStrength > 0.0f &&
                (lensCaps?.isHardwareBokehSupported == true)
            val enableFaceDetect = activeMode == CameraMode.PORTRAIT &&
                ((lensCaps?.manualCapabilities?.maxFaceDetectCount ?: 0) > 0)
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
                exposureCompensationIndex = effectiveNonProEvIndex
            )
            camera2Control.clearCaptureRequestOptions()
            camera2Control.setCaptureRequestOptions(resetOptions)
            Log.d(
                TAG,
                "Applied non-Pro Camera2 settings for ${activeMode.name} on ${activeLens.displayName} (evIndex=$effectiveNonProEvIndex, hardwareHdr=$enableHardwareHdr, nightScene=$enableHardwareNightScene, portraitBokeh=$enableHardwarePortraitBokeh)"
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

        if (!isCaptureInFlight.compareAndSet(false, true)) {
            return@LaunchedEffect
        }
        isCapturing = true
        showShutterFlashOverlay = true

        val mainExecutor = ContextCompat.getMainExecutor(context)
        val captureSelectedFilter = com.example.core.processing.CameraFilterProcessor.resolveEffectiveFilter(captureMode, latestSelectedFilter)

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
                            onCaptureError?.invoke("Failed to read captured image buffer: ${e.localizedMessage}")
                        }
                        return
                    } finally {
                        // Release camera buffer immediately before decoding/saving
                        try { imageProxy.close() } catch (_: Exception) {}
                    }

                    try {
                        if (captureSoundsEnabled) {
                            runCatching {
                                val sound = android.media.MediaActionSound()
                                sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                            }
                        }

                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
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
                        val processedPayload = if (captureMode == CameraMode.PORTRAIT) {
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
                                    jpegQuality = 95
                                )
                            ) {
                                is PortraitProcessingOutcome.ProcessedPortrait -> {
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
                            val maxDeviceFrames = com.example.core.camera.NightSightDeviceSupport.getMaxSupportedFrames(context)
                            val desiredFrames = when (captureNightSightExposure) {
                                NightSightExposure.OFF -> 1
                                NightSightExposure.AUTO -> minOf(
                                    com.example.core.camera.NightSightDeviceSupport.DEFAULT_AUTO_FRAMES,
                                    maxDeviceFrames
                                )
                                NightSightExposure.MAX -> maxDeviceFrames
                            }

                            val companionFrames = mutableListOf<ByteArray>()
                            if (desiredFrames > 1) {
                                for (frameIndex in 1 until desiredFrames) {
                                    if (activeLens != captureLens || activeMode != captureMode) {
                                        Log.d(TAG, "Night Sight companion frame capture cancelled due to mode/lens change")
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
                                                        if (!timedOut.get()) {
                                                            val buf = companionProxy.planes[0].buffer
                                                            val arr = ByteArray(buf.remaining())
                                                            buf.get(arr)
                                                            companionBytes = arr
                                                        }
                                                    } catch (e: Exception) {
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
                                        val completedInTime = latch.await(450L, TimeUnit.MILLISECONDS)
                                        if (!completedInTime) {
                                            timedOut.set(true)
                                            break
                                        }
                                        val capturedArr = companionBytes
                                        if (companionFailed || capturedArr == null || capturedArr.isEmpty()) {
                                            break
                                        }
                                        companionFrames.add(capturedArr)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "Night Sight companion frame $frameIndex skipped: ${e.localizedMessage}")
                                        break
                                    }
                                }
                            }

                            val supportPath = cachedCapabilities[captureLens]?.nightSightSupportPath
                                ?: com.example.core.camera.NightSightDeviceSupport.resolveNightSightSupportPath(
                                    isHardwareNightSceneSupported = false,
                                    context = context
                                )

                            when (
                                val outcome = NightSightProcessor.processNightSightJpegBytes(
                                    sourceBytes = bytes,
                                    additionalFramesBytes = companionFrames,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    exposure = captureNightSightExposure,
                                    supportPath = supportPath,
                                    jpegQuality = 95
                                )
                            ) {
                                is NightSightProcessingOutcome.ProcessedNightSight -> {
                                    com.example.core.processing.ProcessedPhotoPayload(
                                        jpegBytes = outcome.jpegBytes,
                                        width = outcome.width,
                                        height = outcome.height,
                                        exifOrientation = outcome.exifOrientation,
                                        wasRecompressed = true
                                    )
                                }
                                is NightSightProcessingOutcome.FallbackSingleFrame -> {
                                    if (captureNightSightExposure != NightSightExposure.OFF) {
                                        portraitFallbackReason = outcome.reason
                                    }
                                    if (outcome.width > 0 && outcome.height > 0) {
                                        com.example.core.processing.ProcessedPhotoPayload(
                                            jpegBytes = outcome.jpegBytes,
                                            width = outcome.width,
                                            height = outcome.height,
                                            exifOrientation = outcome.exifOrientation,
                                            wasRecompressed = true
                                        )
                                    } else {
                                        null
                                    }
                                }
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
                                desiredFrames = captureAstroDuration.targetFrames
                            }

                            Log.d(
                                TAG,
                                "Astro Capture Initiated: Requested=${captureAstroDuration.displayName} (${requestedSeconds}s), " +
                                "Lens=${captureLens.displayName}, ManualShutter=$isManualShutterSupported, " +
                                "MaxSensorExposureMs=${maxExposureNanos / 1_000_000L}ms, TargetFrames=$desiredFrames, " +
                                "PerFrameExpMs=${perFrameExposureMs}ms"
                            )

                            val companionFrames = mutableListOf<ByteArray>()
                            if (desiredFrames > 1) {
                                for (frameIndex in 1 until desiredFrames) {
                                    if (activeLens != captureLens || activeMode != captureMode) {
                                        Log.d(TAG, "Astro companion frame capture cancelled")
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
                                                        if (!timedOut.get()) {
                                                            val buf = companionProxy.planes[0].buffer
                                                            val arr = ByteArray(buf.remaining())
                                                            buf.get(arr)
                                                            companionBytes = arr
                                                        }
                                                    } catch (e: Exception) {
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
                                        val completedInTime = latch.await(2500L, TimeUnit.MILLISECONDS)
                                        if (!completedInTime) {
                                            timedOut.set(true)
                                            break
                                        }
                                        val capturedArr = companionBytes
                                        if (companionFailed || capturedArr == null || capturedArr.isEmpty()) {
                                            break
                                        }
                                        companionFrames.add(capturedArr)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "Astro companion frame $frameIndex skipped: ${e.localizedMessage}")
                                        break
                                    }
                                }
                            }

                            val astroTotalAcquisitionMs = maxOf(1L, System.currentTimeMillis() - astroStartAcquisitionMs)

                            when (
                                val outcome = com.example.core.processing.AstroProcessor.processAstroJpegBytes(
                                    sourceBytes = bytes,
                                    additionalFramesBytes = companionFrames,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    duration = captureAstroDuration,
                                    perFrameExposureMs = if (perFrameExposureMs > 0) perFrameExposureMs else (astroTotalAcquisitionMs / maxOf(1, companionFrames.size + 1)),
                                    totalAcquisitionMs = astroTotalAcquisitionMs,
                                    jpegQuality = 95
                                )
                            ) {
                                is com.example.core.processing.AstroProcessingOutcome.ProcessedAstro -> {
                                    com.example.core.processing.ProcessedPhotoPayload(
                                        jpegBytes = outcome.jpegBytes,
                                        width = outcome.width,
                                        height = outcome.height,
                                        exifOrientation = outcome.exifOrientation,
                                        wasRecompressed = true
                                    )
                                }
                                is com.example.core.processing.AstroProcessingOutcome.FallbackNightSight -> {
                                    com.example.core.processing.ProcessedPhotoPayload(
                                        jpegBytes = outcome.jpegBytes,
                                        width = outcome.width,
                                        height = outcome.height,
                                        exifOrientation = outcome.exifOrientation,
                                        wasRecompressed = true
                                    )
                                }
                            }
                        } else if (captureMode == CameraMode.PHOTO) {
                            val captureHdrMode = latestHdrMode
                            if (captureHdrMode != com.example.core.preferences.HdrMode.OFF) {
                                val companionFrames = mutableListOf<ByteArray>()
                                val targetCompanionFrames = 2
                                for (frameIndex in 1..targetCompanionFrames) {
                                    if (activeLens != captureLens || activeMode != captureMode) {
                                        Log.d(TAG, "HDR companion frame capture cancelled due to mode/lens change")
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
                                                        if (!timedOut.get()) {
                                                            val buf = companionProxy.planes[0].buffer
                                                            val arr = ByteArray(buf.remaining())
                                                            buf.get(arr)
                                                            companionBytes = arr
                                                        }
                                                    } catch (e: Exception) {
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
                                        val completedInTime = latch.await(450L, TimeUnit.MILLISECONDS)
                                        if (!completedInTime) {
                                            timedOut.set(true)
                                            break
                                        }
                                        val capturedArr = companionBytes
                                        if (companionFailed || capturedArr == null || capturedArr.isEmpty()) {
                                            break
                                        }
                                        companionFrames.add(capturedArr)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "HDR companion frame $frameIndex skipped: ${e.localizedMessage}")
                                        break
                                    }
                                }

                                val supportPath = cachedCapabilities[captureLens]?.hdrSupportPath
                                    ?: com.example.core.camera.HdrSupportPath.SOFTWARE_MULTI_FRAME_HDR

                                when (
                                    val outcome = com.example.core.processing.HdrProcessor.processHdrJpegBytes(
                                        sourceBytes = bytes,
                                        additionalFramesBytes = companionFrames,
                                        rotationDegrees = rotationDegrees,
                                        aspectRatioMode = captureAspectRatio,
                                        mirrorHorizontally = captureSelfieMirror,
                                        hdrMode = captureHdrMode,
                                        supportPath = supportPath,
                                        jpegQuality = 95
                                    )
                                ) {
                                    is com.example.core.processing.HdrProcessingOutcome.ProcessedHdr -> {
                                        com.example.core.processing.ProcessedPhotoPayload(
                                            jpegBytes = outcome.jpegBytes,
                                            width = outcome.width,
                                            height = outcome.height,
                                            exifOrientation = outcome.exifOrientation,
                                            wasRecompressed = true
                                        )
                                    }
                                    is com.example.core.processing.HdrProcessingOutcome.FallbackSingleFrame -> {
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
                                                jpegQuality = 95
                                            )
                                        }
                                    }
                                }
                            } else {
                                AspectCropUtils.processCapturedJpegBytes(
                                    sourceBytes = bytes,
                                    rotationDegrees = rotationDegrees,
                                    aspectRatioMode = captureAspectRatio,
                                    mirrorHorizontally = captureSelfieMirror,
                                    jpegQuality = 95
                                )
                            }
                        } else {
                            AspectCropUtils.processCapturedJpegBytes(
                                sourceBytes = bytes,
                                rotationDegrees = rotationDegrees,
                                aspectRatioMode = captureAspectRatio,
                                mirrorHorizontally = captureSelfieMirror,
                                jpegQuality = 95
                            )
                        }

                        if (processedPayload == null) {
                            isCaptureInFlight.set(false)
                            mainExecutor.execute {
                                isCapturing = false
                                onCaptureError?.invoke("Failed to decode captured image.")
                            }
                            return
                        }

                        val finalPhotoPayload = if (!isDngOnly && !captureSelectedFilter.isOriginal) {
                            com.example.core.processing.CameraFilterProcessor.applyFilterToPhotoPayload(
                                payload = processedPayload,
                                preset = captureSelectedFilter,
                                jpegQuality = 95
                            )
                        } else {
                            processedPayload
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
                                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelCamera")
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
                                location = location
                            )?.uri
                        }

                        if (savedUri != null) {
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
                                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelCamera")
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

                            isCaptureInFlight.set(false)
                            mainExecutor.execute {
                                isCapturing = false
                                onCaptureSuccess?.invoke(savedUri)
                                portraitFallbackReason?.let { reason ->
                                    onCaptureError?.invoke(reason)
                                }
                            }
                        } else {
                            isCaptureInFlight.set(false)
                            mainExecutor.execute {
                                isCapturing = false
                                onCaptureError?.invoke("Failed to create MediaStore entry.")
                            }
                        }
                    } catch (e: Exception) {
                        isCaptureInFlight.set(false)
                        Log.e(TAG, "Error processing captured photo", e)
                        mainExecutor.execute {
                            isCapturing = false
                            onCaptureError?.invoke("Photo processing failed: ${e.localizedMessage}")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCaptureInFlight.set(false)
                    Log.e(TAG, "Photo capture failed", exception)
                    mainExecutor.execute {
                        isCapturing = false
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

                // Coordinate sensor-measured progress with keyframe acquisition
                val currentFrames = panoramaKeyframes.size
                if (panoramaAnalyzer.motionTracker.shouldAcquireNextKeyframe(currentFrames, lastSensorKeyframeProgress)) {
                    lastSensorKeyframeProgress = panoramaAnalyzer.motionTracker.currentProgress()
                    panoramaAnalyzer.requestManualStepCapture()
                    // Also acquire from preview surface if ImageAnalysis hasn't delivered the slice yet
                    val previewBmp = runCatching { previewView.bitmap }.getOrNull()
                    if (previewBmp != null && previewBmp.width > 16 && previewBmp.height > 16 &&
                        panoramaKeyframes.size < PanoramaProcessor.DEFAULT_TARGET_FRAMES
                    ) {
                        panoramaKeyframes.add(previewBmp)
                        panoramaAnalyzer.recordExternalKeyframe(lastSensorKeyframeProgress)
                    }
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
            // Capture anchor frame #1 at the starting orientation (progress = 0.0f)
            val initialPreviewBmp = runCatching { previewView.bitmap }.getOrNull()
            if (initialPreviewBmp != null && initialPreviewBmp.width > 16 && initialPreviewBmp.height > 16) {
                panoramaKeyframes.add(initialPreviewBmp)
            }
            val currentDisplayRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            panoramaAnalyzer.startSession(
                selectedDirection = panoramaDirection,
                targetFrameCount = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
                screenRotation = currentDisplayRotation
            )
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
                    jpegQuality = 94
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
                                jpegQuality = 94
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

    // Control video recording lifecycle and state transitions
    LaunchedEffect(isRecordingRequested, boundVideoCapture) {
        val capture = boundVideoCapture
        if (isRecordingRequested) {
            if (activeRecording != null) return@LaunchedEffect
            if (capture == null) {
                onRecordingError?.invoke("Video capture is not supported or initialized on this lens.")
                onRecordingStateChanged?.invoke(false)
                return@LaunchedEffect
            }

            try {
                val recordingMode = latestActiveMode
                val recordingSlowMoSpeed = latestSlowMoSpeed
                val recordingTimeLapseSpeed = latestTimeLapseSpeed
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

                // Enable audio in standard Video mode; in Slow Motion and Time Lapse modes, omit 1x audio
                // so the remuxed video track exclusively drives container duration and playback timing.
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

                val mainExecutor = ContextCompat.getMainExecutor(context)
                val recording = pendingRecording.start(mainExecutor) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            onRecordingStateChanged?.invoke(true)
                        }
                        is VideoRecordEvent.Status -> {
                            val nanos = recordEvent.recordingStats.recordedDurationNanos
                            val seconds = nanos / 1_000_000_000L
                            onRecordingDurationChanged?.invoke(seconds)
                        }
                        is VideoRecordEvent.Finalize -> {
                            onRecordingStateChanged?.invoke(false)
                            activeRecording = null

                            val hasValidTempFile = tempRecordFile.exists() && tempRecordFile.length() > 0L
                            if (!recordEvent.hasError() && hasValidTempFile) {
                                companionCaptureExecutor.execute {
                                    var finalVideoFile: java.io.File? = null
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
                                                val processedFile = processTimeLapseVideoFile(
                                                    context = context,
                                                    inputFile = tempRecordFile,
                                                    speed = recordingTimeLapseSpeed
                                                )
                                                runCatching { tempRecordFile.delete() }
                                                processedFile
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
                                            mainExecutor.execute {
                                                val modeLabel = if (recordingMode == CameraMode.TIME_LAPSE) "time-lapse" else "slow-motion"
                                                onRecordingError?.invoke("Failed to finalize $modeLabel video output.")
                                            }
                                            return@execute
                                        }

                                        // Verify actual recorded video resolution, duration, and frame-rate metadata from finalized file
                                        var verifiedWidth = 0
                                        var verifiedHeight = 0
                                        var verifiedFps = 0f
                                        var durationMs = 0L
                                        val retriever = android.media.MediaMetadataRetriever()
                                        try {
                                            retriever.setDataSource(finalVideoFile.absolutePath)
                                            verifiedWidth = retriever.extractMetadata(
                                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                                            )?.toIntOrNull() ?: 0
                                            verifiedHeight = retriever.extractMetadata(
                                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                                            )?.toIntOrNull() ?: 0
                                            val captureFps = retriever.extractMetadata(
                                                android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                                            )?.toFloatOrNull()
                                            durationMs = retriever.extractMetadata(
                                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                                            )?.toLongOrNull() ?: 0L
                                            val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                retriever.extractMetadata(
                                                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
                                                )?.toLongOrNull() ?: 0L
                                            } else 0L
                                            verifiedFps = when {
                                                captureFps != null && captureFps > 0f -> captureFps
                                                durationMs > 0L && frameCount > 0L -> (frameCount * 1000f) / durationMs
                                                else -> 0f
                                            }
                                            Log.d(
                                                TAG,
                                                "Verified finalized video: ${verifiedWidth}x${verifiedHeight}, duration=${durationMs}ms @ ${String.format(Locale.US, "%.1f", verifiedFps)} FPS (mode=${recordingMode.name})"
                                            )
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Unable to read finalized video metadata", e)
                                        } finally {
                                            runCatching { retriever.release() }
                                        }

                                        val savedMediaStoreUri = saveVideoFileToMediaStore(
                                            context = context,
                                            videoFile = finalVideoFile,
                                            displayName = displayName,
                                            width = verifiedWidth,
                                            height = verifiedHeight,
                                            durationMs = durationMs
                                        )
                                        runCatching { finalVideoFile.delete() }

                                        mainExecutor.execute {
                                            if (savedMediaStoreUri != null && savedMediaStoreUri != Uri.EMPTY) {
                                                onRecordingSuccess?.invoke(savedMediaStoreUri)
                                            } else {
                                                onRecordingError?.invoke("Failed to save recorded video to MediaStore.")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error finalizing and saving video", e)
                                        runCatching { tempRecordFile.delete() }
                                        runCatching { finalVideoFile?.delete() }
                                        mainExecutor.execute {
                                            onRecordingError?.invoke("Failed to save recorded video: ${e.localizedMessage}")
                                        }
                                    }
                                }
                            } else {
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
                onRecordingError?.invoke("Failed to start video recording: ${e.localizedMessage}")
                onRecordingStateChanged?.invoke(false)
                activeRecording = null
            }
        } else {
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
 * Queries Camera2 CameraCharacteristics and CameraX VideoCapabilities to discover
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

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: emptyArray()

            val photoSizes = outputSizes.map { size ->
                PhotoOutputSize(width = size.width, height = size.height)
            }.sortedByDescending { it.totalPixels }

            val highestNativeSize = photoSizes.firstOrNull()?.copy(isSensorMaxResolution = true)

            // Discover video output sizes and per-resolution minimum frame durations
            val rawVideoSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)
                ?: map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?: emptyArray()

            val minFrameDurationByRes = mutableMapOf<VideoResolution, Long>()
            for (res in VideoResolution.VIDEO_MODE_OPTIONS) {
                val matchingSize = rawVideoSizes.firstOrNull { size ->
                    (size.width == res.width && size.height == res.height) ||
                        (size.width == res.height && size.height == res.width)
                }
                if (matchingSize != null && map != null) {
                    val minDur = try {
                        map.getOutputMinFrameDuration(android.media.MediaRecorder::class.java, matchingSize)
                    } catch (_: Exception) {
                        0L
                    }
                    minFrameDurationByRes[res] = minDur
                }
            }

            // Query CameraX Recorder.getVideoCapabilities for this camera selector when cameraProvider is available
            val cameraXSupportedResolutions: Set<VideoResolution>? = try {
                if (cameraProvider != null) {
                    val selector = if (lens == CameraLens.FRONT) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    val matchingCameraInfo = selector.filter(cameraProvider.availableCameraInfos).firstOrNull()
                    if (matchingCameraInfo != null) {
                        val videoCaps = Recorder.getVideoCapabilities(matchingCameraInfo)
                        val qualities = videoCaps.getSupportedQualities(DynamicRange.SDR)
                        val resolvedSet = mutableSetOf<VideoResolution>()
                        for (q in qualities) {
                            val profile = try {
                                videoCaps.getProfiles(q, DynamicRange.SDR)?.defaultVideoProfile
                            } catch (_: Exception) {
                                null
                            }
                            if (profile != null) {
                                val matchedByDim = VideoResolution.VIDEO_MODE_OPTIONS.firstOrNull { res ->
                                    (profile.width == res.width && profile.height == res.height) ||
                                        (profile.width == res.height && profile.height == res.width)
                                }
                                if (matchedByDim != null) {
                                    resolvedSet.add(matchedByDim)
                                }
                            }
                            q.toVideoResolution()?.let { resolvedSet.add(it) }
                        }
                        resolvedSet
                    } else null
                } else {
                    val camIdInt = id.toIntOrNull()
                    if (camIdInt != null) {
                        buildSet {
                            if (android.media.CamcorderProfile.hasProfile(camIdInt, android.media.CamcorderProfile.QUALITY_720P)) {
                                add(VideoResolution.HD_720P)
                            }
                            if (android.media.CamcorderProfile.hasProfile(camIdInt, android.media.CamcorderProfile.QUALITY_1080P)) {
                                add(VideoResolution.FHD_1080P)
                            }
                            if (android.media.CamcorderProfile.hasProfile(camIdInt, android.media.CamcorderProfile.QUALITY_2160P)) {
                                add(VideoResolution.UHD_4K)
                            }
                        }.takeIf { it.isNotEmpty() }
                    } else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query CameraX VideoCapabilities for ${lens.displayName}", e)
                null
            }

            val aeFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                ?: emptyList()

            val supportedVideoQualities = discoverVideoQualitiesFromHardware(
                videoOutputSizes = rawVideoSizes.toList(),
                minFrameDurationNanosByResolution = minFrameDurationByRes,
                aeFpsRanges = aeFpsRanges,
                cameraXSupportedResolutions = cameraXSupportedResolutions
            )

            // Discover real stabilization capabilities
            val availableVideoStabModes = characteristics.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            ) ?: IntArray(0)
            val availableOisModes = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            ) ?: IntArray(0)

            val hasDigitalVideoStab = availableVideoStabModes.any {
                it == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON
            }
            val hasOpticalStab = availableOisModes.any {
                it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON
            }
            val isActiveStabSupported = hasOpticalStab && hasDigitalVideoStab

            // Discover real flash / torch hardware availability
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
            val isHardwareHdrSceneSupported = com.example.core.camera.isCamera2HardwareHdrSceneModeSupported(
                availableSceneModes = sceneModes,
                availableControlModes = controlModes
            )
            val isHardwareNightSceneSupported = com.example.core.camera.isCamera2HardwareNightSceneModeSupported(
                availableSceneModes = sceneModes,
                availableControlModes = controlModes
            )
            val availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
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
                availableZoomRatios = listOf(0.5f, 1.0f, 2.0f, 5.0f)
            )

            val highSpeedFpsRanges = try {
                map?.getHighSpeedVideoFpsRanges() ?: emptyArray()
            } catch (_: Exception) {
                emptyArray()
            }
            val supportedSlowMoRatesList = mutableListOf<VideoFrameRate>()
            val slowMoResolutionsByRate = mutableMapOf<VideoFrameRate, List<VideoResolution>>()
            val maxHighSpeedFps = highSpeedFpsRanges.maxOfOrNull { it.upper } ?: aeFpsRanges.maxOfOrNull { it.upper } ?: 30
            val candidateSlowMoRates = listOf(
                VideoFrameRate.FPS_120,
                VideoFrameRate.FPS_240,
                VideoFrameRate.FPS_360,
                VideoFrameRate.FPS_480
            )
            for (rate in candidateSlowMoRates) {
                if (maxHighSpeedFps >= rate.fps) {
                    supportedSlowMoRatesList.add(rate)
                    val matchingRanges = highSpeedFpsRanges.filter { it.upper >= rate.fps }
                    val sizesForRate = matchingRanges.flatMap { range ->
                        try {
                            map?.getHighSpeedVideoSizesFor(range)?.toList() ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    val mappedResolutions = sizesForRate.mapNotNull { sz ->
                        val maxDim = maxOf(sz.width, sz.height)
                        val minDim = minOf(sz.width, sz.height)
                        when {
                            maxDim >= 3840 && minDim >= 2160 -> VideoResolution.UHD_4K
                            maxDim >= 1920 && minDim >= 1080 -> VideoResolution.FHD_1080P
                            maxDim >= 1280 && minDim >= 720 -> VideoResolution.HD_720P
                            else -> null
                        }
                    }.distinct()
                    if (mappedResolutions.isNotEmpty()) {
                        slowMoResolutionsByRate[rate] = mappedResolutions
                    }
                }
            }
            if (supportedSlowMoRatesList.isEmpty()) {
                supportedSlowMoRatesList.add(VideoFrameRate.FPS_120)
                supportedSlowMoRatesList.add(VideoFrameRate.FPS_240)
                val fallbackRes = supportedVideoQualities.keys.filter {
                    it == VideoResolution.HD_720P || it == VideoResolution.FHD_1080P
                }.ifEmpty { listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P) }
                slowMoResolutionsByRate[VideoFrameRate.FPS_120] = fallbackRes
                slowMoResolutionsByRate[VideoFrameRate.FPS_240] = fallbackRes
            }

            val lensCap = LensCapabilities(
                lens = lens,
                supportedPhotoSizes = photoSizes,
                highestNativeStillSize = highestNativeSize,
                supportedVideoQualities = supportedVideoQualities,
                supportedSlowMoRates = supportedSlowMoRatesList,
                supportedSlowMoResolutionsByRate = slowMoResolutionsByRate,
                isStabilizationSupported = hasDigitalVideoStab || hasOpticalStab,
                isOpticalStabilizationSupported = hasOpticalStab,
                isActiveStabilizationSupported = isActiveStabSupported,
                isTorchSupported = hasFlashUnit,
                sensorDescription = "Camera ID $id",
                manualCapabilities = manualCap
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
    targetOutputFps: Int = TimeLapseSpeed.TARGET_OUTPUT_FPS
): java.io.File? {
    if (!inputFile.exists() || inputFile.length() == 0L) return null
    val multiplier = speed.multiplier.coerceAtLeast(1)
    var extractor: android.media.MediaExtractor? = null
    var muxer: android.media.MediaMuxer? = null
    var muxerStarted = false
    val outputFile = java.io.File.createTempFile("timelapse_out_${multiplier}x_", ".mp4", context.cacheDir)
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
            if (!extractor.advance()) break
        }

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
            if (!extractor.advance()) break
        }

        extractor.unselectTrack(videoTrackIndex)

        if (writtenCount == 0) {
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
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PixelCamera")
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

