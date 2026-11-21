package com.example.core.processing

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.AstroDuration
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Result of Astrophotography multi-frame stacking and processing.
 */
sealed interface AstroProcessingOutcome {
    val jpegBytes: ByteArray
    val width: Int
    val height: Int
    val exifOrientation: Int

    data class ProcessedAstro(
        override val jpegBytes: ByteArray,
        override val width: Int,
        override val height: Int,
        override val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        val framesStacked: Int,
        val durationLabel: String,
        val perFrameExposureMs: Long = 0L,
        val totalAcquisitionMs: Long = 0L,
        val processingDurationMs: Long,
        val wasPartialLowMemoryMerge: Boolean = false
    ) : AstroProcessingOutcome

    data class FallbackNightSight(
        override val jpegBytes: ByteArray,
        override val width: Int,
        override val height: Int,
        override val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        val reason: String
    ) : AstroProcessingOutcome
}

/**
 * Memory-bounded streaming Astrophotography frame accumulator.
 *
 * Mirrors [NightSightStreamingAccumulator] so long astrophotography sessions never hold all
 * captured frames in RAM simultaneously:
 * 1. Extracts compact star keypoints via strided sampling (never allocating a full-frame IntArray).
 * 2. Estimates translational star alignment shift `(dx, dy)` against the reference frame.
 * 3. Streams the aligned frame in bounded horizontal strips ([STRIP_HEIGHT]) directly into
 *    compact running sums (`ShortArray` R/G/B + `ByteArray` per-pixel max for satellite/airplane
 *    trail outlier rejection + `ByteArray` sample counts).
 * 4. Immediately recycles every incoming frame [Bitmap] in a `finally` block.
 *
 * Peak memory stays strictly O(1) with respect to frame count, and any frames already merged
 * remain safely finalizable even if capture is stopped early ("Stop and save what I have")
 * or if low memory occurs mid-session.
 */
