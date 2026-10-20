package com.example.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.example.core.preferences.AstroFocusMode
import com.example.core.preferences.AstroStrategy
import com.example.core.preferences.FlashMode
import com.example.core.preferences.ProFocusMode
import com.example.core.preferences.VideoStabilizationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Physical or logical camera lenses on the device.
 */
enum class CameraLens(val id: String, val displayName: String, val facing: LensFacing, val defaultZoom: Float = 1.0f) {
    BACK_ULTRAWIDE("1", "Ultrawide (0.5x)", LensFacing.BACK, 0.5f),
    BACK_MAIN("0", "Rear Main (1x)", LensFacing.BACK, 1.0f),
    BACK_TELEPHOTO("2", "Telephoto (5x)", LensFacing.BACK, 5.0f),
    FRONT("3", "Front Camera (1x)", LensFacing.FRONT, 1.0f)
}

/**
 * Standard video resolutions according to industry specifications.
 * 2K resolution strictly represents 2560x1440 (1440p QHD).
 */
enum class VideoResolution(
    val id: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val label: String
) {
    HD_720P("720p", "720p HD", 1280, 720, "1280 × 720"),
    FHD_1080P("1080p", "1080p Full HD", 1920, 1080, "1920 × 1080"),
    QHD_2K("2k_1440p", "2K / 1440p", 2560, 1440, "2560 × 1440"),
    UHD_4K("4k_uhd", "4K UHD", 3840, 2160, "3840 × 2160");

    companion object {
        val VIDEO_MODE_OPTIONS = listOf(HD_720P, FHD_1080P, QHD_2K, UHD_4K)
    }
}

/**
 * Supported video frame rates.
 */
enum class VideoFrameRate(val fps: Int, val displayName: String) {
    FPS_24(24, "24 FPS"),
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS"),
    FPS_120(120, "120 FPS"),
    FPS_240(240, "240 FPS"),
    FPS_360(360, "360 FPS"),
    FPS_480(480, "480 FPS");

    companion object {
        val VIDEO_MODE_OPTIONS = listOf(FPS_24, FPS_30, FPS_60)
    }
}

/**
 * Reconciled video quick settings guaranteed to be valid for a specific camera lens.
 */
data class ReconciledVideoSettings(
    val resolution: VideoResolution,
    val frameRate: VideoFrameRate,
    val stabilization: VideoStabilizationMode,
    val flashMode: FlashMode
)

/**
 * Concrete still-image capture output size from hardware streams.
 */
data class PhotoOutputSize(
    val width: Int,
    val height: Int,
    val isSensorMaxResolution: Boolean = false,
    val isBinned: Boolean = false,
    val note: String? = null
) {
    val totalPixels: Long get() = width.toLong() * height
    val megapixels: Float get() = totalPixels / 1_000_000f
    val formattedMegapixels: String get() = String.format(Locale.US, "%.1f MP", megapixels)
    val dimensionsLabel: String get() = "$width × $height"
}

/**
 * Minimum maximum-zoom target (20.0x) requested across Photo, Video, Pro, and Selfie modes
 * wherever the active camera hardware and CameraX/Camera2 pipeline permit it.
 */
const val TARGET_MAX_ZOOM_RATIO = 20.0f

/**
 * Resolves the usable hardware zoom range for a camera lens, separating the 20x target
 * from the actual hardware-supported maximum.
 * - If the hardware supports >= 20x (e.g. 20x, 30x), the full hardware range up to its actual max is usable.
 * - If the hardware supports < 20x (e.g. 4x, 8x, 10x), the range is gracefully capped at the real hardware maximum.
 */
fun resolveSupportedZoomRange(
    hardwareMinZoom: Float,
    hardwareMaxZoom: Float,
    targetMaxZoom: Float = TARGET_MAX_ZOOM_RATIO
): ClosedFloatingPointRange<Float> {
    val safeMin = if (hardwareMinZoom.isFinite() && hardwareMinZoom > 0f) {
        ((hardwareMinZoom * 100f).toInt() / 100f).coerceAtLeast(0.1f)
    } else {
        1.0f
    }
    val rawMax = if (hardwareMaxZoom.isFinite() && hardwareMaxZoom >= safeMin) {
        (hardwareMaxZoom * 10f).toInt() / 10f
    } else {
        safeMin
    }
    // Never fabricate zoom beyond rawMax; if rawMax exceeds targetMaxZoom (20x), allow up to rawMax.
    val effectiveMax = if (rawMax >= targetMaxZoom) rawMax else rawMax.coerceAtLeast(safeMin)
    return safeMin..effectiveMax
}

/**
 * Dynamically generates zoom shortcut buttons from the active camera's actual usable zoom range.
 * Never fabricates 0.5x when ultrawide (< 1.0x) is unsupported, and never generates shortcuts
 * above the camera's actual hardware maximum zoom ratio.
 */
fun generateZoomShortcuts(minZoomRatio: Float, maxZoomRatio: Float): List<Float> {
    val range = resolveSupportedZoomRange(minZoomRatio, maxZoomRatio)
    val cleanMin = ((range.start * 10f).toInt() / 10f).coerceAtLeast(0.1f)
    val cleanMax = ((range.endInclusive * 10f).toInt() / 10f).coerceAtLeast(cleanMin)

    val shortcuts = mutableListOf<Float>()

    // 1. Include ultrawide shortcut (< 1.0x) ONLY when genuinely supported by the active camera
    if (cleanMin < 0.95f) {
        shortcuts.add(cleanMin)
    }

    // 2. Include 1.0x baseline shortcut
    if (1.0f in cleanMin..cleanMax) {
        shortcuts.add(1.0f)
    } else if (shortcuts.isEmpty()) {
        shortcuts.add(cleanMin)
    }

    // 3. Add supported standard zoom milestones within the genuine hardware bounds
    val milestones = if (cleanMax >= TARGET_MAX_ZOOM_RATIO) {
        listOf(2.0f, 5.0f, TARGET_MAX_ZOOM_RATIO)
    } else {
        listOf(2.0f, 5.0f, 10.0f, TARGET_MAX_ZOOM_RATIO)
    }

    for (milestone in milestones) {
        if (milestone > cleanMin && milestone <= cleanMax) {
            if (shortcuts.none { kotlin.math.abs(it - milestone) < 0.15f }) {
                shortcuts.add(milestone)
            }
        }
    }

    // 4. If the camera's actual max zoom is meaningfully above the highest milestone (e.g., 4.0x, 8.0x, or 30.0x),
    // include the actual max zoom shortcut so the user can jump directly to the camera's limit.
    val lastShortcut = shortcuts.lastOrNull() ?: 1.0f
    if (cleanMax > lastShortcut + 0.4f) {
        shortcuts.add(cleanMax)
    }

    return shortcuts.distinct().sorted()
}

/**
 * Clamps any requested zoom ratio to the active camera's supported [minZoomRatio, maxZoomRatio] range.
 */
fun clampZoomRatio(requestedZoom: Float, minZoomRatio: Float, maxZoomRatio: Float): Float {
    if (!requestedZoom.isFinite()) return 1.0f.coerceIn(minZoomRatio, maxZoomRatio.coerceAtLeast(minZoomRatio))
    val range = resolveSupportedZoomRange(minZoomRatio, maxZoomRatio)
    val clamped = requestedZoom.coerceIn(range.start, range.endInclusive)
    return kotlin.math.round(clamped * 10f) / 10f
}

/**
 * Computes the continuous pinched zoom ratio from a multi-touch pinch scale factor
 * (> 1.0f when pinching outward to zoom in, < 1.0f when pinching inward to zoom out),
 * clamped strictly to the active camera's supported [minZoomRatio, maxZoomRatio] bounds.
 */
fun calculatePinchZoomRatio(
    currentZoomRatio: Float,
    pinchScaleFactor: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float
): Float {
    val range = resolveSupportedZoomRange(minZoomRatio, maxZoomRatio)
    val safeCurrent = if (currentZoomRatio.isFinite() && currentZoomRatio > 0f) {
        currentZoomRatio.coerceIn(range.start, range.endInclusive)
    } else {
        1.0f.coerceIn(range.start, range.endInclusive)
    }
    val safeScale = if (pinchScaleFactor.isFinite() && pinchScaleFactor > 0f) {
        pinchScaleFactor
    } else {
        1.0f
    }
    return (safeCurrent * safeScale).coerceIn(range.start, range.endInclusive)
}

/**
 * Formats a zoom ratio for display in the UI.
 * - When [asCompactShortcut] is false (active badge or slider label): always shows 1 decimal place (e.g. "1.0x", "5.0x", "20.0x").
 * - When [asCompactShortcut] is true (unselected shortcut pill): shows integer when exact (e.g. "1x", "2x", "5x", "20x") or 1 decimal (e.g. "0.5x").
 */
fun formatZoomRatio(zoomRatio: Float, asCompactShortcut: Boolean = false): String {
    val rounded = kotlin.math.round(zoomRatio * 10f) / 10f
    return if (asCompactShortcut && kotlin.math.abs(rounded - kotlin.math.round(rounded)) < 0.05f) {
        String.format(Locale.US, "%.0fx", rounded)
    } else {
        String.format(Locale.US, "%.1fx", rounded)
    }
}

/**
 * Focus and metering indicator status for non-Pro camera modes.
 */
enum class FocusIndicatorStatus {
    IDLE,
    FOCUSING,
    FOCUSED,
    FOCUS_UNSUCCESSFUL,
    LOCKED,
    METERING_ONLY,
    LOCKED_METERING_ONLY,
    UNSUPPORTED;

