package com.example.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.core.preferences.FlashMode
import com.example.core.preferences.VideoStabilizationMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val HW_TAG = "Camera2Hardware"

/**
 * Aggregated hardware video encoder capabilities queried from [MediaCodecList]
 * via [android.media.MediaCodecInfo.VideoCapabilities.getSupportedFrameRatesFor] and
 * [android.media.MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported].
 */
data class HardwareEncoderVideoInfo(
    val maxFpsByResolution: Map<VideoResolution, Double>,
    val summaryLines: List<String>
)

/**
 * Queries hardware/regular MediaCodec H.264 (AVC) and HEVC video encoders to determine
 * the exact maximum supported frame rate for each standard [VideoResolution].
 */
fun queryHardwareEncoderVideoCapabilities(): HardwareEncoderVideoInfo {
    val maxFpsByRes = LinkedHashMap<VideoResolution, Double>()
    val bestCodecByRes = LinkedHashMap<VideoResolution, String>()
    val supportedRatesByRes = LinkedHashMap<VideoResolution, MutableSet<Int>>()
    try {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val videoMimeTypes = setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC
        )
        val testRates = listOf(24, 30, 60, 120)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                val matchedMime = videoMimeTypes.firstOrNull { it.equals(type, ignoreCase = true) } ?: continue
                val caps = runCatching { info.getCapabilitiesForType(matchedMime)?.videoCapabilities }.getOrNull() ?: continue
                for (res in VideoResolution.VIDEO_MODE_OPTIONS) {
                    val w = res.width
                    val h = res.height
                    val landscapeOk = runCatching { caps.isSizeSupported(w, h) }.getOrDefault(false)
                    val portraitOk = runCatching { caps.isSizeSupported(h, w) }.getOrDefault(false)
                    if (!landscapeOk && !portraitOk) continue

                    val r1 = if (landscapeOk) {
                        runCatching { caps.getSupportedFrameRatesFor(w, h)?.upper ?: 0.0 }.getOrDefault(0.0)
                    } else 0.0
                    val r2 = if (portraitOk) {
                        runCatching { caps.getSupportedFrameRatesFor(h, w)?.upper ?: 0.0 }.getOrDefault(0.0)
                    } else 0.0
                    var effectiveMax = maxOf(r1, r2)

                    val rateSet = supportedRatesByRes.getOrPut(res) { LinkedHashSet() }
                    for (fps in testRates) {
                        val rateOk = runCatching {
                            (landscapeOk && caps.areSizeAndRateSupported(w, h, fps.toDouble())) ||
                                (portraitOk && caps.areSizeAndRateSupported(h, w, fps.toDouble()))
                        }.getOrDefault(false)
                        if (rateOk || effectiveMax >= fps - 0.5) {
                            rateSet.add(fps)
                            if (rateOk && fps.toDouble() > effectiveMax) {
                                effectiveMax = fps.toDouble()
                            }
                        }
                    }

                    val current = maxFpsByRes[res] ?: 0.0
                    if (effectiveMax > current) {
                        maxFpsByRes[res] = effectiveMax
                        bestCodecByRes[res] = "${info.name} (${matchedMime.substringAfter("/")})"
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.w(HW_TAG, "Unable to query MediaCodecList video encoder limits", e)
    }

    val summaryLines = VideoResolution.VIDEO_MODE_OPTIONS.map { res ->
        val maxFps = maxFpsByRes[res]
        val codec = bestCodecByRes[res]
        val rates = supportedRatesByRes[res].orEmpty().sorted()
        if (maxFps != null && maxFps > 0.0) {
            val fpsStr = String.format(Locale.US, "%.1f", maxFps)
            val ratesStr = if (rates.isNotEmpty()) rates.joinToString("/") else "none"
            "${res.id} (${res.width}x${res.height}): max ${fpsStr}fps [$ratesStr] via ${codec ?: "encoder"}"
        } else {
            "${res.id} (${res.width}x${res.height}): NOT SUPPORTED by AVC/HEVC encoders"
        }
    }
    return HardwareEncoderVideoInfo(
        maxFpsByResolution = maxFpsByRes,
        summaryLines = summaryLines
    )
}

/**
 * Matches actual `EncoderProfiles` / `CamcorderProfile` video frame dimensions against standard
 * [VideoResolution] tiers strictly by real width x height (never by unverified quality codes).
 */
fun queryCamcorderProfileMaxFpsByResolution(cameraId: String): Map<VideoResolution, Int> {
    val camcorderMaxFpsByRes = mutableMapOf<VideoResolution, Int>()
    val camIdInt = cameraId.toIntOrNull() ?: return camcorderMaxFpsByRes
    val candidateQualityCodes = listOf(
        CamcorderProfile.QUALITY_720P,
        CamcorderProfile.QUALITY_1080P,
        11, // QUALITY_QHD (2560x1440)
        12, // QUALITY_2K (2048x1080)
        CamcorderProfile.QUALITY_2160P
    )
    fun matchResolution(w: Int, h: Int): VideoResolution? {
        val maxD = maxOf(w, h)
        val minD = minOf(w, h)
        return when {
            maxD == 1280 && minD == 720 -> VideoResolution.HD_720P
            maxD == 1920 && (minD == 1080 || minD == 1088) -> VideoResolution.FHD_1080P
            maxD == 2560 && minD == 1440 -> VideoResolution.QHD_2K
            maxD == 3840 && minD == 2160 -> VideoResolution.UHD_4K
            else -> null
        }
    }

    for (qCode in candidateQualityCodes) {
        runCatching {
            if (!CamcorderProfile.hasProfile(camIdInt, qCode)) return@runCatching
            var recorded = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val encProfiles = runCatching { CamcorderProfile.getAll(cameraId, qCode) }.getOrNull()
                encProfiles?.videoProfiles?.forEach { vp ->
                    val matched = matchResolution(vp.width, vp.height)
                    if (matched != null && vp.frameRate > 0) {
                        val prev = camcorderMaxFpsByRes[matched] ?: 0
                        if (vp.frameRate > prev) camcorderMaxFpsByRes[matched] = vp.frameRate
                        recorded = true
                    }
                }
            }
            if (!recorded) {
                val legacyProf = runCatching {
                    @Suppress("DEPRECATION")
                    CamcorderProfile.get(camIdInt, qCode)
                }.getOrNull()
                if (legacyProf != null) {
                    val matched = matchResolution(legacyProf.videoFrameWidth, legacyProf.videoFrameHeight)
                    if (matched != null && legacyProf.videoFrameRate > 0) {
                        val prev = camcorderMaxFpsByRes[matched] ?: 0
                        if (legacyProf.videoFrameRate > prev) camcorderMaxFpsByRes[matched] = legacyProf.videoFrameRate
                    }
                }
            }
        }
    }
    return camcorderMaxFpsByRes
}

/**
 * Per-camera cached verifier that probes Camera2 `SessionConfiguration` support for every candidate
 * `(VideoResolution, VideoFrameRate)` combination using valid `OutputConfiguration` surfaces
 * (`SurfaceTexture` preview + `ImageFormat.PRIVATE / USAGE_VIDEO_ENCODE` video surface) so unsupported
 * configurations are hidden without falsely rejecting valid 1080p or 60 FPS combinations.
 */
object VideoSessionConfigVerifier {

    private data class CachedVerificationEntry(
        val signature: String,
        val verifiedQualities: Map<VideoResolution, List<VideoFrameRate>>,
        val evaluations: List<VideoComboEvaluationResult>
    )

