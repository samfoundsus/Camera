package com.example.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
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
import android.media.ImageReader
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
import androidx.core.content.ContextCompat
import com.example.core.preferences.FlashMode
import com.example.core.preferences.VideoStabilizationMode
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
 * Per-camera cached verifier that probes Camera2 `SessionConfiguration` support for every candidate
 * `(VideoResolution, VideoFrameRate)` combination so unsupported configurations are hidden before
 * the user sees them, without re-probing on every rebind.
 */
object VideoSessionConfigVerifier {

    private val verifiedCache = ConcurrentHashMap<String, Map<VideoResolution, List<VideoFrameRate>>>()
    private val failedCombosByCamera = ConcurrentHashMap<String, MutableSet<Pair<VideoResolution, VideoFrameRate>>>()

    fun getCached(cameraId: String): Map<VideoResolution, List<VideoFrameRate>>? = verifiedCache[cameraId]

    fun markComboFailed(cameraId: String, resolution: VideoResolution, frameRate: VideoFrameRate) {
        val set = failedCombosByCamera.getOrPut(cameraId) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        set.add(resolution to frameRate)
        verifiedCache[cameraId]?.let { existing ->
            val updated = LinkedHashMap<VideoResolution, List<VideoFrameRate>>()
            for ((res, rates) in existing) {
                val filtered = rates.filter { !(res == resolution && it == frameRate) }
                if (filtered.isNotEmpty()) updated[res] = filtered
            }
            if (updated.isNotEmpty()) verifiedCache[cameraId] = updated
        }
    }

    fun clearCacheForTesting() {
        verifiedCache.clear()
        failedCombosByCamera.clear()
    }

    /**
     * Verifies candidate `(VideoResolution, VideoFrameRate)` combinations for [cameraId] by testing
     * Camera2 `SessionConfiguration` support (API 35+ directly on [CameraCharacteristics], API 29-34
     * via deferred `OutputConfiguration(size, MediaRecorder::class.java)` + preview surface when a
     * camera device can be queried). Caches the result per [cameraId].
     */
    @SuppressLint("MissingPermission")
    fun verifyAndCacheVideoCombinations(
        context: Context,
        cameraId: String,
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        aeFpsRanges: List<Pair<Int, Int>>,
        allowDeviceOpenProbe: Boolean = false
    ): Map<VideoResolution, List<VideoFrameRate>> {
        if (candidates.isEmpty()) return candidates
        verifiedCache[cameraId]?.let { cached ->
            val failed = failedCombosByCamera[cameraId].orEmpty()
            return if (failed.isEmpty()) cached else filterOutFailed(cached, failed)
        }

        val failedSet = failedCombosByCamera[cameraId].orEmpty()
        val cameraManager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        }.getOrNull() ?: return filterOutFailed(candidates, failedSet)

        val characteristics = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return filterOutFailed(candidates, failedSet)

        // API 35+ supports CameraCharacteristics.isSessionConfigurationSupported without even opening the camera!
        if (Build.VERSION.SDK_INT >= 35) {
            val verified = probeWithCharacteristicsApi35(characteristics, candidates, aeFpsRanges)
            if (verified.isNotEmpty()) {
                val filtered = filterOutFailed(verified, failedSet)
                verifiedCache[cameraId] = filtered
                return filtered
            }
        }

        // API 29-34: optionally probe via CameraDevice.isSessionConfigurationSupported before CameraX binds
        val hasCameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (allowDeviceOpenProbe && hasCameraPerm && !isRobolectricRuntime()) {
            val probed = probeWithTemporaryCameraDevice(cameraManager, cameraId, characteristics, candidates, aeFpsRanges)
            if (probed != null && probed.isNotEmpty()) {
                val filtered = filterOutFailed(probed, failedSet)
                verifiedCache[cameraId] = filtered
                return filtered
            }
        }

        val fallbackFiltered = filterOutFailed(candidates, failedSet)
        verifiedCache[cameraId] = fallbackFiltered
        return fallbackFiltered
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