    val isLockedState: Boolean
        get() = this == LOCKED || this == LOCKED_METERING_ONLY
}

/**
 * Represents a user tap-to-focus or tap-and-hold focus-lock request on the live preview.
 */
data class FocusTouchRequest(
    val requestId: Long,
    val tapX: Float,
    val tapY: Float,
    val previewWidth: Float,
    val previewHeight: Float,
    val isLockRequested: Boolean,
    val isAfRequested: Boolean = true,
    val isAeRequested: Boolean = true
)

/**
 * Independently verified Autofocus (AF) and Auto-Exposure (AE) metering capabilities
 * for a camera lens and active CameraX session.
 * Never assumes that autofocus automatically performs auto-exposure metering or vice versa.
 */
data class VerifiedFocusMeteringCapabilities(
    val isAfSupported: Boolean,
    val isAeSupported: Boolean,
    val isAwbSupported: Boolean = false,
    val isFocusLockSupported: Boolean = isAfSupported
) {
    val isAnyMeteringSupported: Boolean
        get() = isAfSupported || isAeSupported

    /**
     * Computes the exact CameraX [androidx.camera.core.FocusMeteringAction] bitmask flags
     * from the independently verified AF, AE, and AWB capabilities.
     * Returns 0 when neither AF nor AE metering is supported.
     */
    val focusMeteringFlags: Int
        get() {
            var flags = 0
            if (isAfSupported) {
                flags = flags or androidx.camera.core.FocusMeteringAction.FLAG_AF
            }
            if (isAeSupported) {
                flags = flags or androidx.camera.core.FocusMeteringAction.FLAG_AE
            }
            if (isAwbSupported && (isAfSupported || isAeSupported)) {
                flags = flags or androidx.camera.core.FocusMeteringAction.FLAG_AWB
            }
            return flags
        }
}

/**
 * Independently verifies Autofocus (FLAG_AF), Auto-Exposure (FLAG_AE), and Auto-White-Balance (FLAG_AWB)
 * support against both hardware [ManualSensorCapabilities] and the live CameraX [androidx.camera.core.CameraInfo].
 *
 * Note: Calling `CameraInfo.isFocusMeteringSupported(combinedAction)` with `FLAG_AF or FLAG_AE` returns true
 * if *either* AF or AE is supported. Therefore, AF and AE MUST be probed with separate single-flag
 * [androidx.camera.core.FocusMeteringAction] instances so fixed-focus lenses or AE-unsupported lenses
 * are detected accurately.
 */
fun verifyCameraXFocusMeteringSupport(
    cameraInfo: androidx.camera.core.CameraInfo?,
    manualCapabilities: ManualSensorCapabilities?,
    meteringPoint: androidx.camera.core.MeteringPoint
): VerifiedFocusMeteringCapabilities {
    val hardwareSupportsAf = manualCapabilities?.isAutoFocusSupported ?: true
    val hardwareSupportsAe = manualCapabilities?.isAutoExposureSupported ?: true
    val hardwareSupportsAwb = (manualCapabilities?.maxAwbRegions ?: 0) > 0
    val hardwareSupportsFocusLock = manualCapabilities?.isFocusLockSupported ?: hardwareSupportsAf

    if (cameraInfo == null) {
        return VerifiedFocusMeteringCapabilities(
            isAfSupported = hardwareSupportsAf,
            isAeSupported = hardwareSupportsAe,
            isAwbSupported = hardwareSupportsAwb,
            isFocusLockSupported = hardwareSupportsAf && hardwareSupportsFocusLock
        )
    }

    val cameraXSupportsAf = try {
        val afProbe = androidx.camera.core.FocusMeteringAction.Builder(
            meteringPoint,
            androidx.camera.core.FocusMeteringAction.FLAG_AF
        ).build()
        cameraInfo.isFocusMeteringSupported(afProbe)
    } catch (_: Exception) {
        false
    }

    val cameraXSupportsAe = try {
        val aeProbe = androidx.camera.core.FocusMeteringAction.Builder(
            meteringPoint,
            androidx.camera.core.FocusMeteringAction.FLAG_AE
        ).build()
        cameraInfo.isFocusMeteringSupported(aeProbe)
    } catch (_: Exception) {
        false
    }

    val cameraXSupportsAwb = try {
        val awbProbe = androidx.camera.core.FocusMeteringAction.Builder(
            meteringPoint,
            androidx.camera.core.FocusMeteringAction.FLAG_AWB
        ).build()
        cameraInfo.isFocusMeteringSupported(awbProbe)
    } catch (_: Exception) {
        false
    }

    val verifiedAf = hardwareSupportsAf && cameraXSupportsAf
    val verifiedAe = hardwareSupportsAe && cameraXSupportsAe
    val verifiedAwb = hardwareSupportsAwb && cameraXSupportsAwb

    return VerifiedFocusMeteringCapabilities(
        isAfSupported = verifiedAf,
        isAeSupported = verifiedAe,
        isAwbSupported = verifiedAwb,
        isFocusLockSupported = verifiedAf && hardwareSupportsFocusLock
    )
}

/**
 * Builds a CameraX [androidx.camera.core.FocusMeteringAction] at [meteringPoint] using the
 * independently verified [capabilities], or returns null if neither AF nor AE metering is supported.
 *
 * - When both AF and AE are supported, both `FLAG_AF` and `FLAG_AE` are attached to [meteringPoint]
 *   so CameraX triggers autofocus AND meters auto-exposure at the exact same point.
 * - When [isLockRequested] is true (tap-and-hold), `disableAutoCancel()` is applied so the camera
 *   performs AF and AE metering at [meteringPoint] and then holds the locked focus and metering state.
 * - When [isLockRequested] is false (normal tap), a 5-second auto-cancel duration is applied.
 */
fun buildCameraXFocusMeteringAction(
    meteringPoint: androidx.camera.core.MeteringPoint,
    capabilities: VerifiedFocusMeteringCapabilities,
    isLockRequested: Boolean,
    autoCancelDurationSeconds: Long = 5L
): androidx.camera.core.FocusMeteringAction? {
    val flags = capabilities.focusMeteringFlags
    if (flags == 0) return null

    val builder = androidx.camera.core.FocusMeteringAction.Builder(meteringPoint, flags)
    if (isLockRequested) {
        builder.disableAutoCancel()
    } else if (autoCancelDurationSeconds > 0L) {
        builder.setAutoCancelDuration(autoCancelDurationSeconds, java.util.concurrent.TimeUnit.SECONDS)
    }
    return builder.build()
}

/**
 * Normalized 2D coordinate [0.0f, 1.0f] within the active preview surface,
 * accounting for preview bounds clamping and front-camera horizontal mirroring.
 */
data class NormalizedMeteringCoordinate(
    val x: Float,
    val y: Float,
    val clampedPixelX: Float,
    val clampedPixelY: Float,
    val isMirroredForFrontCamera: Boolean
)

/**
 * Converts a touch point in preview view pixels into a clamped and normalized metering coordinate,
 * applying horizontal mirroring when the front selfie camera is active.
 */
fun computeNormalizedMeteringCoordinate(
    tapX: Float,
    tapY: Float,
    previewWidth: Float,
    previewHeight: Float,
    isFrontCamera: Boolean,
    isSelfieMirrorEnabled: Boolean = true
): NormalizedMeteringCoordinate {
    val safeWidth = if (previewWidth.isFinite() && previewWidth > 1f) previewWidth else 1f
    val safeHeight = if (previewHeight.isFinite() && previewHeight > 1f) previewHeight else 1f
    val clampedX = if (tapX.isFinite()) tapX.coerceIn(0f, safeWidth) else safeWidth / 2f
    val clampedY = if (tapY.isFinite()) tapY.coerceIn(0f, safeHeight) else safeHeight / 2f

    val rawNormX = (clampedX / safeWidth).coerceIn(0f, 1f)
    val rawNormY = (clampedY / safeHeight).coerceIn(0f, 1f)
    val shouldMirror = isFrontCamera && isSelfieMirrorEnabled
    val effectiveNormX = if (shouldMirror) (1f - rawNormX).coerceIn(0f, 1f) else rawNormX

    return NormalizedMeteringCoordinate(
        x = effectiveNormX,
        y = rawNormY,
        clampedPixelX = clampedX,
        clampedPixelY = clampedY,
        isMirroredForFrontCamera = shouldMirror
    )
}

/**
 * Clamps a requested exposure compensation index to the active camera's supported [evRange].
 */
fun clampExposureIndex(requestedIndex: Int, evRange: ClosedRange<Int>?): Int {
    if (evRange == null) return 0
    val minIdx = minOf(evRange.start, evRange.endInclusive)
    val maxIdx = maxOf(evRange.start, evRange.endInclusive)
    return requestedIndex.coerceIn(minIdx, maxIdx)
}

/**
 * Converts an integer exposure compensation index and hardware step (e.g. 1/3 or 1/2 EV) to EV float value.
 */
fun exposureIndexToEv(index: Int, step: Float): Float {
    val safeStep = if (step.isFinite() && step > 0f) step else (1f / 3f)
    val rawEv = index * safeStep
    return kotlin.math.round(rawEv * 100f) / 100f
}

/**
 * Converts a target EV float value into the nearest valid integer exposure compensation index
 * within the camera's supported [evRange].
 */
