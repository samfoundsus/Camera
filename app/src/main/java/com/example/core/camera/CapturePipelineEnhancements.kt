package com.example.core.camera

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.FlashMode
import com.example.core.preferences.HdrMode
import com.example.core.preferences.NightSightExposure
import com.example.core.preferences.PhotoCaptureSpeedMode
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.example.core.processing.AspectCropUtils
import com.example.core.processing.ProcessedPhotoPayload
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

private const val PIPELINE_TAG = "CapturePipeline"
private const val LATENCY_TAG = "CaptureLatency"

/**
 * Hardware-verified CameraX OEM Extension availability for a single camera lens.
 * Populated strictly from [ExtensionsManager.isExtensionAvailable]; never fabricated.
 */
data class CameraExtensionAvailability(
    val isHdrAvailable: Boolean = false,
    val isNightAvailable: Boolean = false,
    val isAutoAvailable: Boolean = false,
    val isFaceRetouchAvailable: Boolean = false,
    val isBokehAvailable: Boolean = false
) {
    val supportedExtensionNames: List<String>
        get() = buildList {
            if (isHdrAvailable) add("HDR")
            if (isNightAvailable) add("Night")
            if (isAutoAvailable) add("Auto")
            if (isFaceRetouchAvailable) add("Face Retouch")
            if (isBokehAvailable) add("Bokeh")
        }

    val hasAnyExtension: Boolean
        get() = isHdrAvailable || isNightAvailable || isAutoAvailable || isFaceRetouchAvailable || isBokehAvailable
}

/**
 * Hardware-verified Camera2 ISP processing quality capabilities and Zero-Shutter-Lag support.
 */
data class IspQualityCapabilities(
    val availableNoiseReductionModes: List<Int> = emptyList(),
    val availableEdgeModes: List<Int> = emptyList(),
    val availableTonemapModes: List<Int> = emptyList(),
    val availableColorCorrectionAberrationModes: List<Int> = emptyList(),
    val isZslSupported: Boolean = false
) {
    val supportsHighQualityNoiseReduction: Boolean
        get() = availableNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    val supportsFastNoiseReduction: Boolean
        get() = availableNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)

    val supportsHighQualityEdge: Boolean
        get() = availableEdgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)
    val supportsFastEdge: Boolean
        get() = availableEdgeModes.contains(CaptureRequest.EDGE_MODE_FAST)

    val supportsHighQualityTonemap: Boolean
        get() = availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
    val supportsFastTonemap: Boolean
        get() = availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_FAST)

    val supportsHighQualityAberration: Boolean
        get() = availableColorCorrectionAberrationModes.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
    val supportsFastAberration: Boolean
        get() = availableColorCorrectionAberrationModes.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)

    fun summaryLabel(): String {
        val hqFeatures = buildList {
            if (supportsHighQualityNoiseReduction) add("NR-HQ")
            if (supportsHighQualityEdge) add("Edge-HQ")
            if (supportsHighQualityTonemap) add("Tonemap-HQ")
            if (supportsHighQualityAberration) add("Aberration-HQ")
        }
        val zslText = if (isZslSupported) "ZSL=Yes" else "ZSL=No"
        val ispText = if (hqFeatures.isEmpty()) "ISP-HQ=None" else hqFeatures.joinToString(", ")
        return "$zslText | $ispText"
    }

    companion object {
        fun fromCameraCharacteristics(
            characteristics: CameraCharacteristics,
            cameraXZslSupported: Boolean = false
        ): IspQualityCapabilities {
            val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList()
                ?: emptyList()
            val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList()
                ?: emptyList()
            val tonemapModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList()
                ?: emptyList()
            val aberrationModes = characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)?.toList()
                ?: emptyList()
            val reqCaps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()
                ?: emptyList()
            val hwReprocessing = reqCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING) ||
                reqCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING)
            return IspQualityCapabilities(
                availableNoiseReductionModes = nrModes,
                availableEdgeModes = edgeModes,
                availableTonemapModes = tonemapModes,
                availableColorCorrectionAberrationModes = aberrationModes,
                isZslSupported = cameraXZslSupported || hwReprocessing
            )
        }
    }
}

/**
 * Helper for querying and selecting real CameraX OEM Extensions (`HDR`, `NIGHT`, `AUTO`, `FACE_RETOUCH`, `BOKEH`).
 */
