package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.example.core.camera.NightSightDeviceSupport
import com.example.core.camera.NightSightSupportPath
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.NightSightExposure
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Outcome of processing captured frames in Night Sight mode.
 */
sealed interface NightSightProcessingOutcome {
    data class ProcessedNightSight(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val exifOrientation: Int,
        val framesMerged: Int,
        val durationMs: Long,
        val supportPath: NightSightSupportPath,
        val alignmentShifts: List<Pair<Int, Int>> = emptyList(),
        val wasPartialLowMemoryMerge: Boolean = false
    ) : NightSightProcessingOutcome

    data class FallbackSingleFrame(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val exifOrientation: Int,
        val reason: String
    ) : NightSightProcessingOutcome
}

/**
 * Memory-bounded streaming Night Sight accumulator.
 *
 * Instead of holding all burst frames in RAM until capture completes, each incoming frame is:
 * 1. Downsampled to a compact luminance map (1/4 resolution for large captures) for fast,
 *    low-memory translation alignment.
 * 2. Merged directly into a single compact running sum (`ShortArray` R/G/B + `ByteArray` weights)
 *    in horizontal strips of [STRIP_HEIGHT] rows.
 * 3. Recycled immediately so peak heap usage stays strictly bounded regardless of frame count.
 *
 * If memory pressure (`OutOfMemoryError`) occurs mid-burst, all frames merged so far remain valid
 * and can be finalized and saved safely without crashing or losing the shot.
 */