fun evToExposureIndex(evValue: Float, evRange: ClosedRange<Int>?, step: Float): Int {
    if (evRange == null || !evValue.isFinite()) return 0
    val safeStep = if (step.isFinite() && step > 0f) step else (1f / 3f)
    val nearestIndex = kotlin.math.round(evValue / safeStep).toInt()
    return clampExposureIndex(nearestIndex, evRange)
}

/**
 * Formats an exposure compensation index and step into a user-facing EV label (e.g., "0.0 EV", "+1.0 EV", "-0.7 EV").
 */
fun formatEvValue(index: Int, step: Float, includeUnit: Boolean = true): String {
    val ev = exposureIndexToEv(index, step)
    val roundedTenth = kotlin.math.round(ev * 10f) / 10f
    val signPrefix = when {
        index > 0 && roundedTenth > 0f -> "+"
        index < 0 && roundedTenth == 0f -> "-"
        else -> ""
    }
    val numeric = String.format(Locale.US, "%s%.1f", signPrefix, roundedTenth)
    return if (includeUnit) "$numeric EV" else numeric
}

/**
 * Returns true if automatic tap-to-focus and tap-and-hold focus lock are enabled for [mode].
 * All non-Pro modes (including Night Sight) support tap-to-focus; Pro mode keeps its own manual controls separate.
 */
fun isTapToFocusEnabledForMode(mode: com.example.core.model.CameraMode): Boolean {
    return mode != com.example.core.model.CameraMode.PRO
}

/**
 * Returns true if the focus-adjacent vertical exposure compensation (EV) slider is enabled for [mode].
 * Enabled in all supported non-Pro modes (Photo, Portrait, Video, Slow Motion, HD, Panorama)
 * except Night Sight (which uses its own low-light exposure and multi-frame pipeline) and Pro mode
 * (which uses its dedicated manual exposure controls).
 */
fun isFocusEvSliderEnabledForMode(mode: com.example.core.model.CameraMode): Boolean {
    return isTapToFocusEnabledForMode(mode) && mode != com.example.core.model.CameraMode.NIGHT_SIGHT
}

/**
 * Resolves the effective hardware exposure compensation index to apply for [mode] given
 * the non-Pro focus-adjacent slider index [autoExposureIndex], the Pro mode EV value [proEv],
 * and the active camera's [isExposureCompensationSupported], [evRange], and [evStep].
 *
 * - Returns 0 when exposure compensation is unsupported by the active camera or when [mode]
 *   is [com.example.core.model.CameraMode.NIGHT_SIGHT] (where the focus-adjacent EV slider is disabled).
 * - Uses [evToExposureIndex] with [proEv] in [com.example.core.model.CameraMode.PRO].
 * - Uses [clampExposureIndex] with [autoExposureIndex] in all supported non-Pro modes
 *   (Photo, Portrait, Video, Slow Motion, HD, Panorama).
 */
fun resolveModeExposureCompensationIndex(
    mode: com.example.core.model.CameraMode,
    autoExposureIndex: Int,
    proEv: Float,
    isExposureCompensationSupported: Boolean,
    evRange: ClosedRange<Int>?,
    evStep: Float
): Int {
    if (!isExposureCompensationSupported || evRange == null) return 0
    val minIdx = minOf(evRange.start, evRange.endInclusive)
    val maxIdx = maxOf(evRange.start, evRange.endInclusive)
    if (minIdx >= 0 && maxIdx <= 0) return 0
    return when {
        mode == com.example.core.model.CameraMode.PRO -> evToExposureIndex(proEv, minIdx..maxIdx, evStep)
        isFocusEvSliderEnabledForMode(mode) -> clampExposureIndex(autoExposureIndex, minIdx..maxIdx)
        else -> 0
    }
}

/**
 * Hardware manual sensor capabilities discovered via Camera2/CameraCharacteristics and CameraX ZoomState/ExposureState.
 * Ensures the app does not hardcode ISO, shutter speed, focus, white balance, exposure, or zoom ranges.
 */
data class ManualSensorCapabilities(
    val isManualIsoSupported: Boolean = false,
    val isoRange: ClosedRange<Int>? = null,
    val isManualShutterSupported: Boolean = false,
    val shutterSpeedRangeNanos: ClosedRange<Long>? = null,
    val isManualFocusSupported: Boolean = false,
    val minimumFocusDistanceDiopters: Float? = null,
    val isAutoFocusSupported: Boolean = true,
    val isAutoExposureSupported: Boolean = true,
    val isFocusLockSupported: Boolean = true,
    val maxAfRegions: Int = 1,
    val maxAeRegions: Int = 1,
    val maxAwbRegions: Int = 1,
    val isAeLockSupported: Boolean = false,
    val availableAfModes: List<Int> = emptyList(),
    val availableAeModes: List<Int> = emptyList(),
    val availableAwbModes: List<Int> = emptyList(),
    val availableSceneModes: List<Int> = emptyList(),
    val availableControlModes: List<Int> = emptyList(),
    val isHardwareHdrSceneModeSupported: Boolean = false,
    val isHardwareNightSceneModeSupported: Boolean = false,
    val isManualWhiteBalanceSupported: Boolean = false,
    val whiteBalanceKelvinRange: ClosedRange<Int>? = null,
    val isExposureCompensationSupported: Boolean = true,
    val evRange: ClosedRange<Int>? = -6..6,
    val evStepRational: Float = 1f / 3f,
    val isRawDngSupported: Boolean = false,
    val isFocusPeakingSupported: Boolean = false,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 4.0f,
    val targetMaxZoomRatio: Float = TARGET_MAX_ZOOM_RATIO,
    val isLogicalMultiCamera: Boolean = false,
    val physicalCameraIds: List<String> = emptyList(),
    val isHardwareDepthOutputSupported: Boolean = false,
    val isDepthExclusive: Boolean = false,
    val hasDepth16Output: Boolean = false,
    val hasDynamicDepthJpegOutput: Boolean = false,
    val isExtendedBokehStillSupported: Boolean = false,
    val isExtendedBokehContinuousSupported: Boolean = false,
    val maxFaceDetectCount: Int = 0,
    val availableFaceDetectModes: List<Int> = emptyList(),
    val availableZoomRatios: List<Float> = generateZoomShortcuts(minZoomRatio, maxZoomRatio)
) {
    val isTarget20xSupported: Boolean
        get() = maxZoomRatio >= targetMaxZoomRatio
}

/**
 * Genuine HDR implementation path available for still capture.
 * Never labels ordinary automatic exposure (AE) as HDR and never claims proprietary Pixel HDR+.
 */
enum class HdrSupportPath(val displayName: String) {
    HARDWARE_SCENE_MODE_HDR("Camera2 Hardware Scene HDR"),
    COMPATIBLE_EXTENSION("CameraX Vendor HDR Extension"),
    SOFTWARE_MULTI_FRAME_HDR("Software Multi-Frame Exposure Fusion"),
    UNAVAILABLE("Standard Auto-Exposure Capture (HDR Unavailable)")
}

/**
 * Verified Portrait depth / segmentation path for a specific camera lens (front or rear).
 * Never assumes a hardware depth sensor or multiple physical lenses unless verified via Camera2.
 */
enum class PortraitDepthSupportPath(
    val displayName: String,
    val supportsLivePreviewBokeh: Boolean
) {
    HARDWARE_EXTENDED_BOKEH(
        displayName = "Camera2 Hardware Bokeh + On-Device Subject Matte",
        supportsLivePreviewBokeh = true
    ),
    HARDWARE_DEPTH_SENSOR_ASSISTED(
        displayName = "Hardware Depth Output + On-Device Subject Matte",
        supportsLivePreviewBokeh = false
    ),
    LOGICAL_MULTI_CAMERA_ASSISTED(
        displayName = "Multi-Lens Logical Camera + On-Device Subject Matte",
        supportsLivePreviewBokeh = false
    ),
    ON_DEVICE_SEGMENTATION_ONLY(
        displayName = "Single-Lens On-Device Subject Matte",
        supportsLivePreviewBokeh = false
    )
}

/**
 * Verified Night Sight low-light capture and fusion path for a specific camera lens.
 * Never claims unsupported hardware; dynamically resolves between Camera2 hardware night scene,
 * computational multi-frame low-light fusion, and single-frame sensor-enhanced integration.
 */
enum class NightSightSupportPath(
    val displayName: String,
    val isMultiFrameFusion: Boolean = false
) {
    HARDWARE_SCENE_NIGHT(
        displayName = "Camera2 Hardware Night Scene",
        isMultiFrameFusion = false
    ),
    MULTI_FRAME_LOW_LIGHT_FUSION(
        displayName = "Computational Multi-Frame Low-Light Fusion",
        isMultiFrameFusion = true
    ),
    SENSOR_ENHANCED_INTEGRATION(
        displayName = "Sensor-Enhanced Low-Light Integration",
        isMultiFrameFusion = false
    ),
    UNAVAILABLE(
        displayName = "Standard Capture (Night Sight Unavailable)",
        isMultiFrameFusion = false
    )
}

/**
 * Verified Astrophotography pipeline path for a specific camera lens.
 * Dynamically resolves between hardware manual long exposure, computational star-aligned multi-frame,
 * and adaptive low-light night sky fallback.
 */