object CameraExtensionsHelper {

    fun queryAvailability(
        context: Context,
        cameraProvider: ProcessCameraProvider?,
        baseCameraSelector: CameraSelector
    ): CameraExtensionAvailability {
        if (cameraProvider == null) return CameraExtensionAvailability()
        return try {
            val manager = ExtensionsManager.getInstanceAsync(context, cameraProvider)
                .get(1500L, TimeUnit.MILLISECONDS)
            CameraExtensionAvailability(
                isHdrAvailable = runCatching {
                    manager.isExtensionAvailable(baseCameraSelector, ExtensionMode.HDR)
                }.getOrDefault(false),
                isNightAvailable = runCatching {
                    manager.isExtensionAvailable(baseCameraSelector, ExtensionMode.NIGHT)
                }.getOrDefault(false),
                isAutoAvailable = runCatching {
                    manager.isExtensionAvailable(baseCameraSelector, ExtensionMode.AUTO)
                }.getOrDefault(false),
                isFaceRetouchAvailable = runCatching {
                    manager.isExtensionAvailable(baseCameraSelector, ExtensionMode.FACE_RETOUCH)
                }.getOrDefault(false),
                isBokehAvailable = runCatching {
                    manager.isExtensionAvailable(baseCameraSelector, ExtensionMode.BOKEH)
                }.getOrDefault(false)
            )
        } catch (t: Throwable) {
            Log.d(PIPELINE_TAG, "CameraX Extensions unavailable on this device: ${t.localizedMessage}")
            CameraExtensionAvailability()
        }
    }

    /**
     * Resolves the real [ExtensionMode] to bind for the current mode and user preferences.
     * Never returns an extension mode that the device does not explicitly report as available.
     */
    fun resolveDesiredExtensionMode(
        availability: CameraExtensionAvailability,
        activeMode: CameraMode,
        hdrMode: HdrMode,
        portraitRetouch: PortraitRetouch,
        portraitBlurLevel: PortraitBlurLevel,
        nightSightExposure: NightSightExposure,
        photoCaptureSpeedMode: PhotoCaptureSpeedMode
    ): Int {
        return when (activeMode) {
            CameraMode.PHOTO -> {
                when (hdrMode) {
                    HdrMode.ON -> if (availability.isHdrAvailable) ExtensionMode.HDR else ExtensionMode.NONE
                    HdrMode.AUTO -> when {
                        availability.isHdrAvailable -> ExtensionMode.HDR
                        availability.isAutoAvailable -> ExtensionMode.AUTO
                        else -> ExtensionMode.NONE
                    }
                    HdrMode.OFF -> if (photoCaptureSpeedMode == PhotoCaptureSpeedMode.QUALITY && availability.isAutoAvailable) {
                        ExtensionMode.AUTO
                    } else {
                        ExtensionMode.NONE
                    }
                }
            }
            CameraMode.NIGHT_SIGHT -> {
                if (nightSightExposure != NightSightExposure.OFF && availability.isNightAvailable) {
                    ExtensionMode.NIGHT
                } else {
                    ExtensionMode.NONE
                }
            }
            CameraMode.PORTRAIT -> {
                when {
                    portraitRetouch != PortraitRetouch.OFF && availability.isFaceRetouchAvailable -> ExtensionMode.FACE_RETOUCH
                    portraitBlurLevel != PortraitBlurLevel.OFF && availability.isBokehAvailable -> ExtensionMode.BOKEH
                    else -> ExtensionMode.NONE
                }
            }
            else -> ExtensionMode.NONE
        }
    }

    /**
     * Returns an extension-enabled [CameraSelector] when [extensionMode] != [ExtensionMode.NONE]
     * and supported by [ExtensionsManager], or safely falls back to [baseSelector].
     */
    fun resolveCameraSelectorWithExtension(
        context: Context,
        cameraProvider: ProcessCameraProvider,
        baseSelector: CameraSelector,
        extensionMode: Int
    ): Pair<CameraSelector, Int> {
        if (extensionMode == ExtensionMode.NONE) return baseSelector to ExtensionMode.NONE
        return try {
            val manager = ExtensionsManager.getInstanceAsync(context, cameraProvider)
                .get(1500L, TimeUnit.MILLISECONDS)
            if (manager.isExtensionAvailable(baseSelector, extensionMode)) {
                manager.getExtensionEnabledCameraSelector(baseSelector, extensionMode) to extensionMode
            } else {
                baseSelector to ExtensionMode.NONE
            }
        } catch (t: Throwable) {
            Log.w(PIPELINE_TAG, "Falling back to base CameraSelector (extension $extensionMode failed): ${t.localizedMessage}")
            baseSelector to ExtensionMode.NONE
        }
    }