    private val verifiedCache = ConcurrentHashMap<String, CachedVerificationEntry>()
    private val failedCombosByCamera = ConcurrentHashMap<String, MutableSet<Pair<VideoResolution, VideoFrameRate>>>()

    fun getCached(cameraId: String): Map<VideoResolution, List<VideoFrameRate>>? =
        verifiedCache[cameraId]?.verifiedQualities

    fun getCachedEvaluations(cameraId: String): List<VideoComboEvaluationResult>? =
        verifiedCache[cameraId]?.evaluations

    fun markComboFailed(cameraId: String, resolution: VideoResolution, frameRate: VideoFrameRate) {
        val set = failedCombosByCamera.getOrPut(cameraId) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        set.add(resolution to frameRate)
        verifiedCache[cameraId]?.let { existing ->
            val updated = LinkedHashMap<VideoResolution, List<VideoFrameRate>>()
            for ((res, rates) in existing.verifiedQualities) {
                val filtered = rates.filter { !(res == resolution && it == frameRate) }
                if (filtered.isNotEmpty()) updated[res] = filtered
            }
            val updatedEvals = existing.evaluations.map { eval ->
                if (eval.resolution == resolution && eval.frameRate == frameRate) {
                    eval.copy(
                        passed = false,
                        failureReasons = (eval.failureReasons + "runtime capture session failed").distinct(),
                        sessionStatus = "FAIL: runtime session failed"
                    )
                } else eval
            }
            if (updated.isNotEmpty()) {
                verifiedCache[cameraId] = existing.copy(
                    verifiedQualities = updated,
                    evaluations = updatedEvals
                )
            }
        }
    }

    fun clearCacheForTesting() {
        verifiedCache.clear()
        failedCombosByCamera.clear()
    }

    private fun buildCandidateSignature(
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        aeFpsRanges: List<Pair<Int, Int>>,
        allowDeviceOpenProbe: Boolean
    ): String {
        val candPart = candidates.entries.joinToString("|") { (r, rates) ->
            "${r.id}:${rates.joinToString(",") { it.fps.toString() }}"
        }
        val aePart = aeFpsRanges.joinToString(",") { "${it.first}-${it.second}" }
        return "$candPart#$aePart#$allowDeviceOpenProbe"
    }

    /**
     * Verifies candidate `(VideoResolution, VideoFrameRate)` combinations for [cameraId] and returns
     * both the verified qualities map and updated per-combo PASS/FAIL diagnostic evaluations.
     */
    @SuppressLint("MissingPermission")
    fun verifyAndCacheVideoCombinationsWithDiagnostics(
        context: Context,
        cameraId: String,
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        staticEvaluations: List<VideoComboEvaluationResult>,
        aeFpsRanges: List<Pair<Int, Int>>,
        allowDeviceOpenProbe: Boolean = false
    ): Pair<Map<VideoResolution, List<VideoFrameRate>>, List<VideoComboEvaluationResult>> {
        if (candidates.isEmpty()) return candidates to staticEvaluations
        val signature = buildCandidateSignature(candidates, aeFpsRanges, allowDeviceOpenProbe)
        val failedSet = failedCombosByCamera[cameraId].orEmpty()

        verifiedCache[cameraId]?.let { cached ->
            if (cached.signature == signature) {
                val filtered = if (failedSet.isEmpty()) cached.verifiedQualities else filterOutFailed(cached.verifiedQualities, failedSet)
                return filtered to cached.evaluations
            }
        }

        val cameraManager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        }.getOrNull()
        val characteristics = cameraManager?.let {
            runCatching { it.getCameraCharacteristics(cameraId) }.getOrNull()
        }
        if (cameraManager == null || characteristics == null) {
            val fallbackFiltered = filterOutFailed(candidates, failedSet)
            val fallbackEvals = staticEvaluations.map { eval ->
                if (eval.passed) eval.copy(sessionStatus = "SKIPPED (no CameraCharacteristics)") else eval
            }
            return fallbackFiltered to fallbackEvals
        }

        // API 35+ supports CameraCharacteristics.isSessionConfigurationSupported without opening the camera.
        if (Build.VERSION.SDK_INT >= 35 && !isRobolectricRuntime()) {
            val (verified, updatedEvals) = probeWithCharacteristicsApi35(
                characteristics = characteristics,
                candidates = candidates,
                staticEvaluations = staticEvaluations,
                aeFpsRanges = aeFpsRanges
            )
            if (verified.isNotEmpty()) {
                val filtered = filterOutFailed(verified, failedSet)
                verifiedCache[cameraId] = CachedVerificationEntry(signature, filtered, updatedEvals)
                return filtered to updatedEvals
            }
        }

        // API 29-34: probe via CameraDevice.isSessionConfigurationSupported only when explicitly allowed
        val hasCameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (allowDeviceOpenProbe && hasCameraPerm && !isRobolectricRuntime()) {
            val probeResult = probeWithTemporaryCameraDevice(
                cameraManager = cameraManager,
                cameraId = cameraId,
                characteristics = characteristics,
                candidates = candidates,
                staticEvaluations = staticEvaluations,
                aeFpsRanges = aeFpsRanges
            )
            if (probeResult != null && probeResult.first.isNotEmpty()) {
                val filtered = filterOutFailed(probeResult.first, failedSet)
                verifiedCache[cameraId] = CachedVerificationEntry(signature, filtered, probeResult.second)
                return filtered to probeResult.second
            }
        }