enum class AstroSupportPath(
    val displayName: String,
    val isGenuineLongExposure: Boolean,
    val isMultiFrameFusion: Boolean
) {
    HARDWARE_MANUAL_LONG_EXPOSURE(
        displayName = "Hardware Manual Long-Exposure Stacking",
        isGenuineLongExposure = true,
        isMultiFrameFusion = true
    ),
    COMPUTATIONAL_MULTI_FRAME_ASTRO(
        displayName = "Computational Star-Aligned Multi-Frame Stacking",
        isGenuineLongExposure = false,
        isMultiFrameFusion = true
    ),
    NIGHT_SIGHT_ADAPTIVE_FALLBACK(
        displayName = "Adaptive Low-Light Night Sky Fallback",
        isGenuineLongExposure = false,
        isMultiFrameFusion = true
    )
}

/**
 * Utility for resolving Astrophotography hardware capabilities.
 */
object AstroDeviceSupport {
    fun resolveAstroSupportPath(
        isManualShutterSupported: Boolean,
        maxExposureNanos: Long?,
        isManualIsoSupported: Boolean
    ): AstroSupportPath {
        val maxExposureSec = (maxExposureNanos ?: 0L) / 1_000_000_000f
        return when {
            isManualShutterSupported && maxExposureSec >= 1.0f -> AstroSupportPath.HARDWARE_MANUAL_LONG_EXPOSURE
            isManualIsoSupported || isManualShutterSupported -> AstroSupportPath.COMPUTATIONAL_MULTI_FRAME_ASTRO
            else -> AstroSupportPath.NIGHT_SIGHT_ADAPTIVE_FALLBACK
        }
    }
}

/**
 * Verified hardware capabilities for an individual lens.
 * Populated exclusively by hardware detection queries; never fabricated.
 */