    fun extensionModeDisplayName(extensionMode: Int): String = when (extensionMode) {
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.NIGHT -> "Night"
        ExtensionMode.AUTO -> "Auto"
        ExtensionMode.FACE_RETOUCH -> "Face Retouch"
        ExtensionMode.BOKEH -> "Bokeh"
        else -> "None"
    }
}

/**
 * Pure policy engine for CameraX capture modes, Camera2 ISP quality keys, JPEG quality,
 * and 3A (AE/AF/AWB) convergence gating.
 */
object CameraCaptureQualityPolicy {

    const val SAVED_JPEG_QUALITY = 100

    /**
     * Chooses the exact CameraX [ImageCapture] capture mode:
     * - HD, Night Sight, Portrait, Pro, Astro -> `CAPTURE_MODE_MAXIMIZE_QUALITY`
     * - Photo (Quality toggle) -> `CAPTURE_MODE_MAXIMIZE_QUALITY`
     * - Photo (Fast toggle) -> `CAPTURE_MODE_ZERO_SHUTTER_LAG` where ZSL is supported (no extension & flash not forced ON),
     *   otherwise `CAPTURE_MODE_MINIMIZE_LATENCY`
     * - Panorama -> `CAPTURE_MODE_MINIMIZE_LATENCY`
     */
    fun resolveCameraXCaptureMode(
        activeMode: CameraMode,
        photoCaptureSpeedMode: PhotoCaptureSpeedMode,
        isZslSupported: Boolean,
        activeExtensionMode: Int = ExtensionMode.NONE,
        flashMode: FlashMode = FlashMode.AUTO
    ): Int {
        return when (activeMode) {
            CameraMode.HD,
            CameraMode.NIGHT_SIGHT,
            CameraMode.PORTRAIT,
            CameraMode.PRO,
            CameraMode.ASTRO -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY

            CameraMode.PHOTO -> {
                if (photoCaptureSpeedMode == PhotoCaptureSpeedMode.QUALITY) {
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                } else if (isZslSupported &&
                    activeExtensionMode == ExtensionMode.NONE &&
                    flashMode != FlashMode.ON
                ) {
                    ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                } else {
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }
            }

            CameraMode.PANORAMA,
            CameraMode.VIDEO,
            CameraMode.SLOW_MOTION,
            CameraMode.TIME_LAPSE -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }

    /**
     * Resolves whether ISP capture quality keys should target `HIGH_QUALITY` vs `FAST`.
     */
    fun shouldUseHighQualityIspForStillCapture(
        activeMode: CameraMode,
        photoCaptureSpeedMode: PhotoCaptureSpeedMode
    ): Boolean = when (activeMode) {
        CameraMode.HD,
        CameraMode.NIGHT_SIGHT,
        CameraMode.PORTRAIT,
        CameraMode.PRO,
        CameraMode.ASTRO -> true
        CameraMode.PHOTO -> photoCaptureSpeedMode == PhotoCaptureSpeedMode.QUALITY
        else -> false
    }

    /**
     * Applies Camera2 ISP quality keys (`NOISE_REDUCTION_MODE`, `EDGE_MODE`, `TONEMAP_MODE`,
     * `COLOR_CORRECTION_ABERRATION_MODE`) strictly when the lens reports support for them.
     * Uses `HIGH_QUALITY` when [preferHighQuality] is true, and `FAST` for preview / Fast mode.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun applyIspQualityToCaptureRequestOptions(
        builder: CaptureRequestOptions.Builder,
        ispCaps: IspQualityCapabilities?,
        preferHighQuality: Boolean
    ) {
        if (ispCaps == null) return

        val nrMode = when {
            preferHighQuality && ispCaps.supportsHighQualityNoiseReduction -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            ispCaps.supportsFastNoiseReduction -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> null
        }
        nrMode?.let { builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, it) }

        val edgeMode = when {
            preferHighQuality && ispCaps.supportsHighQualityEdge -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            ispCaps.supportsFastEdge -> CaptureRequest.EDGE_MODE_FAST
            else -> null
        }
        edgeMode?.let { builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, it) }

        val tonemapMode = when {
            preferHighQuality && ispCaps.supportsHighQualityTonemap -> CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            ispCaps.supportsFastTonemap -> CaptureRequest.TONEMAP_MODE_FAST
            else -> null
        }
        tonemapMode?.let { builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, it) }

        val aberrationMode = when {
            preferHighQuality && ispCaps.supportsHighQualityAberration -> CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            ispCaps.supportsFastAberration -> CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
            else -> null
        }
        aberrationMode?.let { builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
    }

    /**
     * Applies ISP quality keys and `JPEG_QUALITY = 100` to an [ImageCapture.Builder] via [Camera2Interop.Extender].
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun applyStillCaptureCamera2Interop(
        imageCaptureBuilder: ImageCapture.Builder,
        ispCaps: IspQualityCapabilities?,
        preferHighQuality: Boolean
    ) {
        val extender = Camera2Interop.Extender(imageCaptureBuilder)
        extender.setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, SAVED_JPEG_QUALITY.toByte())

        if (ispCaps == null) return

        val nrMode = when {
            preferHighQuality && ispCaps.supportsHighQualityNoiseReduction -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            ispCaps.supportsFastNoiseReduction -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> null
        }
        nrMode?.let { extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, it) }

        val edgeMode = when {
            preferHighQuality && ispCaps.supportsHighQualityEdge -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            ispCaps.supportsFastEdge -> CaptureRequest.EDGE_MODE_FAST
            else -> null
        }
        edgeMode?.let { extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, it) }

        val tonemapMode = when {
            preferHighQuality && ispCaps.supportsHighQualityTonemap -> CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            ispCaps.supportsFastTonemap -> CaptureRequest.TONEMAP_MODE_FAST
            else -> null
        }
        tonemapMode?.let { extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, it) }

        val aberrationMode = when {
            preferHighQuality && ispCaps.supportsHighQualityAberration -> CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            ispCaps.supportsFastAberration -> CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
            else -> null
        }
        aberrationMode?.let { extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
    }

    /**
     * Applies Camera2 ISP quality keys directly onto a raw [CaptureRequest.Builder] (used by dedicated HD max-res Camera2 captures).
     */
    fun applyIspQualityToCamera2Builder(
        builder: CaptureRequest.Builder,
        ispCaps: IspQualityCapabilities?,
        preferHighQuality: Boolean
    ) {
        builder.set(CaptureRequest.JPEG_QUALITY, SAVED_JPEG_QUALITY.toByte())
        if (ispCaps == null) return

        val nrMode = when {
            preferHighQuality && ispCaps.supportsHighQualityNoiseReduction -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            ispCaps.supportsFastNoiseReduction -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> null
        }
        nrMode?.let { builder.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }

        val edgeMode = when {
            preferHighQuality && ispCaps.supportsHighQualityEdge -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            ispCaps.supportsFastEdge -> CaptureRequest.EDGE_MODE_FAST
            else -> null
        }
        edgeMode?.let { builder.set(CaptureRequest.EDGE_MODE, it) }

        val tonemapMode = when {
            preferHighQuality && ispCaps.supportsHighQualityTonemap -> CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            ispCaps.supportsFastTonemap -> CaptureRequest.TONEMAP_MODE_FAST
            else -> null
        }
        tonemapMode?.let { builder.set(CaptureRequest.TONEMAP_MODE, it) }

        val aberrationMode = when {
            preferHighQuality && ispCaps.supportsHighQualityAberration -> CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            ispCaps.supportsFastAberration -> CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
            else -> null
        }
        aberrationMode?.let { builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
    }

    /**
     * Checks whether AE, AF, and AWB have all converged from the continuously running preview stream.
     */
    fun isAeConverged(aeState: Int): Boolean =
        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
            aeState == CaptureResult.CONTROL_AE_STATE_INACTIVE

    fun isAfConverged(afState: Int): Boolean =
        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_INACTIVE

    fun isAwbConverged(awbState: Int): Boolean =
        awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED ||
            awbState == CaptureResult.CONTROL_AWB_STATE_INACTIVE

    fun is3AConverged(aeState: Int, afState: Int, awbState: Int): Boolean =
        isAeConverged(aeState) && isAfConverged(afState) && isAwbConverged(awbState)

    /**
     * Returns true only when precapture settling/waiting is actually needed:
     * - Flash is forced ON or AE reports FLASH_REQUIRED under FlashMode.AUTO
     * - AE, AF, or AWB has not yet converged on the live preview
     * - Portrait face-metering or an explicit tap-to-focus just requested convergence
     */
    fun needsPrecaptureConvergenceWait(
        aeState: Int,
        afState: Int,
        awbState: Int,
        flashMode: FlashMode,
        activeMode: CameraMode,
        hasActiveTapFocus: Boolean
    ): Boolean {
        val flashNeeded = flashMode == FlashMode.ON ||
            (flashMode == FlashMode.AUTO && aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)
        if (flashNeeded) return true
        if (activeMode == CameraMode.PORTRAIT || hasActiveTapFocus) return true
        return !is3AConverged(aeState, afState, awbState)
    }
}

/**
 * Bounded background photo save queue.
 * Allows the next photo to be captured immediately while previous photo(s) finish
 * processing and writing to MediaStore on a background thread, while capping concurrent
 * in-flight payloads so memory cannot run out under rapid-fire capture.
 */
class BoundedPhotoSaveQueue(
    val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT
) {
    private val safeCapacity = maxInFlight.coerceIn(1, 4)
    private val permits = Semaphore(safeCapacity, true)
    private val pendingCounter = AtomicInteger(0)
    private val workerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhotoSaveQueueWorker").apply { priority = Thread.NORM_PRIORITY }
    }

    val pendingCount: Int
        get() = pendingCounter.get()

    val isFull: Boolean
        get() = permits.availablePermits() <= 0

    /**
     * Attempts to enqueue a save job on the background worker thread without blocking the main thread.
     * Returns `true` if accepted, or `false` if the queue is at its memory-safe cap.
     */
    fun tryEnqueue(task: () -> Unit): Boolean {
        if (!permits.tryAcquire()) {
            Log.w(PIPELINE_TAG, "PhotoSaveQueue at capacity ($safeCapacity in flight); rejecting burst overflow safely")
            return false
        }
        pendingCounter.incrementAndGet()
        try {
            workerExecutor.execute {
                try {
                    task()
                } finally {
                    pendingCounter.decrementAndGet()
                    permits.release()
                }
            }
            return true
        } catch (t: Throwable) {
            pendingCounter.decrementAndGet()
            permits.release()
            Log.e(PIPELINE_TAG, "Failed to submit save task to PhotoSaveQueue: ${t.localizedMessage}", t)
            return false
        }
    }

    fun shutdown() {
        runCatching { workerExecutor.shutdown() }
    }

    companion object {
        const val DEFAULT_MAX_IN_FLIGHT = 3
    }
}

/**
 * End-to-end capture latency measurement recorded from shutter press to MediaStore file write.
 */
data class CaptureLatencyRecord(
    val timestampMs: Long,
    val modeName: String,
    val speedModeLabel: String,
    val extensionLabel: String,
    val capturePathLabel: String,
    val shutterToCaptureMs: Long,
    val captureToSavedMs: Long,
    val totalShutterToSavedMs: Long,
    val width: Int,
    val height: Int
) {
    fun formatSummaryLine(): String {
        val dims = if (width > 0 && height > 0) "${width}x${height}" else "unknown"
        return "$modeName [$speedModeLabel | Ext:$extensionLabel | $capturePathLabel] " +
            "total=${totalShutterToSavedMs}ms (capture=${shutterToCaptureMs}ms, save=${captureToSavedMs}ms) $dims"
    }
}

/**
 * Thread-safe capture latency logger and recent history tracker shown in the hidden Hardware Debug screen.
 */
object CaptureLatencyTracker {
    private const val MAX_RECORDS = 10
    private val lock = Any()
    private val records = ArrayDeque<CaptureLatencyRecord>()

