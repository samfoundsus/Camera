package com.example.core.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Architectural contract for custom multi-frame processing pipelines (HDR exposure fusion, Night Sight, Super Res).
 */
data class FrameCaptureRequest(
    val frameCount: Int,
    val exposureCompensationSteps: List<Int>,
    val targetMode: String,
    val capturedFrames: List<Bitmap> = emptyList(),
    val evStep: Float = 1f / 3f
)

sealed interface ProcessingResult {
    data class Success(
        val outputBitmap: Bitmap,
        val processingDurationMs: Long,
        val frameAlignmentsPx: List<Pair<Int, Int>> = emptyList()
    ) : ProcessingResult
    data class Failure(val errorMessage: String, val throwable: Throwable? = null) : ProcessingResult
    data object InProgress : ProcessingResult
}

interface ImageProcessingPipeline {
    suspend fun processMultiFrame(request: FrameCaptureRequest): ProcessingResult
}

/**
 * Software multi-frame HDR processor implementing:
 * 1. Mean-normalized luminance translation alignment across bracketed exposures ([-EV, 0 EV, +EV]).
 * 2. Exposure-compensated motion detection and ghosting suppression against the 0 EV reference frame.
 * 3. Mertens-style multi-exposure fusion (well-exposedness, local contrast, saturation weights)
 *    with Reinhard highlight tone mapping.
 * 4. Guaranteed safe fallback to the normal 0 EV reference frame if any frame is invalid or fusion fails.
 */
class SoftwareHdrProcessor : ImageProcessingPipeline {

    override suspend fun processMultiFrame(request: FrameCaptureRequest): ProcessingResult {
        val frames = request.capturedFrames
        if (frames.size < 2) {
            return ProcessingResult.Failure("Software HDR requires at least 2 bracketed frames (got ${frames.size}).")
        }
        val evOffsets = if (request.exposureCompensationSteps.size == frames.size) {
            request.exposureCompensationSteps.map { it * request.evStep }
        } else {
            frames.indices.map { idx -> (idx - frames.size / 2).toFloat() }
        }
        return fuseExposureBracketedFrames(frames = frames, exposureEvOffsets = evOffsets)
    }