        val fallbackFiltered = filterOutFailed(candidates, failedSet)
        val fallbackEvals = staticEvaluations.map { eval ->
            if (eval.passed) eval.copy(sessionStatus = "PASS (static stream+AE+encoder verified)") else eval
        }
        verifiedCache[cameraId] = CachedVerificationEntry(signature, fallbackFiltered, fallbackEvals)
        return fallbackFiltered to fallbackEvals
    }

    @SuppressLint("MissingPermission")
    fun verifyAndCacheVideoCombinations(
        context: Context,
        cameraId: String,
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        aeFpsRanges: List<Pair<Int, Int>>,
        allowDeviceOpenProbe: Boolean = false
    ): Map<VideoResolution, List<VideoFrameRate>> {
        return verifyAndCacheVideoCombinationsWithDiagnostics(
            context = context,
            cameraId = cameraId,
            candidates = candidates,
            staticEvaluations = emptyList(),
            aeFpsRanges = aeFpsRanges,
            allowDeviceOpenProbe = allowDeviceOpenProbe
        ).first
    }

    private fun filterOutFailed(
        map: Map<VideoResolution, List<VideoFrameRate>>,
        failed: Set<Pair<VideoResolution, VideoFrameRate>>
    ): Map<VideoResolution, List<VideoFrameRate>> {
        if (failed.isEmpty()) return map
        val out = LinkedHashMap<VideoResolution, List<VideoFrameRate>>()
        for ((res, rates) in map) {
            val kept = rates.filter { (res to it) !in failed }
            if (kept.isNotEmpty()) out[res] = kept
        }
        return out.ifEmpty { map }
    }

    private fun isRobolectricRuntime(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true)
    }

    private fun pickCandidatePreviewSizesForVideo(characteristics: CameraCharacteristics, videoRes: VideoResolution): List<Size> {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return listOf(Size(1280, 720))
        val targetRatio = videoRes.width.toFloat() / videoRes.height.toFloat()
        val ordered = LinkedHashSet<Size>()
        // Prefer exact matching preview size when <= 1080p (required by some MediaTek HALs for 1080p60)
        if (videoRes.width <= 1920 && videoRes.height <= 1080) {
            previewSizes.firstOrNull { sz ->
                maxOf(sz.width, sz.height) == videoRes.width && minOf(sz.width, sz.height) == videoRes.height
            }?.let { ordered.add(it) }
        }
        // Also try 1280x720 (or closest 16:9 preview <= 1080p)
        previewSizes
            .filter { maxOf(it.width, it.height) <= 1920 && minOf(it.width, it.height) <= 1080 }
            .minByOrNull { sz ->
                val r = maxOf(sz.width, sz.height).toFloat() / minOf(sz.width, sz.height).coerceAtLeast(1).toFloat()
                kotlin.math.abs(r - targetRatio) * 1000f + kotlin.math.abs(maxOf(sz.width, sz.height) - 1280) * 0.1f
            }?.let { ordered.add(it) }
        if (ordered.isEmpty()) ordered.add(Size(1280, 720))
        return ordered.toList()
    }

    private fun createVideoProbeImageReader(videoSize: Size): ImageReader? {
        return runCatching {
            ImageReader.newInstance(
                videoSize.width,
                videoSize.height,
                ImageFormat.PRIVATE,
                2,
                HardwareBuffer.USAGE_VIDEO_ENCODE
            )
        }.getOrNull()
    }

    private fun probeWithCharacteristicsApi35(
        characteristics: CameraCharacteristics,
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        staticEvaluations: List<VideoComboEvaluationResult>,
        aeFpsRanges: List<Pair<Int, Int>>
    ): Pair<Map<VideoResolution, List<VideoFrameRate>>, List<VideoComboEvaluationResult>> {
        val result = LinkedHashMap<VideoResolution, List<VideoFrameRate>>()
        val statusByCombo = LinkedHashMap<Pair<VideoResolution, VideoFrameRate>, Pair<Boolean, String>>()
        val probeExecutor = Executors.newSingleThreadExecutor()
        try {
            val isSupportedMethod = runCatching {
                CameraCharacteristics::class.java.getMethod(
                    "isSessionConfigurationSupported",
                    SessionConfiguration::class.java
                )
            }.getOrNull() ?: return emptyMap<VideoResolution, List<VideoFrameRate>>() to staticEvaluations

            var anyHalUnsupported = false
            var passedCount = 0
            var totalTested = 0

            for ((res, rates) in candidates) {
                val previewSizes = pickCandidatePreviewSizesForVideo(characteristics, res)
                val videoSize = Size(res.width, res.height)
                val reader = createVideoProbeImageReader(videoSize)
                if (reader == null) {
                    for (rate in rates) {
                        statusByCombo[res to rate] = true to "PASS (video probe surface unavailable)"
                    }
                    result[res] = rates
                    continue
                }
                try {
                    var streamPairSupported = false
                    var lastErr: String? = null
                    for (previewSize in previewSizes) {
                        val check = runCatching {
                            val outputs = listOf(
                                OutputConfiguration(previewSize, SurfaceTexture::class.java),
                                OutputConfiguration(reader.surface)
                            )
                            val config = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                outputs,
                                probeExecutor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {}
                                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                                }
                            )
                            (isSupportedMethod.invoke(characteristics, config) as? Boolean) == true
                        }
                        if (check.isSuccess && check.getOrDefault(false)) {
                            streamPairSupported = true
                            break
                        } else if (check.isFailure) {
                            val cause = check.exceptionOrNull()?.cause ?: check.exceptionOrNull()
                            if (cause is UnsupportedOperationException) {
                                anyHalUnsupported = true
                                streamPairSupported = true
                                break
                            }
                            lastErr = cause?.javaClass?.simpleName ?: "exception"
                        }
                    }

                    val keptRates = mutableListOf<VideoFrameRate>()
                    for (rate in rates) {
                        totalTested++
                        if (streamPairSupported) {
                            passedCount++
                            keptRates.add(rate)
                            statusByCombo[res to rate] = true to if (anyHalUnsupported) "UNSUPPORTED_BY_HAL" else "PASS"
                        } else {
                            statusByCombo[res to rate] = false to "FAIL: API 35 session config rejected (${lastErr ?: "unsupported"})"
                        }
                    }
                    if (keptRates.isNotEmpty()) {
                        result[res] = keptRates
                    }
                } finally {
                    runCatching { reader.close() }
                }
            }

            // Safeguard: if HAL rejected every single candidate (even standard 720p/1080p@30),
            // treat HAL query as unsupported rather than dropping all valid resolutions.
            if (totalTested > 0 && passedCount == 0) {
                val safeEvals = staticEvaluations.map { eval ->
                    if (eval.passed) eval.copy(sessionStatus = "UNSUPPORTED_BY_HAL (all rejected; kept static)") else eval
                }
                return candidates to safeEvals
            }

            val updatedEvals = staticEvaluations.map { eval ->
                val pair = statusByCombo[eval.resolution to eval.frameRate]
                if (pair != null) {
                    if (pair.first) {
                        eval.copy(sessionStatus = pair.second)
                    } else {
                        eval.copy(
                            passed = false,
                            failureReasons = (eval.failureReasons + pair.second).distinct(),
                            sessionStatus = pair.second
                        )
                    }
                } else eval
            }
            return result to updatedEvals
        } catch (t: Throwable) {
            Log.w(HW_TAG, "API 35 session config probe skipped: ${t.localizedMessage}")
            return emptyMap<VideoResolution, List<VideoFrameRate>>() to staticEvaluations
        } finally {
            probeExecutor.shutdown()
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeWithTemporaryCameraDevice(
        cameraManager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics,
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        staticEvaluations: List<VideoComboEvaluationResult>,
        aeFpsRanges: List<Pair<Int, Int>>
    ): Pair<Map<VideoResolution, List<VideoFrameRate>>, List<VideoComboEvaluationResult>>? {
        val thread = HandlerThread("VideoSessionProbe").apply { start() }
        val handler = Handler(thread.looper)
        val probeExecutor = Executors.newSingleThreadExecutor()
        val openedDeviceRef = AtomicReference<CameraDevice?>(null)
        val openLatch = CountDownLatch(1)
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        openedDeviceRef.set(camera)
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        openLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                        openLatch.countDown()
                    }
                },
                handler
            )
            if (!openLatch.await(1200L, TimeUnit.MILLISECONDS)) {
                return null
            }
            val device = openedDeviceRef.get() ?: return null
            val verified = LinkedHashMap<VideoResolution, List<VideoFrameRate>>()
            val statusByCombo = LinkedHashMap<Pair<VideoResolution, VideoFrameRate>, Triple<Boolean, String, Pair<Int, Int>?>>()
            var totalTested = 0
            var passedCount = 0

            for ((res, rates) in candidates) {
                val previewSizes = pickCandidatePreviewSizesForVideo(characteristics, res)
                val videoSize = Size(res.width, res.height)
                val reader = createVideoProbeImageReader(videoSize)
                if (reader == null) {
                    verified[res] = rates
                    rates.forEach { rate ->
                        statusByCombo[res to rate] = Triple(true, "PASS (probe surface unavailable)", selectBestVideoAeFpsRange(aeFpsRanges, rate.fps))
                    }
                    continue
                }
                try {
                    val keptRates = mutableListOf<VideoFrameRate>()
                    for (rate in rates) {
                        totalTested++
                        val candidateRanges = selectAllValidVideoAeFpsRanges(aeFpsRanges, rate.fps)
                            .ifEmpty { listOf(selectBestVideoAeFpsRange(aeFpsRanges, rate.fps)) }

                        var comboPassed = false
                        var matchedRange: Pair<Int, Int>? = candidateRanges.firstOrNull()
                        var statusNote = "FAIL: session configuration rejected"

                        outer@ for (previewSize in previewSizes) {
                            for (fpsPair in candidateRanges) {
                                val probeOutcome = runCatching {
                                    val outputs = listOf(
                                        OutputConfiguration(previewSize, SurfaceTexture::class.java),
                                        OutputConfiguration(reader.surface)
                                    )
                                    val sessionConfig = SessionConfiguration(
                                        SessionConfiguration.SESSION_REGULAR,
                                        outputs,
                                        probeExecutor,
                                        object : CameraCaptureSession.StateCallback() {
                                            override fun onConfigured(session: CameraCaptureSession) {}
                                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                                        }
                                    )
                                    val reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    reqBuilder.set(
                                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        Range(fpsPair.first, fpsPair.second)
                                    )
                                    sessionConfig.sessionParameters = reqBuilder.build()
                                    device.isSessionConfigurationSupported(sessionConfig)
                                }
                                if (probeOutcome.isSuccess) {
                                    if (probeOutcome.getOrDefault(false)) {
                                        comboPassed = true
                                        matchedRange = fpsPair
                                        statusNote = "PASS (${previewSize.width}x${previewSize.height} + ${videoSize.width}x${videoSize.height} @ [${fpsPair.first},${fpsPair.second}])"
                                        break@outer
                                    } else {
                                        statusNote = "FAIL: session config rejected (${previewSize.width}x${previewSize.height} + ${videoSize.width}x${videoSize.height} @ [${fpsPair.first},${fpsPair.second}])"
                                    }
                                } else {
                                    val exc = probeOutcome.exceptionOrNull()
                                    if (exc is UnsupportedOperationException) {
                                        comboPassed = true
                                        matchedRange = fpsPair
                                        statusNote = "UNSUPPORTED_BY_HAL"
                                        break@outer
                                    }
                                    // IllegalArgumentException on deferred OutputConfiguration means the probe itself wasn't accepted by HAL
                                    if (exc is IllegalArgumentException) {
                                        comboPassed = true
                                        matchedRange = fpsPair
                                        statusNote = "PROBE_UNSUPPORTED (IAE)"
                                        break@outer
                                    }
                                    statusNote = "FAIL: ${exc?.javaClass?.simpleName ?: "error"}"
                                }
                            }
                        }

                        if (comboPassed) {
                            passedCount++
                            keptRates.add(rate)
                        } else {
                            Log.i(HW_TAG, "Session probe rejected camera $cameraId: ${res.displayName} @ ${rate.displayName} ($statusNote)")
                        }
                        statusByCombo[res to rate] = Triple(comboPassed, statusNote, matchedRange)
                    }
                    if (keptRates.isNotEmpty()) {
                        verified[res] = keptRates
                    }
                } finally {
                    runCatching { reader.close() }
                }
            }

            // Safeguard: MediaTek and some vendor HALs return false for ALL isSessionConfigurationSupported
            // calls when sessionParameters or ImageFormat.PRIVATE surfaces are used. Never wipe out all
            // valid resolutions (e.g. if 720p@30 AND 1080p@30 both failed the probe).
            val standard30Failed = (VideoResolution.HD_720P in candidates && VideoResolution.HD_720P !in verified) &&
                (VideoResolution.FHD_1080P in candidates && VideoResolution.FHD_1080P !in verified)
            if ((totalTested > 0 && passedCount == 0) || standard30Failed) {
                val safeEvals = staticEvaluations.map { eval ->
                    if (eval.passed) eval.copy(sessionStatus = "UNSUPPORTED_BY_HAL (probe rejected baseline; kept static)") else eval
                }
                return candidates to safeEvals
            }

            val updatedEvals = staticEvaluations.map { eval ->
                val outcome = statusByCombo[eval.resolution to eval.frameRate]
                if (outcome != null) {
                    if (outcome.first) {
                        eval.copy(
                            chosenAeFpsRange = outcome.third ?: eval.chosenAeFpsRange,
                            sessionStatus = outcome.second
                        )
                    } else {
                        eval.copy(
                            passed = false,
                            failureReasons = (eval.failureReasons + outcome.second).distinct(),
                            chosenAeFpsRange = outcome.third ?: eval.chosenAeFpsRange,
                            sessionStatus = outcome.second
                        )
                    }
                } else eval
            }
            return verified to updatedEvals
        } catch (t: Throwable) {
            Log.w(HW_TAG, "Temporary CameraDevice session probe skipped for camera $cameraId: ${t.localizedMessage}")
            return null
        } finally {
            runCatching { openedDeviceRef.get()?.close() }
            probeExecutor.shutdown()
            thread.quitSafely()
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun queryExtensionsForCameraId(
    context: Context,
    cameraProvider: ProcessCameraProvider?,
    cameraId: String,
    lensFacing: LensFacing,
    isPrimaryForFacing: Boolean
): Pair<List<String>, String> {
    if (cameraProvider == null) return emptyList<String>() to "CameraProvider unavailable"
    return try {
        val selectorById = CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == cameraId }.getOrDefault(false)
                }
            }
            .build()
        val hasExactMatch = runCatching {
            selectorById.filter(cameraProvider.availableCameraInfos).isNotEmpty()
        }.getOrDefault(false)
        val selector = when {
            hasExactMatch -> selectorById
            isPrimaryForFacing -> if (lensFacing == LensFacing.FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            else -> return emptyList<String>() to "Not bound as CameraX logical camera"
        }
        val avail = CameraExtensionsHelper.queryAvailability(context, cameraProvider, selector)
        val names = avail.supportedExtensionNames
        names to (names.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None")
    } catch (t: Throwable) {
        emptyList<String>() to "Query error (${t.javaClass.simpleName})"
    }
}