data class LensCapabilities(
    val lens: CameraLens,
    val supportedPhotoSizes: List<PhotoOutputSize> = emptyList(),
    val highestNativeStillSize: PhotoOutputSize? = null,
    val supportedVideoQualities: Map<VideoResolution, List<VideoFrameRate>> = emptyMap(),
    val supportedSlowMoRates: List<VideoFrameRate> = emptyList(),
    val supportedSlowMoResolutionsByRate: Map<VideoFrameRate, List<VideoResolution>> = emptyMap(),
    val supportedStabilizationModesByConfig: Map<Pair<VideoResolution, VideoFrameRate>, List<VideoStabilizationMode>> = emptyMap(),
    val isStabilizationSupported: Boolean = false,
    val isDigitalVideoStabilizationSupported: Boolean = isStabilizationSupported,
    val isPreviewStabilizationSupported: Boolean = false,
    val isOpticalStabilizationSupported: Boolean = false,
    val isActiveStabilizationSupported: Boolean = false,
    val isTorchSupported: Boolean = false,
    val isFullSensorResolutionExposed: Boolean = false,
    val sensorPixelArraySize: PhotoOutputSize? = null,
    val camera2CameraId: String? = null,
    val physicalCameraIds: List<String> = emptyList(),
    val focalLengthMm: Float? = null,
    val sensorOrientationDegrees: Int = if (lens.facing == LensFacing.FRONT) 270 else 90,
    val sensorDescription: String? = null,
    val manualCapabilities: ManualSensorCapabilities = ManualSensorCapabilities()
) {
    val resolvedHardwareLabel: String
        get() = sensorDescription?.takeIf { it.isNotBlank() } ?: lens.displayName

    fun isSlowMoRateSupported(rate: VideoFrameRate): Boolean = rate in supportedSlowMoRates

    fun getSupportedSlowMoResolutions(rate: VideoFrameRate): List<VideoResolution> {
        val explicit = supportedSlowMoResolutionsByRate[rate]
        if (!explicit.isNullOrEmpty()) return explicit
        val standard = supportedVideoQualities.keys.filter {
            it == VideoResolution.HD_720P || it == VideoResolution.FHD_1080P
        }
        return standard.ifEmpty { listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P) }
    }

    fun resolveSlowMoResolution(
        rate: VideoFrameRate,
        preferred: VideoResolution = VideoResolution.FHD_1080P
    ): VideoResolution {
        val supported = getSupportedSlowMoResolutions(rate)
        if (preferred in supported) return preferred
        if (VideoResolution.FHD_1080P in supported) return VideoResolution.FHD_1080P
        if (VideoResolution.HD_720P in supported) return VideoResolution.HD_720P
        return supported.firstOrNull() ?: VideoResolution.HD_720P
    }
    val minZoomRatio: Float
        get() = manualCapabilities.minZoomRatio

    val maxZoomRatio: Float
        get() = manualCapabilities.maxZoomRatio

    val targetMaxZoomRatio: Float
        get() = manualCapabilities.targetMaxZoomRatio

    val isTarget20xZoomSupported: Boolean
        get() = manualCapabilities.isTarget20xSupported

    val isLogicalMultiCamera: Boolean
        get() = manualCapabilities.isLogicalMultiCamera

    val isAutoFocusSupported: Boolean
        get() = manualCapabilities.isAutoFocusSupported

    val isAutoExposureSupported: Boolean
        get() = manualCapabilities.isAutoExposureSupported

    val isFocusLockSupported: Boolean
        get() = manualCapabilities.isFocusLockSupported

    val isManualIsoSupported: Boolean
        get() = manualCapabilities.isManualIsoSupported

    val isManualShutterSupported: Boolean
        get() = manualCapabilities.isManualShutterSupported

    val isManualFocusSupported: Boolean
        get() = manualCapabilities.isManualFocusSupported

    val isRawDngSupported: Boolean
        get() = manualCapabilities.isRawDngSupported

    val isHardwareHdrSceneModeSupported: Boolean
        get() = manualCapabilities.isHardwareHdrSceneModeSupported

    val hdrSupportPath: HdrSupportPath
        get() = if (isHardwareHdrSceneModeSupported) {
            HdrSupportPath.HARDWARE_SCENE_MODE_HDR
        } else {
            HdrSupportPath.SOFTWARE_MULTI_FRAME_HDR
        }

    val isHardwareDepthSupported: Boolean
        get() = manualCapabilities.isHardwareDepthOutputSupported ||
            manualCapabilities.hasDepth16Output ||
            manualCapabilities.hasDynamicDepthJpegOutput

    val isHardwareBokehSupported: Boolean
        get() = manualCapabilities.isExtendedBokehStillSupported ||
            manualCapabilities.isExtendedBokehContinuousSupported

    val portraitDepthSupportPath: PortraitDepthSupportPath
        get() = when {
            isHardwareBokehSupported -> PortraitDepthSupportPath.HARDWARE_EXTENDED_BOKEH
            isHardwareDepthSupported -> PortraitDepthSupportPath.HARDWARE_DEPTH_SENSOR_ASSISTED
            isLogicalMultiCamera && manualCapabilities.physicalCameraIds.size >= 2 ->
                PortraitDepthSupportPath.LOGICAL_MULTI_CAMERA_ASSISTED
            else -> PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY
        }

    val isHardwareNightSceneModeSupported: Boolean
        get() = manualCapabilities.isHardwareNightSceneModeSupported

    val nightSightSupportPath: NightSightSupportPath
        get() = NightSightDeviceSupport.resolveNightSightSupportPath(
            isHardwareNightSceneSupported = isHardwareNightSceneModeSupported
        )

    val astroSupportPath: AstroSupportPath
        get() = AstroDeviceSupport.resolveAstroSupportPath(
            isManualShutterSupported = isManualShutterSupported,
            maxExposureNanos = manualCapabilities.shutterSpeedRangeNanos?.endInclusive,
            isManualIsoSupported = isManualIsoSupported
        )

    val availableAwbModes: List<Int>
        get() = manualCapabilities.availableAwbModes

    val isExposureCompensationSupported: Boolean
        get() = manualCapabilities.isExposureCompensationSupported &&
            manualCapabilities.evRange != null &&
            (manualCapabilities.evRange.start < 0 || manualCapabilities.evRange.endInclusive > 0)

    val evIndexRange: IntRange
        get() = manualCapabilities.evRange?.let { it.start..it.endInclusive } ?: (0..0)

    val evStep: Float
        get() = manualCapabilities.evStepRational

    val zoomShortcuts: List<Float>
        get() = manualCapabilities.availableZoomRatios.ifEmpty {
            generateZoomShortcuts(minZoomRatio, maxZoomRatio)
        }

    /**
     * Selects the optimal supported hardware JPEG output size for this lens based on the
     * selected [aspectRatioMode] and whether maximum native resolution is requested.
     * - Prefers a native hardware stream matching [aspectRatioMode] when its pixel count is at least
     *   90% of the cropped pixel area of the maximum sensor stream (avoiding post-capture cropping).
     * - Otherwise selects the highest-resolution sensor stream that yields the maximum effective
     *   pixel count for [aspectRatioMode].
     */
    fun selectOptimalPhotoOutputSize(
        aspectRatioMode: com.example.core.preferences.AspectRatioMode,
        preferMaxNative: Boolean = true
    ): PhotoOutputSize? {
        if (supportedPhotoSizes.isEmpty()) return highestNativeStillSize
        val sortedSizes = supportedPhotoSizes.sortedByDescending { it.totalPixels }
        val candidatePool = if (!preferMaxNative && sortedSizes.first().totalPixels > 16_000_000L) {
            // For standard binned mode on ultra-high-res sensors (>16MP), prefer ~12MP-16MP tier if available
            sortedSizes.filter { it.totalPixels <= 16_500_000L }.ifEmpty { sortedSizes }
        } else {
            sortedSizes
        }

        val targetLandscapeRatio = when (aspectRatioMode) {
            com.example.core.preferences.AspectRatioMode.RATIO_4_3 -> 4f / 3f
            com.example.core.preferences.AspectRatioMode.RATIO_1_1 -> 1f
            com.example.core.preferences.AspectRatioMode.RATIO_9_16 -> 16f / 9f
        }

        val maxEffectivePixels = candidatePool.maxOf { size ->
            val maxDim = maxOf(size.width, size.height).toFloat()
            val minDim = minOf(size.width, size.height).toFloat()
            val sizeRatio = maxDim / minDim.coerceAtLeast(1f)
            val croppedW: Float
            val croppedH: Float
            if (sizeRatio > targetLandscapeRatio) {
                croppedH = minDim
                croppedW = minDim * targetLandscapeRatio
            } else {
                croppedW = maxDim
                croppedH = maxDim / targetLandscapeRatio
            }
            (croppedW * croppedH).toLong()
        }

        // First look for a native hardware size whose aspect ratio already matches targetLandscapeRatio
        // and retains at least 90% of the maximum possible effective resolution.
        val nativeAspectMatch = candidatePool.firstOrNull { size ->
            val maxDim = maxOf(size.width, size.height).toFloat()
            val minDim = minOf(size.width, size.height).toFloat().coerceAtLeast(1f)
            val ratio = maxDim / minDim
            kotlin.math.abs(ratio - targetLandscapeRatio) <= 0.03f &&
                size.totalPixels >= (maxEffectivePixels * 90L) / 100L
        }
        if (nativeAspectMatch != null) return nativeAspectMatch

        // Otherwise return the highest-resolution candidate
        return candidatePool.first()
    }

    fun clampZoom(requestedZoom: Float): Float =
        clampZoomRatio(requestedZoom, minZoomRatio, maxZoomRatio)

    fun clampExposure(requestedIndex: Int): Int =
        if (isExposureCompensationSupported) {
            clampExposureIndex(requestedIndex, manualCapabilities.evRange)
        } else {
            0
        }

    fun isResolutionSupported(res: VideoResolution): Boolean =
        supportedVideoQualities[res]?.isNotEmpty() == true

    fun getSupportedVideoModeResolutions(): List<VideoResolution> =
        VideoResolution.VIDEO_MODE_OPTIONS.filter { isResolutionSupported(it) }

    fun getSupportedFrameRates(res: VideoResolution): List<VideoFrameRate> =
        supportedVideoQualities[res] ?: emptyList()

    fun getSupportedVideoModeFrameRates(res: VideoResolution): List<VideoFrameRate> =
        getSupportedFrameRates(res).filter { it in VideoFrameRate.VIDEO_MODE_OPTIONS }

    fun isFrameRateSupported(res: VideoResolution, fps: VideoFrameRate): Boolean =
        fps in getSupportedVideoModeFrameRates(res)

    fun getSupportedStabilizationModes(
        res: VideoResolution = VideoResolution.FHD_1080P,
        fps: VideoFrameRate = VideoFrameRate.FPS_30
    ): List<VideoStabilizationMode> {
        val explicitModes = supportedStabilizationModesByConfig[res to fps]
        if (!explicitModes.isNullOrEmpty()) {
            return explicitModes
        }
        return buildList {
            add(VideoStabilizationMode.OFF)
            // Electronic video stabilization (EIS) is supported at 720p (24/30/60 FPS) and 1080p (24/30 FPS).
            // At 1080p 60 FPS, 2K (1440p), and 4K UHD, EIS crop/readout is constrained unless optical stabilization (OIS) is present.
            val isEisEligibleCombo = when (res) {
                VideoResolution.HD_720P -> fps == VideoFrameRate.FPS_24 || fps == VideoFrameRate.FPS_30 || fps == VideoFrameRate.FPS_60
                VideoResolution.FHD_1080P -> fps == VideoFrameRate.FPS_24 || fps == VideoFrameRate.FPS_30
                VideoResolution.QHD_2K, VideoResolution.UHD_4K -> false
            }
            if ((isStabilizationSupported && isEisEligibleCombo) || isOpticalStabilizationSupported) {
                add(VideoStabilizationMode.STANDARD)
            }
            // Active stabilization requires dedicated hardware support (OIS + EIS/Preview stabilization)
            // and is not available at 2K, 4K UHD, or 60 FPS due to sensor readout & crop bounds.
            if (isActiveStabilizationSupported &&
                res != VideoResolution.UHD_4K &&
                res != VideoResolution.QHD_2K &&
                fps != VideoFrameRate.FPS_60
            ) {
                add(VideoStabilizationMode.ACTIVE)
            }
        }
    }

    fun isStabilizationModeSupported(
        mode: VideoStabilizationMode,
        res: VideoResolution = VideoResolution.FHD_1080P,
        fps: VideoFrameRate = VideoFrameRate.FPS_30
    ): Boolean = mode in getSupportedStabilizationModes(res, fps)

    fun resolveSupportedResolution(requested: VideoResolution): VideoResolution {
        if (isResolutionSupported(requested)) return requested
        val supported = getSupportedVideoModeResolutions()
        if (supported.isEmpty()) return VideoResolution.HD_720P
        return when (requested) {
            VideoResolution.UHD_4K -> when {
                VideoResolution.QHD_2K in supported -> VideoResolution.QHD_2K
                VideoResolution.FHD_1080P in supported -> VideoResolution.FHD_1080P
                VideoResolution.HD_720P in supported -> VideoResolution.HD_720P
                else -> supported.last()
            }
            VideoResolution.QHD_2K -> when {
                VideoResolution.FHD_1080P in supported -> VideoResolution.FHD_1080P
                VideoResolution.HD_720P in supported -> VideoResolution.HD_720P
                else -> supported.last()
            }
            VideoResolution.FHD_1080P -> when {
                VideoResolution.HD_720P in supported -> VideoResolution.HD_720P
                else -> supported.first()
            }
            VideoResolution.HD_720P -> supported.first()
        }
    }

    fun resolveSupportedFrameRate(res: VideoResolution, requested: VideoFrameRate): VideoFrameRate {
        val supportedRates = getSupportedVideoModeFrameRates(res)
        if (requested in supportedRates) return requested
        if (VideoFrameRate.FPS_30 in supportedRates) return VideoFrameRate.FPS_30
        if (VideoFrameRate.FPS_24 in supportedRates) return VideoFrameRate.FPS_24
        return supportedRates.firstOrNull() ?: VideoFrameRate.FPS_30
    }

    fun resolveSupportedStabilization(
        res: VideoResolution,
        fps: VideoFrameRate,
        requested: VideoStabilizationMode
    ): VideoStabilizationMode {
        val supportedModes = getSupportedStabilizationModes(res, fps)
        if (requested in supportedModes) return requested
        return when (requested) {
            VideoStabilizationMode.ACTIVE -> {
                if (VideoStabilizationMode.STANDARD in supportedModes) {
                    VideoStabilizationMode.STANDARD
                } else {
                    VideoStabilizationMode.OFF
                }
            }
            VideoStabilizationMode.STANDARD -> VideoStabilizationMode.OFF
            VideoStabilizationMode.OFF -> VideoStabilizationMode.OFF
        }
    }

    fun resolveSupportedFlashMode(requested: FlashMode): FlashMode {
        return if (isTorchSupported) requested else FlashMode.OFF
    }

    fun reconcileVideoSettings(
        requestedResolution: VideoResolution,
        requestedFrameRate: VideoFrameRate,
        requestedStabilization: VideoStabilizationMode,
        requestedFlashMode: FlashMode
    ): ReconciledVideoSettings {
        val resolvedRes = resolveSupportedResolution(requestedResolution)
        val resolvedFps = resolveSupportedFrameRate(resolvedRes, requestedFrameRate)
        val resolvedStab = resolveSupportedStabilization(resolvedRes, resolvedFps, requestedStabilization)
        val resolvedFlash = resolveSupportedFlashMode(requestedFlashMode)
        return ReconciledVideoSettings(
            resolution = resolvedRes,
            frameRate = resolvedFps,
            stabilization = resolvedStab,
            flashMode = resolvedFlash
        )
    }
}

/**
 * State representing discovery status of camera capabilities.
 * Guarantees UI does not pretend or display fabricated hardware specs before discovery.
 */
sealed interface CameraCapabilitiesState {
    /**
     * Camera engine has not yet bound to hardware devices.
     * UI clearly reflects discovery pending state without claiming false capabilities.
     */
    data class PendingDiscovery(
        val activeLens: CameraLens = CameraLens.BACK_MAIN,
        val availableLenses: List<CameraLens> = listOf(CameraLens.BACK_MAIN, CameraLens.FRONT),
        val message: String = "Querying device camera hardware capabilities…"
    ) : CameraCapabilitiesState

    /**
     * Real camera engine has queried hardware characteristics (e.g. StreamConfigurationMap)
     * and populated verified capabilities per lens.
     */
    data class Verified(
        val activeLens: CameraLens,
        val availableLenses: List<CameraLens>,
        val capabilitiesByLens: Map<CameraLens, LensCapabilities>
    ) : CameraCapabilitiesState {
        val currentLensCapabilities: LensCapabilities? get() = capabilitiesByLens[activeLens]
    }
}

/**
 * Repository interface for managing and querying device camera capabilities.
 */
interface CameraCapabilitiesRepository {
    val capabilitiesState: StateFlow<CameraCapabilitiesState>
    val activeLens: StateFlow<CameraLens>
    fun setActiveLens(lens: CameraLens)
    fun setDiscoveredCapabilities(capabilities: Map<CameraLens, LensCapabilities>)
    fun resetToPending()
}

