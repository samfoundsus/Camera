package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ExifInterface
import android.media.FaceDetector
import android.media.ImageReader
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.core.camera.PortraitBlurDeviceSupport
import com.example.core.camera.PortraitDepthSupportPath
import com.example.core.camera.PortraitStageTimings
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Classification of the primary subject segmented by the on-device Portrait engine.
 */
enum class DetectedPortraitSubjectType(val displayName: String) {
    PERSON("Person (Face & Upper Body)"),
    GENERAL_SUBJECT("Foreground Subject"),
    NONE("No Distinct Subject")
}

/**
 * Result of on-device subject segmentation and edge-aware guided-filter alpha matting.
 */
data class SubjectSegmentationMask(
    val width: Int,
    val height: Int,
    val alphaMatte: FloatArray,
    val guideCoeffA: FloatArray,
    val guideCoeffB: FloatArray,
    val subjectType: DetectedPortraitSubjectType,
    val confidence: Float,
    val subjectCoverageRatio: Float,
    val detectedFaceCount: Int,
    val subjectBoundsNormalized: RectF? = null,
    val isHardwareDepthDataUsed: Boolean = false,
    val segmentationInputWidth: Int = width,
    val segmentationInputHeight: Int = height
)

/**
 * Bitmap-level result of Portrait mode processing.
 */
sealed interface PortraitBitmapResult {
    data class Success(
        val outputBitmap: Bitmap,
        val subjectType: DetectedPortraitSubjectType,
        val subjectConfidence: Float,
        val detectedFaceCount: Int,
        val appliedBlurLevel: PortraitBlurLevel,
        val appliedBlurStrength: Float,
        val simulatedApertureLabel: String,
        val appliedBlurRadiusPx: Int,
        val appliedRetouch: PortraitRetouch,
        val isHardwareDepthDataUsed: Boolean = false,
        val processingDurationMs: Long,
        val stageTimings: PortraitStageTimings? = null
    ) : PortraitBitmapResult

    data class FallbackOriginal(
        val originalBitmap: Bitmap,
        val reason: String,
        val stageTimings: PortraitStageTimings? = null
    ) : PortraitBitmapResult
}

/**
 * End-to-end JPEG payload result of Portrait capture processing, including safe fallback to the
 * oriented & aspect-cropped original image if segmentation or processing fails.
 */
sealed interface PortraitProcessingOutcome {
    val jpegBytes: ByteArray
    val width: Int
    val height: Int
    val exifOrientation: Int
    val stageTimings: PortraitStageTimings?

    data class ProcessedPortrait(
        override val jpegBytes: ByteArray,
        override val width: Int,
        override val height: Int,
        override val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        val subjectType: DetectedPortraitSubjectType,
        val subjectConfidence: Float,
        val detectedFaceCount: Int,
        val appliedBlurLevel: PortraitBlurLevel,
        val appliedBlurStrength: Float = appliedBlurLevel.blurStrength,
        val simulatedApertureLabel: String = PortraitBlurLevel.simulatedApertureLabelForStrength(appliedBlurStrength),
        val appliedBlurRadiusPx: Int,
        val appliedRetouch: PortraitRetouch,
        val depthSupportPath: PortraitDepthSupportPath,
        val isHardwareDepthDataUsed: Boolean = false,
        val processingDurationMs: Long,
        override val stageTimings: PortraitStageTimings? = null
    ) : PortraitProcessingOutcome

    data class FallbackOriginal(
        override val jpegBytes: ByteArray,
        override val width: Int,
        override val height: Int,
        override val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        val reason: String,
        override val stageTimings: PortraitStageTimings? = null
    ) : PortraitProcessingOutcome
}

/**
 * High-speed on-device Portrait Mode segmentation, edge-only guided-filter alpha matting,
 * GPU-accelerated (RenderEffect / RuntimeShader) with multi-threaded tiled CPU fallback
 * bokeh blur and full-resolution compositing.
 *
 * Key speed & memory optimizations:
 * 1. Preloads & warms up a cached singleton ML Kit [SubjectSegmenter] when entering Portrait mode.
 * 2. Runs segmentation on a downscaled copy (512–640 px longest side), upscales bilinearly,
 *    and runs guided-filter edge refinement ONLY along the subject boundary band.
 * 3. Blurs a downscaled background copy (1/4 to 1/2 resolution) using GPU [RenderEffect.createBlurEffect]
 *    on API 31+ (or O(1) multi-threaded tiled separable blur on CPU fallback) with strict foreground
 *    exclusion `(1 - alpha)^3` to eliminate subject halos.
 * 4. Composites via GPU [RuntimeShader] (AGSL, API 33+) or multi-threaded tiled CPU strips so
 *    subject pixels remain 100% crisp and untouched while keeping peak memory low.
 * 5. Decodes captured JPEG once (reusing mutable buffers via `inBitmap` pool), recycles temporary
 *    bitmaps immediately, and encodes the final JPEG once.
 */
object PortraitProcessor {

    private const val TAG = "PortraitProcessor"
    private const val ML_KIT_SEGMENTATION_MAX_DIM = 640
    private const val MAX_ANALYSIS_DIMENSION = 256
    private const val MAX_GPU_BOKEH_DIMENSION = 768
    private const val MAX_CPU_BOKEH_DIMENSION = 576
    private const val MAX_GPU_COMPOSITE_DIMENSION = 4096
    private const val MAX_DETECTED_FACES = 5
    private const val MAX_BORDER_PALETTE_CLUSTERS = 8
    private const val MAX_FOREGROUND_SEED_CLUSTERS = 4
    private const val TILE_ROW_HEIGHT = 128
    private const val MAX_POOL_BITMAPS = 2
    const val MIN_SUBJECT_CONFIDENCE = 0.22f