/**
 * Inspects EVERY camera ID on the device (logical from `getCameraIdList()` + physical sub-cameras from
 * `getPhysicalCameraIds()`) and collects:
 * - Every candidate Video Resolution + FPS combination with PASS/FAIL + exact failure reason
 * - Hardware encoder `VideoCapabilities.getSupportedFrameRatesFor` limits
 * - Raw MediaRecorder/MediaCodec video sizes & min frame durations
 * - JPEG, YUV_420_888, and RAW_SENSOR sizes for Standard, High-Res, and API 31+ Maximum-Resolution
 * - `SENSOR_INFO_PIXEL_ARRAY_SIZE`, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, and Ultra-High-Res capability flag
 * - CameraX Extensions availability per camera
 * - Stabilization modes & AE FPS ranges
 */
fun collectCameraHardwareDebugReport(context: Context): CameraHardwareDebugReport {
    val cameraManager = runCatching {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }.getOrNull()

    val encoderInfo = queryHardwareEncoderVideoCapabilities()
    val cameraProvider = runCatching {
        ProcessCameraProvider.getInstance(context).get(1200L, TimeUnit.MILLISECONDS)
    }.getOrNull()

    val entries = mutableListOf<CameraIdDebugInfo>()
    if (cameraManager != null) {
        val logicalIds = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())
        val seenIds = LinkedHashSet<String>()
        val idsToInspect = mutableListOf<Triple<String, Boolean, String?>>() // (id, isPhysicalOnly, parentLogical)

        for (id in logicalIds) {
            if (seenIds.add(id)) {
                idsToInspect.add(Triple(id, false, null))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val physIds = runCatching {
                    cameraManager.getCameraCharacteristics(id).physicalCameraIds.toList()
                }.getOrDefault(emptyList())
                for (pid in physIds) {
                    if (seenIds.add(pid)) {
                        idsToInspect.add(Triple(pid, true, id))
                    }
                }
            }
        }

        val backFocals = mutableListOf<Float>()
        val frontFocals = mutableListOf<Float>()
        var firstBackId: String? = null
        var firstFrontId: String? = null
        for ((cid, isPhysOnly, _) in idsToInspect) {
            runCatching {
                val c = cameraManager.getCameraCharacteristics(cid)
                val f = c.get(CameraCharacteristics.LENS_FACING)
                if (!isPhysOnly) {
                    if (f == CameraCharacteristics.LENS_FACING_BACK && firstBackId == null) firstBackId = cid
                    if (f == CameraCharacteristics.LENS_FACING_FRONT && firstFrontId == null) firstFrontId = cid
                }
                val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                if (fl != null) {
                    if (f == CameraCharacteristics.LENS_FACING_FRONT) frontFocals.add(fl) else backFocals.add(fl)
                }
            }
        }

        for ((cid, isPhysOnly, parentId) in idsToInspect) {
            runCatching {
                val chars = cameraManager.getCameraCharacteristics(cid)
                val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
                val lensFacing = if (facingInt == CameraCharacteristics.LENS_FACING_FRONT) LensFacing.FRONT else LensFacing.BACK
                val facingLabel = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else -> "Unknown"
                }
                val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { chars.physicalCameraIds.toList() }.getOrDefault(emptyList())
                } else emptyList()

                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
                val label = resolveCameraLabelFromMetadata(
                    facing = lensFacing,
                    focalLengthMm = focals.firstOrNull(),
                    cameraId = cid,
                    physicalCameraIds = physicalIds,
                    allSameFacingFocalLengths = if (lensFacing == LensFacing.FRONT) frontFocals else backFocals
                )

                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                    CaptureResolutionResolver.formatMegapixelsWithDimensions(it.width, it.height)
                } ?: "n/a"

                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                    CaptureResolutionResolver.formatMegapixelsWithDimensions(it.width(), it.height())
                } ?: "n/a"

                val reqCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
                val supportsUltraHighRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    reqCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
                } else false

                val scMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val recSizes = scMap?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                val codecSizes = scMap?.getOutputSizes(MediaCodec::class.java) ?: emptyArray()
                val combinedVideoSizes = if (recSizes.isNotEmpty()) {
                    (recSizes + codecSizes).distinct()
                } else {
                    val texSizes = scMap?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                    (codecSizes + texSizes).distinct()
                }

                val videoSizes = recSizes
                    .sortedByDescending { it.width.toLong() * it.height.toLong() }
                    .map { sz ->
                        val minDur = runCatching {
                            scMap?.getOutputMinFrameDuration(MediaRecorder::class.java, sz) ?: 0L
                        }.getOrDefault(0L)
                        val maxFps = if (minDur > 0L) 1_000_000_000.0 / minDur.toDouble() else 0.0
                        VideoSizeFpsDebugEntry(sz.width, sz.height, maxFps, minDur)
                    }

                val streamVideoSizePairs = LinkedHashSet<Pair<Int, Int>>()
                for (sz in combinedVideoSizes) {
                    if (sz.width > 0 && sz.height > 0) {
                        streamVideoSizePairs.add(maxOf(sz.width, sz.height) to minOf(sz.width, sz.height))
                    }
                }
                val minFrameDurationByRes = mutableMapOf<VideoResolution, Long>()
                for (res in VideoResolution.VIDEO_MODE_OPTIONS) {
                    val exactSize = combinedVideoSizes.firstOrNull { sz ->
                        (sz.width == res.width && sz.height == res.height) ||
                            (sz.width == res.height && sz.height == res.width)
                    } ?: if (res == VideoResolution.FHD_1080P) {
                        combinedVideoSizes.firstOrNull { sz ->
                            (sz.width == 1920 && sz.height == 1088) || (sz.width == 1088 && sz.height == 1920)
                        }
                    } else null
                    if (exactSize != null && scMap != null) {
                        val recDur = runCatching { scMap.getOutputMinFrameDuration(MediaRecorder::class.java, exactSize) }.getOrDefault(0L)
                        val codecDur = runCatching { scMap.getOutputMinFrameDuration(MediaCodec::class.java, exactSize) }.getOrDefault(0L)
                        val bestDur = listOf(recDur, codecDur).filter { it > 0L }.minOrNull() ?: 0L
                        if (bestDur > 0L) minFrameDurationByRes[res] = bestDur
                    }
                }

                val highSpeedEntries = buildList {
                    val hsSizes = runCatching { scMap?.highSpeedVideoSizes ?: emptyArray() }.getOrDefault(emptyArray())
                    for (sz in hsSizes.sortedByDescending { it.width.toLong() * it.height.toLong() }) {
                        val ranges = runCatching { scMap?.getHighSpeedVideoFpsRangesFor(sz) ?: emptyArray() }.getOrDefault(emptyArray())
                        val rangeStr = ranges.joinToString("/") { "[${it.lower}-${it.upper}]" }
                        add("${sz.width}x${sz.height}@$rangeStr")
                    }
                }
                val highSpeedMap = mutableMapOf<Pair<Int, Int>, Int>()
                runCatching { scMap?.highSpeedVideoSizes ?: emptyArray() }.getOrDefault(emptyArray()).forEach { sz ->
                    val maxFps = runCatching { scMap?.getHighSpeedVideoFpsRangesFor(sz) ?: emptyArray() }
                        .getOrDefault(emptyArray()).maxOfOrNull { it.upper } ?: 0
                    if (maxFps > 0) {
                        val key = maxOf(sz.width, sz.height) to minOf(sz.width, sz.height)
                        if (maxFps > (highSpeedMap[key] ?: 0)) highSpeedMap[key] = maxFps
                    }
                }

                fun formatSizeList(sizes: Array<Size>?): List<String> =
                    sizes?.sortedByDescending { it.width.toLong() * it.height.toLong() }?.mapNotNull { sz ->
                        CaptureResolutionResolver.formatMegapixelsWithDimensions(sz.width, sz.height)
                    } ?: emptyList()

                fun queryMaxResSizesForFormat(format: Int): List<String> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
                    val maxMap = runCatching {
                        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    }.getOrNull() ?: return emptyList()
                    val combined = ((runCatching { maxMap.getOutputSizes(format) }.getOrNull() ?: emptyArray()) +
                        (runCatching { maxMap.getHighResolutionOutputSizes(format) }.getOrNull() ?: emptyArray()))
                        .distinctBy { it.width to it.height }
                        .toTypedArray()
                    return formatSizeList(combined)
                }

                val stdJpegs = formatSizeList(scMap?.getOutputSizes(ImageFormat.JPEG))
                val hiResJpegs = formatSizeList(runCatching { scMap?.getHighResolutionOutputSizes(ImageFormat.JPEG) }.getOrNull())
                val maxResJpegs = queryMaxResSizesForFormat(ImageFormat.JPEG)

                val stdYuvs = formatSizeList(scMap?.getOutputSizes(ImageFormat.YUV_420_888))
                val hiResYuvs = formatSizeList(runCatching { scMap?.getHighResolutionOutputSizes(ImageFormat.YUV_420_888) }.getOrNull())
                val maxResYuvs = queryMaxResSizesForFormat(ImageFormat.YUV_420_888)

                val stdRaws = formatSizeList(scMap?.getOutputSizes(ImageFormat.RAW_SENSOR))
                val hiResRaws = formatSizeList(runCatching { scMap?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR) }.getOrNull())
                val maxResRaws = queryMaxResSizesForFormat(ImageFormat.RAW_SENSOR)

                val rawVideoStabModes = (chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)).toList()
                val rawOisModes = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)).toList()
                val videoStabModes = rawVideoStabModes.map { mode ->
                    when (mode) {
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF(0)"
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON(1)"
                        2 -> "PREVIEW_STABILIZATION(2)"
                        else -> "MODE($mode)"
                    }
                }
                val oisModes = rawOisModes.map { mode ->
                    when (mode) {
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF(0)"
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON(1)"
                        else -> "MODE($mode)"
                    }
                }
                val rawAeRanges = (chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()).toList()
                val aeFpsPairs = rawAeRanges.map { it.lower to it.upper }
                val aeRanges = rawAeRanges.map { "[${it.lower}, ${it.upper}]" }

                val ispCapsSummary = IspQualityCapabilities.fromCameraCharacteristics(chars).summaryLabel()
                val isPrimaryForFacing = cid == firstBackId || cid == firstFrontId
                val (extNames, extStatusLabel) = queryExtensionsForCameraId(
                    context = context,
                    cameraProvider = cameraProvider,
                    cameraId = cid,
                    lensFacing = lensFacing,
                    isPrimaryForFacing = isPrimaryForFacing
                )

                val camcorderMaxByRes = queryCamcorderProfileMaxFpsByResolution(cid)
                val staticProfile = buildVerifiedVideoCapabilityProfile(
                    streamVideoSizes = streamVideoSizePairs,
                    minFrameDurationNanosByResolution = minFrameDurationByRes,
                    aeFpsRanges = aeFpsPairs,
                    highSpeedSizeToMaxFps = highSpeedMap,
                    cameraXSupportedResolutions = emptySet(),
                    camcorderMaxFpsByResolution = camcorderMaxByRes,
                    codecMaxFpsByResolution = encoderInfo.maxFpsByResolution,
                    availableVideoStabilizationModes = rawVideoStabModes,
                    availableOpticalStabilizationModes = rawOisModes
                )
                val cachedSessionEvals = VideoSessionConfigVerifier.getCachedEvaluations(cid)
                val finalEvaluations = if (!cachedSessionEvals.isNullOrEmpty()) {
                    cachedSessionEvals
                } else {
                    VideoSessionConfigVerifier.verifyAndCacheVideoCombinationsWithDiagnostics(
                        context = context,
                        cameraId = cid,
                        candidates = staticProfile.supportedVideoQualities,
                        staticEvaluations = staticProfile.videoComboEvaluations,
                        aeFpsRanges = aeFpsPairs,
                        allowDeviceOpenProbe = false
                    ).second
                }

                entries.add(
                    CameraIdDebugInfo(
                        cameraId = cid,
                        isPhysicalOnly = isPhysOnly,
                        parentLogicalId = parentId,
                        facingLabel = facingLabel,
                        metadataLabel = label,
                        physicalCameraIds = physicalIds,
                        focalLengthsMm = focals,
                        pixelArraySize = pixelArray,
                        activeArraySize = activeArray,
                        videoSizesWithMaxFps = videoSizes,
                        highSpeedVideoEntries = highSpeedEntries,
                        standardJpegSizes = stdJpegs,
                        highResJpegSizes = hiResJpegs,
                        maxResJpegSizes = maxResJpegs,
                        videoStabilizationModes = videoStabModes,
                        opticalStabilizationModes = oisModes,
                        aeFpsRanges = aeRanges,
                        ispQualitySummary = ispCapsSummary,
                        supportedExtensions = extNames,
                        extensionsStatusLabel = extStatusLabel,
                        standardYuvSizes = stdYuvs,
                        highResYuvSizes = hiResYuvs,
                        maxResYuvSizes = maxResYuvs,
                        standardRawSizes = stdRaws,
                        highResRawSizes = hiResRaws,
                        maxResRawSizes = maxResRaws,
                        supportsUltraHighResSensorCapability = supportsUltraHighRes,
                        encoderCapabilitiesSummary = encoderInfo.summaryLines,
                        videoComboEvaluations = finalEvaluations
                    )
                )
            }
        }
    }

    return CameraHardwareDebugReport(
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        cameras = entries
    )
}

