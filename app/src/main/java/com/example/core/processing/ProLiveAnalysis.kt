package com.example.core.processing

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Normalized 256-bin luminance histogram data computed in real-time from camera preview frames.
 */
data class LiveHistogramData(
    val normalizedBins: FloatArray, // 256 normalized values 0.0f..1.0f
    val isShadowClipping: Boolean = false,
    val isHighlightClipping: Boolean = false,
    val averageLuminance: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LiveHistogramData
        return normalizedBins.contentEquals(other.normalizedBins) &&
            isShadowClipping == other.isShadowClipping &&
            isHighlightClipping == other.isHighlightClipping &&
            averageLuminance == other.averageLuminance
    }

    override fun hashCode(): Int {
        var result = normalizedBins.contentHashCode()
        result = 31 * result + isShadowClipping.hashCode()
        result = 31 * result + isHighlightClipping.hashCode()
        result = 31 * result + averageLuminance.hashCode()
        return result
    }
}

/**
 * Focus-peaking edge overlay frame generated from real-time Sobel spatial gradient analysis.
 */
data class FocusPeakingFrame(
    val bitmap: Bitmap,
    val timestamp: Long = SystemClock.uptimeMillis()
)

/**
 * Ultra-lightweight CameraX ImageAnalysis analyzer for Pro camera mode.
 * Computes:
 * 1. Real-time 256-bin luminance histogram (Y-channel distribution).
 * 2. Real-time focus peaking highlights using a 3x3 Sobel gradient filter on downscaled preview luminance.
 *
 * Guarantees:
 * - Always closes [ImageProxy] in a finally block to prevent CameraX buffer starvation.
 * - Throttles execution rate to ~16 FPS (60ms interval) to consume < 2% CPU and avoid preview stutter.
 * - Double-buffers native bitmaps to eliminate garbage collection pressure and memory allocations per frame.
 * - Only runs when in Pro mode and either histogram or focus peaking is enabled.
 */