    /**
     * Fuses exposure-bracketed [frames] with corresponding [exposureEvOffsets] (e.g. `[-1.0f, 0.0f, +1.0f]`).
     * Never silently returns a partially processed bitmap; returns [ProcessingResult.Failure] if inputs are invalid.
     */
    fun fuseExposureBracketedFrames(
        frames: List<Bitmap>,
        exposureEvOffsets: List<Float>,
        maxSearchShiftPx: Int = 6,
        ghostingThreshold: Float = 0.22f
    ): ProcessingResult {
        val startTime = System.currentTimeMillis()
        if (frames.size < 2) {
            return ProcessingResult.Failure("At least 2 frames are required for multi-frame HDR fusion.")
        }
        if (frames.any { it.isRecycled || it.width <= 0 || it.height <= 0 }) {
            return ProcessingResult.Failure("One or more input frames are recycled or have invalid dimensions.")
        }

        val width = frames[0].width
        val height = frames[0].height
        if (frames.any { it.width != width || it.height != height }) {
            return ProcessingResult.Failure("All bracketed frames must have identical dimensions (${width}x${height}).")
        }

        return try {
            val safeEvOffsets = if (exposureEvOffsets.size == frames.size) {
                exposureEvOffsets
            } else {
                frames.indices.map { idx -> (idx - frames.size / 2).toFloat() }
            }

            // Pick the frame closest to 0.0 EV as the sharp spatial reference frame
            val refIndex = safeEvOffsets.indices.minByOrNull { abs(safeEvOffsets[it]) } ?: (frames.size / 2)
            val refEv = safeEvOffsets[refIndex]

            val frameCount = frames.size
            val pixelCount = width * height
            val argbBuffers = Array(frameCount) { idx ->
                IntArray(pixelCount).also { buf ->
                    frames[idx].getPixels(buf, 0, width, 0, 0, width, height)
                }
            }

            // Compute normalized luminance [0..1] per frame and mean luminance for alignment
            val lumaBuffers = Array(frameCount) { FloatArray(pixelCount) }
            val meanLuma = FloatArray(frameCount)
            for (f in 0 until frameCount) {
                val argb = argbBuffers[f]
                val luma = lumaBuffers[f]
                var sum = 0.0
                for (i in 0 until pixelCount) {
                    val c = argb[i]
                    val r = ((c shr 16) and 0xFF) / 255f
                    val g = ((c shr 8) and 0xFF) / 255f
                    val b = (c and 0xFF) / 255f
                    val y = 0.299f * r + 0.587f * g + 0.114f * b
                    luma[i] = y
                    sum += y
                }
                meanLuma[f] = (sum / pixelCount.coerceAtLeast(1)).toFloat().coerceAtLeast(0.01f)
            }

            // 1. Estimate 2D translation alignment (dx, dy) for each frame relative to refIndex
            val alignments = MutableList(frameCount) { 0 to 0 }
            val effectiveMaxShift = maxSearchShiftPx.coerceIn(0, minOf(width, height) / 4)
            if (effectiveMaxShift > 0) {
                for (f in 0 until frameCount) {
                    if (f == refIndex) continue
                    alignments[f] = estimateTranslationShift(
                        refLuma = lumaBuffers[refIndex],
                        refMean = meanLuma[refIndex],
                        targetLuma = lumaBuffers[f],
                        targetMean = meanLuma[f],
                        width = width,
                        height = height,
                        maxShift = effectiveMaxShift
                    )
                }
            }

            // 2. Mertens multi-exposure fusion with ghosting reduction and Reinhard tone mapping
            val outPixels = IntArray(pixelCount)
            val refLuma = lumaBuffers[refIndex]
            val sigmaSq2 = 2f * 0.20f * 0.20f

            // Precompute exposure gain ratio relative to reference frame for ghosting check
            val evGainToRef = FloatArray(frameCount) { f ->
                val deltaEv = safeEvOffsets[f] - refEv
                2.0.pow(deltaEv.toDouble()).toFloat().coerceIn(0.125f, 8.0f)
            }

            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    val idx = rowOffset + x
                    val yRef = refLuma[idx]

                    var accumR = 0f
                    var accumG = 0f
                    var accumB = 0f
                    var weightSum = 0f

                    for (f in 0 until frameCount) {
                        val (dx, dy) = alignments[f]
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sy = (y + dy).coerceIn(0, height - 1)
                        val sIdx = sy * width + sx

                        val c = argbBuffers[f][sIdx]
                        val r = ((c shr 16) and 0xFF) / 255f
                        val g = ((c shr 8) and 0xFF) / 255f
                        val b = (c and 0xFF) / 255f
                        val lum = lumaBuffers[f][sIdx]

                        // Well-exposedness weight: peaks at mid-tone 0.52
                        val diffMid = lum - 0.52f
                        val exposedWeight = exp(-(diffMid * diffMid) / sigmaSq2)

                        // Local contrast weight (4-neighbor Laplacian approximation)
                        val leftX = (sx - 1).coerceAtLeast(0)
                        val rightX = (sx + 1).coerceAtMost(width - 1)
                        val upY = (sy - 1).coerceAtLeast(0)
                        val downY = (sy + 1).coerceAtMost(height - 1)
                        val laplacian = abs(
                            4f * lum -
                                lumaBuffers[f][sy * width + leftX] -
                                lumaBuffers[f][sy * width + rightX] -
                                lumaBuffers[f][upY * width + sx] -
                                lumaBuffers[f][downY * width + sx]
                        )
                        val contrastWeight = 0.15f + laplacian

                        // Color saturation weight
                        val maxCh = max(r, max(g, b))
                        val minCh = min(r, min(g, b))
                        val satWeight = 0.15f + (maxCh - minCh)

                        // Ghosting rejection weight: compare exposure-normalized luma against reference frame
                        // (except when reference pixel itself is clipped black <0.04 or blown white >0.96)
                        var ghostWeight = 1.0f
                        if (f != refIndex && yRef in 0.05f..0.94f) {
                            val expectedLum = (yRef * evGainToRef[f]).coerceIn(0f, 1f)
                            val motionDiff = abs(lum - expectedLum)
                            if (motionDiff > ghostingThreshold) {
                                val excess = (motionDiff - ghostingThreshold) / ghostingThreshold.coerceAtLeast(0.05f)
                                ghostWeight = (1f - excess).coerceIn(0.02f, 1.0f)
                            }
                        }

                        val w = (exposedWeight * contrastWeight * satWeight * ghostWeight).coerceAtLeast(1e-4f)
                        accumR += r * w
                        accumG += g * w
                        accumB += b * w
                        weightSum += w
                    }

                    var fusedR = accumR / weightSum.coerceAtLeast(1e-5f)
                    var fusedG = accumG / weightSum.coerceAtLeast(1e-5f)
                    var fusedB = accumB / weightSum.coerceAtLeast(1e-5f)

                    // Subtle Reinhard highlight tone mapping + shadow lift to preserve dynamic range
                    val fusedLum = 0.299f * fusedR + 0.587f * fusedG + 0.114f * fusedB
                    if (fusedLum > 0.001f) {
                        val whitePointSq = 1.35f * 1.35f
                        val mappedLum = (fusedLum * (1f + fusedLum / whitePointSq)) / (1f + 0.85f * fusedLum)
                        val scale = (mappedLum / fusedLum).coerceIn(0.5f, 1.4f)
                        fusedR = (fusedR * scale).coerceIn(0f, 1f)
                        fusedG = (fusedG * scale).coerceIn(0f, 1f)
                        fusedB = (fusedB * scale).coerceIn(0f, 1f)
                    }

                    val outR = (fusedR * 255f).roundToInt().coerceIn(0, 255)
                    val outG = (fusedG * 255f).roundToInt().coerceIn(0, 255)
                    val outB = (fusedB * 255f).roundToInt().coerceIn(0, 255)
                    outPixels[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }

            val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            resultBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            ProcessingResult.Success(
                outputBitmap = resultBitmap,
                processingDurationMs = elapsed,
                frameAlignmentsPx = alignments
            )
        } catch (t: Throwable) {
            ProcessingResult.Failure("Software HDR fusion failed: ${t.localizedMessage}", t)
        }
    }