/**
 * Result of a dedicated Camera2 maximum-resolution JPEG still capture.
 */
data class Camera2StillCaptureResult(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int
)

/**
 * Dedicated Camera2 single-surface `ImageReader(JPEG)` capturer for HD Mode.
 *
 * Because Android's `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` stream (API 31+) cannot be combined with
 * normal-resolution preview streams in the same capture session, and because some sensors only expose
 * full-resolution streams on a specific physical camera ID, this capturer performs a clean single-surface
 * still-capture session switch (after CameraX unbinds) with full AE/AF precapture convergence.
 */
object Camera2MaxResolutionStillCapturer {

    @SuppressLint("MissingPermission")
    fun captureMaximumResolutionJpeg(
        context: Context,
        source: HdMaxPhotoCaptureSource,
        flashMode: FlashMode,
        exposureCompensationIndex: Int,
        displayRotation: Int
    ): Camera2StillCaptureResult? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val thread = HandlerThread("Camera2MaxResStill").apply { start() }
        val handler = Handler(thread.looper)
        val callbackExecutor = Executors.newSingleThreadExecutor()

        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var imageReader: ImageReader? = null

        try {
            val characteristics = cameraManager.getCameraCharacteristics(source.cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val deviceRotationDegrees = when (displayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val jpegOrientation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation + deviceRotationDegrees) % 360
            } else {
                (sensorOrientation - deviceRotationDegrees + 360) % 360
            }

            // 1. Open target Camera2 device (logical or physical ID)
            val openLatch = CountDownLatch(1)
            val openRef = AtomicReference<CameraDevice?>(null)
            cameraManager.openCamera(
                source.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        openRef.set(camera)
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        openLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(HW_TAG, "Max-res Camera2 open error $error on camera ${source.cameraId}")
                        runCatching { camera.close() }
                        openLatch.countDown()
                    }
                },
                handler
            )
            if (!openLatch.await(3000L, TimeUnit.MILLISECONDS)) {
                Log.w(HW_TAG, "Timed out opening Camera2 ${source.cameraId} for max-res HD capture")
                return null
            }
            cameraDevice = openRef.get() ?: return null

            // 2. Create single-surface ImageReader at exact maximum resolution (JPEG or YUV_420_888)
            val isYuvSource = source.sourceTag.startsWith("YUV", ignoreCase = true)
            val readerFormat = if (isYuvSource) ImageFormat.YUV_420_888 else ImageFormat.JPEG
            imageReader = ImageReader.newInstance(source.width, source.height, readerFormat, 2)
            val imageBytesRef = AtomicReference<ByteArray?>(null)
            val imageLatch = CountDownLatch(1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image != null) {
                    try {
                        val bytes = if (isYuvSource) {
                            encodeYuv420888ToJpeg(image, CameraCaptureQualityPolicy.SAVED_JPEG_QUALITY)
                        } else {
                            val buffer = image.planes[0].buffer
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        }
                        imageBytesRef.set(bytes)
                    } catch (e: Throwable) {
                        Log.e(HW_TAG, "Error reading max-res ${source.sourceTag} image", e)
                    } finally {
                        runCatching { image.close() }
                        imageLatch.countDown()
                    }
                }
            }, handler)

            // 3. Configure single-surface session (no preview surface mixed with max-res stream)
            val sessionLatch = CountDownLatch(1)
            val sessionRef = AtomicReference<CameraCaptureSession?>(null)
            val outputConfig = OutputConfiguration(imageReader.surface)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                callbackExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sessionRef.set(session)
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(HW_TAG, "Max-res Camera2 session configuration failed for ${source.width}x${source.height}")
                        sessionLatch.countDown()
                    }
                }
            )
            cameraDevice.createCaptureSession(sessionConfig)
            if (!sessionLatch.await(3000L, TimeUnit.MILLISECONDS)) {
                return null
            }
            captureSession = sessionRef.get() ?: return null

            // 4. Build still capture request with SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION on API 31+
            val ispCaps = IspQualityCapabilities.fromCameraCharacteristics(characteristics)
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                CameraCaptureQualityPolicy.applyIspQualityToCamera2Builder(
                    builder = this,
                    ispCaps = ispCaps,
                    preferHighQuality = true
                )
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

                val aeMode = when (flashMode) {
                    FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    FlashMode.OFF, FlashMode.TORCH -> CaptureRequest.CONTROL_AE_MODE_ON
                }
                set(CaptureRequest.CONTROL_AE_MODE, aeMode)
                if (flashMode == FlashMode.TORCH) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
                if (exposureCompensationIndex != 0) {
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensationIndex)
                }
                if (source.requiresMaxResPixelMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    set(
                        CaptureRequest.SENSOR_PIXEL_MODE,
                        CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                    )
                }
            }

            captureSession.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.i(
                            HW_TAG,
                            "Max-res Camera2 capture completed on camera ${source.cameraId} " +
                                "(${source.width}x${source.height}, maxResPixelMode=${source.requiresMaxResPixelMode})"
                        )
                    }
                },
                handler
            )

            if (!imageLatch.await(8000L, TimeUnit.MILLISECONDS)) {
                Log.w(HW_TAG, "Timed out waiting for max-res JPEG frame on camera ${source.cameraId}")
                return null
            }

            val bytes = imageBytesRef.get() ?: return null
            if (bytes.isEmpty()) return null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            val actualW = if (boundsOpts.outWidth > 0) boundsOpts.outWidth else source.width
            val actualH = if (boundsOpts.outHeight > 0) boundsOpts.outHeight else source.height

            val decodedLabel = CaptureResolutionResolver.formatMegapixelsWithDimensions(actualW, actualH)
                ?: "${actualW}x${actualH}"
            Log.i(
                HW_TAG,
                "Captured HD Max-Res JPEG: requested=${source.width}x${source.height}, decoded=$decodedLabel"
            )
            return Camera2StillCaptureResult(
                jpegBytes = bytes,
                width = actualW,
                height = actualH,
                rotationDegrees = if (isYuvSource) jpegOrientation else 0 // JPEG_ORIENTATION already baked in EXIF for JPEG
            )
        } catch (t: Throwable) {
            Log.e(HW_TAG, "Camera2 max-resolution capture failed: ${t.localizedMessage}", t)
            return null
        } finally {
            runCatching { captureSession?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { imageReader?.close() }
            callbackExecutor.shutdown()
            thread.quitSafely()
        }
    }

    private fun encodeYuv420888ToJpeg(image: Image, jpegQuality: Int): ByteArray? {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val nv21 = ByteArray(width * height * 3 / 2)
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var outPos = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (yPixelStride == 1) {
                yBuf.position(rowStart)
                yBuf.get(nv21, outPos, width)
                outPos += width
            } else {
                for (col in 0 until width) {
                    nv21[outPos++] = yBuf.get(rowStart + col * yPixelStride)
                }
            }
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                nv21[outPos++] = vBuf.get(vRowStart + col * vPixelStride)
                nv21[outPos++] = uBuf.get(uRowStart + col * uPixelStride)
            }
        }

        val baos = ByteArrayOutputStream(width * height / 4)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val ok = yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality.coerceIn(90, 100), baos)
        return if (ok) baos.toByteArray() else null
    }
}