/**
 * Default in-memory implementation of [CameraCapabilitiesRepository].
 */
class DefaultCameraCapabilitiesRepository : CameraCapabilitiesRepository {
    private val _activeLens = MutableStateFlow(CameraLens.BACK_MAIN)
    override val activeLens: StateFlow<CameraLens> = _activeLens.asStateFlow()

    private val _capabilitiesState = MutableStateFlow<CameraCapabilitiesState>(
        CameraCapabilitiesState.PendingDiscovery(activeLens = _activeLens.value)
    )
    override val capabilitiesState: StateFlow<CameraCapabilitiesState> = _capabilitiesState.asStateFlow()

    override fun setActiveLens(lens: CameraLens) {
        _activeLens.value = lens
        val current = _capabilitiesState.value
        _capabilitiesState.value = when (current) {
            is CameraCapabilitiesState.PendingDiscovery -> current.copy(activeLens = lens)
            is CameraCapabilitiesState.Verified -> current.copy(activeLens = lens)
        }
    }

    override fun setDiscoveredCapabilities(capabilities: Map<CameraLens, LensCapabilities>) {
        if (capabilities.isEmpty()) return
        val resolvedActive = if (_activeLens.value in capabilities.keys) {
            _activeLens.value
        } else {
            capabilities.keys.first()
        }
        _activeLens.value = resolvedActive
        _capabilitiesState.value = CameraCapabilitiesState.Verified(
            activeLens = resolvedActive,
            availableLenses = capabilities.keys.toList(),
            capabilitiesByLens = capabilities
        )
    }

    override fun resetToPending() {
        _capabilitiesState.value = CameraCapabilitiesState.PendingDiscovery(activeLens = _activeLens.value)
    }
}

/**
 * Converts a user shutter speed string label (e.g. "1/1000s", "1/60s", "2s", "0.5s") into exposure time in nanoseconds.
 * Returns null if the value is "AUTO" or cannot be parsed.
 */
fun parseShutterSpeedToNanos(shutterSpeedStr: String): Long? {
    if (shutterSpeedStr.equals("AUTO", ignoreCase = true)) return null
    val cleaned = shutterSpeedStr.trim().removeSuffix("s").removeSuffix("S").trim()
    return if (cleaned.contains("/")) {
        val parts = cleaned.split("/")
        val num = parts.getOrNull(0)?.toDoubleOrNull() ?: 1.0
        val den = parts.getOrNull(1)?.toDoubleOrNull() ?: 60.0
        if (den != 0.0) {
            val sec = num / den
            (sec * 1_000_000_000.0).toLong().coerceAtLeast(1000L)
        } else null
    } else {
        val sec = cleaned.toDoubleOrNull() ?: return null
        (sec * 1_000_000_000.0).toLong().coerceAtLeast(1000L)
    }
}

/**
 * Formats exposure time in seconds into user-facing shutter label (e.g., "1/1000s", "1/60s", "2s").
 */
fun formatShutterSpeedLabel(sec: Float): String {
    return if (sec >= 1.0f) {
        if (kotlin.math.abs(sec - kotlin.math.round(sec)) < 0.05f) {
            "${kotlin.math.round(sec).toInt()}s"
        } else {
            String.format(Locale.US, "%.1fs", sec)
        }
    } else {
        val denominator = kotlin.math.round(1.0f / sec).toInt().coerceAtLeast(1)
        "1/${denominator}s"
    }
}

/**
 * Formats exposure time in nanoseconds into user-facing shutter label (e.g., "1/1000s", "1/60s", "2s").
 */
fun formatShutterSpeedFromNanos(nanos: Long): String {
    val sec = (nanos.toDouble() / 1_000_000_000.0).toFloat()
    return formatShutterSpeedLabel(sec)
}

/**
 * Maps Pro mode white balance preset ("AUTO", "DAYLIGHT", "CLOUDY", "SHADE", "INCANDESCENT", "TUNGSTEN", "FLUORESCENT", or Kelvin e.g. "5500K")
 * to the corresponding Camera2 [android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_*] constant.
 * Respects hardware-supported AWB modes, falling back to [CaptureRequest.CONTROL_AWB_MODE_AUTO] if unsupported.
 */
fun mapWhiteBalanceToCamera2AwbMode(wbStr: String, availableModes: List<Int> = emptyList()): Int {
    if (wbStr.equals("AUTO", ignoreCase = true)) {
        return CaptureRequest.CONTROL_AWB_MODE_AUTO
    }
    val upper = wbStr.uppercase(Locale.US).trim()
    val targetMode = when {
        upper == "DAYLIGHT" || upper == "5500K" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        upper == "CLOUDY" || upper == "6500K" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        upper == "SHADE" || upper == "7500K" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        upper == "INCANDESCENT" || upper == "TUNGSTEN" || upper == "3000K" || upper == "2800K" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        upper == "FLUORESCENT" || upper == "4000K" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        upper.endsWith("K") -> {
            val k = upper.removeSuffix("K").toIntOrNull() ?: 5500
            when {
                k <= 3500 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                k <= 4500 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                k <= 6000 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                k <= 7000 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                else -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            }
        }
        else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
    }
    return if (availableModes.isEmpty() || availableModes.contains(targetMode)) {
        targetMode
    } else {
        CaptureRequest.CONTROL_AWB_MODE_AUTO
    }
}

/**
 * Parses color temperature in Kelvin from a preset or custom string (e.g. "4500K", "Daylight", "Cloudy").
 */
fun parseKelvinFromWbString(wbStr: String): Int {
    val upper = wbStr.uppercase(Locale.US).trim()
    return when {
        upper.endsWith("K") -> upper.removeSuffix("K").toIntOrNull() ?: 5500
        upper == "INCANDESCENT" || upper == "TUNGSTEN" -> 2800
        upper == "FLUORESCENT" -> 4000
        upper == "DAYLIGHT" -> 5500
        upper == "CLOUDY" -> 6500
        upper == "SHADE" -> 7500
        else -> 5500
    }
}

/**
 * Maps a Kelvin color temperature [2000K .. 10000K] to Camera2 [android.hardware.camera2.params.RggbChannelVector]
 * based on standard Planckian blackbody locus color gains.
 */
fun kelvinToRggbChannelVector(kelvin: Int): android.hardware.camera2.params.RggbChannelVector {
    val k = kelvin.coerceIn(2000, 10000).toFloat()
    val rGain = if (k <= 5500f) {
        1.0f + (5500f - k) / 3500f * 1.25f
    } else {
        (5500f / k).coerceIn(0.5f, 1.0f)
    }
    val bGain = if (k >= 5500f) {
        1.0f + (k - 5500f) / 4500f * 1.5f
    } else {
        (k / 5500f).coerceIn(0.4f, 1.0f)
    }
    return android.hardware.camera2.params.RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
}