    /**
     * Executes multi-frame HDR fusion and guarantees a reliable normal-photo fallback:
     * if fusion fails for any reason, returns an intact copy of the normal reference frame.
     */
    fun fuseWithSafeFallback(
        frames: List<Bitmap>,
        exposureEvOffsets: List<Float>
    ): Bitmap {
        require(frames.isNotEmpty()) { "At least one frame must be provided." }
        val refIndex = if (exposureEvOffsets.size == frames.size) {
            exposureEvOffsets.indices.minByOrNull { abs(exposureEvOffsets[it]) } ?: (frames.size / 2)
        } else {
            frames.size / 2
        }.coerceIn(frames.indices)

        val result = fuseExposureBracketedFrames(frames, exposureEvOffsets)
        return when (result) {
            is ProcessingResult.Success -> result.outputBitmap
            else -> frames[refIndex].copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    /**
     * Estimates integer pixel shift `(dx, dy)` that aligns [targetLuma] onto [refLuma]
     * using mean-normalized L1 error over a central region of interest.
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
        if (maxShift <= 0 || width < 8 || height < 8) return 0 to 0
        val marginX = maxOf(maxShift + 1, width / 6)
        val marginY = maxOf(maxShift + 1, height / 6)
        val step = maxOf(1, minOf(width, height) / 64)

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
                        // Ignore saturated/clipped pixels during shift estimation
                        if (rVal in 0.05f..0.92f) {
                            val tVal = (targetLuma[tarRow + x + dx] * normFactor).coerceIn(0f, 1f)
                            err += abs(rVal - tVal)
                            count++
                        }
                        x += step
                    }
                    y += step
                }
                val avgErr = if (count > 0) err / count else Float.MAX_VALUE
                // Slight bias toward (0,0) when error is tied
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
}