/**
 * Direct Camera2 + [MediaRecorder] video recording engine that guarantees exact user-selected
 * resolution (720p, 1080p, 2K 2560x1440, 4K), exact FPS (`setVideoFrameRate` + `setCaptureRate` +
 * `CONTROL_AE_TARGET_FPS_RANGE`), scaled bitrate, and per-resolution EIS/OIS stabilization.
 */
class Camera2DirectVideoRecorder(private val context: Context) {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var currentRequestBuilder: CaptureRequest.Builder? = null
    private var currentOutputFile: File? = null
    private val isRecordingActive = AtomicBoolean(false)

    private fun chooseSupportedVideoEncoder(width: Int, height: Int, fps: Int): Int {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            fun encoderSupports(mime: String): Boolean {
                for (info in list.codecInfos) {
                    if (!info.isEncoder) continue
                    if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                    val vCaps = runCatching { info.getCapabilitiesForType(mime)?.videoCapabilities }.getOrNull() ?: continue
                    val ok = runCatching {
                        vCaps.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                            vCaps.areSizeAndRateSupported(height, width, fps.toDouble())
                    }.getOrDefault(false)
                    if (ok) return true
                }
                return false
            }
            when {
                encoderSupports(MediaFormat.MIMETYPE_VIDEO_AVC) -> MediaRecorder.VideoEncoder.H264
                encoderSupports(MediaFormat.MIMETYPE_VIDEO_HEVC) -> MediaRecorder.VideoEncoder.HEVC
                else -> MediaRecorder.VideoEncoder.H264
            }
        } catch (_: Throwable) {
            MediaRecorder.VideoEncoder.H264
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        cameraId: String,
        surfaceTexture: SurfaceTexture,
        resolution: VideoResolution,
        frameRate: VideoFrameRate,
        aeFpsRange: Range<Int>,
        stabilizationMode: VideoStabilizationMode,
        lensCaps: LensCapabilities?,
        zoomRatio: Float,
        torchEnabled: Boolean,
        exposureCompensationIndex: Int,
        displayRotation: Int,
        includeAudio: Boolean,
        outputFile: File,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission missing.")
            return false
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            onError("CameraManager unavailable.")
            return false
        }