    private fun pickPreviewSizeForVideo(characteristics: CameraCharacteristics, videoRes: VideoResolution): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1280, 720)
        val targetRatio = videoRes.width.toFloat() / videoRes.height.toFloat()
        return previewSizes
            .filter { maxOf(it.width, it.height) <= 1920 && minOf(it.width, it.height) <= 1080 }
            .minByOrNull { sz ->
                val r = maxOf(sz.width, sz.height).toFloat() / minOf(sz.width, sz.height).coerceAtLeast(1).toFloat()
                kotlin.math.abs(r - targetRatio) * 1000f + kotlin.math.abs(maxOf(sz.width, sz.height) - 1280) * 0.1f
            } ?: Size(1280, 720)
    }

    private fun probeWithCharacteristicsApi35(
        characteristics: CameraCharacteristics,
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        aeFpsRanges: List<Pair<Int, Int>>
    ): Map<VideoResolution, List<VideoFrameRate>> {
        val result = LinkedHashMap<VideoResolution, List<VideoFrameRate>>()
        val probeExecutor = Executors.newSingleThreadExecutor()
        try {
            val isSupportedMethod = runCatching {
                CameraCharacteristics::class.java.getMethod(
                    "isSessionConfigurationSupported",
                    SessionConfiguration::class.java
                )
            }.getOrNull() ?: return emptyMap()

            for ((res, rates) in candidates) {
                val previewSize = pickPreviewSizeForVideo(characteristics, res)
                val videoSize = Size(res.width, res.height)
                val outputs = listOf(
                    OutputConfiguration(previewSize, SurfaceTexture::class.java),
                    OutputConfiguration(videoSize, MediaRecorder::class.java)
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
                val supported = runCatching {
                    (isSupportedMethod.invoke(characteristics, config) as? Boolean) == true
                }.getOrDefault(true)
                if (supported) {
                    val keptRates = rates.filter { rate ->
                        val chosenRange = selectBestVideoAeFpsRange(aeFpsRanges, rate.fps)
                        chosenRange.second >= rate.fps || rate == VideoFrameRate.FPS_30
                    }
                    if (keptRates.isNotEmpty()) {
                        result[res] = keptRates
                    }
                } else {
                    Log.w(HW_TAG, "SessionConfiguration rejected for ${res.displayName} via API 35 probe")
                }
            }
        } catch (t: Throwable) {
            Log.w(HW_TAG, "API 35 session config probe skipped: ${t.localizedMessage}")
        } finally {
            probeExecutor.shutdown()
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private fun probeWithTemporaryCameraDevice(
        cameraManager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics,
        candidates: Map<VideoResolution, List<VideoFrameRate>>,
        aeFpsRanges: List<Pair<Int, Int>>
    ): Map<VideoResolution, List<VideoFrameRate>>? {
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
            for ((res, rates) in candidates) {
                val previewSize = pickPreviewSizeForVideo(characteristics, res)
                val videoSize = Size(res.width, res.height)
                val keptRates = mutableListOf<VideoFrameRate>()
                for (rate in rates) {
                    val fpsPair = selectBestVideoAeFpsRange(aeFpsRanges, rate.fps)
                    val supported = runCatching {
                        val outputs = listOf(
                            OutputConfiguration(previewSize, SurfaceTexture::class.java),
                            OutputConfiguration(videoSize, MediaRecorder::class.java)
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
                    }.getOrElse { exc ->
                        // UnsupportedOperationException means HAL doesn't implement isSessionConfigurationSupported;
                        // do not drop valid candidates when HAL doesn't implement the query.
                        if (exc is UnsupportedOperationException) true else false
                    }
                    if (supported) {
                        keptRates.add(rate)
                    } else {
                        Log.i(HW_TAG, "Hidden unsupported video session config on camera $cameraId: ${res.displayName} @ ${rate.displayName} (AE range $fpsPair)")
                    }
                }
                if (keptRates.isNotEmpty()) {
                    verified[res] = keptRates
                }
            }
            return verified
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

/**
 * Inspects EVERY camera ID on the device (logical + physical sub-cameras) and collects all raw
 * video sizes with max FPS, JPEG sizes (standard, high-res, API 31+ maximum-resolution),
 * stabilization modes, and AE FPS ranges for the hidden Settings debug screen.
 */
fun collectCameraHardwareDebugReport(context: Context): CameraHardwareDebugReport {
    val cameraManager = runCatching {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
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

        // Pre-collect focal lengths per facing so labels can distinguish ultrawide vs telephoto from metadata
        val backFocals = mutableListOf<Float>()
        val frontFocals = mutableListOf<Float>()
        for ((cid, _, _) in idsToInspect) {
            runCatching {
                val c = cameraManager.getCameraCharacteristics(cid)
                val f = c.get(CameraCharacteristics.LENS_FACING)
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

                val scMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val videoSizes = scMap?.getOutputSizes(MediaRecorder::class.java)
                    ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
                    ?.map { sz ->
                        val minDur = runCatching {
                            scMap.getOutputMinFrameDuration(MediaRecorder::class.java, sz)
                        }.getOrDefault(0L)
                        val maxFps = if (minDur > 0L) 1_000_000_000.0 / minDur.toDouble() else 0.0
                        VideoSizeFpsDebugEntry(sz.width, sz.height, maxFps, minDur)
                    } ?: emptyList()

                val highSpeedEntries = buildList {
                    val hsSizes = runCatching { scMap?.highSpeedVideoSizes ?: emptyArray() }.getOrDefault(emptyArray())
                    for (sz in hsSizes.sortedByDescending { it.width.toLong() * it.height.toLong() }) {
                        val ranges = runCatching { scMap?.getHighSpeedVideoFpsRangesFor(sz) ?: emptyArray() }.getOrDefault(emptyArray())
                        val rangeStr = ranges.joinToString("/") { "[${it.lower}-${it.upper}]" }
                        add("${sz.width}x${sz.height}@$rangeStr")
                    }
                }

                fun formatSizeList(sizes: Array<Size>?): List<String> =
                    sizes?.sortedByDescending { it.width.toLong() * it.height.toLong() }?.mapNotNull { sz ->
                        CaptureResolutionResolver.formatMegapixelsWithDimensions(sz.width, sz.height)
                    } ?: emptyList()

                val stdJpegs = formatSizeList(scMap?.getOutputSizes(ImageFormat.JPEG))
                val hiResJpegs = formatSizeList(runCatching { scMap?.getHighResolutionOutputSizes(ImageFormat.JPEG) }.getOrNull())
                val maxResJpegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val maxMap = runCatching {
                        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    }.getOrNull()
                    val combined = ((maxMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()) +
                        (runCatching { maxMap?.getHighResolutionOutputSizes(ImageFormat.JPEG) }.getOrNull() ?: emptyArray()))
                        .distinctBy { it.width to it.height }
                        .toTypedArray()
                    formatSizeList(combined)
                } else emptyList()

                val videoStabModes = (chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)).map { mode ->
                    when (mode) {
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF(0)"
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON(1)"
                        2 -> "PREVIEW_STABILIZATION(2)"
                        else -> "MODE($mode)"
                    }
                }
                val oisModes = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)).map { mode ->
                    when (mode) {
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF(0)"
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON(1)"
                        else -> "MODE($mode)"
                    }
                }
                val aeRanges = (chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray())
                    .map { "[${it.lower}, ${it.upper}]" }

                val ispCapsSummary = IspQualityCapabilities.fromCameraCharacteristics(chars).summaryLabel()

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
                        ispQualitySummary = ispCapsSummary
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

            // 2. Create single-surface JPEG ImageReader at exact maximum resolution (maxImages = 2)
            imageReader = ImageReader.newInstance(source.width, source.height, ImageFormat.JPEG, 2)
            val imageBytesRef = AtomicReference<ByteArray?>(null)
            val imageLatch = CountDownLatch(1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        imageBytesRef.set(bytes)
                    } catch (e: Throwable) {
                        Log.e(HW_TAG, "Error reading max-res JPEG plane", e)
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
                rotationDegrees = 0 // JPEG_ORIENTATION already baked in EXIF; AspectCropUtils preserves/handles EXIF
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

            // Pick a compatible preview surface size matching video aspect ratio
            val scMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSizes = scMap?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))
            val targetRatio = resolution.width.toFloat() / resolution.height.toFloat()
            val previewSize = previewSizes
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
            recorder.setCaptureRate(frameRate.fps.toDouble())
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

            val sessionLatch = CountDownLatch(1)
            val sessionRef = AtomicReference<CameraCaptureSession?>(null)
            val sessionExecutor = Executors.newSingleThreadExecutor()
            val outputs = listOf(
                OutputConfiguration(pSurface),
                OutputConfiguration(recorderSurface)
            )
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sessionRef.set(session)
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(
                            HW_TAG,
                            "Camera2 video session configuration failed for ${resolution.width}x${resolution.height} @ ${frameRate.fps}fps"
                        )
                        sessionLatch.countDown()
                    }
                }
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
            sessionConfig.sessionParameters = recordBuilder.build()
            device.createCaptureSession(sessionConfig)

            if (!sessionLatch.await(3000L, TimeUnit.MILLISECONDS)) {
                sessionExecutor.shutdown()
                releaseInternal()
                onError("Timed out configuring video capture session.")
                return false
            }
            sessionExecutor.shutdown()

            val session = sessionRef.get()
            if (session == null) {
                VideoSessionConfigVerifier.markComboFailed(cameraId, resolution, frameRate)
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