class NightSightStreamingAccumulator(
    val width: Int,
    val height: Int,
    private val motionThreshold: Float = 0.18f
) : AutoCloseable {

    private val pixelCount: Int = width * height
    private val alignScale: Int = computeAlignmentDownsampleFactor(width, height)
    private val alignW: Int = (width / alignScale).coerceAtLeast(8)
    private val alignH: Int = (height / alignScale).coerceAtLeast(8)

    private var sumR: ShortArray? = ShortArray(pixelCount)
    private var sumG: ShortArray? = ShortArray(pixelCount)
    private var sumB: ShortArray? = ShortArray(pixelCount)
    private var weightCount: ByteArray? = ByteArray(pixelCount)
    private var refLumaByte: ByteArray? = ByteArray(pixelCount)
    private var refAlignLuma: FloatArray? = FloatArray(alignW * alignH)
    private var refAlignMean: Float = 0.5f

    private val shifts = mutableListOf<Pair<Int, Int>>()
    var framesMerged: Int = 0
        private set

    var encounteredLowMemory: Boolean = false
        private set

    val alignmentShifts: List<Pair<Int, Int>>
        get() = shifts.toList()

    /**
     * Merges a single captured [bitmap] into the running accumulator.
     *
     * When [recycleBitmap] is true (default for live streaming capture), [bitmap] is recycled in a
     * `finally` block immediately after its strips are merged.
     *
     * @return `true` if the frame was merged; `false` if skipped (dimension mismatch or low memory).
     */
    fun addFrame(bitmap: Bitmap, recycleBitmap: Boolean = true): Boolean {
        val sR = sumR ?: return false
        val sG = sumG ?: return false
        val sB = sumB ?: return false
        val wC = weightCount ?: return false
        val rLuma = refLumaByte ?: return false
        val refSmall = refAlignLuma ?: return false

        try {
            if (bitmap.isRecycled || bitmap.width != width || bitmap.height != height) {
                return false
            }

            if (framesMerged == 0) {
                ingestReferenceFrame(bitmap, sR, sG, sB, wC, rLuma, refSmall)
                shifts.add(0 to 0)
                framesMerged = 1
                return true
            }

            if (framesMerged >= MAX_ACCUMULATOR_FRAMES) {
                return false
            }

            // 1. Build reduced-resolution luminance buffer for low-memory alignment
            val targetSmall = FloatArray(alignW * alignH)
            val targetMean = extractDownsampledLuma(bitmap, alignScale, alignW, alignH, targetSmall)

            // 2. Coarse shift estimation at reduced resolution
            val coarseMaxShift = (MAX_FULL_RES_SHIFT / alignScale).coerceAtLeast(2)
            val (coarseDx, coarseDy) = NightSightProcessor.estimateTranslationShift(
                refLuma = refSmall,
                refMean = refAlignMean,
                targetLuma = targetSmall,
                targetMean = targetMean,
                width = alignW,
                height = alignH,
                maxShift = coarseMaxShift
            )

            var bestDx = (coarseDx * alignScale).coerceIn(-MAX_FULL_RES_SHIFT, MAX_FULL_RES_SHIFT)
            var bestDy = (coarseDy * alignScale).coerceIn(-MAX_FULL_RES_SHIFT, MAX_FULL_RES_SHIFT)

            // 3. When downsampled, refine ±(alignScale - 1) px on a small center patch for ±1px accuracy
            if (alignScale > 1 && width >= 64 && height >= 64) {
                val (refinedDx, refinedDy) = refineShiftOnCenterPatch(
                    bitmap = bitmap,
                    refLumaByte = rLuma,
                    coarseDx = bestDx,
                    coarseDy = bestDy,
                    refineRadius = alignScale
                )
                bestDx = refinedDx
                bestDy = refinedDy
            }

            shifts.add(bestDx to bestDy)

            // 4. Stream-merge the aligned frame into the running sum in bounded horizontal strips
            val stripBuffer = IntArray(width * min(STRIP_HEIGHT, height))
            val motionThresholdByte = (motionThreshold * 255f).roundToInt().coerceIn(8, 255)

            var yStart = 0
            while (yStart < height) {
                val stripRows = min(STRIP_HEIGHT, height - yStart)
                // Determine source Y range needed for destination rows [yStart, yStart + stripRows)
                val minSecY = (yStart + bestDy).coerceIn(0, height - 1)
                val maxSecY = (yStart + stripRows - 1 + bestDy).coerceIn(0, height - 1)
                val secReadHeight = (maxSecY - minSecY + 1).coerceIn(1, stripBuffer.size / width.coerceAtLeast(1))

                bitmap.getPixels(stripBuffer, 0, width, 0, minSecY, width, secReadHeight)

                for (localY in 0 until stripRows) {
                    val y = yStart + localY
                    val secY = y + bestDy
                    if (secY !in minSecY until (minSecY + secReadHeight)) continue
                    val refRow = y * width
                    val bufRow = (secY - minSecY) * width

                    for (x in 0 until width) {
                        val secX = x + bestDx
                        if (secX !in 0 until width) continue
                        val refIdx = refRow + x
                        val curW = wC[refIdx].toInt() and 0xFF
                        if (curW >= 120) continue

                        val sPix = stripBuffer[bufRow + secX]
                        val sr = (sPix shr 16) and 0xFF
                        val sg = (sPix shr 8) and 0xFF
                        val sb = sPix and 0xFF
                        val secLumaB = ((77 * sr + 150 * sg + 29 * sb) shr 8)
                        val refLumaB = rLuma[refIdx].toInt() and 0xFF

                        if (abs(refLumaB - secLumaB) <= motionThresholdByte) {
                            sR[refIdx] = (sR[refIdx].toInt() + sr).toShort()
                            sG[refIdx] = (sG[refIdx].toInt() + sg).toShort()
                            sB[refIdx] = (sB[refIdx].toInt() + sb).toShort()
                            wC[refIdx] = (curW + 1).toByte()
                        }
                    }
                }
                yStart += stripRows
            }

            framesMerged++
            return true
        } catch (oom: OutOfMemoryError) {
            encounteredLowMemory = true
            Log.w(TAG, "Low memory while streaming Night Sight frame ${framesMerged + 1}; keeping $framesMerged merged frames", oom)
            return false
        } finally {
            if (recycleBitmap && !bitmap.isRecycled) {
                try {
                    bitmap.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Finalizes the accumulated running sum into a tone-mapped, chroma-denoised [Bitmap]
     * using strip/tile processing so peak RAM stays low even on full-size captures.
     *
     * Releases internal accumulator buffers as soon as they are no longer needed.
     */
    fun finalizeToBitmap(
        exposure: NightSightExposure,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap {
        val sR = checkNotNull(sumR) { "Accumulator already closed or empty" }
        val sG = checkNotNull(sumG) { "Accumulator already closed or empty" }
        val sB = checkNotNull(sumB) { "Accumulator already closed or empty" }
        val wC = checkNotNull(weightCount) { "Accumulator already closed or empty" }

        // Release alignment buffers immediately before allocating the output bitmap
        refLumaByte = null
        refAlignLuma = null

        onProgress?.invoke(0.10f)

        // 1. Estimate global mean luminance by subsampled pass over accumulator (0 bytes allocated)
        val sampleStepY = max(1, height / 240)
        val sampleStepX = max(1, width / 240)
        var sumLuma = 0.0
        var sampleCount = 0
        var sy = 0
        while (sy < height) {
            val row = sy * width
            var sx = 0
            while (sx < width) {
                val idx = row + sx
                val w = (wC[idx].toInt() and 0xFF).coerceAtLeast(1)
                val r = (sR[idx].toInt() / w).coerceIn(0, 255) / 255f
                val g = (sG[idx].toInt() / w).coerceIn(0, 255) / 255f
                val b = (sB[idx].toInt() / w).coerceIn(0, 255) / 255f
                sumLuma += (0.299f * r + 0.587f * g + 0.114f * b)
                sampleCount++
                sx += sampleStepX
            }
            sy += sampleStepY
        }
        val meanLuma = (sumLuma / sampleCount.coerceAtLeast(1)).toFloat()

        val exposureMultiplier = when (exposure) {
            NightSightExposure.MAX -> 1.35f
            NightSightExposure.AUTO -> 1.0f
            NightSightExposure.OFF -> 0.0f
        }
        val targetShadowPivot = 0.38f
        val shadowLift = if (meanLuma < targetShadowPivot) {
            ((targetShadowPivot - meanLuma) / targetShadowPivot) * exposureMultiplier
        } else {
            0.0f
        }

        // 2. Allocate single output Bitmap and process in overlapping horizontal strips
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val chromaStep = max(1, min(width, height) / 300)
        val overlap = chromaStep
        val maxBufRows = min(height, STRIP_HEIGHT + overlap * 2)
        val stripLuma = FloatArray(width * maxBufRows)
        val stripCb = FloatArray(width * maxBufRows)
        val stripCr = FloatArray(width * maxBufRows)
        val stripOutPixels = IntArray(width * min(STRIP_HEIGHT, height))

        var yStart = 0
        while (yStart < height) {
            val coreRows = min(STRIP_HEIGHT, height - yStart)
            val extTop = (yStart - overlap).coerceAtLeast(0)
            val extBot = (yStart + coreRows - 1 + overlap).coerceAtMost(height - 1)
            val extRows = extBot - extTop + 1

            // Convert averaged accumulator values in [extTop..extBot] into YCbCr strip buffers
            for (ly in 0 until extRows) {
                val globalY = extTop + ly
                val srcRow = globalY * width
                val dstRow = ly * width
                for (x in 0 until width) {
                    val srcIdx = srcRow + x
                    val dstIdx = dstRow + x
                    val w = (wC[srcIdx].toInt() and 0xFF).coerceAtLeast(1)
                    val r = (sR[srcIdx].toInt() / w).coerceIn(0, 255) / 255f
                    val g = (sG[srcIdx].toInt() / w).coerceIn(0, 255) / 255f
                    val b = (sB[srcIdx].toInt() / w).coerceIn(0, 255) / 255f

                    stripLuma[dstIdx] = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                    stripCb[dstIdx] = (-0.168736f * r - 0.331264f * g + 0.5f * b).coerceIn(-0.5f, 0.5f)
                    stripCr[dstIdx] = (0.5f * r - 0.418688f * g - 0.081312f * b).coerceIn(-0.5f, 0.5f)
                }
            }

            // Shadow chroma denoising + highlight-preserving tone mapping on core rows
            for (coreY in 0 until coreRows) {
                val ly = (yStart - extTop) + coreY
                val localRow = ly * width
                val outRow = coreY * width

                for (x in 0 until width) {
                    val idx = localRow + x
                    val yOrig = stripLuma[idx]
                    var cb = stripCb[idx]
                    var cr = stripCr[idx]

                    if (yOrig < 0.35f) {
                        var sumCb = 0f
                        var sumCr = 0f
                        var count = 0
                        var dy = -chromaStep
                        while (dy <= chromaStep) {
                            val ny = ly + dy
                            if (ny in 0 until extRows) {
                                val nRow = ny * width
                                var dx = -chromaStep
                                while (dx <= chromaStep) {
                                    val nx = x + dx
                                    if (nx in 0 until width) {
                                        val nIdx = nRow + nx
                                        sumCb += stripCb[nIdx]
                                        sumCr += stripCr[nIdx]
                                        count++
                                    }
                                    dx += chromaStep
                                }
                            }
                            dy += chromaStep
                        }
                        val weight = (1.0f - (yOrig / 0.35f)).coerceIn(0f, 0.85f)
                        cb = cb * (1f - weight) + (sumCb / count.coerceAtLeast(1)) * weight
                        cr = cr * (1f - weight) + (sumCr / count.coerceAtLeast(1)) * weight
                    }

                    val shadowWeight = (1.0f - yOrig).coerceIn(0f, 1f)
                    val kneeTerm = yOrig * shadowWeight * shadowWeight * 1.6f
                    val yBoosted = (yOrig + shadowLift * kneeTerm).coerceIn(0f, 1f)

                    val colorVibrance = 1.0f + 0.15f * shadowLift * shadowWeight
                    val cbAdj = cb * colorVibrance
                    val crAdj = cr * colorVibrance

                    val r = ((yBoosted + 1.402f * crAdj) * 255f).roundToInt().coerceIn(0, 255)
                    val g = ((yBoosted - 0.344136f * cbAdj - 0.714136f * crAdj) * 255f).roundToInt().coerceIn(0, 255)
                    val b = ((yBoosted + 1.772f * cbAdj) * 255f).roundToInt().coerceIn(0, 255)

                    stripOutPixels[outRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            outputBitmap.setPixels(stripOutPixels, 0, width, 0, yStart, width, coreRows)
            yStart += coreRows
            onProgress?.invoke(0.10f + 0.80f * (yStart.toFloat() / height.coerceAtLeast(1)))
        }

        // Release large accumulator arrays before JPEG compression
        close()
        onProgress?.invoke(0.95f)
        return outputBitmap
    }

    override fun close() {
        sumR = null
        sumG = null
        sumB = null
        weightCount = null
        refLumaByte = null
        refAlignLuma = null
    }

    private fun ingestReferenceFrame(
        bitmap: Bitmap,
        sR: ShortArray,
        sG: ShortArray,
        sB: ShortArray,
        wC: ByteArray,
        rLuma: ByteArray,
        refSmall: FloatArray
    ) {
        val stripBuffer = IntArray(width * min(STRIP_HEIGHT, height))
        var yStart = 0
        while (yStart < height) {
            val stripRows = min(STRIP_HEIGHT, height - yStart)
            bitmap.getPixels(stripBuffer, 0, width, 0, yStart, width, stripRows)
            for (localY in 0 until stripRows) {
                val y = yStart + localY
                val dstRow = y * width
                val bufRow = localY * width
                for (x in 0 until width) {
                    val pix = stripBuffer[bufRow + x]
                    val r = (pix shr 16) and 0xFF
                    val g = (pix shr 8) and 0xFF
                    val b = pix and 0xFF
                    val idx = dstRow + x
                    sR[idx] = r.toShort()
                    sG[idx] = g.toShort()
                    sB[idx] = b.toShort()
                    wC[idx] = 1
                    rLuma[idx] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
                }
            }
            yStart += stripRows
        }

        refAlignMean = extractDownsampledLuma(bitmap, alignScale, alignW, alignH, refSmall)
    }

    private fun extractDownsampledLuma(
        bitmap: Bitmap,
        scale: Int,
        dstW: Int,
        dstH: Int,
        outLuma: FloatArray
    ): Float {
        val rowBuf = IntArray(width)
        var sum = 0.0
        for (ay in 0 until dstH) {
            val srcY = (ay * scale).coerceIn(0, height - 1)
            bitmap.getPixels(rowBuf, 0, width, 0, srcY, width, 1)
            val dstRow = ay * dstW
            for (ax in 0 until dstW) {
                val srcX = (ax * scale).coerceIn(0, width - 1)
                val c = rowBuf[srcX]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                outLuma[dstRow + ax] = luma
                sum += luma
            }
        }
        return (sum / (dstW * dstH).coerceAtLeast(1)).toFloat()
    }

    /**
     * Refines a coarse reduced-resolution shift to full-resolution integer pixel accuracy
     * using a compact center crop (~192x192 px) so memory overhead is negligible.
     */
    private fun refineShiftOnCenterPatch(
        bitmap: Bitmap,
        refLumaByte: ByteArray,
        coarseDx: Int,
        coarseDy: Int,
        refineRadius: Int
    ): Pair<Int, Int> {
        val patchW = min(192, width - MAX_FULL_RES_SHIFT * 2 - 4)
        val patchH = min(192, height - MAX_FULL_RES_SHIFT * 2 - 4)
        if (patchW < 24 || patchH < 24) return coarseDx to coarseDy

        val startX = (width - patchW) / 2
        val startY = (height - patchH) / 2
        val pad = refineRadius + 1
        val readX = (startX + coarseDx - pad).coerceIn(0, width - 1)
        val readY = (startY + coarseDy - pad).coerceIn(0, height - 1)
        val readEndX = (startX + patchW + coarseDx + pad).coerceIn(readX + 1, width)
        val readEndY = (startY + patchH + coarseDy + pad).coerceIn(readY + 1, height)
        val readW = readEndX - readX
        val readH = readEndY - readY
        if (readW <= 0 || readH <= 0) return coarseDx to coarseDy

        val patchPixels = IntArray(readW * readH)
        bitmap.getPixels(patchPixels, 0, readW, readX, readY, readW, readH)

        var bestDx = coarseDx
        var bestDy = coarseDy
        var bestErr = Float.MAX_VALUE
        val step = 2

        for (ddy in -refineRadius..refineRadius) {
            val candDy = (coarseDy + ddy).coerceIn(-MAX_FULL_RES_SHIFT, MAX_FULL_RES_SHIFT)
            for (ddx in -refineRadius..refineRadius) {
                val candDx = (coarseDx + ddx).coerceIn(-MAX_FULL_RES_SHIFT, MAX_FULL_RES_SHIFT)
                var err = 0f
                var count = 0
                var py = 0
                while (py < patchH) {
                    val refY = startY + py
                    val tarY = refY + candDy - readY
                    if (tarY in 0 until readH) {
                        val refRow = refY * width
                        val tarRow = tarY * readW
                        var px = 0
                        while (px < patchW) {
                            val refX = startX + px
                            val tarX = refX + candDx - readX
                            if (tarX in 0 until readW) {
                                val rL = (refLumaByte[refRow + refX].toInt() and 0xFF) / 255f
                                if (rL in 0.04f..0.92f) {
                                    val c = patchPixels[tarRow + tarX]
                                    val sr = (c shr 16) and 0xFF
                                    val sg = (c shr 8) and 0xFF
                                    val sb = c and 0xFF
                                    val tL = (0.299f * sr + 0.587f * sg + 0.114f * sb) / 255f
                                    err += abs(rL - tL)
                                    count++
                                }
                            }
                            px += step
                        }
                    }
                    py += step
                }
                val avgErr = if (count > 0) err / count else Float.MAX_VALUE
                val tieBias = (abs(candDx) + abs(candDy)) * 1e-4f
                if (avgErr + tieBias < bestErr) {
                    bestErr = avgErr + tieBias
                    bestDx = candDx
                    bestDy = candDy
                }
            }
        }
        return bestDx to bestDy
    }

    companion object {
        private const val TAG = "NightSightAccumulator"
        private const val STRIP_HEIGHT = 128
        private const val MAX_FULL_RES_SHIFT = 16
        private const val MAX_ACCUMULATOR_FRAMES = 12

        internal fun computeAlignmentDownsampleFactor(width: Int, height: Int): Int {
            val maxDim = max(width, height)
            return when {
                maxDim >= 800 -> 4
                maxDim >= 400 -> 2
                else -> 1
            }
        }
    }
}

/**
 * Genuine low-light computational photography processor for Night Sight.
 *
 * Implements:
 * 1. Streaming multi-frame accumulation (`NightSightStreamingAccumulator`) — never retains all
 *    burst frames in memory simultaneously.
 * 2. Reduced-resolution (quarter-size) translation alignment with center-patch refinement.
 * 3. Temporal averaging across static pixels with motion-based ghost rejection.
 * 4. Strip-based shadow-lifting adaptive tone mapping with highlight knee protection and
 *    edge-preserving chrominance noise suppression.
 * 5. OOM-safe graceful degradation: if memory gets low, finalizes and saves whatever frames
 *    were already merged instead of crashing and losing the shot.
 */
object NightSightProcessor {

    private const val TAG = "NightSightProcessor"

    /**
     * Finalizes a live [NightSightStreamingAccumulator] into an oriented, aspect-cropped JPEG
     * payload ready to save to `DCIM/Camera`.
     */
    fun finalizeStreamingAccumulator(
        accumulator: NightSightStreamingAccumulator,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean = false,
        exposure: NightSightExposure = NightSightExposure.AUTO,
        supportPath: NightSightSupportPath = NightSightSupportPath.MULTI_FRAME_LOW_LIGHT_FUSION,
        jpegQuality: Int = 95,
        startTimeMs: Long = System.currentTimeMillis(),
        onProcessingProgress: ((Float) -> Unit)? = null
    ): NightSightProcessingOutcome.ProcessedNightSight {
        val framesMerged = accumulator.framesMerged.coerceAtLeast(1)
        val shifts = accumulator.alignmentShifts
        val wasPartialLowMem = accumulator.encounteredLowMemory

        val enhancedBitmap = accumulator.finalizeToBitmap(
            exposure = exposure,
            onProgress = { p -> onProcessingProgress?.invoke((p * 0.80f).coerceIn(0f, 0.80f)) }
        )

        var orientedBitmap: Bitmap? = null
        try {
            orientedBitmap = applyCropAndOrientation(
                bitmap = enhancedBitmap,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally
            )
            if (enhancedBitmap != orientedBitmap && !enhancedBitmap.isRecycled) {
                enhancedBitmap.recycle()
            }
            onProcessingProgress?.invoke(0.90f)

            val outStream = ByteArrayOutputStream()
            orientedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(80, 100), outStream)
            val outputBytes = outStream.toByteArray()
            val outW = orientedBitmap.width
            val outH = orientedBitmap.height
            onProcessingProgress?.invoke(1.0f)

            val effectivePath = if (framesMerged >= 2) {
                NightSightSupportPath.MULTI_FRAME_LOW_LIGHT_FUSION
            } else {
                supportPath
            }

            return NightSightProcessingOutcome.ProcessedNightSight(
                jpegBytes = outputBytes,
                width = outW,
                height = outH,
                exifOrientation = 1,
                framesMerged = framesMerged,
                durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L),
                supportPath = effectivePath,
                alignmentShifts = shifts,
                wasPartialLowMemoryMerge = wasPartialLowMem
            )
        } finally {
            if (orientedBitmap != null && !orientedBitmap.isRecycled) {
                try { orientedBitmap.recycle() } catch (_: Exception) {}
            }
            if (!enhancedBitmap.isRecycled) {
                try { enhancedBitmap.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Processes primary captured bytes and any companion burst frames into a low-noise,
     * high-dynamic-range low-light Night Sight photograph using the streaming accumulator so
     * at most ONE decoded [Bitmap] resides in memory at a time.
     */
    fun processNightSightJpegBytes(
        sourceBytes: ByteArray,
        additionalFramesBytes: List<ByteArray> = emptyList(),
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean = false,
        exposure: NightSightExposure = NightSightExposure.AUTO,
        supportPath: NightSightSupportPath = NightSightSupportPath.MULTI_FRAME_LOW_LIGHT_FUSION,
        jpegQuality: Int = 95,
        onProcessingProgress: ((Float) -> Unit)? = null
    ): NightSightProcessingOutcome {
        val startTime = System.currentTimeMillis()

        // 1. If Night Sight exposure is explicitly OFF, use standard fast single-frame processing
        if (exposure == NightSightExposure.OFF) {
            val fallbackPayload = AspectCropUtils.processCapturedJpegBytes(
                sourceBytes = sourceBytes,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                jpegQuality = jpegQuality
            )
            return if (fallbackPayload != null) {
                NightSightProcessingOutcome.FallbackSingleFrame(
                    jpegBytes = fallbackPayload.jpegBytes,
                    width = fallbackPayload.width,
                    height = fallbackPayload.height,
                    exifOrientation = fallbackPayload.exifOrientation,
                    reason = "Night Sight exposure mode is OFF"
                )
            } else {
                NightSightProcessingOutcome.FallbackSingleFrame(
                    jpegBytes = sourceBytes,
                    width = 0,
                    height = 0,
                    exifOrientation = 1,
                    reason = "Failed to decode frame with exposure OFF"
                )
            }
        }

        var accumulator: NightSightStreamingAccumulator? = null
        return try {
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val primaryBitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOptions)
                ?: return fallbackToStandardProcessing(
                    sourceBytes = sourceBytes,
                    rotationDegrees = rotationDegrees,
                    aspectRatioMode = aspectRatioMode,
                    mirrorHorizontally = mirrorHorizontally,
                    jpegQuality = jpegQuality,
                    reason = "Primary frame JPEG decoding returned null"
                )

            val width = primaryBitmap.width
            val height = primaryBitmap.height
            val acc = NightSightStreamingAccumulator(width = width, height = height)
            accumulator = acc

            // Stream primary frame into accumulator and immediately recycle primaryBitmap
            acc.addFrame(primaryBitmap, recycleBitmap = true)

            // Stream each additional frame one by one, recycling immediately after merge
            val maxAllowedFrames = NightSightDeviceSupport.getMaxSupportedFrames()
            val framesToDecode = additionalFramesBytes.take((maxAllowedFrames - 1).coerceAtLeast(0))
            for ((index, extraBytes) in framesToDecode.withIndex()) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(extraBytes, 0, extraBytes.size, decodeOptions)
                    if (bmp != null) {
                        val merged = acc.addFrame(bmp, recycleBitmap = true)
                        if (!merged && acc.encounteredLowMemory) {
                            break
                        }
                    }
                    onProcessingProgress?.invoke(0.35f * ((index + 1).toFloat() / framesToDecode.size.coerceAtLeast(1)))
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "Low memory decoding additional Night Sight frame; finalizing ${acc.framesMerged} merged frames", oom)
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Failed decoding additional frame", e)
                }
            }

            finalizeStreamingAccumulator(
                accumulator = acc,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                exposure = exposure,
                supportPath = supportPath,
                jpegQuality = jpegQuality,
                startTimeMs = startTime,
                onProcessingProgress = { p -> onProcessingProgress?.invoke(0.35f + 0.65f * p) }
            )
        } catch (oom: OutOfMemoryError) {
            accumulator?.close()
            Log.e(TAG, "OutOfMemoryError in NightSightProcessor; falling back to safe single frame", oom)
            fallbackToStandardProcessing(
                sourceBytes = sourceBytes,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                jpegQuality = jpegQuality,
                reason = "OutOfMemoryError during low-light processing"
            )
        } catch (exc: Exception) {
            accumulator?.close()
            Log.e(TAG, "Exception in NightSightProcessor; falling back to safe single frame", exc)
            fallbackToStandardProcessing(
                sourceBytes = sourceBytes,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                jpegQuality = jpegQuality,
                reason = "Processing failure: ${exc.localizedMessage}"
            )
        }
    }

    /**
     * Fuses multi-frame bracketed/burst low-light frames via the streaming accumulator
     * (does not recycle caller-owned input [frames]).
     */
    fun fuseAndEnhanceMultiFrame(
        frames: List<Bitmap>,
        exposure: NightSightExposure
    ): Pair<Bitmap, List<Pair<Int, Int>>> {
        require(frames.isNotEmpty()) { "Frames list must not be empty" }
        val ref = frames[0]
        val accumulator = NightSightStreamingAccumulator(width = ref.width, height = ref.height)
        return try {
            for (frame in frames) {
                accumulator.addFrame(frame, recycleBitmap = false)
            }
            val shifts = accumulator.alignmentShifts
            val outputBitmap = accumulator.finalizeToBitmap(exposure = exposure)
            outputBitmap to shifts
        } finally {
            accumulator.close()
        }
    }

    /**
     * Enhances a single low-light frame using strip-based adaptive shadow lifting with
     * highlight knee protection and chrominance noise filtering.
     */
    fun enhanceSingleFrameLowLight(
        source: Bitmap,
        exposure: NightSightExposure
    ): Bitmap {
        val accumulator = NightSightStreamingAccumulator(width = source.width, height = source.height)
        return try {
            accumulator.addFrame(source, recycleBitmap = false)
            accumulator.finalizeToBitmap(exposure = exposure)
        } finally {
            accumulator.close()
        }
    }

    /**
     * Estimates integer pixel translation shift `(dx, dy)` that aligns [targetLuma] onto [refLuma].
     */
    internal fun estimateTranslationShift(
        refLuma: FloatArray,
        refMean: Float,
        targetLuma: FloatArray,
        targetMean: Float,
        width: Int,
        height: Int,
        maxShift: Int
    ): Pair<Int, Int> {
        if (maxShift <= 0 || width < 16 || height < 16) return 0 to 0
        val marginX = maxOf(maxShift + 2, width / 6)
        val marginY = maxOf(maxShift + 2, height / 6)
        val step = maxOf(1, minOf(width, height) / 48)

        val normFactor = refMean / targetMean.coerceAtLeast(0.01f)
        var bestDx = 0
        var bestDy = 0
        var bestError = Float.MAX_VALUE

        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var err = 0f
                var count = 0
                var y = marginY
                while (y < height - marginY) {
                    val refRow = y * width
                    val tarRow = (y + dy) * width
                    var x = marginX
                    while (x < width - marginX) {
                        val rVal = refLuma[refRow + x]
                        if (rVal in 0.04f..0.92f) {
                            val tVal = (targetLuma[tarRow + x + dx] * normFactor).coerceIn(0f, 1f)
                            err += abs(rVal - tVal)
                            count++
                        }
                        x += step
                    }
                    y += step
                }
                val avgErr = if (count > 0) err / count else Float.MAX_VALUE
                val tieBias = (abs(dx) + abs(dy)) * 1e-4f
                if (avgErr + tieBias < bestError) {
                    bestError = avgErr + tieBias
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    /**
     * Crops and reorients [bitmap] according to [aspectRatioMode], [rotationDegrees], and [mirrorHorizontally].
     */
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
                if (current != bitmap && !current.isRecycled) {
                    current.recycle()
                }
                current = cropped
            }
        }

        return current
    }

    private fun fallbackToStandardProcessing(
        sourceBytes: ByteArray,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean,
        jpegQuality: Int,
        reason: String
    ): NightSightProcessingOutcome {
        val payload = AspectCropUtils.processCapturedJpegBytes(
            sourceBytes = sourceBytes,
            rotationDegrees = rotationDegrees,
            aspectRatioMode = aspectRatioMode,
            mirrorHorizontally = mirrorHorizontally,
            jpegQuality = jpegQuality
        )
        return if (payload != null) {
            NightSightProcessingOutcome.FallbackSingleFrame(
                jpegBytes = payload.jpegBytes,
                width = payload.width,
                height = payload.height,
                exifOrientation = payload.exifOrientation,
                reason = reason
            )
        } else {
            NightSightProcessingOutcome.FallbackSingleFrame(
                jpegBytes = sourceBytes,
                width = 0,
                height = 0,
                exifOrientation = 1,
                reason = "$reason (Fallback decode failed)"
            )
        }
    }
}