        val thread = HandlerThread("Camera2VideoRecorder").apply { start() }
        val bgHandler = Handler(thread.looper)
        handlerThread = thread
        handler = bgHandler
        currentOutputFile = outputFile

        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val deviceRotDeg = when (displayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val orientationHint = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation + deviceRotDeg) % 360
            } else {
                (sensorOrientation - deviceRotDeg + 360) % 360
            }

            // Pick a compatible preview surface size matching video aspect ratio (prefer matching size <= 1080p)
            val scMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSizes = scMap?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))
            val targetRatio = resolution.width.toFloat() / resolution.height.toFloat()
            val exactPreviewMatch = if (resolution.width <= 1920 && resolution.height <= 1080) {
                previewSizes.firstOrNull { sz ->
                    maxOf(sz.width, sz.height) == resolution.width && minOf(sz.width, sz.height) == resolution.height
                }
            } else null
            val previewSize = exactPreviewMatch ?: previewSizes
                .filter { maxOf(it.width, it.height) <= 1920 && minOf(it.width, it.height) <= 1080 }
                .minByOrNull { sz ->
                    val r = maxOf(sz.width, sz.height).toFloat() / minOf(sz.width, sz.height).coerceAtLeast(1).toFloat()
                    kotlin.math.abs(r - targetRatio) * 1000f + kotlin.math.abs(maxOf(sz.width, sz.height) - 1280) * 0.1f
                } ?: Size(1280, 720)

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val pSurface = Surface(surfaceTexture)
            previewSurface = pSurface

            val targetBitrate = calculateTargetVideoBitrate(resolution, frameRate.fps)
            val chosenVideoEncoder = chooseSupportedVideoEncoder(resolution.width, resolution.height, frameRate.fps)

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = recorder

            val audioGranted = includeAudio &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioGranted) {
                recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.setVideoEncodingBitRate(targetBitrate)
            recorder.setVideoFrameRate(frameRate.fps)
            recorder.setVideoSize(resolution.width, resolution.height)
            recorder.setVideoEncoder(chosenVideoEncoder)
            if (audioGranted) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(48_000)
            }
            recorder.setOrientationHint(orientationHint)
            recorder.prepare()

            val recorderSurface = recorder.surface

            val openLatch = CountDownLatch(1)
            val devRef = AtomicReference<CameraDevice?>(null)
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        devRef.set(camera)
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        openLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                        openLatch.countDown()
                    }
                },
                bgHandler
            )
            if (!openLatch.await(3000L, TimeUnit.MILLISECONDS)) {
                releaseInternal()
                onError("Timed out opening camera for video recording.")
                return false
            }
            val device = devRef.get()
            if (device == null) {
                releaseInternal()
                onError("Unable to open camera $cameraId for video recording.")
                return false
            }
            cameraDevice = device

            val sessionExecutor = Executors.newSingleThreadExecutor()
            val outputs = listOf(
                OutputConfiguration(pSurface),
                OutputConfiguration(recorderSurface)
            )

            val recordBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(pSurface)
                addTarget(recorderSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeFpsRange)
                if (torchEnabled && lensCaps?.isTorchSupported == true) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                if (exposureCompensationIndex != 0) {
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensationIndex)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (range != null) {
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(range.lower, range.upper))
                    }
                }
                applyStabilizationToRequest(this, stabilizationMode, lensCaps)
            }

            fun openSession(includeSessionParams: Boolean): CameraCaptureSession? {
                val latch = CountDownLatch(1)
                val ref = AtomicReference<CameraCaptureSession?>(null)
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    sessionExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            ref.set(session)
                            latch.countDown()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.w(
                                HW_TAG,
                                "Camera2 video session configuration failed (sessionParams=$includeSessionParams) " +
                                    "for ${resolution.width}x${resolution.height} @ ${frameRate.fps}fps"
                            )
                            latch.countDown()
                        }
                    }
                )
                if (includeSessionParams) {
                    cfg.sessionParameters = recordBuilder.build()
                }
                device.createCaptureSession(cfg)
                if (!latch.await(3000L, TimeUnit.MILLISECONDS)) return null
                return ref.get()
            }

            val session = openSession(includeSessionParams = true)
                ?: openSession(includeSessionParams = false)
            sessionExecutor.shutdown()

            if (session == null) {
                releaseInternal()
                onError("Capture session failed for ${resolution.displayName} @ ${frameRate.displayName}.")
                return false
            }
            captureSession = session
            currentRequestBuilder = recordBuilder

            session.setRepeatingRequest(recordBuilder.build(), null, bgHandler)
            recorder.start()
            isRecordingActive.set(true)
            Log.i(
                HW_TAG,
                "Started Camera2 MediaRecorder: camera=$cameraId, size=${resolution.width}x${resolution.height}, " +
                    "fps=${frameRate.fps}, aeRange=[${aeFpsRange.lower},${aeFpsRange.upper}], " +
                    "bitrate=${targetBitrate / 1_000_000}Mbps, stab=${stabilizationMode.displayName}"
            )
            onStarted()
            true
        } catch (t: Throwable) {
            Log.e(HW_TAG, "Failed to start Camera2 MediaRecorder", t)
            releaseInternal()
            onError("Video recording failed: ${t.localizedMessage ?: "unknown error"}")
            false
        }
    }

    fun updateLiveControls(
        zoomRatio: Float? = null,
        torchEnabled: Boolean? = null,
        exposureCompensationIndex: Int? = null
    ) {
        if (!isRecordingActive.get()) return
        val session = captureSession ?: return
        val builder = currentRequestBuilder ?: return
        val bgHandler = handler ?: return
        runCatching {
            if (zoomRatio != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceAtLeast(0.5f))
            }
            if (torchEnabled != null) {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }
            if (exposureCompensationIndex != null) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensationIndex)
            }
            session.setRepeatingRequest(builder.build(), null, bgHandler)
        }
    }

    fun stopRecording(): File? {
        if (!isRecordingActive.getAndSet(false)) {
            releaseInternal()
            return null
        }
        val file = currentOutputFile
        try {
            runCatching { captureSession?.stopRepeating() }
            runCatching { captureSession?.abortCaptures() }
            runCatching { mediaRecorder?.stop() }
        } catch (t: Throwable) {
            Log.w(HW_TAG, "Error stopping MediaRecorder cleanly: ${t.localizedMessage}")
        } finally {
            releaseInternal()
        }
        return if (file != null && file.exists() && file.length() > 0L) file else null
    }

    private fun applyStabilizationToRequest(
        builder: CaptureRequest.Builder,
        mode: VideoStabilizationMode,
        lensCaps: LensCapabilities?
    ) {
        when (mode) {
            VideoStabilizationMode.OFF -> {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                if (lensCaps?.isOpticalStabilizationSupported == true) {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                }
            }
            VideoStabilizationMode.STANDARD -> {
                when {
                    lensCaps?.isDigitalVideoStabilizationSupported == true -> {
                        builder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                    }
                    lensCaps?.isPreviewStabilizationSupported == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 2)
                    }
                    lensCaps?.isOpticalStabilizationSupported == true -> {
                        builder.set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        )
                    }
                }
            }
            VideoStabilizationMode.ACTIVE -> {
                if (lensCaps?.isPreviewStabilizationSupported == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 2)
                } else if (lensCaps?.isDigitalVideoStabilizationSupported == true) {
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
                if (lensCaps?.isOpticalStabilizationSupported == true) {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                }
            }
        }
    }

    private fun releaseInternal() {
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        currentRequestBuilder = null
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        handler = null
    }
}