    // Bounded multi-threaded worker pool for parallel tiled blur, compositing, and skin retouch
    private val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val workerThreadId = AtomicInteger(1)
    private val tileWorkerPool = Executors.newFixedThreadPool(workerCount) { r ->
        Thread(r, "PortraitTileWorker-${workerThreadId.getAndIncrement()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    // Background model preloader/warmup executor
    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PortraitModelWarmup").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val segmenterLock = Any()
    @Volatile
    private var cachedSegmenter: SubjectSegmenter? = null
    private val warmupTriggered = AtomicBoolean(false)
    private val warmupCompleted = AtomicBoolean(false)

    // Small bounded inBitmap reuse pool to reduce GC churn across consecutive Portrait shots
    private val bitmapPoolLock = Any()
    private val reusableBitmaps = ArrayDeque<Bitmap>(MAX_POOL_BITMAPS)

    private data class FaceAnchor(
        val centerX: Float,
        val centerY: Float,
        val eyeDistance: Float,
        val confidence: Float
    )

    private data class ColorCluster(
        val meanY: Float,
        val meanCb: Float,
        val meanCr: Float,
        val stdY: Float,
        val stdCb: Float,
        val stdCr: Float,
        val weight: Float = 1.0f
    )

    private data class MultiTierDiscBokeh(
        val nearPixels: IntArray,
        val midPixels: IntArray,
        val farPixels: IntArray,
        val usedGpu: Boolean
    )

    /**
     * Preloads and warms up the ML Kit [SubjectSegmenter] on a background thread when the user
     * enters Portrait mode so the very first shutter press does not stall on model initialization.
     */
    fun warmUpSegmentationModelAsync() {
        if (!warmupTriggered.compareAndSet(false, true)) return
        warmupExecutor.execute {
            val startMs = System.currentTimeMillis()
            var dummyBmp: Bitmap? = null
            try {
                val segmenter = getOrCreateSegmenter() ?: return@execute
                dummyBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                dummyBmp.eraseColor(Color.rgb(128, 128, 128))
                val input = InputImage.fromBitmap(dummyBmp, 0)
                Tasks.await(segmenter.process(input), 4500L, TimeUnit.MILLISECONDS)
                warmupCompleted.set(true)
                Log.i(TAG, "Portrait segmentation model preloaded & warmed up in ${System.currentTimeMillis() - startMs}ms")
            } catch (t: Throwable) {
                Log.d(TAG, "Portrait model warmup skipped (${t.javaClass.simpleName}); fallback segmenter ready")
            } finally {
                if (dummyBmp != null && !dummyBmp.isRecycled) {
                    runCatching { dummyBmp.recycle() }
                }
            }
        }
    }

    fun isSegmentationModelWarmedUp(): Boolean = warmupCompleted.get()

    private fun getOrCreateSegmenter(): SubjectSegmenter? {
        cachedSegmenter?.let { return it }
        synchronized(segmenterLock) {
            cachedSegmenter?.let { return it }
            return try {
                val options = SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask()
                    .build()
                SubjectSegmentation.getClient(options).also { cachedSegmenter = it }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun acquireReusableBitmap(reqWidth: Int, reqHeight: Int): Bitmap? {
        if (reqWidth <= 0 || reqHeight <= 0) return null
        val requiredBytes = reqWidth.toLong() * reqHeight.toLong() * 4L
        synchronized(bitmapPoolLock) {
            val iter = reusableBitmaps.iterator()
            while (iter.hasNext()) {
                val candidate = iter.next()
                if (candidate.isRecycled || !candidate.isMutable || candidate.config != Bitmap.Config.ARGB_8888) {
                    iter.remove()
                    continue
                }
                if (candidate.allocationByteCount.toLong() >= requiredBytes &&
                    candidate.allocationByteCount.toLong() <= requiredBytes * 2L
                ) {
                    iter.remove()
                    return candidate
                }
            }
        }
        return null
    }

    private fun releaseOrRecycleBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (!bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) {
            runCatching { bitmap.recycle() }
            return
        }
        synchronized(bitmapPoolLock) {
            if (reusableBitmaps.size >= MAX_POOL_BITMAPS) {
                val evicted = reusableBitmaps.removeFirst()
                if (!evicted.isRecycled) {
                    runCatching { evicted.recycle() }
                }
            }
            reusableBitmaps.addLast(bitmap)
        }
    }

    /**
     * Decodes a lightweight oriented preview thumbnail (~256 px) directly from raw camera JPEG bytes
     * using `inSampleSize` so the gallery button updates immediately right after shutter press while
     * Portrait processing runs in the background.
     */
    fun decodeFastPreviewThumbnail(
        sourceBytes: ByteArray,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean = false,
        maxDimension: Int = 256
    ): Bitmap? {
        if (sourceBytes.isEmpty()) return null
        var decoded: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
            val rawMax = max(bounds.outWidth, bounds.outHeight)
            if (rawMax <= 0) return null
            var sampleSize = 1
            while ((rawMax / sampleSize) > maxDimension) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }
            decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts) ?: return null
            val oriented = AspectCropUtils.cropAndOrientBitmap(
                sourceBitmap = decoded,
                aspectRatioMode = aspectRatioMode,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = mirrorHorizontally
            )
            if (oriented !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
            oriented
        } catch (_: Throwable) {
            if (decoded != null && !decoded.isRecycled) {
                runCatching { decoded.recycle() }
            }
            null
        }
    }

    /**
     * Processes raw camera JPEG bytes through the complete optimized Portrait pipeline:
     * 1. Decodes the captured JPEG ONCE (using `inSampleSize` only if >20 MP and reusing `inBitmap`),
     *    rotates, crops, and mirrors once.
     * 2. Runs downscaled subject segmentation (512–640 px) + edge-only guided-filter coefficients.
     * 3. Computes downscaled halo-free background bokeh blur on GPU (`RenderEffect`) or multi-threaded CPU.
     * 4. Composites over the sharp full-resolution foreground via GPU (`RuntimeShader`) or multi-threaded
     *    tiled edge-only CPU compositing.
     * 5. Encodes the final JPEG ONCE at `jpegQuality` (default 95/100) and records per-stage timings.
     */
    fun processPortraitJpegBytes(
        sourceBytes: ByteArray,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean = false,
        blurLevel: PortraitBlurLevel = PortraitBlurLevel.MEDIUM,
        blurStrengthOverride: Float? = null,
        retouch: PortraitRetouch = PortraitRetouch.NATURAL,
        focusNormX: Float = 0.5f,
        focusNormY: Float = 0.48f,
        depthSupportPath: PortraitDepthSupportPath = PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY,
        jpegQuality: Int = 95,
        captureElapsedMs: Long = 0L,
        onStageProgress: ((Float, String) -> Unit)? = null
    ): PortraitProcessingOutcome? {
        if (sourceBytes.isEmpty()) return null
        val pipelineStartMs = System.currentTimeMillis()

        val rawStrength = blurStrengthOverride ?: blurLevel.blurStrength
        val effectiveStrength = if (blurLevel == PortraitBlurLevel.OFF && blurStrengthOverride == null) {
            0.0f
        } else {
            PortraitBlurLevel.normalizeBlurStrength(rawStrength)
        }
        val effectiveLevel = PortraitBlurLevel.fromBlurStrength(effectiveStrength)
        val safeQuality = jpegQuality.coerceIn(85, 100)

        // Fast path: if user turned Blur OFF and Retouch OFF, zero segmentation or blur needed!
        if (effectiveStrength <= 0.0f && retouch == PortraitRetouch.OFF) {
            val fastStart = System.currentTimeMillis()
            val bypassPayload = AspectCropUtils.processCapturedJpegBytes(
                sourceBytes = sourceBytes,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                jpegQuality = safeQuality,
                allowExifOrientationBypass = true
            ) ?: return null
            val elapsed = (System.currentTimeMillis() - fastStart).coerceAtLeast(1L)
            return PortraitProcessingOutcome.ProcessedPortrait(
                jpegBytes = bypassPayload.jpegBytes,
                width = bypassPayload.width,
                height = bypassPayload.height,
                exifOrientation = bypassPayload.exifOrientation,
                subjectType = DetectedPortraitSubjectType.GENERAL_SUBJECT,
                subjectConfidence = 1.0f,
                detectedFaceCount = 0,
                appliedBlurLevel = PortraitBlurLevel.OFF,
                appliedBlurStrength = 0.0f,
                simulatedApertureLabel = "Off",
                appliedBlurRadiusPx = 0,
                appliedRetouch = PortraitRetouch.OFF,
                depthSupportPath = depthSupportPath,
                isHardwareDepthDataUsed = false,
                processingDurationMs = elapsed,
                stageTimings = PortraitStageTimings(
                    captureMs = captureElapsedMs,
                    decodeMs = 0L,
                    segmentationMs = 0L,
                    blurMs = 0L,
                    compositingMs = 0L,
                    encodeMs = if (bypassPayload.wasRecompressed) elapsed else 0L,
                    totalMs = captureElapsedMs + elapsed,
                    backend = "Bypass-BlurOff",
                    width = bypassPayload.width,
                    height = bypassPayload.height,
                    subjectType = "BlurOff"
                )
            )
        }

        // Stage 1: Single JPEG Decode + Orientation/Aspect Crop (with inBitmap reuse & safe sample size)
        onStageProgress?.invoke(0.12f, "Decoding…")
        val decodeStartMs = System.currentTimeMillis()
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, boundsOpts)
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        // Keep full resolution up to ~20 MP; only subsample ultra-high-res (>20 MP) to avoid OOM
        val sampleSize = if (rawW.toLong() * rawH.toLong() > 20_000_000L) 2 else 1
        val expectedW = (rawW / sampleSize).coerceAtLeast(1)
        val expectedH = (rawH / sampleSize).coerceAtLeast(1)
        val pooledBitmap = acquireReusableBitmap(expectedW, expectedH)

        var rawDecoded: Bitmap? = null
        try {
            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                inScaled = false
                inSampleSize = sampleSize
                if (pooledBitmap != null) {
                    inBitmap = pooledBitmap
                }
            }
            rawDecoded = try {
                BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOpts)
            } catch (_: IllegalArgumentException) {
                // Retry cleanly without inBitmap if platform rejected the candidate buffer
                releaseOrRecycleBitmap(pooledBitmap)
                val retryOpts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                    inScaled = false
                    inSampleSize = sampleSize
                }
                BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, retryOpts)
            }
        } catch (t: Throwable) {
            releaseOrRecycleBitmap(pooledBitmap)
            Log.e(TAG, "Portrait JPEG decode failed: ${t.localizedMessage}", t)
            return null
        }

        if (rawDecoded == null) {
            releaseOrRecycleBitmap(pooledBitmap)
            return null
        }

        var orientedCropped: Bitmap? = null
        return try {
            orientedCropped = AspectCropUtils.cropAndOrientBitmap(
                sourceBitmap = rawDecoded,
                aspectRatioMode = aspectRatioMode,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = mirrorHorizontally
            )
            if (orientedCropped !== rawDecoded) {
                releaseOrRecycleBitmap(rawDecoded)
                rawDecoded = null
            }
            val decodeMs = (System.currentTimeMillis() - decodeStartMs).coerceAtLeast(0L)

            // Attempt genuine hardware depth map extraction ONLY when hardware depth output is supported
            val extractedDepthMap = if (depthSupportPath == PortraitDepthSupportPath.HARDWARE_DEPTH_SENSOR_ASSISTED) {
                extractEmbeddedDepthGridFromBytes(
                    sourceBytes = sourceBytes,
                    rotationDegrees = rotationDegrees,
                    aspectRatioMode = aspectRatioMode,
                    mirrorHorizontally = mirrorHorizontally,
                    targetW = MAX_ANALYSIS_DIMENSION,
                    targetH = MAX_ANALYSIS_DIMENSION
                )
            } else null

            val bitmapResult = applyPortraitEffectToBitmap(
                sourceBitmap = orientedCropped,
                blurLevel = effectiveLevel,
                blurStrengthOverride = effectiveStrength,
                retouch = retouch,
                focusNormX = focusNormX,
                focusNormY = focusNormY,
                hardwareDepthNormalized = extractedDepthMap?.first,
                hardwareDepthW = extractedDepthMap?.second ?: 0,
                hardwareDepthH = extractedDepthMap?.third ?: 0,
                onStageProgress = onStageProgress
            )

            // Stage 5: Single JPEG Encode (no redundant baseline re-encode!)
            onStageProgress?.invoke(0.86f, "Encoding…")
            val encodeStartMs = System.currentTimeMillis()

            when (bitmapResult) {
                is PortraitBitmapResult.Success -> {
                    try {
                        val outStream = ByteArrayOutputStream(sourceBytes.size.coerceIn(65536, 4 * 1024 * 1024))
                        val ok = bitmapResult.outputBitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, outStream)
                        val encodeMs = (System.currentTimeMillis() - encodeStartMs).coerceAtLeast(0L)
                        val outBytes = if (ok) outStream.toByteArray() else null
                        if (outBytes == null) {
                            encodeFallbackBitmap(
                                fallbackBitmap = orientedCropped,
                                safeQuality = safeQuality,
                                reason = "JPEG encoding failed; saved original photo.",
                                captureMs = captureElapsedMs,
                                decodeMs = decodeMs,
                                baseTimings = bitmapResult.stageTimings
                            )
                        } else {
                            val totalProcessingMs = (System.currentTimeMillis() - pipelineStartMs).coerceAtLeast(1L)
                            val mergedTimings = (bitmapResult.stageTimings ?: PortraitStageTimings()).copy(
                                captureMs = captureElapsedMs,
                                decodeMs = decodeMs,
                                encodeMs = encodeMs,
                                totalMs = captureElapsedMs + totalProcessingMs,
                                width = bitmapResult.outputBitmap.width,
                                height = bitmapResult.outputBitmap.height,
                                subjectType = bitmapResult.subjectType.name,
                                wasFallback = false
                            )
                            PortraitProcessingOutcome.ProcessedPortrait(
                                jpegBytes = outBytes,
                                width = bitmapResult.outputBitmap.width,
                                height = bitmapResult.outputBitmap.height,
                                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                                subjectType = bitmapResult.subjectType,
                                subjectConfidence = bitmapResult.subjectConfidence,
                                detectedFaceCount = bitmapResult.detectedFaceCount,
                                appliedBlurLevel = bitmapResult.appliedBlurLevel,
                                appliedBlurStrength = bitmapResult.appliedBlurStrength,
                                simulatedApertureLabel = bitmapResult.simulatedApertureLabel,
                                appliedBlurRadiusPx = bitmapResult.appliedBlurRadiusPx,
                                appliedRetouch = bitmapResult.appliedRetouch,
                                depthSupportPath = if (bitmapResult.isHardwareDepthDataUsed) {
                                    PortraitDepthSupportPath.HARDWARE_DEPTH_SENSOR_ASSISTED
                                } else if (depthSupportPath == PortraitDepthSupportPath.HARDWARE_EXTENDED_BOKEH) {
                                    PortraitDepthSupportPath.HARDWARE_EXTENDED_BOKEH
                                } else {
                                    PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY
                                },
                                isHardwareDepthDataUsed = bitmapResult.isHardwareDepthDataUsed,
                                processingDurationMs = totalProcessingMs,
                                stageTimings = mergedTimings
                            )
                        }
                    } finally {
                        if (bitmapResult.outputBitmap !== orientedCropped && !bitmapResult.outputBitmap.isRecycled) {
                            releaseOrRecycleBitmap(bitmapResult.outputBitmap)
                        }
                    }
                }
                is PortraitBitmapResult.FallbackOriginal -> {
                    val outStream = ByteArrayOutputStream(sourceBytes.size.coerceIn(65536, 4 * 1024 * 1024))
                    val ok = orientedCropped.compress(Bitmap.CompressFormat.JPEG, safeQuality, outStream)
                    val encodeMs = (System.currentTimeMillis() - encodeStartMs).coerceAtLeast(0L)
                    val fallbackBytes = if (ok) outStream.toByteArray() else sourceBytes
                    val totalProcessingMs = (System.currentTimeMillis() - pipelineStartMs).coerceAtLeast(1L)
                    val mergedTimings = (bitmapResult.stageTimings ?: PortraitStageTimings()).copy(
                        captureMs = captureElapsedMs,
                        decodeMs = decodeMs,
                        encodeMs = encodeMs,
                        totalMs = captureElapsedMs + totalProcessingMs,
                        width = orientedCropped.width,
                        height = orientedCropped.height,
                        subjectType = DetectedPortraitSubjectType.NONE.name,
                        wasFallback = effectiveStrength > 0.0f
                    )
                    if (effectiveStrength <= 0.0f) {
                        PortraitProcessingOutcome.ProcessedPortrait(
                            jpegBytes = fallbackBytes,
                            width = orientedCropped.width,
                            height = orientedCropped.height,
                            exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                            subjectType = DetectedPortraitSubjectType.NONE,
                            subjectConfidence = 0.0f,
                            detectedFaceCount = 0,
                            appliedBlurLevel = PortraitBlurLevel.OFF,
                            appliedBlurStrength = 0.0f,
                            simulatedApertureLabel = "Off",
                            appliedBlurRadiusPx = 0,
                            appliedRetouch = retouch,
                            depthSupportPath = PortraitDepthSupportPath.ON_DEVICE_SEGMENTATION_ONLY,
                            isHardwareDepthDataUsed = false,
                            processingDurationMs = totalProcessingMs,
                            stageTimings = mergedTimings
                        )
                    } else {
                        PortraitProcessingOutcome.FallbackOriginal(
                            jpegBytes = fallbackBytes,
                            width = orientedCropped.width,
                            height = orientedCropped.height,
                            exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                            reason = bitmapResult.reason,
                            stageTimings = mergedTimings
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Portrait processing threw ${t.javaClass.simpleName}: ${t.localizedMessage}", t)
            val fallbackBitmap = orientedCropped ?: rawDecoded
            if (fallbackBitmap != null && !fallbackBitmap.isRecycled) {
                encodeFallbackBitmap(
                    fallbackBitmap = fallbackBitmap,
                    safeQuality = safeQuality,
                    reason = "Portrait processing error (${t.javaClass.simpleName}); saved original photo.",
                    captureMs = captureElapsedMs,
                    decodeMs = 0L,
                    baseTimings = null
                )
            } else {
                null
            }
        } finally {
            if (orientedCropped != null && orientedCropped !== rawDecoded) {
                releaseOrRecycleBitmap(orientedCropped)
            }
            releaseOrRecycleBitmap(rawDecoded)
        }
    }

    private fun encodeFallbackBitmap(
        fallbackBitmap: Bitmap,
        safeQuality: Int,
        reason: String,
        captureMs: Long,
        decodeMs: Long,
        baseTimings: PortraitStageTimings?
    ): PortraitProcessingOutcome.FallbackOriginal? {
        val encStart = System.currentTimeMillis()
        val out = ByteArrayOutputStream(65536)
        if (!fallbackBitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, out)) {
            return null
        }
        val encMs = (System.currentTimeMillis() - encStart).coerceAtLeast(0L)
        val timings = (baseTimings ?: PortraitStageTimings()).copy(
            captureMs = captureMs,
            decodeMs = decodeMs,
            encodeMs = encMs,
            totalMs = captureMs + decodeMs + (baseTimings?.segmentationMs ?: 0L) +
                (baseTimings?.blurMs ?: 0L) + (baseTimings?.compositingMs ?: 0L) + encMs,
            width = fallbackBitmap.width,
            height = fallbackBitmap.height,
            wasFallback = true
        )
        return PortraitProcessingOutcome.FallbackOriginal(
            jpegBytes = out.toByteArray(),
            width = fallbackBitmap.width,
            height = fallbackBitmap.height,
            exifOrientation = ExifInterface.ORIENTATION_NORMAL,
            reason = reason,
            stageTimings = timings
        )
    }

    /**
     * Segments the foreground subject on a downscaled copy and applies halo-free background blur
     * and optional portrait retouching to [sourceBitmap]. Never modifies [sourceBitmap] in place.
     */
    fun applyPortraitEffectToBitmap(
        sourceBitmap: Bitmap,
        blurLevel: PortraitBlurLevel = PortraitBlurLevel.MEDIUM,
        blurStrengthOverride: Float? = null,
        retouch: PortraitRetouch = PortraitRetouch.NATURAL,
        focusNormX: Float = 0.5f,
        focusNormY: Float = 0.48f,
        hardwareDepthNormalized: FloatArray? = null,
        hardwareDepthW: Int = 0,
        hardwareDepthH: Int = 0,
        onStageProgress: ((Float, String) -> Unit)? = null
    ): PortraitBitmapResult {
        val startMs = System.currentTimeMillis()
        if (sourceBitmap.isRecycled || sourceBitmap.width < 4 || sourceBitmap.height < 4) {
            return PortraitBitmapResult.FallbackOriginal(sourceBitmap, "Invalid or too-small source bitmap.")
        }

        val rawStrength = blurStrengthOverride ?: blurLevel.blurStrength
        val effectiveStrength = if (blurLevel == PortraitBlurLevel.OFF && blurStrengthOverride == null) {
            0.0f
        } else {
            PortraitBlurLevel.normalizeBlurStrength(rawStrength)
        }
        val effectiveLevel = PortraitBlurLevel.fromBlurStrength(effectiveStrength)
        val apertureLabel = PortraitBlurLevel.simulatedApertureLabelForStrength(effectiveStrength)

        return try {
            // Stage 2: Downscaled Segmentation + Guided-Filter Coefficients
            onStageProgress?.invoke(0.28f, "Segmenting…")
            val segStartMs = System.currentTimeMillis()
            val mask = segmentSubject(
                sourceBitmap = sourceBitmap,
                focusNormX = focusNormX.coerceIn(0.1f, 0.9f),
                focusNormY = focusNormY.coerceIn(0.1f, 0.9f),
                hardwareDepthNormalized = hardwareDepthNormalized,
                hardwareDepthW = hardwareDepthW,
                hardwareDepthH = hardwareDepthH
            )
            val segmentationMs = (System.currentTimeMillis() - segStartMs).coerceAtLeast(0L)
            val segSizeLabel = "${mask.segmentationInputWidth}x${mask.segmentationInputHeight}"

            val fullW = sourceBitmap.width
            val fullH = sourceBitmap.height

            // When Blur is OFF, always return an unblurred image (applying optional skin retouch if subject is present)
            if (effectiveStrength <= 0.0f) {
                val compStartMs = System.currentTimeMillis()
                val unblurredOut = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (retouch != PortraitRetouch.OFF &&
                    mask.subjectType != DetectedPortraitSubjectType.NONE &&
                    mask.confidence >= MIN_SUBJECT_CONFIDENCE
                ) {
                    applySubtleSkinRetouchTiled(
                        bitmap = unblurredOut,
                        mask = mask,
                        retouch = retouch
                    )
                }
                val compMs = (System.currentTimeMillis() - compStartMs).coerceAtLeast(0L)
                return PortraitBitmapResult.Success(
                    outputBitmap = unblurredOut,
                    subjectType = mask.subjectType,
                    subjectConfidence = mask.confidence,
                    detectedFaceCount = mask.detectedFaceCount,
                    appliedBlurLevel = PortraitBlurLevel.OFF,
                    appliedBlurStrength = 0.0f,
                    simulatedApertureLabel = "Off",
                    appliedBlurRadiusPx = 0,
                    appliedRetouch = retouch,
                    isHardwareDepthDataUsed = mask.isHardwareDepthDataUsed,
                    processingDurationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                    stageTimings = PortraitStageTimings(
                        segmentationMs = segmentationMs,
                        blurMs = 0L,
                        compositingMs = compMs,
                        totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                        backend = "BlurOff",
                        width = fullW,
                        height = fullH,
                        segmentationSizeLabel = segSizeLabel,
                        subjectType = mask.subjectType.name
                    )
                )
            }

            if (mask.subjectType == DetectedPortraitSubjectType.NONE || mask.confidence < MIN_SUBJECT_CONFIDENCE) {
                return PortraitBitmapResult.FallbackOriginal(
                    originalBitmap = sourceBitmap,
                    reason = "No subject found — saved original photo.",
                    stageTimings = PortraitStageTimings(
                        segmentationMs = segmentationMs,
                        totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                        backend = "Fallback-NoSubject",
                        width = fullW,
                        height = fullH,
                        segmentationSizeLabel = segSizeLabel,
                        subjectType = DetectedPortraitSubjectType.NONE.name,
                        wasFallback = true
                    )
                )
            }

            // Stage 3: Smart Downscaled Background Blur (1/4 to 1/2 resolution, never full-res!)
            onStageProgress?.invoke(0.50f, "Blurring background…")
            val blurStartMs = System.currentTimeMillis()
            val maxDim = max(fullW, fullH)
            val preferGpuBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && maxDim >= 256
            val maxBokehCap = if (preferGpuBlur) MAX_GPU_BOKEH_DIMENSION else MAX_CPU_BOKEH_DIMENSION
            val bokehScale = when {
                maxDim >= 2048 -> min(0.25f, maxBokehCap.toFloat() / maxDim.toFloat())
                maxDim >= 1024 -> min(0.375f, maxBokehCap.toFloat() / maxDim.toFloat())
                maxDim >= 480 -> 0.50f
                else -> 1.0f
            }
            val bokehW = (fullW * bokehScale).roundToInt().coerceAtLeast(4)
            val bokehH = (fullH * bokehScale).roundToInt().coerceAtLeast(4)
            val blurSizeLabel = "${bokehW}x${bokehH}"

            val maxSupported = PortraitBlurDeviceSupport.getMaxSupportedBlurStrength()
            val clampedStrength = effectiveStrength.coerceIn(0.0f, maxSupported)

            val maxRadius = (min(bokehW, bokehH) * 0.085f).coerceIn(14f, 34f)
            val minRadius = 2.5f
            val norm = if (clampedStrength <= 1.0f) {
                (0.12f * clampedStrength).coerceIn(0.05f, 0.12f)
            } else {
                (0.12f + 0.88f * ((clampedStrength - 1.0f) / 15.0f).coerceIn(0f, 1f))
            }
            val bokehRadiusPx = (minRadius + (maxRadius - minRadius) * norm).roundToInt().coerceAtLeast(2)
            val fullEquivalentRadiusPx = (bokehRadiusPx / bokehScale).roundToInt().coerceAtLeast(bokehRadiusPx)

            val distanceDepthMap = computeDistanceFromSubjectMask(
                alphaMatte = mask.alphaMatte,
                width = mask.width,
                height = mask.height,
                hardwareDepthNormalized = hardwareDepthNormalized,
                hardwareDepthW = hardwareDepthW,
                hardwareDepthH = hardwareDepthH
            )

            val bokehTiers = try {
                computeMultiTierDiscBokehBackground(
                    sourceBitmap = sourceBitmap,
                    mask = mask,
                    bokehW = bokehW,
                    bokehH = bokehH,
                    maxBlurRadius = bokehRadiusPx,
                    blurStrength = clampedStrength,
                    allowGpu = preferGpuBlur
                )
            } catch (oom: OutOfMemoryError) {
                System.gc()
                return PortraitBitmapResult.FallbackOriginal(
                    originalBitmap = sourceBitmap,
                    reason = "Low memory condition during background blur; safely preserved original photo."
                )
            } catch (e: Throwable) {
                return PortraitBitmapResult.FallbackOriginal(
                    originalBitmap = sourceBitmap,
                    reason = "Processing failure during background blur (${e.message ?: "unknown"}); safely preserved original photo."
                )
            }

            // Combine near/mid/far tiers into a SINGLE depth-graduated background buffer at bokeh resolution
            // so full-res compositing only samples ONE background image instead of three!
            val depthBlendedBgPixels = blendBokehTiersByDepthParallel(
                tiers = bokehTiers,
                distanceDepthMap = distanceDepthMap,
                maskW = mask.width,
                maskH = mask.height,
                bokehW = bokehW,
                bokehH = bokehH
            )
            val blurMs = (System.currentTimeMillis() - blurStartMs).coerceAtLeast(0L)

            // Stage 4: Full-Resolution Compositing (GPU RuntimeShader on API 33+ or Multi-Threaded Tiled Edge-Only CPU)
            onStageProgress?.invoke(0.70f, "Compositing…")
            val compStartMs = System.currentTimeMillis()
            var usedGpuComposite = false
            val outputBitmap: Bitmap = run {
                val gpuComposited = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    retouch == PortraitRetouch.OFF &&
                    max(fullW, fullH) <= MAX_GPU_COMPOSITE_DIMENSION
                ) {
                    tryCompositePortraitOnGpuApi33(
                        sourceBitmap = sourceBitmap,
                        depthBlendedBgPixels = depthBlendedBgPixels,
                        bokehW = bokehW,
                        bokehH = bokehH,
                        mask = mask
                    )
                } else {
                    null
                }
                if (gpuComposited != null) {
                    usedGpuComposite = true
                    gpuComposited
                } else {
                    compositePortraitMultiThreadedTiled(
                        sourceBitmap = sourceBitmap,
                        depthBlendedBgPixels = depthBlendedBgPixels,
                        bokehW = bokehW,
                        bokehH = bokehH,
                        mask = mask,
                        retouch = retouch
                    )
                }
            }
            val compositingMs = (System.currentTimeMillis() - compStartMs).coerceAtLeast(0L)

            val backendLabel = when {
                bokehTiers.usedGpu && usedGpuComposite -> "GPU-RenderEffect+AGSL"
                bokehTiers.usedGpu -> "GPU-RenderEffect+CPU-Tiles(${workerCount}T)"
                usedGpuComposite -> "CPU-Tiles(${workerCount}T)+AGSL"
                else -> "CPU-MultiThread(${workerCount}T)"
            }
            val totalDurationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

            PortraitBitmapResult.Success(
                outputBitmap = outputBitmap,
                subjectType = mask.subjectType,
                subjectConfidence = mask.confidence,
                detectedFaceCount = mask.detectedFaceCount,
                appliedBlurLevel = effectiveLevel,
                appliedBlurStrength = effectiveStrength,
                simulatedApertureLabel = apertureLabel,
                appliedBlurRadiusPx = fullEquivalentRadiusPx,
                appliedRetouch = retouch,
                isHardwareDepthDataUsed = mask.isHardwareDepthDataUsed,
                processingDurationMs = totalDurationMs,
                stageTimings = PortraitStageTimings(
                    segmentationMs = segmentationMs,
                    blurMs = blurMs,
                    compositingMs = compositingMs,
                    totalMs = totalDurationMs,
                    backend = backendLabel,
                    width = fullW,
                    height = fullH,
                    segmentationSizeLabel = segSizeLabel,
                    blurSizeLabel = blurSizeLabel,
                    subjectType = mask.subjectType.name
                )
            )
        } catch (t: Throwable) {
            PortraitBitmapResult.FallbackOriginal(
                originalBitmap = sourceBitmap,
                reason = "Portrait effect failed (${t.localizedMessage ?: t.javaClass.simpleName}); preserved original."
            )
        }
    }

    /**
     * Performs on-device subject detection and segmentation on a downscaled copy of [sourceBitmap],
     * returning a [SubjectSegmentationMask] with edge-aligned Guided Filter coefficients.
     */
    fun segmentSubject(
        sourceBitmap: Bitmap,
        focusNormX: Float = 0.5f,
        focusNormY: Float = 0.48f,
        hardwareDepthNormalized: FloatArray? = null,
        hardwareDepthW: Int = 0,
        hardwareDepthH: Int = 0
    ): SubjectSegmentationMask {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        val maxDim = max(srcW, srcH)
        val scale = if (maxDim > MAX_ANALYSIS_DIMENSION) {
            MAX_ANALYSIS_DIMENSION.toFloat() / maxDim.toFloat()
        } else {
            1.0f
        }
        // Ensure even width for Android FaceDetector compatibility
        var workW = (srcW * scale).roundToInt().coerceAtLeast(8)
        if (workW % 2 != 0) workW = (workW - 1).coerceAtLeast(8)
        val workH = (srcH * scale).roundToInt().coerceAtLeast(8)

        val workPixels = resampleBitmapPixels(sourceBitmap, workW, workH)
        val n = workW * workH
        val luma = FloatArray(n)
        for (i in 0 until n) {
            val c = workPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        // 1. Primary path: Cached, warmed-up ML Kit Subject Segmentation on a downscaled copy (512–640 px)
        val mlKitResult = trySegmentWithMlKit(sourceBitmap, workW, workH)
        if (mlKitResult != null) {
            val (mlKitMask, mlInW, mlInH) = mlKitResult
            val cleaned = cleanupCoarseMask(mlKitMask, workW, workH)
            val (coeffA, coeffB, refinedAlpha) = computeEdgeOnlyGuidedFilterMatte(
                guidanceLuma = luma,
                coarseMask = cleaned,
                width = workW,
                height = workH,
                radius = max(3, min(workW, workH) / 36),
                eps = 0.0020f
            )
            var fgCount = 0
            var minX = workW
            var maxX = 0
            var minY = workH
            var maxY = 0
            for (y in 0 until workH) {
                val row = y * workW
                for (x in 0 until workW) {
                    if (refinedAlpha[row + x] >= 0.45f) {
                        fgCount++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            val coverage = fgCount.toFloat() / n.toFloat()
            if (coverage in 0.015f..0.92f) {
                val bounds = if (maxX >= minX && maxY >= minY) {
                    RectF(
                        minX.toFloat() / workW,
                        minY.toFloat() / workH,
                        (maxX + 1).toFloat() / workW,
                        (maxY + 1).toFloat() / workH
                    )
                } else null
                return SubjectSegmentationMask(
                    width = workW,
                    height = workH,
                    alphaMatte = refinedAlpha,
                    guideCoeffA = coeffA,
                    guideCoeffB = coeffB,
                    subjectType = DetectedPortraitSubjectType.PERSON,
                    confidence = 0.92f,
                    subjectCoverageRatio = coverage,
                    detectedFaceCount = 0,
                    subjectBoundsNormalized = bounds,
                    isHardwareDepthDataUsed = false,
                    segmentationInputWidth = mlInW,
                    segmentationInputHeight = mlInH
                )
            }
        }

        // 2. Fallback path: Fast on-device YCbCr + FaceDetector + border-palette segmenter (runs at 256px)
        val cbArr = FloatArray(n)
        val crArr = FloatArray(n)
        for (i in 0 until n) {
            val c = workPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            cbArr[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            crArr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        val borderClusters = buildBorderBackgroundClusters(workW, workH, luma, cbArr, crArr)

        val faceAnchors = detectFacesAndBiometricClusters(
            workW = workW,
            workH = workH,
            workPixels = workPixels,
            luma = luma,
            cbArr = cbArr,
            crArr = crArr,
            borderClusters = borderClusters
        )

        val personMask = FloatArray(n)
        if (faceAnchors.isNotEmpty()) {
            computePersonProbabilityMask(
                workW = workW,
                workH = workH,
                luma = luma,
                cbArr = cbArr,
                crArr = crArr,
                faceAnchors = faceAnchors,
                borderClusters = borderClusters,
                outPersonProb = personMask
            )
        }

        val salientMask = FloatArray(n)
        val salientContrastScore = computeSalientSubjectProbabilityMask(
            workW = workW,
            workH = workH,
            luma = luma,
            cbArr = cbArr,
            crArr = crArr,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            borderClusters = borderClusters,
            outSalientProb = salientMask
        )

        val hasHardwareDepth = hardwareDepthNormalized != null &&
            hardwareDepthW >= 4 &&
            hardwareDepthH >= 4 &&
            hardwareDepthNormalized.size >= hardwareDepthW * hardwareDepthH

        val coarseMask = FloatArray(n)
        val hasStrongFaceAnchor = faceAnchors.isNotEmpty()
        for (y in 0 until workH) {
            val row = y * workW
            val dy = if (hasHardwareDepth) {
                ((y + 0.5f) * hardwareDepthH / workH.toFloat() - 0.5f).coerceIn(0f, (hardwareDepthH - 1).toFloat())
            } else 0f
            for (x in 0 until workW) {
                val idx = row + x
                val segProb = if (hasStrongFaceAnchor) {
                    max(personMask[idx], salientMask[idx] * 0.72f)
                } else {
                    salientMask[idx]
                }
                coarseMask[idx] = if (hasHardwareDepth && hardwareDepthNormalized != null) {
                    val dx = ((x + 0.5f) * hardwareDepthW / workW.toFloat() - 0.5f).coerceIn(0f, (hardwareDepthW - 1).toFloat())
                    val depthCloseness = sampleBilinear(hardwareDepthNormalized, hardwareDepthW, hardwareDepthH, dx, dy)
                    (0.58f * segProb + 0.42f * depthCloseness).coerceIn(0f, 1f)
                } else {
                    segProb.coerceIn(0f, 1f)
                }
            }
        }

        val cleanedCoarseMask = cleanupCoarseMask(coarseMask, workW, workH)
        val (coeffA, coeffB, refinedAlpha) = computeEdgeOnlyGuidedFilterMatte(
            guidanceLuma = luma,
            coarseMask = cleanedCoarseMask,
            width = workW,
            height = workH,
            radius = max(3, min(workW, workH) / 36),
            eps = 0.0020f
        )

        var fgCount = 0
        var alphaSumFg = 0f
        var minX = workW
        var maxX = 0
        var minY = workH
        var maxY = 0
        for (y in 0 until workH) {
            val row = y * workW
            for (x in 0 until workW) {
                val a = refinedAlpha[row + x]
                if (a >= 0.45f) {
                    fgCount++
                    alphaSumFg += a
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val coverage = fgCount.toFloat() / n.toFloat()
        val meanFgAlpha = if (fgCount > 0) alphaSumFg / fgCount else 0f

        val subjectType: DetectedPortraitSubjectType
        val confidence: Float
        if (faceAnchors.isNotEmpty() && coverage in 0.015f..0.90f) {
            subjectType = DetectedPortraitSubjectType.PERSON
            val faceConf = faceAnchors.maxOf { it.confidence }
            confidence = (0.55f * faceConf + 0.45f * meanFgAlpha).coerceIn(0f, 1f)
        } else if (salientContrastScore >= 0.14f && coverage in 0.025f..0.85f) {
            subjectType = DetectedPortraitSubjectType.GENERAL_SUBJECT
            confidence = (0.55f * salientContrastScore + 0.45f * meanFgAlpha).coerceIn(0f, 1f)
        } else if (hasHardwareDepth && coverage in 0.03f..0.85f) {
            subjectType = DetectedPortraitSubjectType.GENERAL_SUBJECT
            confidence = (0.45f + 0.50f * meanFgAlpha).coerceIn(0f, 1f)
        } else {
            subjectType = DetectedPortraitSubjectType.NONE
            confidence = 0f
        }

        val normalizedBounds = if (fgCount > 0 && maxX >= minX && maxY >= minY) {
            RectF(
                minX.toFloat() / workW,
                minY.toFloat() / workH,
                (maxX + 1).toFloat() / workW,
                (maxY + 1).toFloat() / workH
            )
        } else {
            null
        }

        return SubjectSegmentationMask(
            width = workW,
            height = workH,
            alphaMatte = refinedAlpha,
            guideCoeffA = coeffA,
            guideCoeffB = coeffB,
            subjectType = subjectType,
            confidence = confidence,
            subjectCoverageRatio = coverage,
            detectedFaceCount = faceAnchors.size,
            subjectBoundsNormalized = normalizedBounds,
            isHardwareDepthDataUsed = hasHardwareDepth,
            segmentationInputWidth = workW,
            segmentationInputHeight = workH
        )
    }

    /**
     * Runs the cached, pre-warmed ML Kit [SubjectSegmenter] on a downscaled copy (longest side
     * capped at [ML_KIT_SEGMENTATION_MAX_DIM] = 640 px, never full resolution) and resamples the
     * soft confidence mask bilinearly to [targetW] x [targetH].
     */
    private fun trySegmentWithMlKit(
        sourceBitmap: Bitmap,
        targetW: Int,
        targetH: Int
    ): Triple<FloatArray, Int, Int>? {
        val segmenter = getOrCreateSegmenter() ?: return null
        var scaledInput: Bitmap? = null
        return try {
            val srcW = sourceBitmap.width
            val srcH = sourceBitmap.height
            val maxDim = max(srcW, srcH)
            val mlBitmap = if (maxDim > ML_KIT_SEGMENTATION_MAX_DIM) {
                val scale = ML_KIT_SEGMENTATION_MAX_DIM.toFloat() / maxDim.toFloat()
                val dstW = (srcW * scale).roundToInt().coerceAtLeast(32)
                val dstH = (srcH * scale).roundToInt().coerceAtLeast(32)
                scaledInput = Bitmap.createScaledBitmap(sourceBitmap, dstW, dstH, true)
                scaledInput!!
            } else {
                sourceBitmap
            }

            val inputImage = InputImage.fromBitmap(mlBitmap, 0)
            val result = Tasks.await(segmenter.process(inputImage), 3000L, TimeUnit.MILLISECONDS)
            val maskBuffer = result?.foregroundConfidenceMask ?: return null
            maskBuffer.rewind()
            val mlW = mlBitmap.width
            val mlH = mlBitmap.height
            val expectedCount = mlW * mlH
            if (maskBuffer.remaining() < expectedCount) return null

            val rawMask = FloatArray(expectedCount)
            maskBuffer.get(rawMask)

            val outMask = FloatArray(targetW * targetH)
            for (y in 0 until targetH) {
                val sy = ((y + 0.5f) * mlH / targetH.toFloat() - 0.5f).coerceIn(0f, (mlH - 1).toFloat())
                val dstRow = y * targetW
                for (x in 0 until targetW) {
                    val sx = ((x + 0.5f) * mlW / targetW.toFloat() - 0.5f).coerceIn(0f, (mlW - 1).toFloat())
                    outMask[dstRow + x] = sampleBilinear(rawMask, mlW, mlH, sx, sy).coerceIn(0f, 1f)
                }
            }
            Triple(outMask, mlW, mlH)
        } catch (t: Throwable) {
            Log.d(TAG, "ML Kit Subject Segmentation unavailable (${t.javaClass.simpleName}); using on-device segmenter")
            null
        } finally {
            if (scaledInput != null && scaledInput !== sourceBitmap && !scaledInput!!.isRecycled) {
                runCatching { scaledInput!!.recycle() }
            }
        }
    }

    /**
     * Downscales [sourceBitmap] directly to [dstW] x [dstH] using native Skia bilinear filtering
     * via `Bitmap.createScaledBitmap` instead of allocating a full-resolution `IntArray` on the heap,
     * falling back to direct sampling if scaled bitmap pixels are unavailable in JVM tests.
     */
    internal fun resampleBitmapPixels(sourceBitmap: Bitmap, dstW: Int, dstH: Int): IntArray {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        if (srcW == dstW && srcH == dstH) {
            val samePixels = IntArray(srcW * srcH)
            sourceBitmap.getPixels(samePixels, 0, srcW, 0, 0, srcW, srcH)
            return samePixels
        }

        // Native Skia Canvas/createScaledBitmap downscale avoids allocating a 48 MB full-res IntArray!
        var scaledBmp: Bitmap? = null
        try {
            scaledBmp = Bitmap.createScaledBitmap(sourceBitmap, dstW, dstH, true)
            if (scaledBmp != null && scaledBmp !== sourceBitmap &&
                scaledBmp.width == dstW && scaledBmp.height == dstH
            ) {
                val dstPixels = IntArray(dstW * dstH)
                scaledBmp.getPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
                // Guard against stub JVM Robolectric returning all-zero pixels on scaled bitmaps
                var hasNonZero = false
                val step = max(1, dstPixels.size / 64)
                var i = 0
                while (i < dstPixels.size) {
                    if ((dstPixels[i] and 0x00FFFFFF) != 0) {
                        hasNonZero = true
                        break
                    }
                    i += step
                }
                if (hasNonZero) {
                    return dstPixels
                }
            }
        } catch (_: Throwable) {
            // Fall through to direct row-by-row bilinear fallback
        } finally {
            if (scaledBmp != null && scaledBmp !== sourceBitmap && !scaledBmp.isRecycled) {
                runCatching { scaledBmp.recycle() }
            }
        }

        // Memory-safe row-by-row bilinear fallback (allocates only 2 source rows, never full srcW*srcH!)
        val dstPixels = IntArray(dstW * dstH)
        val rowBuf0 = IntArray(srcW)
        val rowBuf1 = IntArray(srcW)
        var cachedY0 = -1
        var cachedY1 = -1

        for (dy in 0 until dstH) {
            val sy = ((dy + 0.5f) * srcH / dstH.toFloat() - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            if (y0 != cachedY0) {
                sourceBitmap.getPixels(rowBuf0, 0, srcW, 0, y0, srcW, 1)
                cachedY0 = y0
            }
            if (y1 != cachedY1) {
                if (y1 == y0) {
                    System.arraycopy(rowBuf0, 0, rowBuf1, 0, srcW)
                } else {
                    sourceBitmap.getPixels(rowBuf1, 0, srcW, 0, y1, srcW, 1)
                }
                cachedY1 = y1
            }
            val dstRow = dy * dstW
            for (dx in 0 until dstW) {
                val sx = ((dx + 0.5f) * srcW / dstW.toFloat() - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - x0

                val c00 = rowBuf0[x0]
                val c10 = rowBuf0[x1]
                val c01 = rowBuf1[x0]
                val c11 = rowBuf1[x1]

                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy

                val r = (((c00 shr 16) and 0xFF) * w00 +
                    ((c10 shr 16) and 0xFF) * w10 +
                    ((c01 shr 16) and 0xFF) * w01 +
                    ((c11 shr 16) and 0xFF) * w11).roundToInt().coerceIn(0, 255)
                val g = (((c00 shr 8) and 0xFF) * w00 +
                    ((c10 shr 8) and 0xFF) * w10 +
                    ((c01 shr 8) and 0xFF) * w01 +
                    ((c11 shr 8) and 0xFF) * w11).roundToInt().coerceIn(0, 255)
                val b = ((c00 and 0xFF) * w00 +
                    (c10 and 0xFF) * w10 +
                    (c01 and 0xFF) * w01 +
                    (c11 and 0xFF) * w11).roundToInt().coerceIn(0, 255)

                dstPixels[dstRow + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return dstPixels
    }

    /**
     * Computes Guided Filter matte coefficients while locking pure-core (`alpha >= 0.98`) and
     * pure-background (`alpha <= 0.02`) pixels to constant coefficients (`a = 0, b = 1` or `b = 0`)
     * so full-resolution refinement ONLY executes along the subject boundary!
     */
    private fun computeEdgeOnlyGuidedFilterMatte(
        guidanceLuma: FloatArray,
        coarseMask: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val (meanA, meanB, refined) = computeGuidedFilterMatte(
            guidanceLuma = guidanceLuma,
            coarseMask = coarseMask,
            width = width,
            height = height,
            radius = radius,
            eps = eps
        )
        // Lock interior core and far background outside the edge transition band
        for (i in refined.indices) {
            val a = refined[i]
            if (a >= 0.985f && coarseMask[i] >= 0.95f) {
                meanA[i] = 0f
                meanB[i] = 1f
                refined[i] = 1f
            } else if (a <= 0.015f && coarseMask[i] <= 0.05f) {
                meanA[i] = 0f
                meanB[i] = 0f
                refined[i] = 0f
            }
        }
        return Triple(meanA, meanB, refined)
    }

    /**
     * Fast 2D Box-Window Guided Filter (He et al.) using O(1)-per-pixel sliding-window box filters.
     */
    internal fun computeGuidedFilterMatte(
        guidanceLuma: FloatArray,
        coarseMask: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val n = width * height
        val iSq = FloatArray(n)
        val iTimesP = FloatArray(n)
        for (idx in 0 until n) {
            val g = guidanceLuma[idx]
            iSq[idx] = g * g
            iTimesP[idx] = g * coarseMask[idx]
        }

        val meanI = boxFilter2DSliding(guidanceLuma, width, height, radius)
        val meanP = boxFilter2DSliding(coarseMask, width, height, radius)
        val meanII = boxFilter2DSliding(iSq, width, height, radius)
        val meanIP = boxFilter2DSliding(iTimesP, width, height, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (idx in 0 until n) {
            val covIP = meanIP[idx] - meanI[idx] * meanP[idx]
            val varI = (meanII[idx] - meanI[idx] * meanI[idx]).coerceAtLeast(0f)
            val ak = covIP / (varI + eps)
            val bk = meanP[idx] - ak * meanI[idx]
            a[idx] = ak
            b[idx] = bk
        }

        val meanA = boxFilter2DSliding(a, width, height, radius)
        val meanB = boxFilter2DSliding(b, width, height, radius)
        val refined = FloatArray(n)
        for (idx in 0 until n) {
            val raw = (meanA[idx] * guidanceLuma[idx] + meanB[idx]).coerceIn(0f, 1f)
            val t = ((raw - 0.14f) / 0.72f).coerceIn(0f, 1f)
            refined[idx] = t * t * (3f - 2f * t)
        }
        return Triple(meanA, meanB, refined)
    }

    /**
     * O(W * H) separable sliding-window 2D box filter independent of [radius].
     */
    private fun boxFilter2DSliding(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val horiz = FloatArray(width * height)
        val out = FloatArray(width * height)
        val r = radius.coerceIn(1, max(1, min(width, height) / 2))

        // Horizontal sliding window
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            val initEnd = min(width - 1, r)
            for (x in 0..initEnd) {
                sum += src[row + x]
            }
            for (x in 0 until width) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(width - 1)
                if (x > 0) {
                    val addIdx = x + r
                    if (addIdx < width) sum += src[row + addIdx]
                    val remIdx = x - r - 1
                    if (remIdx >= 0) sum -= src[row + remIdx]
                }
                horiz[row + x] = sum / (x1 - x0 + 1)
            }
        }

        // Vertical sliding window
        for (x in 0 until width) {
            var sum = 0f
            val initEnd = min(height - 1, r)
            for (y in 0..initEnd) {
                sum += horiz[y * width + x]
            }
            for (y in 0 until height) {
                val y0 = (y - r).coerceAtLeast(0)
                val y1 = (y + r).coerceAtMost(height - 1)
                if (y > 0) {
                    val addIdx = y + r
                    if (addIdx < height) sum += horiz[addIdx * width + x]
                    val remIdx = y - r - 1
                    if (remIdx >= 0) sum -= horiz[remIdx * width + x]
                }
                out[y * width + x] = sum / (y1 - y0 + 1)
            }
        }
        return out
    }

    /**
     * Computes 3 concentric halo-free background bokeh buffers (`near`, `mid`, `far`) at downscaled
     * resolution `[bokehW] x [bokehH]` with foreground pixels strictly excluded (`weight = (1 - alpha)^3`).
     *
     * Prefers GPU `HardwareRenderer` + `RenderNode` + `RenderEffect.createBlurEffect` on API 31+,
     * automatically falling back to an O(1) multi-threaded tiled separable blur on CPU.
     */
    private fun computeMultiTierDiscBokehBackground(
        sourceBitmap: Bitmap,
        mask: SubjectSegmentationMask,
        bokehW: Int,
        bokehH: Int,
        maxBlurRadius: Int,
        blurStrength: Float,
        allowGpu: Boolean
    ): MultiTierDiscBokeh {
        val srcPixels = resampleBitmapPixels(sourceBitmap, bokehW, bokehH)
        val nearRadius = max(2, (maxBlurRadius * 0.38f).roundToInt())
        val midRadius = max(nearRadius + 1, (maxBlurRadius * 0.70f).roundToInt())
        val farRadius = max(midRadius + 1, maxBlurRadius)

        val boostFactor = if (blurStrength <= 1.0f) {
            blurStrength.coerceIn(0.15f, 1.0f)
        } else {
            (blurStrength / 16.0f).coerceIn(0.15f, 1.0f)
        }

        // 1. Try GPU RenderEffect blur on API 31+ (halo-free via premultiplied foreground-excluded alpha)
        if (allowGpu && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val gpuResult = tryComputeMultiTierBokehOnGpuApi31(
                srcPixels = srcPixels,
                mask = mask,
                bokehW = bokehW,
                bokehH = bokehH,
                nearRadius = nearRadius,
                midRadius = midRadius,
                farRadius = farRadius,
                boostFactor = boostFactor
            )
            if (gpuResult != null) {
                return gpuResult
            }
        }

        // 2. Multi-threaded O(1) tiled CPU fallback (splits across available CPU cores)
        return computeMultiTierBokehMultiThreadedCpu(
            srcPixels = srcPixels,
            mask = mask,
            bokehW = bokehW,
            bokehH = bokehH,
            nearRadius = nearRadius,
            midRadius = midRadius,
            farRadius = farRadius,
            boostFactor = boostFactor
        )
    }

    /**
     * GPU-accelerated downscaled bokeh blur using [HardwareRenderer], [RenderNode], and
     * [RenderEffect.createBlurEffect] (API 31+).
     *
     * Foreground pixels are masked to alpha=0 before blurring so `RenderEffect` computes
     * `sum(RGB * bgWeight)` and `sum(bgWeight)` in one GPU pass; un-premultiplying yields
     * a completely halo-free background blur in ~5–12 ms on device GPUs.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun tryComputeMultiTierBokehOnGpuApi31(
        srcPixels: IntArray,
        mask: SubjectSegmentationMask,
        bokehW: Int,
        bokehH: Int,
        nearRadius: Int,
        midRadius: Int,
        farRadius: Int,
        boostFactor: Float
    ): MultiTierDiscBokeh? {
        var bgPremulBitmap: Bitmap? = null
        var imageReader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        return try {
            val n = bokehW * bokehH
            val premulPixels = IntArray(n)
            val maskW = mask.width
            val maskH = mask.height
            val alphaMatte = mask.alphaMatte

            runParallelRanges(bokehH) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val row = y * bokehW
                    val my = ((y + 0.5f) * maskH / bokehH.toFloat() - 0.5f).coerceIn(0f, (maskH - 1).toFloat())
                    for (x in 0 until bokehW) {
                        val idx = row + x
                        val mx = ((x + 0.5f) * maskW / bokehW.toFloat() - 0.5f).coerceIn(0f, (maskW - 1).toFloat())
                        val fgAlpha = sampleBilinear(alphaMatte, maskW, maskH, mx, my).coerceIn(0f, 1f)
                        if (fgAlpha >= 0.85f) {
                            premulPixels[idx] = 0 // Strictly exclude foreground from GPU blur!
                            continue
                        }
                        val c = srcPixels[idx]
                        var r = ((c shr 16) and 0xFF).toFloat()
                        var g = ((c shr 8) and 0xFF).toFloat()
                        var b = (c and 0xFF).toFloat()

                        val bgFactor = (1.0f - fgAlpha).coerceIn(0f, 1f)
                        val w = (bgFactor * bgFactor * bgFactor).coerceIn(0f, 1f)
                        val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                        if (fgAlpha < 0.30f && lum > 0.74f) {
                            val lift = 1.0f + 0.28f * boostFactor * ((lum - 0.74f) / 0.26f)
                            r = (r * lift).coerceAtMost(255f)
                            g = (g * lift).coerceAtMost(255f)
                            b = (b * lift).coerceAtMost(255f)
                        }
                        val aInt = (w * 255f).roundToInt().coerceIn(0, 255)
                        val rInt = r.roundToInt().coerceIn(0, 255)
                        val gInt = g.roundToInt().coerceIn(0, 255)
                        val bInt = b.roundToInt().coerceIn(0, 255)
                        // Bitmap.setPixels expects non-premultiplied ARGB and premultiplies on upload
                        premulPixels[idx] = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
                    }
                }
            }

            bgPremulBitmap = Bitmap.createBitmap(bokehW, bokehH, Bitmap.Config.ARGB_8888)
            bgPremulBitmap.setPixels(premulPixels, 0, bokehW, 0, 0, bokehW, bokehH)

            imageReader = ImageReader.newInstance(
                bokehW,
                bokehH,
                PixelFormat.RGBA_8888,
                2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                    HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
            renderer = HardwareRenderer().apply {
                setSurface(imageReader.surface)
                setOpaque(false)
            }
            val renderNode = RenderNode("PortraitBokehGpuNode").apply {
                setPosition(0, 0, bokehW, bokehH)
            }
            renderer.setContentRoot(renderNode)

            fun renderSingleBlurTier(radiusPx: Int): IntArray? {
                val sigma = (radiusPx * 0.65f).coerceAtLeast(1.2f)
                renderNode.setRenderEffect(
                    RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
                )
                val canvas = renderNode.beginRecording(bokehW, bokehH)
                try {
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    canvas.drawBitmap(bgPremulBitmap, 0f, 0f, null)
                } finally {
                    renderNode.endRecording()
                }

                val syncResult = renderer.createRenderRequest()
                    .setWaitForPresent(true)
                    .syncAndDraw()
                if (syncResult != HardwareRenderer.SYNC_OK &&
                    syncResult != HardwareRenderer.SYNC_REDRAW_REQUESTED
                ) {
                    return null
                }

                val image = imageReader.acquireLatestImage() ?: return null
                return try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val out = IntArray(n)
                    val rowBytes = ByteArray(rowStride)
                    var nonTransparentCount = 0
                    for (y in 0 until bokehH) {
                        buf.position(y * rowStride)
                        val bytesToRead = min(rowStride, buf.remaining())
                        buf.get(rowBytes, 0, bytesToRead)
                        val outRow = y * bokehW
                        for (x in 0 until bokehW) {
                            val off = x * pixelStride
                            val rPre = rowBytes[off].toInt() and 0xFF
                            val gPre = rowBytes[off + 1].toInt() and 0xFF
                            val bPre = rowBytes[off + 2].toInt() and 0xFF
                            val a = rowBytes[off + 3].toInt() and 0xFF
                            val idx = outRow + x
                            if (a >= 3) {
                                nonTransparentCount++
                                // Un-premultiply GPU blurred color to remove all edge darkening/halos
                                val invA = 255f / a.toFloat()
                                val rOut = (rPre * invA).roundToInt().coerceIn(0, 255)
                                val gOut = (gPre * invA).roundToInt().coerceIn(0, 255)
                                val bOut = (bPre * invA).roundToInt().coerceIn(0, 255)
                                out[idx] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
                            } else {
                                out[idx] = srcPixels[idx]
                            }
                        }
                    }
                    if (nonTransparentCount < (n / 32).coerceAtLeast(1)) null else out
                } finally {
                    runCatching { image.close() }
                }
            }

            val near = renderSingleBlurTier(nearRadius) ?: return null
            val mid = renderSingleBlurTier(midRadius) ?: return null
            val far = renderSingleBlurTier(farRadius) ?: return null

            MultiTierDiscBokeh(
                nearPixels = near,
                midPixels = mid,
                farPixels = far,
                usedGpu = true
            )
        } catch (t: Throwable) {
            Log.d(TAG, "GPU RenderEffect bokeh unavailable (${t.javaClass.simpleName}); using multi-threaded CPU tiles")
            null
        } finally {
            runCatching { renderer?.destroy() }
            runCatching { imageReader?.close() }
            if (bgPremulBitmap != null && !bgPremulBitmap.isRecycled) {
                runCatching { bgPremulBitmap.recycle() }
            }
        }
    }

    /**
     * Multi-threaded O(1)-per-pixel separable normalized blur fallback across [workerCount] CPU cores.
     * Runs 2 sliding-window separable passes on `(wR, wG, wB, wSum)` so subject colors (`weight ~ 0`)
     * never bleed into the background, while cutting compute cost by >15x vs 2D disc loops.
     */
    private fun computeMultiTierBokehMultiThreadedCpu(
        srcPixels: IntArray,
        mask: SubjectSegmentationMask,
        bokehW: Int,
        bokehH: Int,
        nearRadius: Int,
        midRadius: Int,
        farRadius: Int,
        boostFactor: Float
    ): MultiTierDiscBokeh {
        val n = bokehW * bokehH
        val wR = FloatArray(n)
        val wG = FloatArray(n)
        val wB = FloatArray(n)
        val wSum = FloatArray(n)

        val maskW = mask.width
        val maskH = mask.height
        val alphaMatte = mask.alphaMatte

        runParallelRanges(bokehH) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val row = y * bokehW
                val my = ((y + 0.5f) * maskH / bokehH.toFloat() - 0.5f).coerceIn(0f, (maskH - 1).toFloat())
                for (x in 0 until bokehW) {
                    val idx = row + x
                    val mx = ((x + 0.5f) * maskW / bokehW.toFloat() - 0.5f).coerceIn(0f, (maskW - 1).toFloat())
                    val fgAlpha = sampleBilinear(alphaMatte, maskW, maskH, mx, my).coerceIn(0f, 1f)

                    val c = srcPixels[idx]
                    val r = ((c shr 16) and 0xFF).toFloat()
                    val g = ((c shr 8) and 0xFF).toFloat()
                    val b = (c and 0xFF).toFloat()

                    val bgFactor = (1.0f - fgAlpha).coerceIn(0f, 1f)
                    val baseWeight = if (fgAlpha >= 0.85f) {
                        0.0001f
                    } else {
                        bgFactor * bgFactor * bgFactor + 0.0005f
                    }
                    val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    val highlightBoost = if (fgAlpha < 0.30f && lum > 0.74f) {
                        1.0f + 1.45f * boostFactor * ((lum - 0.74f) / 0.26f)
                    } else {
                        1.0f
                    }
                    val weight = baseWeight * highlightBoost
                    wR[idx] = r * weight
                    wG[idx] = g * weight
                    wB[idx] = b * weight
                    wSum[idx] = weight
                }
            }
        }

        // Run 3 tiers (near, mid, far) using fast O(1) multi-threaded 2-pass separable normalized blur
        val nearPixels = separableNormalizedBlurParallel(srcPixels, wR, wG, wB, wSum, bokehW, bokehH, nearRadius)
        val midPixels = separableNormalizedBlurParallel(srcPixels, wR, wG, wB, wSum, bokehW, bokehH, midRadius)
        val farPixels = separableNormalizedBlurParallel(srcPixels, wR, wG, wB, wSum, bokehW, bokehH, farRadius)

        return MultiTierDiscBokeh(
            nearPixels = nearPixels,
            midPixels = midPixels,
            farPixels = farPixels,
            usedGpu = false
        )
    }

    /**
     * Multi-threaded 2-iteration separable sliding-window normalized blur on `(wR, wG, wB, wSum)`.
     * Two box passes approximate a smooth bell/lens-like falloff in strictly `O(W * H)` time
     * independent of [radius].
     */
    private fun separableNormalizedBlurParallel(
        fallbackSrc: IntArray,
        srcR: FloatArray,
        srcG: FloatArray,
        srcB: FloatArray,
        srcW: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray {
        val n = width * height
        val r1 = (radius * 0.72f).roundToInt().coerceIn(1, max(1, min(width, height) / 3))
        val r2 = (radius * 0.58f).roundToInt().coerceIn(1, max(1, min(width, height) / 3))

        val tmpR = FloatArray(n)
        val tmpG = FloatArray(n)
        val tmpB = FloatArray(n)
        val tmpW = FloatArray(n)

        val curR = FloatArray(n)
        val curG = FloatArray(n)
        val curB = FloatArray(n)
        val curW = FloatArray(n)

        fun boxPass4Channels(
            inR: FloatArray, inG: FloatArray, inB: FloatArray, inW: FloatArray,
            midR: FloatArray, midG: FloatArray, midB: FloatArray, midW: FloatArray,
            outR: FloatArray, outG: FloatArray, outB: FloatArray, outW: FloatArray,
            rad: Int
        ) {
            // Horizontal pass parallelized by row strips
            runParallelRanges(height) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val row = y * width
                    var sR = 0f
                    var sG = 0f
                    var sB = 0f
                    var sW = 0f
                    val initEnd = min(width - 1, rad)
                    for (x in 0..initEnd) {
                        val idx = row + x
                        sR += inR[idx]; sG += inG[idx]; sB += inB[idx]; sW += inW[idx]
                    }
                    for (x in 0 until width) {
                        if (x > 0) {
                            val addX = x + rad
                            if (addX < width) {
                                val aIdx = row + addX
                                sR += inR[aIdx]; sG += inG[aIdx]; sB += inB[aIdx]; sW += inW[aIdx]
                            }
                            val remX = x - rad - 1
                            if (remX >= 0) {
                                val rIdx = row + remX
                                sR -= inR[rIdx]; sG -= inG[rIdx]; sB -= inB[rIdx]; sW -= inW[rIdx]
                            }
                        }
                        val outIdx = row + x
                        midR[outIdx] = sR
                        midG[outIdx] = sG
                        midB[outIdx] = sB
                        midW[outIdx] = sW
                    }
                }
            }

            // Vertical pass parallelized by column strips
            runParallelRanges(width) { xStart, xEnd ->
                for (x in xStart until xEnd) {
                    var sR = 0f
                    var sG = 0f
                    var sB = 0f
                    var sW = 0f
                    val initEnd = min(height - 1, rad)
                    for (y in 0..initEnd) {
                        val idx = y * width + x
                        sR += midR[idx]; sG += midG[idx]; sB += midB[idx]; sW += midW[idx]
                    }
                    for (y in 0 until height) {
                        if (y > 0) {
                            val addY = y + rad
                            if (addY < height) {
                                val aIdx = addY * width + x
                                sR += midR[aIdx]; sG += midG[aIdx]; sB += midB[aIdx]; sW += midW[aIdx]
                            }
                            val remY = y - rad - 1
                            if (remY >= 0) {
                                val rIdx = remY * width + x
                                sR -= midR[rIdx]; sG -= midG[rIdx]; sB -= midB[rIdx]; sW -= midW[rIdx]
                            }
                        }
                        val outIdx = y * width + x
                        outR[outIdx] = sR
                        outG[outIdx] = sG
                        outB[outIdx] = sB
                        outW[outIdx] = sW
                    }
                }
            }
        }

        // Pass 1: src -> cur
        boxPass4Channels(srcR, srcG, srcB, srcW, tmpR, tmpG, tmpB, tmpW, curR, curG, curB, curW, r1)
        // Pass 2: cur -> cur (smooth tent/Gaussian-like bokeh profile)
        boxPass4Channels(curR, curG, curB, curW, tmpR, tmpG, tmpB, tmpW, curR, curG, curB, curW, r2)

        val outPixels = IntArray(n)
        runParallelRanges(height) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val row = y * width
                for (x in 0 until width) {
                    val idx = row + x
                    val w = curW[idx]
                    if (w > 1e-4f) {
                        val outR = (curR[idx] / w).roundToInt().coerceIn(0, 255)
                        val outG = (curG[idx] / w).roundToInt().coerceIn(0, 255)
                        val outB = (curB[idx] / w).roundToInt().coerceIn(0, 255)
                        outPixels[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                    } else {
                        outPixels[idx] = fallbackSrc[idx]
                    }
                }
            }
        }
        return outPixels
    }

    /**
     * Collapses the 3 bokeh tiers (`near`, `mid`, `far`) into a single depth-graduated background
     * buffer at bokeh resolution `[bokehW] x [bokehH]` in parallel across CPU workers.
     */
    private fun blendBokehTiersByDepthParallel(
        tiers: MultiTierDiscBokeh,
        distanceDepthMap: FloatArray,
        maskW: Int,
        maskH: Int,
        bokehW: Int,
        bokehH: Int
    ): IntArray {
        val out = IntArray(bokehW * bokehH)
        val nearTier = tiers.nearPixels
        val midTier = tiers.midPixels
        val farTier = tiers.farPixels

        runParallelRanges(bokehH) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val row = y * bokehW
                val my = ((y + 0.5f) * maskH / bokehH.toFloat() - 0.5f).coerceIn(0f, (maskH - 1).toFloat())
                // Subtle ground-plane depth taper at bottom 10% of frame
                val verticalDepthWeight = if (y > bokehH * 0.90f) {
                    val t = (y - bokehH * 0.90f) / (bokehH * 0.10f).coerceAtLeast(1f)
                    (1f - 0.22f * t).coerceIn(0.78f, 1f)
                } else {
                    1.0f
                }
                for (x in 0 until bokehW) {
                    val idx = row + x
                    val mx = ((x + 0.5f) * maskW / bokehW.toFloat() - 0.5f).coerceIn(0f, (maskW - 1).toFloat())
                    val depthFalloff = (sampleBilinear(distanceDepthMap, maskW, maskH, mx, my) * verticalDepthWeight)
                        .coerceIn(0f, 1f)
                    val outR: Int
                    val outG: Int
                    val outB: Int
                    if (depthFalloff < 0.5f) {
                        val t = (depthFalloff * 2f).coerceIn(0f, 1f)
                        val c0 = nearTier[idx]
                        val c1 = midTier[idx]
                        outR = (((c0 shr 16) and 0xFF) * (1f - t) + ((c1 shr 16) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                        outG = (((c0 shr 8) and 0xFF) * (1f - t) + ((c1 shr 8) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                        outB = ((c0 and 0xFF) * (1f - t) + (c1 and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    } else {
                        val t = ((depthFalloff - 0.5f) * 2f).coerceIn(0f, 1f)
                        val c0 = midTier[idx]
                        val c1 = farTier[idx]
                        outR = (((c0 shr 16) and 0xFF) * (1f - t) + ((c1 shr 16) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                        outG = (((c0 shr 8) and 0xFF) * (1f - t) + ((c1 shr 8) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                        outB = ((c0 and 0xFF) * (1f - t) + (c1 and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    }
                    out[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
        return out
    }

    /**
     * GPU-accelerated full-resolution Portrait compositing using an AGSL [RuntimeShader] (API 33+).
     * Bilinearly upscales the downscaled blurred background and soft mask directly on the GPU,
     * applying edge-only guided-filter refinement around the subject boundary.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun tryCompositePortraitOnGpuApi33(
        sourceBitmap: Bitmap,
        depthBlendedBgPixels: IntArray,
        bokehW: Int,
        bokehH: Int,
        mask: SubjectSegmentationMask
    ): Bitmap? {
        val fullW = sourceBitmap.width
        val fullH = sourceBitmap.height
        var bgBitmap: Bitmap? = null
        var maskCoeffBitmap: Bitmap? = null
        var imageReader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        return try {
            bgBitmap = Bitmap.createBitmap(bokehW, bokehH, Bitmap.Config.ARGB_8888)
            bgBitmap.setPixels(depthBlendedBgPixels, 0, bokehW, 0, 0, bokehW, bokehH)

            // Pack (coarseAlpha, encodedCoeffA, encodedCoeffB) into an ARGB_8888 texture for GPU bilinear sampling
            val maskW = mask.width
            val maskH = mask.height
            val packedMask = IntArray(maskW * maskH)
            for (i in packedMask.indices) {
                val rAlpha = (mask.alphaMatte[i].coerceIn(0f, 1f) * 255f).roundToInt()
                // Map coeffA from [-2..2] -> [0..255] and coeffB from [-1..2] -> [0..255]
                val gA = (((mask.guideCoeffA[i].coerceIn(-2f, 2f) + 2f) / 4f) * 255f).roundToInt().coerceIn(0, 255)
                val bB = (((mask.guideCoeffB[i].coerceIn(-1f, 2f) + 1f) / 3f) * 255f).roundToInt().coerceIn(0, 255)
                packedMask[i] = (0xFF shl 24) or (rAlpha shl 16) or (gA shl 8) or bB
            }
            maskCoeffBitmap = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
            maskCoeffBitmap.setPixels(packedMask, 0, maskW, 0, 0, maskW, maskH)

            val agslSource = """
                uniform shader fgTex;
                uniform shader bgTex;
                uniform shader maskTex;
                uniform float2 fgSize;
                uniform float2 bgSize;
                uniform float2 maskSize;

                half4 main(float2 coord) {
                    half4 fg = fgTex.eval(coord);
                    float2 uv = coord / fgSize;
                    half4 m = maskTex.eval(uv * maskSize);
                    float coarseAlpha = float(m.r);
                    if (coarseAlpha >= 0.985) {
                        return half4(fg.rgb, 1.0);
                    }
                    half4 bg = bgTex.eval(uv * bgSize);
                    if (coarseAlpha <= 0.015) {
                        return half4(bg.rgb, 1.0);
                    }
                    // Edge-only guided-filter refinement along the subject boundary
                    float coeffA = float(m.g) * 4.0 - 2.0;
                    float coeffB = float(m.b) * 3.0 - 1.0;
                    float luma = dot(float3(fg.rgb), float3(0.299, 0.587, 0.114));
                    float rawAlpha = clamp(coeffA * luma + coeffB, 0.0, 1.0);
                    float t = clamp((rawAlpha - 0.10) / 0.78, 0.0, 1.0);
                    float refinedAlpha = t * t * (3.0 - 2.0 * t);
                    half3 mixedRgb = mix(bg.rgb, fg.rgb, half(refinedAlpha));
                    return half4(mixedRgb, 1.0);
                }
            """.trimIndent()

            val shader = RuntimeShader(agslSource)
            shader.setInputShader("fgTex", BitmapShader(sourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            shader.setInputShader("bgTex", BitmapShader(bgBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            shader.setInputShader("maskTex", BitmapShader(maskCoeffBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            shader.setFloatUniform("fgSize", fullW.toFloat(), fullH.toFloat())
            shader.setFloatUniform("bgSize", bokehW.toFloat(), bokehH.toFloat())
            shader.setFloatUniform("maskSize", maskW.toFloat(), maskH.toFloat())

            imageReader = ImageReader.newInstance(
                fullW,
                fullH,
                PixelFormat.RGBA_8888,
                2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                    HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
            renderer = HardwareRenderer().apply {
                setSurface(imageReader.surface)
                setOpaque(true)
            }
            val node = RenderNode("PortraitCompositeAgslNode").apply {
                setPosition(0, 0, fullW, fullH)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                this.shader = shader
            }
            val canvas = node.beginRecording(fullW, fullH)
            try {
                canvas.drawRect(0f, 0f, fullW.toFloat(), fullH.toFloat(), paint)
            } finally {
                node.endRecording()
            }
            renderer.setContentRoot(node)
            val syncResult = renderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()
            if (syncResult != HardwareRenderer.SYNC_OK &&
                syncResult != HardwareRenderer.SYNC_REDRAW_REQUESTED
            ) {
                return null
            }

            val image = imageReader.acquireLatestImage() ?: return null
            try {
                val hwBuf = image.hardwareBuffer
                if (hwBuf != null) {
                    val wrapped = Bitmap.wrapHardwareBuffer(hwBuf, null)
                    if (wrapped != null) {
                        val softwareCopy = wrapped.copy(Bitmap.Config.ARGB_8888, true)
                        wrapped.recycle()
                        hwBuf.close()
                        if (softwareCopy != null) {
                            // Sanity check non-black output (guards headless/stub renderers)
                            val cSample = softwareCopy.getPixel(fullW / 2, fullH / 2)
                            val cOrig = sourceBitmap.getPixel(fullW / 2, fullH / 2)
                            if ((cSample and 0x00FFFFFF) != 0 || (cOrig and 0x00FFFFFF) == 0) {
                                return softwareCopy
                            }
                            softwareCopy.recycle()
                        }
                    } else {
                        hwBuf.close()
                    }
                }
                null
            } finally {
                runCatching { image.close() }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "GPU AGSL compositing unavailable (${t.javaClass.simpleName}); using multi-threaded CPU tiles")
            null
        } finally {
            runCatching { renderer?.destroy() }
            runCatching { imageReader?.close() }
            if (bgBitmap != null && !bgBitmap.isRecycled) runCatching { bgBitmap.recycle() }
            if (maskCoeffBitmap != null && !maskCoeffBitmap.isRecycled) runCatching { maskCoeffBitmap.recycle() }
        }
    }

    /**
     * Multi-threaded tiled full-resolution Portrait compositor with **edge-only** guided-filter
     * refinement. Processes horizontal tiles of [TILE_ROW_HEIGHT] rows in parallel across
     * [workerCount] threads so peak heap stays tiny (~2 MB per tile) and interior core / pure
     * background pixels bypass all guided-filter math.
     */
    private fun compositePortraitMultiThreadedTiled(
        sourceBitmap: Bitmap,
        depthBlendedBgPixels: IntArray,
        bokehW: Int,
        bokehH: Int,
        mask: SubjectSegmentationMask,
        retouch: PortraitRetouch
    ): Bitmap {
        val fullW = sourceBitmap.width
        val fullH = sourceBitmap.height
        val outputBitmap = acquireReusableBitmap(fullW, fullH)?.takeIf {
            it.width == fullW && it.height == fullH
        } ?: Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)

        val maskW = mask.width
        val maskH = mask.height
        val alphaMatte = mask.alphaMatte
        val coeffA = mask.guideCoeffA
        val coeffB = mask.guideCoeffB
        val retouchStrength = when (retouch) {
            PortraitRetouch.OFF -> 0f
            PortraitRetouch.NATURAL -> 0.28f
            PortraitRetouch.SMOOTH -> 0.52f
        }

        val bitmapIoLock = Any()
        val tileCount = (fullH + TILE_ROW_HEIGHT - 1) / TILE_ROW_HEIGHT
        val nextTileIndex = AtomicInteger(0)

        val tasks = List(min(workerCount, tileCount)) {
            Callable {
                while (true) {
                    val tileIdx = nextTileIndex.getAndIncrement()
                    if (tileIdx >= tileCount) break
                    val yStart = tileIdx * TILE_ROW_HEIGHT
                    val yEnd = min(fullH, yStart + TILE_ROW_HEIGHT)
                    val tileH = yEnd - yStart

                    // Read 1-row padding when skin retouch is active so 3x3 bilateral filter has tile neighbors
                    val padTop = if (retouchStrength > 0f && yStart > 0) 1 else 0
                    val padBot = if (retouchStrength > 0f && yEnd < fullH) 1 else 0
                    val readY = yStart - padTop
                    val readH = tileH + padTop + padBot
                    val srcTile = IntArray(fullW * readH)
                    synchronized(bitmapIoLock) {
                        sourceBitmap.getPixels(srcTile, 0, fullW, 0, readY, fullW, readH)
                    }

                    val outTile = IntArray(fullW * tileH)

                    for (localY in 0 until tileH) {
                        val y = yStart + localY
                        val srcRowIdx = (localY + padTop) * fullW
                        val outRowIdx = localY * fullW
                        val my = ((y + 0.5f) * maskH / fullH.toFloat() - 0.5f).coerceIn(0f, (maskH - 1).toFloat())
                        val byF = ((y + 0.5f) * bokehH / fullH.toFloat() - 0.5f).coerceIn(0f, (bokehH - 1).toFloat())
                        val by0 = byF.toInt().coerceIn(0, bokehH - 1)
                        val by1 = (by0 + 1).coerceAtMost(bokehH - 1)
                        val bfy = byF - by0
                        val bgRow0 = by0 * bokehW
                        val bgRow1 = by1 * bokehW

                        for (x in 0 until fullW) {
                            val mx = ((x + 0.5f) * maskW / fullW.toFloat() - 0.5f).coerceIn(0f, (maskW - 1).toFloat())
                            val coarseAlpha = sampleBilinear(alphaMatte, maskW, maskH, mx, my)

                            val origColor = srcTile[srcRowIdx + x]
                            // Fast-path 1: Deep inside subject core -> skip guided filter & background sampling completely!
                            if (coarseAlpha >= 0.985f) {
                                outTile[outRowIdx + x] = if (retouchStrength > 0f && x > 0 && x < fullW - 1 &&
                                    (localY + padTop) > 0 && (localY + padTop) < readH - 1
                                ) {
                                    applyRetouchSinglePixel(srcTile, fullW, x, localY + padTop, origColor, retouchStrength)
                                } else {
                                    origColor
                                }
                                continue
                            }

                            // Sample bilinearly upscaled blurred background pixel
                            val bxF = ((x + 0.5f) * bokehW / fullW.toFloat() - 0.5f).coerceIn(0f, (bokehW - 1).toFloat())
                            val bx0 = bxF.toInt().coerceIn(0, bokehW - 1)
                            val bx1 = (bx0 + 1).coerceAtMost(bokehW - 1)
                            val bfx = bxF - bx0
                            val bg00 = depthBlendedBgPixels[bgRow0 + bx0]
                            val bg10 = depthBlendedBgPixels[bgRow0 + bx1]
                            val bg01 = depthBlendedBgPixels[bgRow1 + bx0]
                            val bg11 = depthBlendedBgPixels[bgRow1 + bx1]
                            val w00 = (1f - bfx) * (1f - bfy)
                            val w10 = bfx * (1f - bfy)
                            val w01 = (1f - bfx) * bfy
                            val w11 = bfx * bfy
                            val bgR = ((bg00 shr 16) and 0xFF) * w00 + ((bg10 shr 16) and 0xFF) * w10 +
                                ((bg01 shr 16) and 0xFF) * w01 + ((bg11 shr 16) and 0xFF) * w11
                            val bgG = ((bg00 shr 8) and 0xFF) * w00 + ((bg10 shr 8) and 0xFF) * w10 +
                                ((bg01 shr 8) and 0xFF) * w01 + ((bg11 shr 8) and 0xFF) * w11
                            val bgB = (bg00 and 0xFF) * w00 + (bg10 and 0xFF) * w10 +
                                (bg01 and 0xFF) * w01 + (bg11 and 0xFF) * w11

                            // Fast-path 2: Pure background -> skip guided filter completely!
                            if (coarseAlpha <= 0.015f) {
                                val rOut = bgR.roundToInt().coerceIn(0, 255)
                                val gOut = bgG.roundToInt().coerceIn(0, 255)
                                val bOut = bgB.roundToInt().coerceIn(0, 255)
                                outTile[outRowIdx + x] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
                                continue
                            }

                            // Edge-only refinement band: evaluate Guided Filter (a * I + b) along mask boundary
                            val fgColor = if (retouchStrength > 0f && coarseAlpha >= 0.65f &&
                                x > 0 && x < fullW - 1 &&
                                (localY + padTop) > 0 && (localY + padTop) < readH - 1
                            ) {
                                applyRetouchSinglePixel(srcTile, fullW, x, localY + padTop, origColor, retouchStrength)
                            } else {
                                origColor
                            }
                            val fgR = (fgColor shr 16) and 0xFF
                            val fgG = (fgColor shr 8) and 0xFF
                            val fgB = fgColor and 0xFF

                            val lumaFull = (0.299f * fgR + 0.587f * fgG + 0.114f * fgB) / 255f
                            val aVal = sampleBilinear(coeffA, maskW, maskH, mx, my)
                            val bVal = sampleBilinear(coeffB, maskW, maskH, mx, my)
                            val rawAlpha = (aVal * lumaFull + bVal).coerceIn(0f, 1f)
                            val normT = ((rawAlpha - 0.10f) / 0.78f).coerceIn(0f, 1f)
                            val refinedAlpha = normT * normT * (3f - 2f * normT)

                            if (refinedAlpha >= 0.96f) {
                                outTile[outRowIdx + x] = fgColor
                                continue
                            }

                            val bgMix = (1f - refinedAlpha).coerceIn(0f, 1f)
                            val fgMix = 1f - bgMix
                            val outR = (fgR * fgMix + bgR * bgMix).roundToInt().coerceIn(0, 255)
                            val outG = (fgG * fgMix + bgG * bgMix).roundToInt().coerceIn(0, 255)
                            val outB = (fgB * fgMix + bgB * bgMix).roundToInt().coerceIn(0, 255)
                            outTile[outRowIdx + x] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                        }
                    }

                    synchronized(bitmapIoLock) {
                        outputBitmap.setPixels(outTile, 0, fullW, 0, yStart, fullW, tileH)
                    }
                }
            }
        }
        invokeWorkerTasks(tasks)
        return outputBitmap
    }

    private fun applySubtleSkinRetouchTiled(
        bitmap: Bitmap,
        mask: SubjectSegmentationMask,
        retouch: PortraitRetouch
    ) {
        val blendStrength = when (retouch) {
            PortraitRetouch.OFF -> return
            PortraitRetouch.NATURAL -> 0.28f
            PortraitRetouch.SMOOTH -> 0.52f
        }
        val width = bitmap.width
        val height = bitmap.height
        val maskW = mask.width
        val maskH = mask.height
        val alphaMatte = mask.alphaMatte
        val bitmapIoLock = Any()
        val tileCount = (height + TILE_ROW_HEIGHT - 1) / TILE_ROW_HEIGHT
        val nextTile = AtomicInteger(0)

        val tasks = List(min(workerCount, tileCount)) {
            Callable {
                while (true) {
                    val tileIdx = nextTile.getAndIncrement()
                    if (tileIdx >= tileCount) break
                    val yStart = tileIdx * TILE_ROW_HEIGHT
                    val yEnd = min(height, yStart + TILE_ROW_HEIGHT)
                    val tileH = yEnd - yStart
                    val padTop = if (yStart > 0) 1 else 0
                    val padBot = if (yEnd < height) 1 else 0
                    val readY = yStart - padTop
                    val readH = tileH + padTop + padBot
                    val srcTile = IntArray(width * readH)
                    synchronized(bitmapIoLock) {
                        bitmap.getPixels(srcTile, 0, width, 0, readY, width, readH)
                    }
                    val outTile = IntArray(width * tileH)
                    for (localY in 0 until tileH) {
                        val y = yStart + localY
                        val my = ((y * (maskH - 1)) / (height - 1).coerceAtLeast(1)).coerceIn(0, maskH - 1)
                        val maskRow = my * maskW
                        val sRow = (localY + padTop) * width
                        val oRow = localY * width
                        for (x in 0 until width) {
                            val c = srcTile[sRow + x]
                            val mx = ((x * (maskW - 1)) / (width - 1).coerceAtLeast(1)).coerceIn(0, maskW - 1)
                            if (x > 0 && x < width - 1 &&
                                (localY + padTop) > 0 && (localY + padTop) < readH - 1 &&
                                alphaMatte[maskRow + mx] >= 0.65f
                            ) {
                                outTile[oRow + x] = applyRetouchSinglePixel(srcTile, width, x, localY + padTop, c, blendStrength)
                            } else {
                                outTile[oRow + x] = c
                            }
                        }
                    }
                    synchronized(bitmapIoLock) {
                        bitmap.setPixels(outTile, 0, width, 0, yStart, width, tileH)
                    }
                }
            }
        }
        invokeWorkerTasks(tasks)
    }

    private fun applyRetouchSinglePixel(
        tile: IntArray,
        width: Int,
        x: Int,
        localY: Int,
        c: Int,
        blendStrength: Float
    ): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val yVal = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        if (!isSkinTonePixel(c, yVal, cb, cr)) return c

        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var wTotal = 0f
        for (ny in (localY - 1)..(localY + 1)) {
            val nRow = ny * width
            for (nx in (x - 1)..(x + 1)) {
                val nc = tile[nRow + nx]
                val nr = (nc shr 16) and 0xFF
                val ng = (nc shr 8) and 0xFF
                val nb = nc and 0xFF
                val diff = abs(nr - r) + abs(ng - g) + abs(nb - b)
                if (diff <= 48) {
                    val w = 1f - (diff / 64f)
                    sumR += nr * w
                    sumG += ng * w
                    sumB += nb * w
                    wTotal += w
                }
            }
        }
        if (wTotal <= 0f) return c
        val outR = (r * (1f - blendStrength) + (sumR / wTotal) * blendStrength).roundToInt().coerceIn(0, 255)
        val outG = (g * (1f - blendStrength) + (sumG / wTotal) * blendStrength).roundToInt().coerceIn(0, 255)
        val outB = (b * (1f - blendStrength) + (sumB / wTotal) * blendStrength).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun runParallelRanges(totalSize: Int, block: (start: Int, end: Int) -> Unit) {
        if (totalSize < 64 || workerCount <= 1) {
            block(0, totalSize)
            return
        }
        val chunks = min(workerCount, totalSize)
        val chunkSize = (totalSize + chunks - 1) / chunks
        val tasks = ArrayList<Callable<Unit>>(chunks)
        for (i in 0 until chunks) {
            val start = i * chunkSize
            val end = min(totalSize, start + chunkSize)
            if (start < end) {
                tasks.add(Callable { block(start, end) })
            }
        }
        invokeWorkerTasks(tasks)
    }

    private fun invokeWorkerTasks(tasks: List<Callable<Unit>>) {
        if (tasks.size <= 1) {
            tasks.firstOrNull()?.call()
            return
        }
        val futures = tileWorkerPool.invokeAll(tasks)
        for (f in futures) {
            f.get()
        }
    }

    private fun detectFacesAndBiometricClusters(
        workW: Int,
        workH: Int,
        workPixels: IntArray,
        luma: FloatArray,
        cbArr: FloatArray,
        crArr: FloatArray,
        borderClusters: List<ColorCluster>
    ): List<FaceAnchor> {
        val anchors = mutableListOf<FaceAnchor>()

        var rgb565Bitmap: Bitmap? = null
        try {
            rgb565Bitmap = Bitmap.createBitmap(workW, workH, Bitmap.Config.RGB_565)
            rgb565Bitmap.setPixels(workPixels, 0, workW, 0, 0, workW, workH)
            val detector = FaceDetector(workW, workH, MAX_DETECTED_FACES)
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_DETECTED_FACES)
            val foundCount = detector.findFaces(rgb565Bitmap, faces)
            val midPoint = PointF()
            for (i in 0 until foundCount) {
                val face = faces[i] ?: continue
                val conf = face.confidence()
                val eyeDist = face.eyesDistance()
                if (conf >= 0.3f && eyeDist >= 4f) {
                    face.getMidPoint(midPoint)
                    anchors.add(
                        FaceAnchor(
                            centerX = midPoint.x.coerceIn(0f, (workW - 1).toFloat()),
                            centerY = midPoint.y.coerceIn(0f, (workH - 1).toFloat()),
                            eyeDistance = eyeDist.coerceIn(6f, min(workW, workH) * 0.45f),
                            confidence = conf.coerceIn(0.3f, 1.0f)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Continue with perceptual YCbCr biometric skin-cluster detection
        } finally {
            if (rgb565Bitmap != null && !rgb565Bitmap.isRecycled) {
                rgb565Bitmap.recycle()
            }
        }

        if (anchors.isNotEmpty()) {
            return anchors
        }

        val marginX = (workW * 0.08f).toInt().coerceAtLeast(2)
        val marginY = (workH * 0.06f).toInt().coerceAtLeast(2)
        val maxScanY = (workH * 0.82f).toInt().coerceAtMost(workH - marginY)

        var borderSkinCount = 0
        var borderTotalCount = 0
        for (x in 0 until workW) {
            borderTotalCount += 2
            if (isSkinTonePixel(workPixels[x], luma[x], cbArr[x], crArr[x])) borderSkinCount++
            val botIdx = (workH - 1) * workW + x
            if (isSkinTonePixel(workPixels[botIdx], luma[botIdx], cbArr[botIdx], crArr[botIdx])) borderSkinCount++
        }
        val borderSkinRatio = borderSkinCount.toFloat() / borderTotalCount.coerceAtLeast(1)
        if (borderSkinRatio > 0.35f) {
            return emptyList()
        }

        var skinCount = 0
        var minSkinX = workW
        var maxSkinX = 0
        var minSkinY = workH
        var maxSkinY = 0
        val skinMask = BooleanArray(workW * workH)

        for (y in marginY until maxScanY) {
            val row = y * workW
            for (x in marginX until (workW - marginX)) {
                val idx = row + x
                if (isSkinTonePixel(workPixels[idx], luma[idx], cbArr[idx], crArr[idx])) {
                    val bgDist = minNormalizedColorDistance(luma[idx], cbArr[idx], crArr[idx], borderClusters)
                    if (bgDist > 1.10f) {
                        skinMask[idx] = true
                        skinCount++
                        if (x < minSkinX) minSkinX = x
                        if (x > maxSkinX) maxSkinX = x
                        if (y < minSkinY) minSkinY = y
                        if (y > maxSkinY) maxSkinY = y
                    }
                }
            }
        }

        val totalArea = (workW * workH).toFloat()
        val skinFraction = skinCount / totalArea
        if (skinFraction in 0.008f..0.48f && maxSkinY >= minSkinY && maxSkinX >= minSkinX) {
            val spanX = (maxSkinX - minSkinX + 1).toFloat()
            val spanY = (maxSkinY - minSkinY + 1).toFloat()
            val bboxArea = (spanX * spanY).coerceAtLeast(1f)
            val fillDensity = skinCount / bboxArea
            if (fillDensity >= 0.20f) {
                val headBottomY = if (spanY > workH * 0.34f) {
                    (minSkinY + (spanY * 0.44f).toInt()).coerceIn(minSkinY + 2, maxSkinY)
                } else {
                    maxSkinY
                }
                var headSumX = 0f
                var headSumY = 0f
                var headCount = 0
                var headMinX = workW
                var headMaxX = 0
                for (y in minSkinY..headBottomY) {
                    val row = y * workW
                    for (x in minSkinX..maxSkinX) {
                        if (skinMask[row + x]) {
                            headSumX += x
                            headSumY += y
                            headCount++
                            if (x < headMinX) headMinX = x
                            if (x > headMaxX) headMaxX = x
                        }
                    }
                }

                if (headCount > 0) {
                    val headCx = headSumX / headCount
                    val headCy = headSumY / headCount
                    val headWidth = (headMaxX - headMinX + 1).toFloat().coerceAtLeast(6f)
                    val headHeight = (headBottomY - minSkinY + 1).toFloat().coerceAtLeast(6f)
                    val estimatedEyeDist = (min(headWidth, headHeight * 1.15f) * 0.50f)
                        .coerceIn(6f, min(workW, workH) * 0.32f)
                    anchors.add(
                        FaceAnchor(
                            centerX = headCx,
                            centerY = headCy,
                            eyeDistance = estimatedEyeDist,
                            confidence = (0.58f + 0.35f * fillDensity).coerceIn(0.58f, 0.94f)
                        )
                    )
                }
            }
        }

        return anchors
    }

    private fun isSkinTonePixel(argb: Int, yVal: Float, cb: Float, cr: Float): Boolean {
        if (yVal !in 0.12f..0.95f) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (r <= g || r <= b || (r - min(g, b)) < 10) return false
        return cb in 72f..132f && cr in 128f..180f
    }

    private fun buildBorderBackgroundClusters(
        workW: Int,
        workH: Int,
        luma: FloatArray,
        cbArr: FloatArray,
        crArr: FloatArray
    ): List<ColorCluster> {
        val strip = max(2, min(workW, workH) / 12)
        val maxSideY = (workH * 0.78f).toInt().coerceAtLeast(strip + 1)

        val numBins = 216
        val binCounts = IntArray(numBins)
        val binSumY = FloatArray(numBins)
        val binSumCb = FloatArray(numBins)
        val binSumCr = FloatArray(numBins)
        val binSumSqY = FloatArray(numBins)
        val binSumSqCb = FloatArray(numBins)
        val binSumSqCr = FloatArray(numBins)
        var totalSamples = 0

        fun addBorderSample(idx: Int) {
            val yVal = luma[idx]
            val cb = cbArr[idx]
            val cr = crArr[idx]
            val by = (yVal * 5.99f).toInt().coerceIn(0, 5)
            val bcb = (((cb - 40f) / 176f) * 5.99f).toInt().coerceIn(0, 5)
            val bcr = (((cr - 40f) / 176f) * 5.99f).toInt().coerceIn(0, 5)
            val bin = (by * 36) + (bcb * 6) + bcr
            binCounts[bin]++
            binSumY[bin] += yVal
            binSumCb[bin] += cb
            binSumCr[bin] += cr
            binSumSqY[bin] += yVal * yVal
            binSumSqCb[bin] += cb * cb
            binSumSqCr[bin] += cr * cr
            totalSamples++
        }

        for (y in 0 until strip) {
            val row = y * workW
            for (x in 0 until workW) {
                addBorderSample(row + x)
            }
        }
        for (y in strip until maxSideY) {
            val row = y * workW
            for (x in 0 until strip) {
                addBorderSample(row + x)
            }
            for (x in (workW - strip) until workW) {
                addBorderSample(row + x)
            }
        }

        if (totalSamples <= 0) {
            return listOf(ColorCluster(0.5f, 128f, 128f, 0.06f, 5.5f, 5.5f))
        }

        val minBinCount = (totalSamples * 0.015f).toInt().coerceAtLeast(2)
        val sortedBins = (0 until numBins)
            .filter { binCounts[it] >= minBinCount }
            .sortedByDescending { binCounts[it] }
            .ifEmpty { (0 until numBins).filter { binCounts[it] > 0 }.sortedByDescending { binCounts[it] } }

        val palette = mutableListOf<ColorCluster>()
        for (bin in sortedBins) {
            val count = binCounts[bin].toFloat()
            val mY = binSumY[bin] / count
            val mCb = binSumCb[bin] / count
            val mCr = binSumCr[bin] / count
            val varY = (binSumSqY[bin] / count - mY * mY).coerceAtLeast(0f)
            val varCb = (binSumSqCb[bin] / count - mCb * mCb).coerceAtLeast(0f)
            val varCr = (binSumSqCr[bin] / count - mCr * mCr).coerceAtLeast(0f)

            val stdY = sqrt(varY).coerceIn(0.045f, 0.085f)
            val stdCb = sqrt(varCb).coerceIn(4.5f, 8.0f)
            val stdCr = sqrt(varCr).coerceIn(4.5f, 8.0f)

            val isDuplicate = palette.any { existing ->
                abs(existing.meanY - mY) < 0.07f &&
                    abs(existing.meanCb - mCb) < 6.0f &&
                    abs(existing.meanCr - mCr) < 6.0f
            }
            if (!isDuplicate) {
                palette.add(
                    ColorCluster(
                        meanY = mY,
                        meanCb = mCb,
                        meanCr = mCr,
                        stdY = stdY,
                        stdCb = stdCb,
                        stdCr = stdCr,
                        weight = count / totalSamples
                    )
                )
                if (palette.size >= MAX_BORDER_PALETTE_CLUSTERS) break
            }
        }

        return palette
    }

    private fun buildForegroundSeedClusters(
        workW: Int,
        workH: Int,
        luma: FloatArray,
        cbArr: FloatArray,
        crArr: FloatArray,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        borderClusters: List<ColorCluster>
    ): List<ColorCluster> {
        val left = (cx - rx).toInt().coerceIn(0, workW - 1)
        val right = (cx + rx).toInt().coerceIn(left, workW - 1)
        val top = (cy - ry).toInt().coerceIn(0, workH - 1)
        val bottom = (cy + ry).toInt().coerceIn(top, workH - 1)

        val numBins = 216
        val binCounts = IntArray(numBins)
        val binSumY = FloatArray(numBins)
        val binSumCb = FloatArray(numBins)
        val binSumCr = FloatArray(numBins)
        var totalFgSamples = 0

        for (y in top..bottom) {
            val row = y * workW
            for (x in left..right) {
                val idx = row + x
                val yVal = luma[idx]
                val cb = cbArr[idx]
                val cr = crArr[idx]
                val bgDist = minNormalizedColorDistance(yVal, cb, cr, borderClusters)
                if (bgDist < 0.70f) continue

                val by = (yVal * 5.99f).toInt().coerceIn(0, 5)
                val bcb = (((cb - 40f) / 176f) * 5.99f).toInt().coerceIn(0, 5)
                val bcr = (((cr - 40f) / 176f) * 5.99f).toInt().coerceIn(0, 5)
                val bin = (by * 36) + (bcb * 6) + bcr
                binCounts[bin]++
                binSumY[bin] += yVal
                binSumCb[bin] += cb
                binSumCr[bin] += cr
                totalFgSamples++
            }
        }

        if (totalFgSamples <= 0) {
            return listOf(sampleRegionColorCluster(workW, workH, luma, cbArr, crArr, cx, cy, rx, ry))
        }

        val sortedBins = (0 until numBins)
            .filter { binCounts[it] > 0 }
            .sortedByDescending { binCounts[it] }

        val clusters = mutableListOf<ColorCluster>()
        for (bin in sortedBins) {
            val count = binCounts[bin].toFloat()
            val mY = binSumY[bin] / count
            val mCb = binSumCb[bin] / count
            val mCr = binSumCr[bin] / count
            val isDuplicate = clusters.any { existing ->
                abs(existing.meanY - mY) < 0.08f &&
                    abs(existing.meanCb - mCb) < 7.0f &&
                    abs(existing.meanCr - mCr) < 7.0f
            }
            if (!isDuplicate) {
                clusters.add(
                    ColorCluster(
                        meanY = mY,
                        meanCb = mCb,
                        meanCr = mCr,
                        stdY = 0.065f,
                        stdCb = 6.0f,
                        stdCr = 6.0f,
                        weight = count / totalFgSamples
                    )
                )
                if (clusters.size >= MAX_FOREGROUND_SEED_CLUSTERS) break
            }
        }

        return clusters.ifEmpty {
            listOf(sampleRegionColorCluster(workW, workH, luma, cbArr, crArr, cx, cy, rx, ry))
        }
    }

    private fun computePersonProbabilityMask(
        workW: Int,
        workH: Int,
        luma: FloatArray,
        cbArr: FloatArray,
        crArr: FloatArray,
        faceAnchors: List<FaceAnchor>,
        borderClusters: List<ColorCluster>,
        outPersonProb: FloatArray
    ) {
        for (anchor in faceAnchors) {
            val dEye = anchor.eyeDistance
            val headCx = anchor.centerX
            val headCy = anchor.centerY - 0.06f * dEye
            val headRx = (1.38f * dEye).coerceAtLeast(6f)
            val headRy = (1.78f * dEye).coerceAtLeast(8f)

            val torsoCx = anchor.centerX
            val torsoTopY = anchor.centerY + 1.00f * dEye
            val torsoCy = anchor.centerY + 2.95f * dEye
            val torsoRx = (2.95f * dEye).coerceAtLeast(12f)
            val torsoRy = (2.75f * dEye).coerceAtLeast(12f)

            val faceSeed = sampleRegionColorCluster(
                workW, workH, luma, cbArr, crArr, headCx, anchor.centerY, dEye * 0.72f, dEye * 0.80f
            )
            val hairSeed = sampleRegionColorCluster(
                workW, workH, luma, cbArr, crArr, headCx, (headCy - headRy * 0.68f).coerceAtLeast(1f), dEye * 0.85f, dEye * 0.35f
            )
            val torsoSeed = sampleRegionColorCluster(
                workW, workH, luma, cbArr, crArr, torsoCx, min((workH - 2).toFloat(), torsoTopY + dEye * 1.15f), dEye * 1.35f, dEye * 1.05f
            )
            val fgPersonClusters = listOf(faceSeed, hairSeed, torsoSeed)

            for (y in 0 until workH) {
                val row = y * workW
                for (x in 0 until workW) {
                    val idx = row + x
                    val dxHead = (x - headCx) / headRx
                    val dyHead = (y - headCy) / headRy
                    val distSqHead = dxHead * dxHead + dyHead * dyHead

                    val dxTorso = (x - torsoCx) / torsoRx
                    val dyTorso = (y - torsoCy) / torsoRy
                    val distSqTorso = if (y >= anchor.centerY + 0.50f * dEye) {
                        dxTorso * dxTorso + dyTorso * dyTorso
                    } else {
                        99f
                    }

                    val spatialHead = when {
                        distSqHead <= 0.68f -> 1.0f
                        distSqHead <= 1.75f -> exp(-1.7f * (distSqHead - 0.68f))
                        else -> 0f
                    }
                    val spatialTorso = when {
                        distSqTorso <= 0.65f -> 0.98f
                        distSqTorso <= 1.65f -> 0.98f * exp(-1.8f * (distSqTorso - 0.65f))
                        else -> 0f
                    }
                    val spatialPrior = max(spatialHead, spatialTorso)
                    if (spatialPrior < 0.05f) continue

                    val bgDist = minNormalizedColorDistance(luma[idx], cbArr[idx], crArr[idx], borderClusters)
                    val fgDist = minNormalizedColorDistance(luma[idx], cbArr[idx], crArr[idx], fgPersonClusters)

                    if (distSqHead <= 0.56f || distSqTorso <= 0.42f) {
                        outPersonProb[idx] = max(outPersonProb[idx], 0.96f)
                        continue
                    }

                    if (bgDist < 0.80f && fgDist > bgDist * 1.20f && distSqHead > 0.85f && distSqTorso > 0.72f) {
                        continue
                    }

                    val colorLikelihood = ((bgDist - 0.40f) / (bgDist + fgDist + 0.30f)).coerceIn(0f, 1f)
                    val combined = if (distSqHead <= 0.92f || distSqTorso <= 0.75f) {
                        (0.72f * spatialPrior + 0.28f * colorLikelihood).coerceIn(0f, 1f)
                    } else {
                        (spatialPrior * (0.30f + 0.70f * colorLikelihood)).coerceIn(0f, 1f)
                    }

                    if (combined > outPersonProb[idx]) {
                        outPersonProb[idx] = combined
                    }
                }
            }
        }
    }

    private fun computeSalientSubjectProbabilityMask(
        workW: Int,
        workH: Int,
        luma: FloatArray,
        cbArr: FloatArray,
        crArr: FloatArray,
        focusNormX: Float,
        focusNormY: Float,
        borderClusters: List<ColorCluster>,
        outSalientProb: FloatArray
    ): Float {
        val seedCx = (focusNormX * workW).coerceIn(workW * 0.18f, workW * 0.82f)
        val seedCy = (focusNormY * workH).coerceIn(workH * 0.18f, workH * 0.82f)
        val seedRx = (workW * 0.18f).coerceAtLeast(4f)
        val seedRy = (workH * 0.22f).coerceAtLeast(4f)

        val fgSeedClusters = buildForegroundSeedClusters(
            workW, workH, luma, cbArr, crArr, seedCx, seedCy, seedRx, seedRy, borderClusters
        )

        var weightedSeedToBorderDist = 0f
        var weightSum = 0f
        for (fgCluster in fgSeedClusters) {
            val d = minNormalizedColorDistance(
                fgCluster.meanY, fgCluster.meanCb, fgCluster.meanCr, borderClusters
            )
            weightedSeedToBorderDist += d * fgCluster.weight
            weightSum += fgCluster.weight
        }
        val seedToBorderDist = if (weightSum > 0f) weightedSeedToBorderDist / weightSum else 0f

        val gradMag = FloatArray(workW * workH)
        var maxGrad = 0.01f
        for (y in 1 until workH - 1) {
            val r0 = (y - 1) * workW
            val r1 = y * workW
            val r2 = (y + 1) * workW
            for (x in 1 until workW - 1) {
                val gx = (luma[r0 + x + 1] + 2f * luma[r1 + x + 1] + luma[r2 + x + 1]) -
                    (luma[r0 + x - 1] + 2f * luma[r1 + x - 1] + luma[r2 + x - 1])
                val gy = (luma[r2 + x - 1] + 2f * luma[r2 + x] + luma[r2 + x + 1]) -
                    (luma[r0 + x - 1] + 2f * luma[r0 + x] + luma[r0 + x + 1])
                val g = sqrt(gx * gx + gy * gy)
                gradMag[r1 + x] = g
                if (g > maxGrad) maxGrad = g
            }
        }

        var seedGradSum = 0f
        var seedGradCount = 0
        for (y in (seedCy - seedRy).toInt().coerceAtLeast(1)..(seedCy + seedRy).toInt().coerceAtMost(workH - 2)) {
            val row = y * workW
            for (x in (seedCx - seedRx).toInt().coerceAtLeast(1)..(seedCx + seedRx).toInt().coerceAtMost(workW - 2)) {
                seedGradSum += gradMag[row + x]
                seedGradCount++
            }
        }
        val avgSeedGrad = if (seedGradCount > 0) seedGradSum / seedGradCount else 0f

        val contrastScore = ((seedToBorderDist - 0.70f) / 2.0f + (avgSeedGrad * 1.2f)).coerceIn(0f, 1f)
        if (contrastScore < 0.12f) {
            return contrastScore
        }

        val spatialSigmaX = workW * 0.36f
        val spatialSigmaY = workH * 0.42f

        for (y in 0 until workH) {
            val row = y * workW
            val dy = (y - seedCy) / spatialSigmaY
            for (x in 0 until workW) {
                val idx = row + x
                val dx = (x - seedCx) / spatialSigmaX
                val distSq = dx * dx + dy * dy
                val spatialPrior = exp(-0.65f * distSq).coerceIn(0f, 1f)

                val bgDist = minNormalizedColorDistance(luma[idx], cbArr[idx], crArr[idx], borderClusters)
                val fgDist = minNormalizedColorDistance(luma[idx], cbArr[idx], crArr[idx], fgSeedClusters)

                if (bgDist < 0.80f && bgDist < fgDist * 0.90f) {
                    outSalientProb[idx] = 0f
                    continue
                }

                val colorRatio = ((bgDist - 0.55f) / (bgDist + fgDist + 0.35f)).coerceIn(0f, 1f)
                val edgeBoost = if (colorRatio > 0.35f) (gradMag[idx] / maxGrad).coerceIn(0f, 1f) * 0.14f else 0f
                val isOuterEdge = x < 3 || x >= workW - 3 || y < 3
                val borderPenalty = if (isOuterEdge) 0.05f else 1.0f

                outSalientProb[idx] = ((colorRatio * 0.85f + edgeBoost) * spatialPrior * borderPenalty * 1.45f)
                    .coerceIn(0f, 1f)
            }
        }

        return contrastScore
    }

    private fun sampleRegionColorCluster(
        workW: Int,
        workH: Int,
        luma: FloatArray,
        cbArr: FloatArray,
        crArr: FloatArray,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float
    ): ColorCluster {
        val left = (cx - rx).toInt().coerceIn(0, workW - 1)
        val right = (cx + rx).toInt().coerceIn(left, workW - 1)
        val top = (cy - ry).toInt().coerceIn(0, workH - 1)
        val bottom = (cy + ry).toInt().coerceIn(top, workH - 1)

        var sumY = 0f
        var sumCb = 0f
        var sumCr = 0f
        var count = 0
        for (y in top..bottom) {
            val row = y * workW
            for (x in left..right) {
                val idx = row + x
                sumY += luma[idx]
                sumCb += cbArr[idx]
                sumCr += crArr[idx]
                count++
            }
        }
        if (count <= 0) {
            return ColorCluster(0.5f, 128f, 128f, 0.06f, 6f, 6f)
        }
        val mY = sumY / count
        val mCb = sumCb / count
        val mCr = sumCr / count

        var varY = 0f
        var varCb = 0f
        var varCr = 0f
        for (y in top..bottom) {
            val row = y * workW
            for (x in left..right) {
                val idx = row + x
                val dy = luma[idx] - mY
                val dcb = cbArr[idx] - mCb
                val dcr = crArr[idx] - mCr
                varY += dy * dy
                varCb += dcb * dcb
                varCr += dcr * dcr
            }
        }
        return ColorCluster(
            meanY = mY,
            meanCb = mCb,
            meanCr = mCr,
            stdY = sqrt(varY / count).coerceIn(0.05f, 0.12f),
            stdCb = sqrt(varCb / count).coerceIn(5.0f, 11.0f),
            stdCr = sqrt(varCr / count).coerceIn(5.0f, 11.0f)
        )
    }

    private fun minNormalizedColorDistance(
        yVal: Float,
        cb: Float,
        cr: Float,
        clusters: List<ColorCluster>
    ): Float {
        if (clusters.isEmpty()) return 1.0f
        var minDist = Float.MAX_VALUE
        for (c in clusters) {
            val dy = abs(yVal - c.meanY) / c.stdY.coerceAtLeast(0.045f)
            val dcb = abs(cb - c.meanCb) / c.stdCb.coerceAtLeast(4.5f)
            val dcr = abs(cr - c.meanCr) / c.stdCr.coerceAtLeast(4.5f)
            val d = sqrt(0.35f * dy * dy + 0.325f * dcb * dcb + 0.325f * dcr * dcr)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    private fun cleanupCoarseMask(mask: FloatArray, width: Int, height: Int): FloatArray {
        val dilated = FloatArray(width * height)
        val closed = FloatArray(width * height)

        for (y in 0 until height) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x1 = (x + 1).coerceAtMost(width - 1)
                var maxV = 0f
                for (ny in y0..y1) {
                    val row = ny * width
                    for (nx in x0..x1) {
                        val v = mask[row + nx]
                        if (v > maxV) maxV = v
                    }
                }
                dilated[y * width + x] = maxV
            }
        }

        for (y in 0 until height) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x1 = (x + 1).coerceAtMost(width - 1)
                var minV = 1f
                var sumV = 0f
                var count = 0
                for (ny in y0..y1) {
                    val row = ny * width
                    for (nx in x0..x1) {
                        val v = dilated[row + nx]
                        if (v < minV) minV = v
                        sumV += v
                        count++
                    }
                }
                val avgV = sumV / count
                closed[y * width + x] = (0.65f * minV + 0.35f * avgV).coerceIn(0f, 1f)
            }
        }
        return closed
    }

    internal fun computeDistanceFromSubjectMask(
        alphaMatte: FloatArray,
        width: Int,
        height: Int,
        hardwareDepthNormalized: FloatArray? = null,
        hardwareDepthW: Int = 0,
        hardwareDepthH: Int = 0
    ): FloatArray {
        val n = width * height
        val dist = FloatArray(n)
        val inf = (width + height).toFloat()

        for (i in 0 until n) {
            dist[i] = if (alphaMatte[i] >= 0.50f) 0f else inf
        }

        val diag = 1.4142f
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var d = dist[row + x]
                if (d == 0f) continue
                if (x > 0) d = min(d, dist[row + x - 1] + 1f)
                if (y > 0) {
                    val prevRow = (y - 1) * width
                    d = min(d, dist[prevRow + x] + 1f)
                    if (x > 0) d = min(d, dist[prevRow + x - 1] + diag)
                    if (x + 1 < width) d = min(d, dist[prevRow + x + 1] + diag)
                }
                dist[row + x] = d
            }
        }

        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                var d = dist[row + x]
                if (d == 0f) continue
                if (x + 1 < width) d = min(d, dist[row + x + 1] + 1f)
                if (y + 1 < height) {
                    val nextRow = (y + 1) * width
                    d = min(d, dist[nextRow + x] + 1f)
                    if (x > 0) d = min(d, dist[nextRow + x - 1] + diag)
                    if (x + 1 < width) d = min(d, dist[nextRow + x + 1] + diag)
                }
                dist[row + x] = d
            }
        }

        val maxFalloffDist = (min(width, height) * 0.24f).coerceAtLeast(8f)
        val hasHwDepth = hardwareDepthNormalized != null &&
            hardwareDepthW >= 4 &&
            hardwareDepthH >= 4 &&
            hardwareDepthNormalized.size >= hardwareDepthW * hardwareDepthH

        val depthMap = FloatArray(n)
        for (y in 0 until height) {
            val row = y * width
            val dy = if (hasHwDepth) {
                ((y + 0.5f) * hardwareDepthH / height.toFloat() - 0.5f).coerceIn(0f, (hardwareDepthH - 1).toFloat())
            } else 0f
            for (x in 0 until width) {
                val idx = row + x
                val normDist = (dist[idx] / maxFalloffDist).coerceIn(0f, 1f)
                val smoothDist = normDist * normDist * (3f - 2f * normDist)
                val geometricFalloff = (0.22f + 0.78f * smoothDist).coerceIn(0f, 1f)
                depthMap[idx] = if (hasHwDepth && hardwareDepthNormalized != null) {
                    val dx = ((x + 0.5f) * hardwareDepthW / width.toFloat() - 0.5f).coerceIn(0f, (hardwareDepthW - 1).toFloat())
                    val hwBgDepth = (1f - sampleBilinear(hardwareDepthNormalized, hardwareDepthW, hardwareDepthH, dx, dy)).coerceIn(0f, 1f)
                    (0.55f * geometricFalloff + 0.45f * hwBgDepth).coerceIn(0f, 1f)
                } else {
                    geometricFalloff
                }
            }
        }
        return depthMap
    }

    internal fun extractEmbeddedDepthGridFromBytes(
        sourceBytes: ByteArray,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean,
        targetW: Int,
        targetH: Int
    ): Triple<FloatArray, Int, Int>? {
        if (sourceBytes.size < 128) return null
        return try {
            var depthImageBytes: ByteArray? = null
            val asciiWindow = String(sourceBytes, 0, min(sourceBytes.size, 65536), Charsets.ISO_8859_1)
            val gdepthMarker = "GDepth:Data=\""
            val markerIdx = asciiWindow.indexOf(gdepthMarker)
            if (markerIdx >= 0) {
                val startIdx = markerIdx + gdepthMarker.length
                val endIdx = asciiWindow.indexOf('"', startIdx)
                if (endIdx > startIdx + 16) {
                    val b64 = asciiWindow.substring(startIdx, endIdx)
                    depthImageBytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                }
            }

            if (depthImageBytes == null) {
                var eoiIndex = -1
                for (i in 2 until sourceBytes.size - 4) {
                    if (sourceBytes[i] == 0xFF.toByte() && sourceBytes[i + 1] == 0xD9.toByte()) {
                        for (j in (i + 2) until min(i + 64, sourceBytes.size - 3)) {
                            if (sourceBytes[j] == 0xFF.toByte() &&
                                sourceBytes[j + 1] == 0xD8.toByte() &&
                                sourceBytes[j + 2] == 0xFF.toByte()
                            ) {
                                eoiIndex = j
                                break
                            }
                        }
                        if (eoiIndex > 0) break
                    }
                }
                if (eoiIndex > 0 && sourceBytes.size - eoiIndex > 128) {
                    depthImageBytes = sourceBytes.copyOfRange(eoiIndex, sourceBytes.size)
                }
            }

            val validDepthBytes = depthImageBytes ?: return null
            val rawDepthBmp = BitmapFactory.decodeByteArray(validDepthBytes, 0, validDepthBytes.size) ?: return null
            val orientedDepthBmp = AspectCropUtils.cropAndOrientBitmap(
                sourceBitmap = rawDepthBmp,
                aspectRatioMode = aspectRatioMode,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = mirrorHorizontally
            )

            val gw = targetW.coerceAtLeast(8)
            val gh = targetH.coerceAtLeast(8)
            val depthPixels = resampleBitmapPixels(orientedDepthBmp, gw, gh)
            if (orientedDepthBmp !== rawDepthBmp && !orientedDepthBmp.isRecycled) {
                orientedDepthBmp.recycle()
            }
            if (!rawDepthBmp.isRecycled) {
                rawDepthBmp.recycle()
            }

            val closeness = FloatArray(gw * gh)
            var minV = 1f
            var maxV = 0f
            for (i in depthPixels.indices) {
                val c = depthPixels[i]
                val v = ((c shr 16) and 0xFF) / 255f
                closeness[i] = v
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val span = maxV - minV
            if (span < 0.08f) return null
            for (i in closeness.indices) {
                closeness[i] = ((closeness[i] - minV) / span).coerceIn(0f, 1f)
            }
            Triple(closeness, gw, gh)
        } catch (_: Throwable) {
            null
        }
    }

    private fun sampleBilinear(grid: FloatArray, gridW: Int, gridH: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, gridW - 1)
        val y0 = y.toInt().coerceIn(0, gridH - 1)
        val x1 = (x0 + 1).coerceAtMost(gridW - 1)
        val y1 = (y0 + 1).coerceAtMost(gridH - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)

        val v00 = grid[y0 * gridW + x0]
        val v10 = grid[y0 * gridW + x1]
        val v01 = grid[y1 * gridW + x0]
        val v11 = grid[y1 * gridW + x1]

        val top = v00 * (1f - fx) + v10 * fx
        val bot = v01 * (1f - fx) + v11 * fx
        return top * (1f - fy) + bot * fy
    }
}