/**
 * Builds real Camera2 [CaptureRequestOptions] for Pro Mode manual sensor controls.
 * Deeply integrates ISO, Shutter Speed, Focus Mode & Distance, White Balance, and EV brightness scaling.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun buildProCamera2CaptureRequestOptions(
    manualIso: String,
    manualShutterSpeed: String,
    proFocusMode: ProFocusMode,
    proFocusDistance: Float,
    proWhiteBalance: String,
    manualCaps: ManualSensorCapabilities?,
    proEv: Float = 0.0f
): CaptureRequestOptions {
    val builder = CaptureRequestOptions.Builder()

    // 1. Exposure (ISO + Shutter Speed)
    val isAutoIso = manualIso.equals("AUTO", ignoreCase = true)
    val isAutoShutter = manualShutterSpeed.equals("AUTO", ignoreCase = true)

    if (isAutoIso && isAutoShutter) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    } else {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

        val hwIsoRange = manualCaps?.isoRange
        val baseIso = if (!isAutoIso) {
            val parsed = manualIso.toIntOrNull() ?: 100
            hwIsoRange?.let { parsed.coerceIn(it.start, it.endInclusive) } ?: parsed
        } else {
            hwIsoRange?.start ?: 100
        }

        // In fully manual exposure mode, EV acts as an explicit sensor exposure multiplier (2^EV)
        val evMultiplier = if (proEv != 0.0f) Math.pow(2.0, proEv.toDouble()) else 1.0
        val effectiveIso = if (!isAutoIso && proEv != 0.0f && hwIsoRange != null) {
            (baseIso * evMultiplier).toInt().coerceIn(hwIsoRange.start, hwIsoRange.endInclusive)
        } else {
            baseIso
        }
        builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, effectiveIso)

        val hwShutterRange = manualCaps?.shutterSpeedRangeNanos
        val baseShutterNanos = if (!isAutoShutter) {
            val parsed = parseShutterSpeedToNanos(manualShutterSpeed) ?: 16_666_666L
            hwShutterRange?.let { parsed.coerceIn(it.start, it.endInclusive) } ?: parsed
        } else {
            hwShutterRange?.let { 16_666_666L.coerceIn(it.start, it.endInclusive) } ?: 16_666_666L
        }
        builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, baseShutterNanos)
    }

    // 2. Focus (Auto vs Manual Focus)
    val isManualFocusSupported = manualCaps?.isManualFocusSupported == true
    if (proFocusMode == ProFocusMode.MANUAL && isManualFocusSupported) {
        val minFocusDist = manualCaps?.minimumFocusDistanceDiopters ?: 10.0f
        // Focus slider: 0.0f is Macro, 1.0f is Infinity
        val targetDiopters = ((1.0f - proFocusDistance.coerceIn(0.0f, 1.0f)) * minFocusDist).coerceAtLeast(0.0f)
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, targetDiopters)
    } else {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    // 3. White Balance
    if (proWhiteBalance.equals("AUTO", ignoreCase = true)) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    } else {
        val hasManualOff = manualCaps?.availableAwbModes?.contains(CaptureRequest.CONTROL_AWB_MODE_OFF) == true
        if (hasManualOff && (proWhiteBalance.endsWith("K", ignoreCase = true) || proWhiteBalance == "CUSTOM")) {
            val kelvin = parseKelvinFromWbString(proWhiteBalance)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggbChannelVector(kelvin))
        } else {
            val awbMode = mapWhiteBalanceToCamera2AwbMode(proWhiteBalance, manualCaps?.availableAwbModes ?: emptyList())
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        }
    }

    return builder.build()
}

/**
 * Builds real Camera2 [CaptureRequestOptions] for Astrophotography mode.
 * Dynamically configures long exposure / sensor sensitivity boost, focus mode (Auto vs Infinity),
 * and locks focus & exposure for multi-frame astrophotography consistency.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun buildAstroCamera2CaptureRequestOptions(
    astroFocus: AstroFocusMode,
    astroStrategy: AstroStrategy,
    manualCaps: ManualSensorCapabilities?,
    exposureCompensationIndex: Int = 0
): CaptureRequestOptions {
    val builder = CaptureRequestOptions.Builder()

    // 1. Focus Mode (Auto vs Infinity)
    if (astroFocus == AstroFocusMode.INFINITY) {
        val isManualFocusSupported = manualCaps?.isManualFocusSupported == true
        if (isManualFocusSupported) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // 0 diopters = infinity
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
    } else {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    // 2. Preview Exposure Strategy: Keep live repeating preview stream auto-exposed at 30 FPS.
    // Multi-second manual shutter exposure times apply exclusively during still capture sequence.
    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    if (manualCaps?.isHardwareNightSceneModeSupported == true) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
    }

    // 3. Exposure compensation
    if (manualCaps?.isExposureCompensationSupported == true && exposureCompensationIndex != 0) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensationIndex)
    }

    return builder.build()
}

/**
 * Checks whether the Camera2 hardware characteristics genuinely support hardware scene-mode HDR
 * (`CONTROL_SCENE_MODE_HDR` alongside `CONTROL_MODE_USE_SCENE_MODE`).
 */