class ProImageAnalyzer(
    private val onHistogramComputed: (LiveHistogramData?) -> Unit,
    private val onFocusPeakingComputed: (FocusPeakingFrame?) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "ProImageAnalyzer"
        private const val ANALYSIS_INTERVAL_MS = 60L // ~16.6 FPS throttle target
        private const val TARGET_GRID_WIDTH = 160
        private const val SOBEL_EDGE_THRESHOLD = 130
        private const val PEAKING_COLOR_ARGB = 0xDD00E676.toInt() // Vibrant neon green with ~87% alpha
    }

    @Volatile
    var isHistogramEnabled: Boolean = false

    @Volatile
    var isFocusPeakingEnabled: Boolean = false

    @Volatile
    var isFrontCamera: Boolean = false

    @Volatile
    var isSelfieMirrorEnabled: Boolean = true

    private var lastAnalyzedTimestampMs = 0L

    // Double-buffered bitmaps for zero GC allocation during focus peaking rendering
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var useBufferA = true

    // Reusable arrays to eliminate per-frame heap allocations
    private var pixelBuffer = IntArray(0)
    private var lumaGrid = Array(0) { IntArray(0) }
    private val rawBins = IntArray(256)

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzedTimestampMs < ANALYSIS_INTERVAL_MS) {
                return
            }

            val runHist = isHistogramEnabled
            val runPeak = isFocusPeakingEnabled

            if (!runHist && !runPeak) {
                return
            }

            lastAnalyzedTimestampMs = now

            val planeY = imageProxy.planes.getOrNull(0) ?: return
            val bufferY = planeY.buffer
            val rowStride = planeY.rowStride
            val pixelStride = planeY.pixelStride
            val imgWidth = imageProxy.width
            val imgHeight = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            if (imgWidth <= 0 || imgHeight <= 0) return

            // Compute uniform downscaled analysis dimensions
            val gridW = TARGET_GRID_WIDTH
            val gridH = ((gridW.toFloat() * imgHeight) / imgWidth).toInt().coerceIn(90, 160)

            // Ensure reusable luma grid allocation matches target dimensions
            if (lumaGrid.size != gridH || (lumaGrid.isNotEmpty() && lumaGrid[0].size != gridW)) {
                lumaGrid = Array(gridH) { IntArray(gridW) }
            }

            // Extract Y (luminance) into downscaled 2D grid
            val scaleX = imgWidth.toFloat() / gridW
            val scaleY = imgHeight.toFloat() / gridH

            for (y in 0 until gridH) {
                val srcY = (y * scaleY).toInt().coerceIn(0, imgHeight - 1)
                val rowOffset = srcY * rowStride
                for (x in 0 until gridW) {
                    val srcX = (x * scaleX).toInt().coerceIn(0, imgWidth - 1)
                    val offset = rowOffset + srcX * pixelStride
                    if (offset < bufferY.capacity()) {
                        lumaGrid[y][x] = bufferY.get(offset).toInt() and 0xFF
                    }
                }
            }

            // 1. Compute Live Luminance Histogram if enabled
            if (runHist) {
                rawBins.fill(0)
                var shadowCount = 0
                var highlightCount = 0
                var totalSum = 0L

                for (y in 0 until gridH) {
                    for (x in 0 until gridW) {
                        val v = lumaGrid[y][x]
                        rawBins[v]++
                        totalSum += v
                        if (v < 8) shadowCount++
                        if (v > 248) highlightCount++
                    }
                }

                var maxBin = 1
                for (b in rawBins) {
                    if (b > maxBin) maxBin = b
                }

                val normalized = FloatArray(256) { i ->
                    (rawBins[i].toFloat() / maxBin).coerceIn(0f, 1f)
                }
                val totalPixels = gridW * gridH
                val isShadowClipping = (shadowCount.toFloat() / totalPixels) > 0.04f
                val isHighlightClipping = (highlightCount.toFloat() / totalPixels) > 0.04f
                val avgLuma = (totalSum.toFloat() / totalPixels)

                onHistogramComputed(
                    LiveHistogramData(
                        normalizedBins = normalized,
                        isShadowClipping = isShadowClipping,
                        isHighlightClipping = isHighlightClipping,
                        averageLuminance = avgLuma
                    )
                )
            }

            // 2. Compute Real Focus Peaking via 3x3 Sobel Operator if enabled
            if (runPeak) {
                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                val outW = if (isRotated) gridH else gridW
                val outH = if (isRotated) gridW else gridH
                val totalOutPixels = outW * outH

                if (pixelBuffer.size != totalOutPixels) {
                    pixelBuffer = IntArray(totalOutPixels)
                } else {
                    pixelBuffer.fill(0)
                }

                val mirror = isFrontCamera && isSelfieMirrorEnabled

                for (y in 1 until gridH - 1) {
                    for (x in 1 until gridW - 1) {
                        // 3x3 Sobel Filter
                        val p00 = lumaGrid[y - 1][x - 1]
                        val p01 = lumaGrid[y - 1][x]
                        val p02 = lumaGrid[y - 1][x + 1]
                        val p10 = lumaGrid[y][x - 1]
                        val p12 = lumaGrid[y][x + 1]
                        val p20 = lumaGrid[y + 1][x - 1]
                        val p21 = lumaGrid[y + 1][x]
                        val p22 = lumaGrid[y + 1][x + 1]

                        val gx = (p02 + 2 * p12 + p22) - (p00 + 2 * p10 + p20)
                        val gy = (p20 + 2 * p21 + p22) - (p00 + 2 * p01 + p02)
                        val mag = abs(gx) + abs(gy)

                        if (mag >= SOBEL_EDGE_THRESHOLD) {
                            // Map (x, y) into viewfinder output coordinates based on sensor rotation & mirror
                            var outX: Int
                            var outY: Int

                            when (rotationDegrees) {
                                90 -> {
                                    outX = (gridH - 1) - y
                                    outY = x
                                    if (mirror) {
                                        outX = (outW - 1) - outX
                                    }
                                }
                                270 -> {
                                    outX = y
                                    outY = (gridW - 1) - x
                                    if (mirror) {
                                        outX = (outW - 1) - outX
                                    }
                                }
                                180 -> {
                                    outX = (gridW - 1) - x
                                    outY = (gridH - 1) - y
                                    if (mirror) {
                                        outX = (outW - 1) - outX
                                    }
                                }
                                else -> { // 0
                                    outX = x
                                    outY = y
                                    if (mirror) {
                                        outX = (outW - 1) - outX
                                    }
                                }
                            }

                            if (outX in 0 until outW && outY in 0 until outH) {
                                pixelBuffer[outY * outW + outX] = PEAKING_COLOR_ARGB
                            }
                        }
                    }
                }

                // Render into alternating double-buffered Bitmap
                val targetBmp = getDoubleBufferedBitmap(outW, outH, pixelBuffer)
                onFocusPeakingComputed(FocusPeakingFrame(targetBmp, SystemClock.uptimeMillis()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing preview frame for Pro mode assists", e)
        } finally {
            // CRITICAL: Always close ImageProxy promptly to prevent CameraX buffer starvation
            imageProxy.close()
        }
    }

    private fun getDoubleBufferedBitmap(w: Int, h: Int, pixels: IntArray): Bitmap {
        if (bufferA == null || bufferA?.width != w || bufferA?.height != h) {
            bufferA?.recycle()
            bufferB?.recycle()
            bufferA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bufferB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val target = if (useBufferA) bufferA!! else bufferB!!
        useBufferA = !useBufferA
        target.setPixels(pixels, 0, w, 0, 0, w, h)
        return target
    }

    fun release() {
        bufferA?.recycle()
        bufferB?.recycle()
        bufferA = null
        bufferB = null
    }
}