    fun record(
        modeName: String,
        speedModeLabel: String,
        extensionLabel: String,
        capturePathLabel: String,
        shutterPressedAtMs: Long,
        frameCapturedAtMs: Long,
        savedAtMs: Long,
        width: Int,
        height: Int
    ): CaptureLatencyRecord {
        val safeCaptureMs = (frameCapturedAtMs - shutterPressedAtMs).coerceAtLeast(0L)
        val safeSaveMs = (savedAtMs - frameCapturedAtMs).coerceAtLeast(0L)
        val safeTotalMs = (savedAtMs - shutterPressedAtMs).coerceAtLeast(0L)
        val entry = CaptureLatencyRecord(
            timestampMs = savedAtMs,
            modeName = modeName,
            speedModeLabel = speedModeLabel,
            extensionLabel = extensionLabel,
            capturePathLabel = capturePathLabel,
            shutterToCaptureMs = safeCaptureMs,
            captureToSavedMs = safeSaveMs,
            totalShutterToSavedMs = safeTotalMs,
            width = width,
            height = height
        )
        synchronized(lock) {
            if (records.size >= MAX_RECORDS) {
                records.removeFirst()
            }
            records.addLast(entry)
        }
        Log.i(LATENCY_TAG, entry.formatSummaryLine())
        return entry
    }