fun isCamera2HardwareHdrSceneModeSupported(
    availableSceneModes: List<Int>,
    availableControlModes: List<Int>
): Boolean {
    val hasHdrScene = availableSceneModes.contains(CaptureRequest.CONTROL_SCENE_MODE_HDR)
    val hasUseSceneControl = availableControlModes.isEmpty() ||
        availableControlModes.contains(CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
    return hasHdrScene && hasUseSceneControl
}

/**
 * Checks whether the Camera2 hardware characteristics genuinely support hardware scene-mode Night
 * (`CONTROL_SCENE_MODE_NIGHT` alongside `CONTROL_MODE_USE_SCENE_MODE`).
 */
fun isCamera2HardwareNightSceneModeSupported(
    availableSceneModes: List<Int>,
    availableControlModes: List<Int>
): Boolean {
    val hasNightScene = availableSceneModes.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
    val hasUseSceneControl = availableControlModes.isEmpty() ||
        availableControlModes.contains(CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
    return hasNightScene && hasUseSceneControl
}

/**
 * Builds [CaptureRequestOptions] that strictly resets all Camera2 manual overrides when switching away
 * from Pro mode to any non-Pro mode (Photo, Video, Night Sight, Portrait, etc.).
 * Ensures AE mode returns to ON, AF returns to Continuous AF (or single-shot lock hold), AWB returns to AUTO,
 * and sensor sensitivity, exposure time, and manual focus distance overrides are not retained.
 * - When [enableHardwareHdrSceneMode] is true (only when genuinely supported in Photo mode and compatible with flash),
 *   applies Camera2 hardware `CONTROL_SCENE_MODE_HDR`.
 * - When [enableHardwareNightSceneMode] is true (only when genuinely supported in Night Sight mode),
 *   applies Camera2 hardware `CONTROL_SCENE_MODE_NIGHT`.
 * - When [enableHardwarePortraitBokeh] is true (only when genuinely supported in Portrait mode on API 30+),
 *   applies Camera2 `CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS` or `CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE`
 *   and hardware face detection (`STATISTICS_FACE_DETECT_MODE_SIMPLE`) when supported.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun buildNonProResetCamera2CaptureRequestOptions(
    isVideoMode: Boolean,
    isHardwareAfLockActive: Boolean,
    supportsAfAutoHold: Boolean,
    enableHardwareHdrSceneMode: Boolean = false,
    enableHardwareNightSceneMode: Boolean = false,
    enableHardwarePortraitBokeh: Boolean = false,
    supportsExtendedBokehContinuous: Boolean = false,
    supportsExtendedBokehStill: Boolean = false,
    enableHardwareFaceDetect: Boolean = false,
    exposureCompensationIndex: Int = 0,
    overrideAfMode: Boolean = true
): CaptureRequestOptions {
    val builder = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensationIndex)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)

    if (!isVideoMode && enableHardwareNightSceneMode) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
    } else if (!isVideoMode && enableHardwareHdrSceneMode && exposureCompensationIndex == 0) {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
    } else {
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
    }

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        if (!isVideoMode && enableHardwarePortraitBokeh && (supportsExtendedBokehContinuous || supportsExtendedBokehStill)) {
            val bokehMode = if (supportsExtendedBokehContinuous) {
                CaptureRequest.CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS
            } else {
                CaptureRequest.CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_EXTENDED_SCENE_MODE, bokehMode)
        } else if (supportsExtendedBokehContinuous || supportsExtendedBokehStill) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_EXTENDED_SCENE_MODE,
                CaptureRequest.CONTROL_EXTENDED_SCENE_MODE_DISABLED
            )
        }
    }

    if (enableHardwareFaceDetect) {
        builder.setCaptureRequestOption(
            CaptureRequest.STATISTICS_FACE_DETECT_MODE,
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
        )
    }

    if (overrideAfMode) {
        val afMode = when {
            isVideoMode -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            isHardwareAfLockActive && supportsAfAutoHold -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
    }
    return builder.build()
}

/**
 * Verified per-lens video capability profile combining Camera2 stream sizes, min-frame durations,
 * AE target FPS ranges, high-speed configurations, EncoderProfiles/CamcorderProfile, hardware
 * MediaCodec encoder limits, and stabilization capabilities.
 */
data class VerifiedVideoCapabilityProfile(
    val supportedVideoQualities: Map<VideoResolution, List<VideoFrameRate>>,
    val supportedSlowMoRates: List<VideoFrameRate>,
    val supportedSlowMoResolutionsByRate: Map<VideoFrameRate, List<VideoResolution>>,
    val supportedStabilizationModesByConfig: Map<Pair<VideoResolution, VideoFrameRate>, List<VideoStabilizationMode>>,
    val isStabilizationSupported: Boolean,
    val isDigitalVideoStabilizationSupported: Boolean = false,
    val isPreviewStabilizationSupported: Boolean = false,
    val isOpticalStabilizationSupported: Boolean = false,
    val isActiveStabilizationSupported: Boolean
)

/**
 * Pure, testable verification logic for building a per-lens [VerifiedVideoCapabilityProfile].
 *
 * Rules enforced:
 * 1. Never uses a generic capability list across lenses; evaluates only the stream sizes and FPS bounds
 *    genuinely exposed by the specific lens, its encoder profiles, and hardware video encoders.
 * 2. Does not treat an AE FPS range alone as proof that every resolution can record at 60 FPS:
 *    60 FPS at a given [VideoResolution] requires BOTH camera sensor/stream readout support
 *    (AE target FPS range >= 60 or high-speed range >= 60, plus stream min-frame duration <= ~18.5ms
 *    or explicit high-speed / CamcorderProfile / EncoderProfiles 60fps support) AND hardware video
 *    encoder throughput at that resolution (`codecMaxFpsByResolution >= 59.0` or profile >= 60).
 * 3. Evaluates `720p @ 30/60`, `1080p @ 30/60`, `2K (2560x1440) @ 30`, and `4K (3840x2160) @ 30/60`
 *    independently for front and rear cameras.
 * 4. Populates per-(resolution, frameRate) hardware stabilization support (`OFF`, `STANDARD`, `ACTIVE`),
 *    distinguishing hardware video stabilization (`CONTROL_VIDEO_STABILIZATION_MODE_ON`),
 *    preview stabilization (`CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION`), and OIS
 *    (`LENS_OPTICAL_STABILIZATION_MODE_ON`).
 */
fun buildVerifiedVideoCapabilityProfile(
    streamVideoSizes: Set<Pair<Int, Int>>,
    minFrameDurationNanosByResolution: Map<VideoResolution, Long>,
    aeFpsRanges: List<Pair<Int, Int>>,
    highSpeedSizeToMaxFps: Map<Pair<Int, Int>, Int>,
    cameraXSupportedResolutions: Set<VideoResolution>,
    camcorderMaxFpsByResolution: Map<VideoResolution, Int>,
    codecMaxFpsByResolution: Map<VideoResolution, Double>,
    availableVideoStabilizationModes: List<Int>,
    availableOpticalStabilizationModes: List<Int>,
    isCameraXVideoStabilizationSupported: Boolean = false,
    isCameraXPreviewStabilizationSupported: Boolean = false
): VerifiedVideoCapabilityProfile {
    val maxAeFps = aeFpsRanges.maxOfOrNull { it.second } ?: 30
    val has24FpsAe = aeFpsRanges.any { (lower, upper) ->
        upper == 24 || (lower <= 24 && upper >= 24)
    }
    val has60FpsAe = maxAeFps >= 60

    val candidateResolutions = listOf(
        VideoResolution.HD_720P,
        VideoResolution.FHD_1080P,
        VideoResolution.QHD_2K,
        VideoResolution.UHD_4K
    )

    val supportedVideoQualities = LinkedHashMap<VideoResolution, List<VideoFrameRate>>()

    for (res in candidateResolutions) {
        val exactPair = res.width to res.height
        val hasExactStreamSize = exactPair in streamVideoSizes ||
            streamVideoSizes.any { (w, h) ->
                val maxD = maxOf(w, h)
                val minD = minOf(w, h)
                maxD == res.width && minD == res.height
            }
        val inCameraX = res in cameraXSupportedResolutions
        val inCamcorder = (camcorderMaxFpsByResolution[res] ?: 0) > 0
        val codecMaxFps = codecMaxFpsByResolution[res] ?: 0.0
        val isEncoderCapableFor30 = codecMaxFps >= 29.0 || inCamcorder || inCameraX

        // A resolution is supported if the camera stream map exposes it (and encoder can encode it)
        // or CameraX / CamcorderProfile explicitly validates it on this lens.
        if (!((hasExactStreamSize && isEncoderCapableFor30) || inCameraX || inCamcorder)) {
            continue
        }

        val rates = mutableListOf<VideoFrameRate>()
        if (has24FpsAe) {
            rates.add(VideoFrameRate.FPS_24)
        }
        rates.add(VideoFrameRate.FPS_30)

        // Check whether 60 FPS is genuinely supported for THIS specific resolution:
        // 1. Sensor/stream readout check: minFrameDuration <= 18.5ms (~54+ FPS) OR highSpeed map >= 60
        //    OR CamcorderProfile/EncoderProfiles explicitly advertises >= 59 FPS for this resolution.
        val minDurationNs = minFrameDurationNanosByResolution[res] ?: 0L
        val highSpeedMaxFps = highSpeedSizeToMaxFps[exactPair]
            ?: highSpeedSizeToMaxFps.entries
                .firstOrNull { (dim, _) -> maxOf(dim.first, dim.second) == res.width && minOf(dim.first, dim.second) == res.height }
                ?.value
            ?: 0
        val profileMaxFps = camcorderMaxFpsByResolution[res] ?: 0

        // Note: QHD_2K (2560x1440) is capped at 30 FPS unless both profile/high-speed AND encoder explicitly support 60 FPS,
        // matching the target 2K @ 30 FPS requirement and avoiding unstable 2K60 configurations.
        val sensorReadoutSupports60 = when {
            profileMaxFps >= 59 -> true
            highSpeedMaxFps >= 60 -> true
            minDurationNs in 1L..18_500_000L && has60FpsAe -> true
            // When minDurationNs is not reported (0L) for a standard HD/FHD stream on a Camera2 HAL that exposes
            // a 60 FPS AE target range and hardware encoder support for 60 FPS at 720p/1080p:
            minDurationNs == 0L && has60FpsAe && (res == VideoResolution.HD_720P || res == VideoResolution.FHD_1080P) -> true
            else -> false
        }

        val encoderSupports60 = codecMaxFps >= 58.0 || profileMaxFps >= 59 || highSpeedMaxFps >= 60

        val is60FpsSupportedForRes = if (res == VideoResolution.QHD_2K) {
            profileMaxFps >= 59 && codecMaxFps >= 58.0 && minDurationNs in 1L..17_500_000L && has60FpsAe
        } else {
            sensorReadoutSupports60 && encoderSupports60 && (has60FpsAe || highSpeedMaxFps >= 60 || profileMaxFps >= 59)
        }

        if (is60FpsSupportedForRes) {
            rates.add(VideoFrameRate.FPS_60)
        }

        supportedVideoQualities[res] = rates
    }

    if (supportedVideoQualities.isEmpty()) {
        supportedVideoQualities[VideoResolution.HD_720P] = listOf(VideoFrameRate.FPS_30)
        supportedVideoQualities[VideoResolution.FHD_1080P] = listOf(VideoFrameRate.FPS_30)
    }

    // Slow-motion (120 / 240 / 360 / 480 FPS) discovery per resolution
    val slowMoCandidates = listOf(
        VideoFrameRate.FPS_120,
        VideoFrameRate.FPS_240,
        VideoFrameRate.FPS_360,
        VideoFrameRate.FPS_480
    )
    val maxOverallHighSpeedFps = maxOf(
        maxAeFps,
        highSpeedSizeToMaxFps.values.maxOrNull() ?: 0
    )
    val supportedSlowMoRates = mutableListOf<VideoFrameRate>()
    val supportedSlowMoResolutionsByRate = LinkedHashMap<VideoFrameRate, List<VideoResolution>>()

    for (slowRate in slowMoCandidates) {
        val resForRate = mutableListOf<VideoResolution>()
        for (res in listOf(VideoResolution.HD_720P, VideoResolution.FHD_1080P)) {
            val hsMax = highSpeedSizeToMaxFps[res.width to res.height]
                ?: highSpeedSizeToMaxFps.entries
                    .firstOrNull { (dim, _) -> maxOf(dim.first, dim.second) == res.width && minOf(dim.first, dim.second) == res.height }
                    ?.value
                ?: 0
            val minDur = minFrameDurationNanosByResolution[res] ?: 0L
            val minDurFps = if (minDur > 0L) (1_000_000_000.0 / minDur.toDouble()).toInt() else 0
            if (hsMax >= slowRate.fps || minDurFps >= slowRate.fps) {
                resForRate.add(res)
            }
        }
        if (resForRate.isEmpty() && maxOverallHighSpeedFps >= slowRate.fps) {
            if (VideoResolution.HD_720P in supportedVideoQualities) {
                resForRate.add(VideoResolution.HD_720P)
            }
        }
        if (resForRate.isNotEmpty()) {
            supportedSlowMoRates.add(slowRate)
            supportedSlowMoResolutionsByRate[slowRate] = resForRate
        }
    }

    // Stabilization capability detection:
    // Mode 1 = CONTROL_VIDEO_STABILIZATION_MODE_ON
    // Mode 2 = CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (API 33+)
    val hasDigitalVideoStab = availableVideoStabilizationModes.contains(
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON
    ) || isCameraXVideoStabilizationSupported
    val hasPreviewStab = availableVideoStabilizationModes.contains(2) || isCameraXPreviewStabilizationSupported
    val hasOis = availableOpticalStabilizationModes.contains(
        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON
    )

    val isAnyStabilizationSupported = hasDigitalVideoStab || hasPreviewStab || hasOis
    val isActiveStabilizationSupported = (hasDigitalVideoStab && hasOis) || hasPreviewStab

    val supportedStabilizationModesByConfig = LinkedHashMap<Pair<VideoResolution, VideoFrameRate>, List<VideoStabilizationMode>>()
    for ((res, rates) in supportedVideoQualities) {
        for (fps in rates) {
            val modes = mutableListOf(VideoStabilizationMode.OFF)
            val isStandardSupportedForCombo = when (res) {
                VideoResolution.HD_720P -> hasDigitalVideoStab || hasPreviewStab || hasOis
                VideoResolution.FHD_1080P -> {
                    if (fps == VideoFrameRate.FPS_60) {
                        hasDigitalVideoStab || hasPreviewStab || hasOis
                    } else {
                        hasDigitalVideoStab || hasPreviewStab || hasOis
                    }
                }
                VideoResolution.QHD_2K -> {
                    (fps != VideoFrameRate.FPS_60 && (hasDigitalVideoStab || hasPreviewStab)) || hasOis
                }
                VideoResolution.UHD_4K -> {
                    hasOis || (fps != VideoFrameRate.FPS_60 && hasPreviewStab)
                }
            }
            if (isStandardSupportedForCombo) {
                modes.add(VideoStabilizationMode.STANDARD)
            }
            if (isActiveStabilizationSupported &&
                (res == VideoResolution.HD_720P || res == VideoResolution.FHD_1080P) &&
                fps != VideoFrameRate.FPS_60
            ) {
                modes.add(VideoStabilizationMode.ACTIVE)
            }
            supportedStabilizationModesByConfig[res to fps] = modes
        }
    }

    return VerifiedVideoCapabilityProfile(
        supportedVideoQualities = supportedVideoQualities,
        supportedSlowMoRates = supportedSlowMoRates,
        supportedSlowMoResolutionsByRate = supportedSlowMoResolutionsByRate,
        supportedStabilizationModesByConfig = supportedStabilizationModesByConfig,
        isStabilizationSupported = hasDigitalVideoStab || hasPreviewStab,
        isDigitalVideoStabilizationSupported = hasDigitalVideoStab,
        isPreviewStabilizationSupported = hasPreviewStab,
        isOpticalStabilizationSupported = hasOis,
        isActiveStabilizationSupported = isActiveStabilizationSupported
    )
}