class AstroStreamingAccumulator(
    val width: Int,
    val height: Int
) : AutoCloseable {

    private val pixelCount: Int = width * height
    private var sumR: ShortArray? = ShortArray(pixelCount)
    private var sumG: ShortArray? = ShortArray(pixelCount)
    private var sumB: ShortArray? = ShortArray(pixelCount)
    private var maxR: ByteArray? = ByteArray(pixelCount)
    private var maxG: ByteArray? = ByteArray(pixelCount)
    private var maxB: ByteArray? = ByteArray(pixelCount)
    private var countMap: ByteArray? = ByteArray(pixelCount)
    private var referenceStars: List<AstroProcessor.StarPoint> = emptyList()

    var framesMerged: Int = 0
        private set

    var framesRejectedForMotion: Int = 0
        private set

    var encounteredLowMemory: Boolean = false
        private set

    /**
     * Aligns and merges a single astrophotography frame into the running accumulator,
     * immediately recycling [bitmap] when [recycleBitmap] is true.
     */
    fun addFrame(bitmap: Bitmap, recycleBitmap: Boolean = true): Boolean {
        val sR = sumR ?: return false
        val sG = sumG ?: return false
        val sB = sumB ?: return false
        val mR = maxR ?: return false
        val mG = maxG ?: return false
        val mB = maxB ?: return false
        val cMap = countMap ?: return false

        try {
            if (bitmap.isRecycled || bitmap.width != width || bitmap.height != height) {
                return false
            }
            if (framesMerged >= MAX_ACCUMULATOR_FRAMES) {
                return false
            }

            if (framesMerged == 0) {
                referenceStars = AstroProcessor.findStarPointsLowMemory(bitmap, maxStars = 15)
                ingestAlignedFrame(bitmap, dx = 0, dy = 0, sR, sG, sB, mR, mG, mB, cMap)
                framesMerged = 1
                return true
            }

            val compStars = AstroProcessor.findStarPointsLowMemory(bitmap, maxStars = 15)
            val shift = AstroProcessor.estimateStarAlignmentShiftFromStars(referenceStars, compStars)
            if (shift == null) {
                framesRejectedForMotion++
                Log.w(TAG, "Astro streaming frame ${framesMerged + 1} skipped due to excessive motion")
                return false
            }

            val dx = shift.first.roundToInt().coerceIn(-MAX_SHIFT_PX, MAX_SHIFT_PX)
            val dy = shift.second.roundToInt().coerceIn(-MAX_SHIFT_PX, MAX_SHIFT_PX)
            ingestAlignedFrame(bitmap, dx = dx, dy = dy, sR, sG, sB, mR, mG, mB, cMap)
            framesMerged++
            return true
        } catch (oom: OutOfMemoryError) {
            encounteredLowMemory = true
            Log.w(TAG, "Low memory while streaming Astro frame ${framesMerged + 1}; keeping $framesMerged merged frames", oom)
            return false
        } finally {
            if (recycleBitmap && !bitmap.isRecycled) {
                runCatching { bitmap.recycle() }
            }
        }
    }

    private fun ingestAlignedFrame(
        bitmap: Bitmap,
        dx: Int,
        dy: Int,
        sR: ShortArray,
        sG: ShortArray,
        sB: ShortArray,
        mR: ByteArray,
        mG: ByteArray,
        mB: ByteArray,
        cMap: ByteArray
    ) {
        val stripRowsMax = min(STRIP_HEIGHT, height)
        val stripBuffer = IntArray(width * stripRowsMax)
        var yStart = 0
        while (yStart < height) {
            val stripRows = min(STRIP_HEIGHT, height - yStart)
            // Companion pixel mapped to reference (x, y) comes from (x - dx, y - dy)
            val srcYMin = (yStart - dy).coerceIn(0, height - 1)
            val srcYMax = (yStart + stripRows - 1 - dy).coerceIn(0, height - 1)
            val readHeight = (srcYMax - srcYMin + 1).coerceIn(1, stripRowsMax)

            bitmap.getPixels(stripBuffer, 0, width, 0, srcYMin, width, readHeight)

            for (localY in 0 until stripRows) {
                val dstY = yStart + localY
                val srcY = dstY - dy
                if (srcY !in srcYMin until (srcYMin + readHeight)) continue
                val dstRow = dstY * width
                val bufRow = (srcY - srcYMin) * width

                for (dstX in 0 until width) {
                    val srcX = dstX - dx
                    if (srcX !in 0 until width) continue
                    val idx = dstRow + dstX
                    val curCount = cMap[idx].toInt() and 0xFF
                    if (curCount >= MAX_ACCUMULATOR_FRAMES) continue

                    val p = stripBuffer[bufRow + srcX]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF

                    sR[idx] = (sR[idx].toInt() + r).toShort()
                    sG[idx] = (sG[idx].toInt() + g).toShort()
                    sB[idx] = (sB[idx].toInt() + b).toShort()

                    if (curCount == 0 || r > (mR[idx].toInt() and 0xFF)) mR[idx] = r.toByte()
                    if (curCount == 0 || g > (mG[idx].toInt() and 0xFF)) mG[idx] = g.toByte()
                    if (curCount == 0 || b > (mB[idx].toInt() and 0xFF)) mB[idx] = b.toByte()
                    cMap[idx] = (curCount + 1).toByte()
                }
            }
            yStart += stripRows
        }
    }

    /**
     * Finalizes the running accumulator into a stacked, satellite-trail-rejected,
     * star-enhanced [Bitmap] in bounded horizontal strips while emitting smooth progress.
     */
    fun finalizeToBitmap(onProgress: ((Float) -> Unit)? = null): Bitmap {
        val sR = checkNotNull(sumR) { "Astro accumulator already closed or empty" }
        val sG = checkNotNull(sumG) { "Astro accumulator already closed or empty" }
        val sB = checkNotNull(sumB) { "Astro accumulator already closed or empty" }
        val mR = checkNotNull(maxR) { "Astro accumulator already closed or empty" }
        val mG = checkNotNull(maxG) { "Astro accumulator already closed or empty" }
        val mB = checkNotNull(maxB) { "Astro accumulator already closed or empty" }
        val cMap = checkNotNull(countMap) { "Astro accumulator already closed or empty" }

        onProgress?.invoke(0.10f)
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stripRowsMax = min(STRIP_HEIGHT, height)
        val stripOut = IntArray(width * stripRowsMax)
        val applyOutlierTrim = framesMerged >= 3

        var yStart = 0
        while (yStart < height) {
            val stripRows = min(STRIP_HEIGHT, height - yStart)
            for (localY in 0 until stripRows) {
                val y = yStart + localY
                val rowIdx = y * width
                val outRow = localY * width
                for (x in 0 until width) {
                    val idx = rowIdx + x
                    val count = (cMap[idx].toInt() and 0xFF).coerceAtLeast(1)
                    val rAvg: Int
                    val gAvg: Int
                    val bAvg: Int
                    if (applyOutlierTrim && count >= 3) {
                        // Trim single brightest pixel per channel to reject satellite/airplane trails & hot pixels
                        val denom = count - 1
                        rAvg = ((sR[idx].toInt() - (mR[idx].toInt() and 0xFF)).coerceAtLeast(0)) / denom
                        gAvg = ((sG[idx].toInt() - (mG[idx].toInt() and 0xFF)).coerceAtLeast(0)) / denom
                        bAvg = ((sB[idx].toInt() - (mB[idx].toInt() and 0xFF)).coerceAtLeast(0)) / denom
                    } else {
                        rAvg = sR[idx].toInt() / count
                        gAvg = sG[idx].toInt() / count
                        bAvg = sB[idx].toInt() / count
                    }
                    // Star detail & sky contrast enhancement
                    val rEnh = (rAvg * 1.08f).roundToInt().coerceIn(0, 255)
                    val gEnh = (gAvg * 1.08f).roundToInt().coerceIn(0, 255)
                    val bEnh = (bAvg * 1.12f).roundToInt().coerceIn(0, 255)
                    stripOut[outRow + x] = (0xFF shl 24) or (rEnh shl 16) or (gEnh shl 8) or bEnh
                }
            }
            outBitmap.setPixels(stripOut, 0, width, 0, yStart, width, stripRows)
            yStart += stripRows
            val frac = (yStart.toFloat() / height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            onProgress?.invoke(0.10f + frac * 0.72f)
        }

        // Release internal accumulator arrays as soon as pixels are materialized
        close()
        return outBitmap
    }

    override fun close() {
        sumR = null
        sumG = null
        sumB = null
        maxR = null
        maxG = null
        maxB = null
        countMap = null
        referenceStars = emptyList()
    }

    companion object {
        private const val TAG = "AstroStreamingAcc"
        private const val STRIP_HEIGHT = 64
        private const val MAX_SHIFT_PX = 50
        private const val MAX_ACCUMULATOR_FRAMES = 64
    }
}

/**
 * Astrophotography processor implementing memory-safe streaming star alignment, outlier rejection
 * noise reduction, and safe fallback.
 */
object AstroProcessor {
    private const val TAG = "AstroProcessor"

    /**
     * Computes a safe `BitmapFactory.Options.inSampleSize` (power of 2) so [AstroStreamingAccumulator]
     * plus one active frame bitmap stays comfortably inside available heap memory.
     */
    fun computeSafeDecodeSampleSize(
        context: Context? = null,
        frameWidth: Int,
        frameHeight: Int
    ): Int {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory().coerceAtLeast(64L * 1024L * 1024L)
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        var freeHeap = (maxHeap - usedHeap).coerceAtLeast(16L * 1024L * 1024L)

        if (context != null) {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val info = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(info)
                    if (info.lowMemory || am.isLowRamDevice) {
                        freeHeap = min(freeHeap, 48L * 1024L * 1024L)
                    }
                }
            }
        }

        val pixels = frameWidth.coerceAtLeast(320).toLong() * frameHeight.coerceAtLeast(240).toLong()
        // 10 B/px accumulator (ShortArray x3 + ByteArray x4) + 4 B/px active frame + 2 B/px scratch = ~16 B/px
        val estimatedPeakBytes = pixels * 16L + 8L * 1024L * 1024L
        val safeBudget = (freeHeap * 0.60).toLong().coerceAtLeast(24L * 1024L * 1024L)

        var sampleSize = 1
        while (sampleSize < 4 && (estimatedPeakBytes / (sampleSize.toLong() * sampleSize.toLong())) > safeBudget) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Finalizes a populated [AstroStreamingAccumulator] into an oriented, cropped JPEG payload
     * while emitting smooth processing progress (0.0 -> 1.0) off the main thread.
     */
    fun finalizeStreamingAccumulator(
        accumulator: AstroStreamingAccumulator,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean,
        duration: AstroDuration,
        perFrameExposureMs: Long = 0L,
        totalAcquisitionMs: Long = 0L,
        jpegQuality: Int = 95,
        onProcessingProgress: ((Float) -> Unit)? = null
    ): AstroProcessingOutcome.ProcessedAstro {
        val startTime = System.currentTimeMillis()
        val stackedCount = accumulator.framesMerged.coerceAtLeast(1)
        val wasLowMem = accumulator.encounteredLowMemory

        val stackedBmp = accumulator.finalizeToBitmap { p ->
            onProcessingProgress?.invoke(p.coerceIn(0.05f, 0.82f))
        }
        onProcessingProgress?.invoke(0.86f)
        val finalBmp = applyCropAndOrientation(stackedBmp, rotationDegrees, aspectRatioMode, mirrorHorizontally)
        if (finalBmp != stackedBmp) {
            runCatching { stackedBmp.recycle() }
        }
        onProcessingProgress?.invoke(0.93f)
        val outWidth = finalBmp.width
        val outHeight = finalBmp.height
        val jpegOut = compressBitmapToJpeg(finalBmp, jpegQuality)
        runCatching { finalBmp.recycle() }
        onProcessingProgress?.invoke(0.99f)

        val procTime = System.currentTimeMillis() - startTime
        Log.d(
            TAG,
            "Astro Streaming Stacked: Duration=${duration.displayName}, FramesStacked=$stackedCount, " +
                "Rotation=$rotationDegrees, Output=${outWidth}x${outHeight}, " +
                "PerFrameExpMs=${perFrameExposureMs}ms, AcquisitionMs=${totalAcquisitionMs}ms, ProcessingMs=${procTime}ms"
        )
        return AstroProcessingOutcome.ProcessedAstro(
            jpegBytes = jpegOut,
            width = outWidth,
            height = outHeight,
            framesStacked = stackedCount,
            durationLabel = duration.displayName,
            perFrameExposureMs = perFrameExposureMs,
            totalAcquisitionMs = totalAcquisitionMs,
            processingDurationMs = procTime,
            wasPartialLowMemoryMerge = wasLowMem
        )
    }

    fun processAstroJpegBytes(
        sourceBytes: ByteArray,
        additionalFramesBytes: List<ByteArray>,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean,
        duration: AstroDuration,
        perFrameExposureMs: Long = 0L,
        totalAcquisitionMs: Long = 0L,
        jpegQuality: Int = 95,
        onProcessingProgress: ((Float) -> Unit)? = null
    ): AstroProcessingOutcome {
        val startTime = System.currentTimeMillis()
        var accumulator: AstroStreamingAccumulator? = null
        try {
            val baseBitmap = decodeBitmapFromBytes(sourceBytes)
                ?: return AstroProcessingOutcome.FallbackNightSight(
                    jpegBytes = sourceBytes,
                    width = 0,
                    height = 0,
                    reason = "Failed to decode base astrophotography frame"
                )

            val width = baseBitmap.width
            val height = baseBitmap.height

            if (additionalFramesBytes.isEmpty()) {
                val processedBmp = applyCropAndOrientation(baseBitmap, rotationDegrees, aspectRatioMode, mirrorHorizontally)
                val outWidth = processedBmp.width
                val outHeight = processedBmp.height
                val outBytes = compressBitmapToJpeg(processedBmp, jpegQuality)
                if (processedBmp != baseBitmap) {
                    runCatching { baseBitmap.recycle() }
                }
                runCatching { processedBmp.recycle() }
                val procTime = System.currentTimeMillis() - startTime
                onProcessingProgress?.invoke(1.0f)
                return AstroProcessingOutcome.ProcessedAstro(
                    jpegBytes = outBytes,
                    width = outWidth,
                    height = outHeight,
                    framesStacked = 1,
                    durationLabel = duration.displayName,
                    perFrameExposureMs = perFrameExposureMs,
                    totalAcquisitionMs = totalAcquisitionMs,
                    processingDurationMs = procTime
                )
            }

            // Stream every frame one-by-one into AstroStreamingAccumulator so we never hold all bitmaps in RAM
            accumulator = AstroStreamingAccumulator(width, height)
            accumulator.addFrame(baseBitmap, recycleBitmap = true)

            for ((idx, frameBytes) in additionalFramesBytes.withIndex()) {
                val compBmp = decodeBitmapFromBytes(frameBytes) ?: continue
                if (compBmp.width == width && compBmp.height == height) {
                    accumulator.addFrame(compBmp, recycleBitmap = true)
                } else {
                    runCatching { compBmp.recycle() }
                }
                val ingestFrac = (idx + 1).toFloat() / additionalFramesBytes.size.coerceAtLeast(1).toFloat()
                onProcessingProgress?.invoke(0.05f + ingestFrac * 0.20f)
                if (accumulator.encounteredLowMemory) break
            }

            return finalizeStreamingAccumulator(
                accumulator = accumulator,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                duration = duration,
                perFrameExposureMs = perFrameExposureMs,
                totalAcquisitionMs = totalAcquisitionMs,
                jpegQuality = jpegQuality,
                onProcessingProgress = { p ->
                    onProcessingProgress?.invoke((0.25f + p * 0.75f).coerceIn(0.25f, 1.0f))
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Astrophotography processing failed, falling back", e)
            runCatching { accumulator?.close() }
            return AstroProcessingOutcome.FallbackNightSight(
                jpegBytes = sourceBytes,
                width = 0,
                height = 0,
                reason = "Astrophotography exception: ${e.localizedMessage}"
            )
        } finally {
            runCatching { accumulator?.close() }
        }
    }

    private fun decodeBitmapFromBytes(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }

    data class StarPoint(val x: Float, val y: Float, val brightness: Float)

    /**
     * Memory-safe star keypoint finder that samples pixels row-by-row instead of allocating
     * a full `IntArray(width * height)` buffer.
     */
    fun findStarPointsLowMemory(bitmap: Bitmap, maxStars: Int = 20): List<StarPoint> {
        val width = bitmap.width
        val height = bitmap.height
        val step = max(1, min(width, height) / 80)
        if (width <= step * 2 || height <= step * 2) return emptyList()

        val rowPixels = IntArray(width)
        val stars = mutableListOf<StarPoint>()
        var y = step
        while (y < height - step) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
            var x = step
            while (x < width - step) {
                val p = rowPixels[x]
                val brightness = 0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)

                if (brightness > 120f) {
                    val pLeft = rowPixels[x - step]
                    val bLeft = 0.299f * ((pLeft shr 16) and 0xFF) +
                        0.587f * ((pLeft shr 8) and 0xFF) +
                        0.114f * (pLeft and 0xFF)
                    val pRight = rowPixels[x + step]
                    val bRight = 0.299f * ((pRight shr 16) and 0xFF) +
                        0.587f * ((pRight shr 8) and 0xFF) +
                        0.114f * (pRight and 0xFF)

                    if (brightness > bLeft && brightness > bRight) {
                        stars.add(StarPoint(x.toFloat(), y.toFloat(), brightness))
                    }
                }
                x += step
            }
            y += step
        }
        return stars.sortedByDescending { it.brightness }.take(maxStars)
    }

    /**
     * Identifies bright point-like star features in a frame.
     */
    fun findStarPoints(bitmap: Bitmap, maxStars: Int = 20): List<StarPoint> =
        findStarPointsLowMemory(bitmap, maxStars)

    /**
     * Estimates translational shift (dx, dy) from pre-extracted star point lists.
     * Rejects shift if motion is excessive (> 50px).
     */
    fun estimateStarAlignmentShiftFromStars(
        baseStars: List<StarPoint>,
        compStars: List<StarPoint>
    ): Pair<Float, Float>? {
        if (baseStars.isEmpty() || compStars.isEmpty()) return Pair(0f, 0f)

        var bestDx = 0f
        var bestDy = 0f
        var maxMatches = 0

        for (b in baseStars.take(5)) {
            for (c in compStars.take(5)) {
                val dx = b.x - c.x
                val dy = b.y - c.y
                if (abs(dx) > 60f || abs(dy) > 60f) continue

                var matches = 0
                for (bs in baseStars) {
                    for (cs in compStars) {
                        if (abs((cs.x + dx) - bs.x) < 4f && abs((cs.y + dy) - bs.y) < 4f) {
                            matches++
                        }
                    }
                }
                if (matches > maxMatches) {
                    maxMatches = matches
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        if (abs(bestDx) > 50f || abs(bestDy) > 50f) return null
        return Pair(bestDx, bestDy)
    }

    /**
     * Estimates translational shift (dx, dy) to align companion bitmap to base bitmap.
     * Rejects shift if motion is excessive (> 50px).
     */
    fun estimateStarAlignmentShift(baseBitmap: Bitmap, companionBitmap: Bitmap): Pair<Float, Float>? {
        val baseStars = findStarPointsLowMemory(baseBitmap, 15)
        val compStars = findStarPointsLowMemory(companionBitmap, 15)
        return estimateStarAlignmentShiftFromStars(baseStars, compStars)
    }

    /**
     * Multi-frame stacking with star alignment and streaming outlier rejection.
     */
    fun stackAstroBitmapsWithOutlierRejection(bitmaps: List<Bitmap>): Bitmap {
        if (bitmaps.isEmpty()) throw IllegalArgumentException("No bitmaps to stack")
        if (bitmaps.size == 1) return bitmaps.first()

        val base = bitmaps.first()
        val acc = AstroStreamingAccumulator(base.width, base.height)
        return try {
            for (bmp in bitmaps) {
                acc.addFrame(bmp, recycleBitmap = false)
            }
            acc.finalizeToBitmap()
        } finally {
            acc.close()
        }
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun applyCropAndOrientation(
        bitmap: Bitmap,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean
    ): Bitmap {
        var current = bitmap

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation != 0 || mirrorHorizontally) {
            val matrix = Matrix().apply {
                if (mirrorHorizontally) {
                    postScale(-1f, 1f, current.width / 2f, current.height / 2f)
                }
                if (normalizedRotation != 0) {
                    postRotate(normalizedRotation.toFloat())
                }
            }
            val transformed = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (transformed != current) {
                current = transformed
            }
        }

        val targetRatio = when (aspectRatioMode) {
            AspectRatioMode.RATIO_4_3 -> 4f / 3f
            AspectRatioMode.RATIO_1_1 -> 1f / 1f
            AspectRatioMode.RATIO_9_16 -> 16f / 9f
        }

        val curW = current.width.toFloat()
        val curH = current.height.toFloat()
        val isCurrentLandscape = curW >= curH
        val effectiveTargetRatio = if (isCurrentLandscape) targetRatio else (1f / targetRatio)

        val cropW: Int
        val cropH: Int
        val currentRatio = curW / curH

        if (abs(currentRatio - effectiveTargetRatio) > 0.02f) {
            if (currentRatio > effectiveTargetRatio) {
                cropH = current.height
                cropW = (cropH * effectiveTargetRatio).roundToInt().coerceAtMost(current.width)
            } else {
                cropW = current.width
                cropH = (cropW / effectiveTargetRatio).roundToInt().coerceAtMost(current.height)
            }
            val startX = ((current.width - cropW) / 2).coerceAtLeast(0)
            val startY = ((current.height - cropH) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(current, startX, startY, cropW, cropH)
            if (cropped != current) {
                current = cropped
            }
        }

        return current
    }
}