/**
 * Reads and logs the real pixel width and height of a saved photo file from MediaStore.
 */
fun logSavedPhotoDimensions(context: Context, uri: Uri, modeLabel: String) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            val verifiedLabel = CaptureResolutionResolver.formatMegapixelsWithDimensions(w, h) ?: "${w}x${h}"
            Log.i(
                HW_TAG,
                "Saved $modeLabel photo verified from file: $verifiedLabel, uri=$uri"
            )
        }
    }
}

/**
 * Reads and logs real recorded video resolution, frame rate, and duration via [MediaMetadataRetriever].
 */
fun inspectAndLogSavedVideoMetadata(
    videoFile: File,
    requestedResolution: VideoResolution,
    requestedFps: Int,
    modeLabel: String
): Triple<Int, Int, Long> {
    var width = 0
    var height = 0
    var durationMs = 0L
    var reportedFps = 0f
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(videoFile.absolutePath)
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
        } else 0L
        reportedFps = when {
            captureFps != null && captureFps > 0f -> captureFps
            durationMs > 0L && frameCount > 0L -> (frameCount * 1000f) / durationMs
            else -> 0f
        }
        Log.i(
            HW_TAG,
            "Saved $modeLabel video verified (MediaMetadataRetriever): actual=${width}x${height} @ " +
                "${String.format(Locale.US, "%.1f", reportedFps)} FPS (duration=${durationMs}ms), " +
                "requested=${requestedResolution.width}x${requestedResolution.height} @ ${requestedFps} FPS"
        )
    } catch (e: Exception) {
        Log.w(HW_TAG, "Unable to read MediaMetadataRetriever stats for ${videoFile.name}", e)
    } finally {
        runCatching { retriever.release() }
    }
    return Triple(width, height, durationMs)
}