    fun getRecentRecords(): List<CaptureLatencyRecord> = synchronized(lock) {
        records.toList().reversed()
    }

    fun clear() = synchronized(lock) {
        records.clear()
    }
}

/**
 * Memory-safe streaming multi-frame noise-reduction accumulator for Quality Photo mode (3–5 frames).
 *
 * Merges frames ONE BY ONE into a single running RGB accumulator and immediately recycles each
 * decoded frame Bitmap so the app never holds multiple full-resolution Bitmaps in RAM simultaneously.
 * Includes lightweight per-pixel motion/ghosting rejection against the reference frame so moving
 * subjects stay sharp while static areas gain cleaner shadows and reduced sensor noise.
 */
class StreamingQualityFrameMerger private constructor(
    val width: Int,
    val height: Int
) {
    private val pixelCount = width * height
    private val sumR = IntArray(pixelCount)
    private val sumG = IntArray(pixelCount)
    private val sumB = IntArray(pixelCount)
    private val weightSum = IntArray(pixelCount)
    private val refLuma = ByteArray(pixelCount)
    private var mergedCount = 0

    val framesMerged: Int
        get() = mergedCount

    /**
     * Merges [bitmap] into the running accumulator. The caller should recycle [bitmap] immediately after.
     */
    fun accumulateFrame(bitmap: Bitmap): Boolean {
        if (bitmap.width != width || bitmap.height != height) return false
        val rowPixels = IntArray(width)
        val isFirst = mergedCount == 0

        for (y in 0 until height) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
            val rowOffset = y * width
            if (isFirst) {
                for (x in 0 until width) {
                    val p = rowPixels[x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val idx = rowOffset + x
                    // Weight 4 for reference frame ensures sharp anchor even under motion
                    sumR[idx] = r * 4
                    sumG[idx] = g * 4
                    sumB[idx] = b * 4
                    weightSum[idx] = 4
                    refLuma[idx] = (((r * 77) + (g * 150) + (b * 29)) shr 8).toByte()
                }
            } else {
                for (x in 0 until width) {
                    val p = rowPixels[x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val luma = ((r * 77) + (g * 150) + (b * 29)) shr 8
                    val idx = rowOffset + x
                    val refY = refLuma[idx].toInt() and 0xFF
                    val diff = abs(luma - refY)
                    // Ghosting protection: pixels matching reference get full weight (3), slight motion (1), large motion (0)
                    val w = when {
                        diff <= 18 -> 3
                        diff <= 34 -> 1
                        else -> 0
                    }
                    if (w > 0) {
                        sumR[idx] += r * w
                        sumG[idx] += g * w
                        sumB[idx] += b * w
                        weightSum[idx] += w
                    }
                }
            }
        }
        mergedCount++
        return true
    }

    /**
     * Produces the final denoised [Bitmap] from the running accumulator.
     */
    fun finalizeBitmap(): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rowPixels = IntArray(width)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val idx = rowOffset + x
                val w = weightSum[idx].coerceAtLeast(1)
                val r = (sumR[idx] / w).coerceIn(0, 255)
                val g = (sumG[idx] / w).coerceIn(0, 255)
                val b = (sumB[idx] / w).coerceIn(0, 255)
                rowPixels[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
        return out
    }

    companion object {
        /**
         * Determines a memory-safe Quality mode burst frame count (3..5 frames) based on live heap headroom.
         * Returns 1 if available memory is too low to safely allocate a running accumulator.
         */
        fun resolveSafeQualityFrameCount(context: Context, width: Int, height: Int): Int {
            if (width <= 0 || height <= 0) return 1
            val pixels = width.toLong() * height.toLong()
            // Accumulator uses ~17 bytes/pixel (4 IntArrays + 1 ByteArray) + 1 decoded Bitmap (~4 bytes/pixel)
            val estimatedPeakBytes = pixels * 24L
            val runtime = Runtime.getRuntime()
            val freeHeapHeadroom = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0L)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val lowMemory = memInfo.lowMemory

            if (lowMemory || estimatedPeakBytes > (freeHeapHeadroom * 65L) / 100L) {
                return 1
            }
            return when {
                estimatedPeakBytes <= (freeHeapHeadroom * 25L) / 100L && pixels <= 14_000_000L -> 5
                estimatedPeakBytes <= (freeHeapHeadroom * 45L) / 100L -> 4
                else -> 3
            }
        }

        /**
         * Attempts to create a [StreamingQualityFrameMerger] from the first captured JPEG frame.
         * Decodes frame 1, initializes the running accumulator, and immediately recycles frame 1's Bitmap.
         */
        fun createFromFirstJpeg(firstJpegBytes: ByteArray): StreamingQualityFrameMerger? {
            return try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                val bmp = BitmapFactory.decodeByteArray(firstJpegBytes, 0, firstJpegBytes.size, opts)
                    ?: return null
                try {
                    val merger = StreamingQualityFrameMerger(bmp.width, bmp.height)
                    if (!merger.accumulateFrame(bmp)) return null
                    merger
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            } catch (oom: OutOfMemoryError) {
                Log.w(PIPELINE_TAG, "OOM initializing StreamingQualityFrameMerger; falling back to single frame", oom)
                null
            } catch (t: Throwable) {
                Log.w(PIPELINE_TAG, "Failed initializing StreamingQualityFrameMerger: ${t.localizedMessage}")
                null
            }
        }

        /**
         * Decodes a subsequent frame JPEG, accumulates it into [merger], and immediately recycles the Bitmap.
         */
        fun mergeNextJpeg(merger: StreamingQualityFrameMerger, jpegBytes: ByteArray): Boolean {
            return try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                    ?: return false
                try {
                    merger.accumulateFrame(bmp)
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            } catch (oom: OutOfMemoryError) {
                Log.w(PIPELINE_TAG, "OOM merging frame ${merger.framesMerged + 1}; saving frames merged so far", oom)
                false
            } catch (t: Throwable) {
                Log.w(PIPELINE_TAG, "Error merging quality frame: ${t.localizedMessage}")
                false
            }
        }

        /**
         * Finalizes [merger], applies orientation/aspect crop/mirror, and compresses to JPEG quality 100.
         */
        fun finalizeToPayload(
            merger: StreamingQualityFrameMerger,
            rotationDegrees: Int,
            aspectRatioMode: AspectRatioMode,
            mirrorHorizontally: Boolean,
            jpegQuality: Int = CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY
        ): ProcessedPhotoPayload? {
            var mergedBitmap: Bitmap? = null
            var orientedBitmap: Bitmap? = null
            return try {
                mergedBitmap = merger.finalizeBitmap()
                orientedBitmap = AspectCropUtils.cropAndOrientBitmap(
                    sourceBitmap = mergedBitmap,
                    aspectRatioMode = aspectRatioMode,
                    rotationDegrees = rotationDegrees,
                    mirrorHorizontally = mirrorHorizontally
                )
                val baos = ByteArrayOutputStream(256 * 1024)
                val compressed = orientedBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    jpegQuality.coerceIn(90, 100),
                    baos
                )
                if (!compressed) return null
                ProcessedPhotoPayload(
                    jpegBytes = baos.toByteArray(),
                    width = orientedBitmap.width,
                    height = orientedBitmap.height,
                    wasRecompressed = true,
                    exifOrientation = android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } catch (t: Throwable) {
                Log.e(PIPELINE_TAG, "Failed finalizing streaming multi-frame merge: ${t.localizedMessage}", t)
                null
            } finally {
                if (orientedBitmap != null && orientedBitmap != mergedBitmap && !orientedBitmap.isRecycled) {
                    orientedBitmap.recycle()
                }
                if (mergedBitmap != null && !mergedBitmap.isRecycled) {
                    mergedBitmap.recycle()
                }
            }
        }
    }
}
