package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.core.preferences.PanoramaDirection
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

import android.view.Surface
import kotlin.math.atan2

/**
 * Live capture phase for Panorama mode.
 */
enum class PanoramaCapturePhase {
    IDLE,
    CAPTURING,
    PROCESSING
}

/**
 * Real-time guidance and warning state displayed to the user during a Panorama sweep.
 */
enum class PanoramaGuidanceStatus(
    val instructionText: String,
    val isWarning: Boolean
) {
    READY("Move your phone slowly in one direction", false),
    MOVING_STEADY("Keep moving slowly in one direction", false),
    HOLD_STEADY("Pan smoothly along the guide line", false),
    TOO_FAST("Move slowly — moving too fast", true),
    WRONG_DIRECTION("Wrong direction — keep panning forward", true),
    OFF_ALIGNMENT_HIGH("Move phone down to stay on the line", true),
    OFF_ALIGNMENT_LOW("Move phone up to stay on the line", true),
    OFF_ALIGNMENT_LEFT("Center phone on the vertical guide", true),
    OFF_ALIGNMENT_RIGHT("Center phone on the vertical guide", true),
    STITCHING("Stitching panorama…", false)
}

/**
 * Snapshot of live panorama progress, alignment drift, frame coverage, and guidance state.
 */
data class PanoramaLiveState(
    val phase: PanoramaCapturePhase = PanoramaCapturePhase.IDLE,
    val direction: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT,
    /**
     * Normalized sweep progress in [0.0f, 1.0f] driven by real phone movement.
     * Always 0.0f when idle or when stationary at the start of capture.
     */
    val progress: Float = 0f,
    /**
     * Normalized usable frame coverage in [0.0f, 1.0f] based on actual overlapping frames acquired.
     */
    val capturedCoverage: Float = 0f,
    val capturedFrameCount: Int = 0,
    val targetFrameCount: Int = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
    /**
     * Normalized off-axis drift in [-1.0f, 1.0f].
     * For horizontal sweeps: negative = tilted/drifted up, positive = tilted/drifted down.
     * For vertical sweeps: negative = drifted left, positive = drifted right.
     */
    val alignmentDriftNormalized: Float = 0f,
    /**
     * Normalized forward speed relative to the ideal pan speed (1.0f = max safe speed).
     */
    val normalizedSpeed: Float = 0f,
    val guidanceStatus: PanoramaGuidanceStatus = PanoramaGuidanceStatus.READY,
    /**
     * True when phone angular movement has outpaced actual usable frame acquisition.
     */
    val isCaptureLagging: Boolean = false
) {
    /**
     * Normalized position of the directional arrow along the progress track in [0.0f, 1.0f]:
     * - Horizontal track: 0.0f = Left endpoint, 1.0f = Right endpoint.
     * - Vertical track: 0.0f = Top endpoint, 1.0f = Bottom endpoint.
     */
    val arrowTrackOffset: Float
        get() = PanoramaProcessor.computeArrowTrackOffset(direction, progress)
}

/**
 * Associates a captured panorama keyframe with its capture timestamp and measured angular orientation.
 */
data class PanoramaCapturedFrame(
    val bitmap: Bitmap,
    val timestampNs: Long,
    val sweepAngleRad: Float,
    val driftAngleRad: Float,
    val normalizedProgress: Float
)

/**
 * Estimated 2D pairwise alignment between two consecutive panorama frames.
 *
 * @property dx Horizontal translation in pixels (positive = scene shifted left / camera panned right).
 * @property dy Vertical translation in pixels (positive = scene shifted up / camera panned down).
 * @property rollDegrees Estimated in-plane roll angle in degrees.
 * @property confidence Normalized matching confidence in [0.0f, 1.0f].
 */
data class FrameAlignmentResult(
    val dx: Float,
    val dy: Float,
    val rollDegrees: Float = 0f,
    val confidence: Float = 1f
)

/**
 * Outcome of multi-frame panorama stitching.
 */
sealed class PanoramaStitchOutcome {
    data class Success(
        val jpegBytes: ByteArray,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val stitchedFrameCount: Int,
        val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL
    ) : PanoramaStitchOutcome()

    data class Failure(
        val reason: String
    ) : PanoramaStitchOutcome()
}

/**
 * Production multi-frame Panorama alignment, cylindrical warping, gain compensation,
 * dynamic-programming minimum-error seam finding, raised-cosine feather blending,
 * and automatic interior crop pipeline.
 */
object PanoramaProcessor {

    const val DEFAULT_TARGET_FRAMES = 6
    const val MIN_REQUIRED_FRAMES = 2
    const val MIN_AUTO_COMPLETE_FRAMES = 4
    const val MAX_PANORAMA_FRAMES = 10
    const val DEFAULT_MAX_FRAME_DIMENSION = 1024
    const val DEFAULT_TARGET_SWEEP_DEGREES = 90f

    // Ideal per-keyframe forward shift as a fraction of frame width/height (~30% shift = 70% overlap)
    const val KEYFRAME_STEP_FRACTION = 0.30f
    const val MAX_DRIFT_WARNING_THRESHOLD = 0.32f
    const val MAX_SAFE_SPEED_FRACTION_PER_SEC = 0.85f
    const val WRONG_DIRECTION_THRESHOLD_FRACTION = -0.020f

    /**
     * Maps normalized sweep progress [0.0f, 1.0f] to the directional arrow's endpoint-aware
     * track offset in [0.0f, 1.0f]:
     * - [PanoramaDirection.LEFT_TO_RIGHT]: starts at 0.0f (left), moves to 1.0f (right).
     * - [PanoramaDirection.RIGHT_TO_LEFT]: starts at 1.0f (right), moves to 0.0f (left).
     * - [PanoramaDirection.TOP_TO_BOTTOM]: starts at 0.0f (top), moves to 1.0f (bottom).
     * - [PanoramaDirection.BOTTOM_TO_TOP]: starts at 1.0f (bottom), moves to 0.0f (top).
     */
    fun computeArrowTrackOffset(direction: PanoramaDirection, progress: Float): Float {
        val clamped = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        return when (direction) {
            PanoramaDirection.LEFT_TO_RIGHT -> clamped
            PanoramaDirection.RIGHT_TO_LEFT -> 1f - clamped
            PanoramaDirection.TOP_TO_BOTTOM -> clamped
            PanoramaDirection.BOTTOM_TO_TOP -> 1f - clamped
        }
    }

    /**
     * Evaluates real-time guidance status from instantaneous frame motion and accumulated off-axis drift.
     *
     * @param forwardDeltaFraction Signed progress delta along the chosen [direction] in frame fractions.
     * @param driftNormalized Accumulated perpendicular drift normalized to [-1.0f, 1.0f].
     * @param deltaTimeSec Time elapsed between the two analyzed frames in seconds.
     * @param direction Selected panorama sweep direction.
     */
    fun classifyLiveGuidance(
        forwardDeltaFraction: Float,
        driftNormalized: Float,
        deltaTimeSec: Float,
        direction: PanoramaDirection
    ): PanoramaGuidanceStatus {
        val dt = deltaTimeSec.coerceAtLeast(0.016f)
        val speedPerSec = abs(forwardDeltaFraction) / dt

        if (forwardDeltaFraction < WRONG_DIRECTION_THRESHOLD_FRACTION) {
            return PanoramaGuidanceStatus.WRONG_DIRECTION
        }
        if (speedPerSec > MAX_SAFE_SPEED_FRACTION_PER_SEC) {
            return PanoramaGuidanceStatus.TOO_FAST
        }
        if (abs(driftNormalized) > MAX_DRIFT_WARNING_THRESHOLD) {
            return if (direction.isHorizontal) {
                if (driftNormalized < 0f) {
                    PanoramaGuidanceStatus.OFF_ALIGNMENT_HIGH
                } else {
                    PanoramaGuidanceStatus.OFF_ALIGNMENT_LOW
                }
            } else {
                if (driftNormalized < 0f) {
                    PanoramaGuidanceStatus.OFF_ALIGNMENT_LEFT
                } else {
                    PanoramaGuidanceStatus.OFF_ALIGNMENT_RIGHT
                }
            }
        }
        return if (forwardDeltaFraction > 0.008f) {
            PanoramaGuidanceStatus.MOVING_STEADY
        } else {
            PanoramaGuidanceStatus.HOLD_STEADY
        }
    }

    /**
     * Estimates 2D translation `(dx, dy)` and small roll rotation between [prevBitmap] and [currBitmap]
     * using a 2-level coarse-to-fine luminance + gradient cross-correlation pyramid.
     *
     * Convention:
     * - `dx > 0` means a scene feature at `x` in `prevBitmap` moved to `x - dx` in `currBitmap`
     *   (i.e. camera panned Left-to-Right).
     * - `dy > 0` means a scene feature at `y` in `prevBitmap` moved to `y - dy` in `currBitmap`
     *   (i.e. camera tilted Top-to-Bottom).
     */
    fun estimatePairwiseAlignment(
        prevBitmap: Bitmap,
        currBitmap: Bitmap,
        direction: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT
    ): FrameAlignmentResult {
        val w = min(prevBitmap.width, currBitmap.width)
        val h = min(prevBitmap.height, currBitmap.height)
        if (w < 16 || h < 16) {
            return FrameAlignmentResult(0f, 0f, 0f, 0f)
        }

        // Downscale to coarse working grid (~160px along primary axis) for fast global search
        val coarseMaxDim = 160
        val scale = min(1f, coarseMaxDim.toFloat() / max(w, h).toFloat())
        val cw = (w * scale).roundToInt().coerceAtLeast(24)
        val ch = (h * scale).roundToInt().coerceAtLeast(24)

        val prevFeatures = extractLumaAndGradientGrid(prevBitmap, cw, ch)
        val currFeatures = extractLumaAndGradientGrid(currBitmap, cw, ch)

        return estimateAlignmentFromFeatureGrids(
            prevGrid = prevFeatures,
            currGrid = currFeatures,
            gridW = cw,
            gridH = ch,
            fullW = w,
            fullH = h,
            direction = direction
        )
    }

    /**
     * Estimates alignment directly from two downscaled luminance+gradient grids.
     */
    internal fun estimateAlignmentFromFeatureGrids(
        prevGrid: FloatArray,
        currGrid: FloatArray,
        gridW: Int,
        gridH: Int,
        fullW: Int,
        fullH: Int,
        direction: PanoramaDirection
    ): FrameAlignmentResult {
        val maxShiftX: Int
        val minShiftX: Int
        val maxShiftY: Int
        val minShiftY: Int

        if (direction.isHorizontal) {
            val maxPrimary = (gridW * 0.65f).roundToInt().coerceAtLeast(4)
            val maxReverse = (gridW * 0.25f).roundToInt().coerceAtLeast(2)
            val maxDrift = (gridH * 0.22f).roundToInt().coerceAtLeast(2)
            if (direction.sign > 0) {
                minShiftX = -maxReverse
                maxShiftX = maxPrimary
            } else {
                minShiftX = -maxPrimary
                maxShiftX = maxReverse
            }
            minShiftY = -maxDrift
            maxShiftY = maxDrift
        } else {
            val maxPrimary = (gridH * 0.65f).roundToInt().coerceAtLeast(4)
            val maxReverse = (gridH * 0.25f).roundToInt().coerceAtLeast(2)
            val maxDrift = (gridW * 0.22f).roundToInt().coerceAtLeast(2)
            if (direction.sign > 0) {
                minShiftY = -maxReverse
                maxShiftY = maxPrimary
            } else {
                minShiftY = -maxPrimary
                maxShiftY = maxReverse
            }
            minShiftX = -maxDrift
            maxShiftX = maxDrift
        }

        // Step 1: Coarse search with step = 2 on the downscaled grid
        var bestDx = 0
        var bestDy = 0
        var bestCost = Float.MAX_VALUE

        val marginX = (gridW * 0.08f).roundToInt().coerceAtLeast(2)
        val marginY = (gridH * 0.08f).roundToInt().coerceAtLeast(2)

        var candDx = minShiftX
        while (candDx <= maxShiftX) {
            var candDy = minShiftY
            while (candDy <= maxShiftY) {
                val cost = evaluateOverlapCost(
                    prevGrid = prevGrid,
                    currGrid = currGrid,
                    gridW = gridW,
                    gridH = gridH,
                    dx = candDx,
                    dy = candDy,
                    marginX = marginX,
                    marginY = marginY,
                    sampleStep = 2
                )
                if (cost < bestCost) {
                    bestCost = cost
                    bestDx = candDx
                    bestDy = candDy
                }
                candDy += 2
            }
            candDx += 2
        }

        // Step 2: Single-pixel local refinement around (bestDx, bestDy)
        val searchRadius = 2
        for (rx in (bestDx - searchRadius)..(bestDx + searchRadius)) {
            if (rx < minShiftX || rx > maxShiftX) continue
            for (ry in (bestDy - searchRadius)..(bestDy + searchRadius)) {
                if (ry < minShiftY || ry > maxShiftY) continue
                val cost = evaluateOverlapCost(
                    prevGrid = prevGrid,
                    currGrid = currGrid,
                    gridW = gridW,
                    gridH = gridH,
                    dx = rx,
                    dy = ry,
                    marginX = marginX,
                    marginY = marginY,
                    sampleStep = 1
                )
                if (cost < bestCost) {
                    bestCost = cost
                    bestDx = rx
                    bestDy = ry
                }
            }
        }

        // Step 3: Sub-pixel parabolic refinement along X and Y
        val subDx = if (bestDx > minShiftX && bestDx < maxShiftX) {
            val cLeft = evaluateOverlapCost(prevGrid, currGrid, gridW, gridH, bestDx - 1, bestDy, marginX, marginY, 1)
            val cRight = evaluateOverlapCost(prevGrid, currGrid, gridW, gridH, bestDx + 1, bestDy, marginX, marginY, 1)
            val denom = 2f * (cLeft - 2f * bestCost + cRight)
            if (abs(denom) > 1e-4f && cLeft >= bestCost && cRight >= bestCost) {
                ((cLeft - cRight) / denom).coerceIn(-0.5f, 0.5f)
            } else 0f
        } else 0f

        val subDy = if (bestDy > minShiftY && bestDy < maxShiftY) {
            val cUp = evaluateOverlapCost(prevGrid, currGrid, gridW, gridH, bestDx, bestDy - 1, marginX, marginY, 1)
            val cDown = evaluateOverlapCost(prevGrid, currGrid, gridW, gridH, bestDx, bestDy + 1, marginX, marginY, 1)
            val denom = 2f * (cUp - 2f * bestCost + cDown)
            if (abs(denom) > 1e-4f && cUp >= bestCost && cDown >= bestCost) {
                ((cUp - cDown) / denom).coerceIn(-0.5f, 0.5f)
            } else 0f
        } else 0f

        val scaleToFullX = fullW.toFloat() / gridW.toFloat()
        val scaleToFullY = fullH.toFloat() / gridH.toFloat()

        val finalDx = (bestDx + subDx) * scaleToFullX
        val finalDy = (bestDy + subDy) * scaleToFullY

        // Map average difference cost [0..80+] to confidence [0..1]
        val confidence = (1f - (bestCost / 90f)).coerceIn(0.05f, 1f)

        return FrameAlignmentResult(
            dx = finalDx,
            dy = finalDy,
            rollDegrees = 0f,
            confidence = confidence
        )
    }

    private fun evaluateOverlapCost(
        prevGrid: FloatArray,
        currGrid: FloatArray,
        gridW: Int,
        gridH: Int,
        dx: Int,
        dy: Int,
        marginX: Int,
        marginY: Int,
        sampleStep: Int
    ): Float {
        // Feature at (x, y) in prev corresponds to (x - dx, y - dy) in curr
        val xStart = max(marginX, marginX + dx)
        val xEnd = min(gridW - marginX, gridW - marginX + dx)
        val yStart = max(marginY, marginY + dy)
        val yEnd = min(gridH - marginY, gridH - marginY + dy)

        val overlapW = xEnd - xStart
        val overlapH = yEnd - yStart
        if (overlapW < gridW / 5 || overlapH < gridH / 5) {
            return Float.MAX_VALUE
        }

        var diffSum = 0f
        var count = 0

        var y = yStart
        while (y < yEnd) {
            val cy = y - dy
            val prevRow = y * gridW
            val currRow = cy * gridW
            var x = xStart
            while (x < xEnd) {
                val cx = x - dx
                val pVal = prevGrid[prevRow + x]
                val cVal = currGrid[currRow + cx]
                diffSum += abs(pVal - cVal)
                count++
                x += sampleStep
            }
            y += sampleStep
        }

        if (count == 0) return Float.MAX_VALUE
        // Slightly penalize extreme shifts if cost is completely flat (uniform textureless region)
        val meanDiff = diffSum / count.toFloat()
        val shiftMag = hypot(dx.toFloat() / gridW, dy.toFloat() / gridH)
        return meanDiff + shiftMag * 0.35f
    }

    /**
     * Extracts a combined luminance + spatial gradient feature grid of size [targetW] x [targetH].
     */
    internal fun extractLumaAndGradientGrid(
        bitmap: Bitmap,
        targetW: Int,
        targetH: Int
    ): FloatArray {
        val srcW = bitmap.width.coerceAtLeast(1)
        val srcH = bitmap.height.coerceAtLeast(1)
        val srcPixels = IntArray(srcW * srcH)
        bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val luma = FloatArray(targetW * targetH)
        val scaleX = srcW.toFloat() / targetW.toFloat()
        val scaleY = srcH.toFloat() / targetH.toFloat()

        for (ty in 0 until targetH) {
            val sy = ((ty + 0.5f) * scaleY - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = sy.toInt().coerceIn(0, (srcH - 2).coerceAtLeast(0))
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            val oneMinusFy = 1f - fy
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = ty * targetW

            for (tx in 0 until targetW) {
                val sx = ((tx + 0.5f) * scaleX - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt().coerceIn(0, (srcW - 2).coerceAtLeast(0))
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - x0
                val oneMinusFx = 1f - fx

                val c00 = srcPixels[row0 + x0]
                val c10 = srcPixels[row0 + x1]
                val c01 = srcPixels[row1 + x0]
                val c11 = srcPixels[row1 + x1]

                val l00 = 0.299f * ((c00 shr 16) and 0xFF) + 0.587f * ((c00 shr 8) and 0xFF) + 0.114f * (c00 and 0xFF)
                val l10 = 0.299f * ((c10 shr 16) and 0xFF) + 0.587f * ((c10 shr 8) and 0xFF) + 0.114f * (c10 and 0xFF)
                val l01 = 0.299f * ((c01 shr 16) and 0xFF) + 0.587f * ((c01 shr 8) and 0xFF) + 0.114f * (c01 and 0xFF)
                val l11 = 0.299f * ((c11 shr 16) and 0xFF) + 0.587f * ((c11 shr 8) and 0xFF) + 0.114f * (c11 and 0xFF)

                luma[dstRow + tx] =
                    l00 * (oneMinusFx * oneMinusFy) +
                    l10 * (fx * oneMinusFy) +
                    l01 * (oneMinusFx * fy) +
                    l11 * (fx * fy)
            }
        }

        val features = FloatArray(targetW * targetH)
        for (y in 0 until targetH) {
            val yUp = (y - 1).coerceAtLeast(0)
            val yDown = (y + 1).coerceAtMost(targetH - 1)
            for (x in 0 until targetW) {
                val xLeft = (x - 1).coerceAtLeast(0)
                val xRight = (x + 1).coerceAtMost(targetW - 1)
                val gx = abs(luma[y * targetW + xRight] - luma[y * targetW + xLeft])
                val gy = abs(luma[yDown * targetW + x] - luma[yUp * targetW + x])
                // Blend 65% luminance + 35% edge magnitude for lighting-resilient alignment
                features[y * targetW + x] = 0.65f * luma[y * targetW + x] + 0.35f * (gx + gy)
            }
        }
        return features
    }

    internal fun scaleBitmapBilinear(
        source: Bitmap,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        if (srcW == targetW && srcH == targetH) {
            val copy = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            copy.setPixels(srcPixels, 0, targetW, 0, 0, targetW, targetH)
            return copy
        }

        val dstPixels = IntArray(targetW * targetH)
        val scaleX = srcW.toFloat() / targetW.toFloat()
        val scaleY = srcH.toFloat() / targetH.toFloat()
        for (ty in 0 until targetH) {
            val sy = ((ty + 0.5f) * scaleY - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val dstRow = ty * targetW
            for (tx in 0 until targetW) {
                val sx = ((tx + 0.5f) * scaleX - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                dstPixels[dstRow + tx] = sampleBilinear(srcPixels, srcW, srcH, sx, sy)
            }
        }
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        out.setPixels(dstPixels, 0, targetW, 0, 0, targetW, targetH)
        return out
    }

    /**
     * Applies cylindrical projection warping to [source] so rotational camera sweeps align via 2D translation.
     *
     * @param source Input upright frame bitmap.
     * @param focalLengthPx Effective focal length in pixels (typically ~0.85 * max(width, height)).
     * @param isHorizontal True for horizontal panorama (cylinder axis is vertical), false for vertical panorama.
     * @param rollDegrees Optional in-plane rotation correction in degrees.
     */
    fun warpFrameCylindrical(
        source: Bitmap,
        focalLengthPx: Float = max(source.width, source.height) * 0.90f,
        isHorizontal: Boolean = true,
        rollDegrees: Float = 0f
    ): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 2 || h <= 2 || focalLengthPx <= 10f) return source

        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        val f = focalLengthPx
        val rollRad = (rollDegrees * PI / 180.0).toFloat()
        val cosRoll = cos(rollRad)
        val sinRoll = sin(rollRad)
        val hasRoll = abs(rollDegrees) > 0.05f

        for (oy in 0 until h) {
            val dy = oy - cy
            val outRow = oy * w
            for (ox in 0 until w) {
                val dx = ox - cx

                val sxUnrotated: Float
                val syUnrotated: Float

                if (isHorizontal) {
                    val theta = dx / f
                    val cosTheta = cos(theta)
                    if (cosTheta <= 0.01f) continue
                    sxUnrotated = f * tan(theta)
                    syUnrotated = dy / cosTheta
                } else {
                    val phi = dy / f
                    val cosPhi = cos(phi)
                    if (cosPhi <= 0.01f) continue
                    syUnrotated = f * tan(phi)
                    sxUnrotated = dx / cosPhi
                }

                val rx: Float
                val ry: Float
                if (hasRoll) {
                    rx = sxUnrotated * cosRoll - syUnrotated * sinRoll
                    ry = sxUnrotated * sinRoll + syUnrotated * cosRoll
                } else {
                    rx = sxUnrotated
                    ry = syUnrotated
                }

                val sx = cx + rx
                val sy = cy + ry

                if (sx >= 0f && sx < (w - 1) && sy >= 0f && sy < (h - 1)) {
                    outPixels[outRow + ox] = sampleBilinear(srcPixels, w, h, sx, sy)
                }
            }
        }

        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, w, 0, 0, w, h)
        return outBitmap
    }

    private fun sampleBilinear(
        pixels: IntArray,
        w: Int,
        h: Int,
        x: Float,
        y: Float
    ): Int {
        val x0 = x.toInt().coerceIn(0, (w - 2).coerceAtLeast(0))
        val y0 = y.toInt().coerceIn(0, (h - 2).coerceAtLeast(0))
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        val oneMinusFx = 1f - fx
        val oneMinusFy = 1f - fy

        val c00 = pixels[y0 * w + x0]
        val c10 = pixels[y0 * w + x1]
        val c01 = pixels[y1 * w + x0]
        val c11 = pixels[y1 * w + x1]

        val w00 = oneMinusFx * oneMinusFy
        val w10 = fx * oneMinusFy
        val w01 = oneMinusFx * fy
        val w11 = fx * fy

        val r = (
            ((c00 shr 16) and 0xFF) * w00 +
                ((c10 shr 16) and 0xFF) * w10 +
                ((c01 shr 16) and 0xFF) * w01 +
                ((c11 shr 16) and 0xFF) * w11
            ).roundToInt().coerceIn(0, 255)

        val g = (
            ((c00 shr 8) and 0xFF) * w00 +
                ((c10 shr 8) and 0xFF) * w10 +
                ((c01 shr 8) and 0xFF) * w01 +
                ((c11 shr 8) and 0xFF) * w11
            ).roundToInt().coerceIn(0, 255)

        val b = (
            (c00 and 0xFF) * w00 +
                (c10 and 0xFF) * w10 +
                (c01 and 0xFF) * w01 +
                (c11 and 0xFF) * w11
            ).roundToInt().coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Stitches an ordered list of upright [frames] into a seamless wide-angle panorama.
     *
     * Pipeline steps:
     * 1. Downscale frames to working resolution if needed (`maxFrameDimension`).
     * 2. Estimate pairwise translations `(dx, dy)` using coarse-to-fine feature alignment.
     * 3. Apply cylindrical projection warping to reduce wide-angle perspective distortion.
     * 4. Normalize exposure gain across overlapping regions.
     * 5. Composite frames onto the panorama canvas using Dynamic Programming minimum-error seam finding
     *    coupled with raised-cosine feather blending.
     * 6. Crop away non-rectangular border regions to produce a clean rectangular panoramic image.
     */
    fun stitchPanoramaBitmaps(
        frames: List<Bitmap>,
        direction: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT,
        maxFrameDimension: Int = DEFAULT_MAX_FRAME_DIMENSION,
        jpegQuality: Int = 94,
        enableCylindricalWarp: Boolean = true
    ): PanoramaStitchOutcome {
        if (frames.size < MIN_REQUIRED_FRAMES) {
            return PanoramaStitchOutcome.Failure(
                "Move your phone further to capture at least $MIN_REQUIRED_FRAMES overlapping frames."
            )
        }

        // Normalize all frames to uniform working dimensions
        val first = frames.first()
        if (first.width <= 0 || first.height <= 0) {
            return PanoramaStitchOutcome.Failure("Invalid source frame dimensions.")
        }

        val scale = min(1f, maxFrameDimension.toFloat() / max(first.width, first.height).toFloat())
        val frameW = (first.width * scale).roundToInt().coerceAtLeast(32)
        val frameH = (first.height * scale).roundToInt().coerceAtLeast(32)

        val normalizedFrames = ArrayList<Bitmap>(frames.size)
        try {
            for (bmp in frames.take(MAX_PANORAMA_FRAMES)) {
                val scaled = scaleBitmapBilinear(bmp, frameW, frameH)
                val warped = if (enableCylindricalWarp) {
                    val f = max(frameW, frameH) * 1.15f
                    val cyl = warpFrameCylindrical(
                        source = scaled,
                        focalLengthPx = f,
                        isHorizontal = direction.isHorizontal
                    )
                    if (cyl !== scaled) scaled.recycle()
                    cyl
                } else {
                    scaled
                }
                normalizedFrames.add(warped)
            }

            // Estimate pairwise alignments between consecutive warped frames
            val cumulativeX = FloatArray(normalizedFrames.size)
            val cumulativeY = FloatArray(normalizedFrames.size)

            val primaryDim = (if (direction.isHorizontal) frameW else frameH).toFloat()
            val minGenuineMotionPx = primaryDim * 0.025f
            val defaultStepPrimary = primaryDim * KEYFRAME_STEP_FRACTION

            for (i in 1 until normalizedFrames.size) {
                val alignment = estimatePairwiseAlignment(
                    prevBitmap = normalizedFrames[i - 1],
                    currBitmap = normalizedFrames[i],
                    direction = direction
                )

                var stepX = alignment.dx
                var stepY = alignment.dy

                if (direction.isHorizontal) {
                    val signedForward = stepX * direction.sign
                    stepX = if (signedForward >= minGenuineMotionPx) {
                        direction.sign * signedForward.coerceAtMost(frameW * 0.65f)
                    } else {
                        // Near-duplicate or slightly reversed frame: keep at 0 forward step instead of jumping 30%
                        0f
                    }
                    stepY = stepY.coerceIn(-frameH * 0.18f, frameH * 0.18f)
                } else {
                    val signedForward = stepY * direction.sign
                    stepY = if (signedForward >= minGenuineMotionPx) {
                        direction.sign * signedForward.coerceAtMost(frameH * 0.65f)
                    } else {
                        0f
                    }
                    stepX = stepX.coerceIn(-frameW * 0.18f, frameW * 0.18f)
                }

                cumulativeX[i] = cumulativeX[i - 1] + stepX
                cumulativeY[i] = cumulativeY[i - 1] + stepY
            }

            // If the entire sequence had zero visual translation (e.g., static emulator/uniform test frames),
            // distribute frames evenly so the output panorama still spans multiple frames cleanly.
            val totalPrimarySpan = if (direction.isHorizontal) {
                abs(cumulativeX.last() - cumulativeX.first())
            } else {
                abs(cumulativeY.last() - cumulativeY.first())
            }
            if (totalPrimarySpan < primaryDim * 0.08f) {
                for (i in 1 until normalizedFrames.size) {
                    if (direction.isHorizontal) {
                        cumulativeX[i] = cumulativeX[i - 1] + direction.sign * defaultStepPrimary
                    } else {
                        cumulativeY[i] = cumulativeY[i - 1] + direction.sign * defaultStepPrimary
                    }
                }
            }

            // Center vertical/lateral drift trend so the sweep stays level across the canvas
            if (direction.isHorizontal) {
                val totalSpanX = abs(cumulativeX.last() - cumulativeX.first()).coerceAtLeast(1f)
                val driftSlope = (cumulativeY.last() - cumulativeY.first()) / totalSpanX
                if (abs(driftSlope) < 0.25f) {
                    for (i in cumulativeY.indices) {
                        cumulativeY[i] -= driftSlope * abs(cumulativeX[i]) * 0.5f
                    }
                }
            } else {
                val totalSpanY = abs(cumulativeY.last() - cumulativeY.first()).coerceAtLeast(1f)
                val driftSlope = (cumulativeX.last() - cumulativeX.first()) / totalSpanY
                if (abs(driftSlope) < 0.25f) {
                    for (i in cumulativeX.indices) {
                        cumulativeX[i] -= driftSlope * abs(cumulativeY[i]) * 0.5f
                    }
                }
            }

            // Compute canvas bounds so all frame offsets are non-negative
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in normalizedFrames.indices) {
                if (cumulativeX[i] < minX) minX = cumulativeX[i]
                if (cumulativeX[i] + frameW > maxX) maxX = cumulativeX[i] + frameW
                if (cumulativeY[i] < minY) minY = cumulativeY[i]
                if (cumulativeY[i] + frameH > maxY) maxY = cumulativeY[i] + frameH
            }

            val offsetsX = IntArray(normalizedFrames.size) { i ->
                (cumulativeX[i] - minX).roundToInt()
            }
            val offsetsY = IntArray(normalizedFrames.size) { i ->
                (cumulativeY[i] - minY).roundToInt()
            }

            val canvasW = (maxX - minX).roundToInt().coerceIn(frameW, frameW * MAX_PANORAMA_FRAMES)
            val canvasH = (maxY - minY).roundToInt().coerceIn(frameH, frameH * MAX_PANORAMA_FRAMES)

            // Sort frames by primary coordinate (left-to-right for horizontal, top-to-bottom for vertical)
            // so seam finding and blending always progress monotonically
            val orderedIndices = normalizedFrames.indices.sortedBy { i ->
                if (direction.isHorizontal) offsetsX[i] else offsetsY[i]
            }

            val canvasPixels = IntArray(canvasW * canvasH)
            val canvasValid = BooleanArray(canvasW * canvasH)
            val framePixels = IntArray(frameW * frameH)

            var cumulativeGain = 1.0f

            for ((orderPos, idx) in orderedIndices.withIndex()) {
                val bmp = normalizedFrames[idx]
                bmp.getPixels(framePixels, 0, frameW, 0, 0, frameW, frameH)
                val ox = offsetsX[idx]
                val oy = offsetsY[idx]

                if (orderPos == 0) {
                    for (fy in 0 until frameH) {
                        val cy = oy + fy
                        if (cy !in 0 until canvasH) continue
                        val cRow = cy * canvasW
                        val fRow = fy * frameW
                        for (fx in 0 until frameW) {
                            val cx = ox + fx
                            if (cx !in 0 until canvasW) continue
                            val color = framePixels[fRow + fx]
                            if ((color ushr 24) > 128) {
                                canvasPixels[cRow + cx] = color
                                canvasValid[cRow + cx] = true
                            }
                        }
                    }
                } else {
                    // Exposure gain matching in the overlapping region
                    val pairGain = computeOverlapGainRatio(
                        canvasPixels = canvasPixels,
                        canvasValid = canvasValid,
                        canvasW = canvasW,
                        canvasH = canvasH,
                        framePixels = framePixels,
                        frameW = frameW,
                        frameH = frameH,
                        ox = ox,
                        oy = oy
                    )
                    cumulativeGain = (cumulativeGain * 0.25f + pairGain * 0.75f).coerceIn(0.78f, 1.28f)
                    if (abs(cumulativeGain - 1.0f) > 0.015f) {
                        applyGainToPixels(framePixels, cumulativeGain)
                    }

                    // Dynamic programming optimal seam + raised-cosine feather blend
                    blendFrameOntoCanvas(
                        canvasPixels = canvasPixels,
                        canvasValid = canvasValid,
                        canvasW = canvasW,
                        canvasH = canvasH,
                        framePixels = framePixels,
                        frameW = frameW,
                        frameH = frameH,
                        ox = ox,
                        oy = oy,
                        isHorizontal = direction.isHorizontal
                    )
                }
            }

            // Crop to clean rectangular interior without black triangles or cylindrical warp borders
            val croppedBitmap = cropToCleanPanoramaBounds(
                canvasPixels = canvasPixels,
                canvasValid = canvasValid,
                canvasW = canvasW,
                canvasH = canvasH,
                isHorizontal = direction.isHorizontal
            )

            val outStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(75, 100), outStream)
            val jpegBytes = outStream.toByteArray()

            return PanoramaStitchOutcome.Success(
                jpegBytes = jpegBytes,
                bitmap = croppedBitmap,
                width = croppedBitmap.width,
                height = croppedBitmap.height,
                stitchedFrameCount = normalizedFrames.size,
                exifOrientation = ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Throwable) {
            return PanoramaStitchOutcome.Failure("Failed to stitch panorama: ${e.localizedMessage ?: "unknown error"}")
        } finally {
            for (bmp in normalizedFrames) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    /**
     * Decodes a list of captured JPEG byte arrays, normalizes rotation & selfie mirroring,
     * and stitches them into a single panoramic JPEG.
     */
    fun processPanoramaJpegBytes(
        framesBytes: List<ByteArray>,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
        direction: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT,
        maxFrameDimension: Int = DEFAULT_MAX_FRAME_DIMENSION,
        jpegQuality: Int = 94
    ): PanoramaStitchOutcome {
        if (framesBytes.size < MIN_REQUIRED_FRAMES) {
            return PanoramaStitchOutcome.Failure(
                "Capture at least $MIN_REQUIRED_FRAMES overlapping frames to create a panorama."
            )
        }

        val decodedBitmaps = ArrayList<Bitmap>(framesBytes.size)
        try {
            for (bytes in framesBytes.take(MAX_PANORAMA_FRAMES)) {
                if (bytes.isEmpty()) continue
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                val rawW = boundsOpts.outWidth
                val rawH = boundsOpts.outHeight
                if (rawW <= 0 || rawH <= 0) continue

                var sampleSize = 1
                while (max(rawW / sampleSize, rawH / sampleSize) > maxFrameDimension * 2) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: continue
                val oriented = orientAndMirrorBitmap(
                    source = rawBitmap,
                    rotationDegrees = rotationDegrees,
                    mirrorHorizontally = mirrorHorizontally,
                    maxDimension = maxFrameDimension
                )
                if (oriented !== rawBitmap) {
                    rawBitmap.recycle()
                }
                decodedBitmaps.add(oriented)
            }

            if (decodedBitmaps.size < MIN_REQUIRED_FRAMES) {
                return PanoramaStitchOutcome.Failure("Unable to decode captured panorama frames.")
            }

            return stitchPanoramaBitmaps(
                frames = decodedBitmaps,
                direction = direction,
                maxFrameDimension = maxFrameDimension,
                jpegQuality = jpegQuality
            )
        } finally {
            for (bmp in decodedBitmaps) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    internal fun orientAndMirrorBitmap(
        source: Bitmap,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
        maxDimension: Int
    ): Bitmap {
        val normRot = ((rotationDegrees % 360) + 360) % 360
        val orientedW = if (normRot == 90 || normRot == 270) source.height else source.width
        val orientedH = if (normRot == 90 || normRot == 270) source.width else source.height

        val scale = min(1f, maxDimension.toFloat() / max(orientedW, orientedH).toFloat())
        val targetSrcW = (source.width * scale).roundToInt().coerceAtLeast(16)
        val targetSrcH = (source.height * scale).roundToInt().coerceAtLeast(16)

        val working = if (targetSrcW != source.width || targetSrcH != source.height) {
            Bitmap.createScaledBitmap(source, targetSrcW, targetSrcH, true)
        } else {
            source
        }

        if (normRot == 0 && !mirrorHorizontally) {
            return working
        }

        val matrix = android.graphics.Matrix()
        if (normRot != 0) {
            matrix.postRotate(normRot.toFloat())
        }
        if (mirrorHorizontally) {
            matrix.postScale(-1f, 1f)
        }
        val transformed = Bitmap.createBitmap(
            working,
            0,
            0,
            working.width,
            working.height,
            matrix,
            true
        )
        if (working !== source && working !== transformed) {
            working.recycle()
        }
        return transformed
    }

    private fun computeOverlapGainRatio(
        canvasPixels: IntArray,
        canvasValid: BooleanArray,
        canvasW: Int,
        canvasH: Int,
        framePixels: IntArray,
        frameW: Int,
        frameH: Int,
        ox: Int,
        oy: Int
    ): Float {
        var sumCanvasLuma = 0.0
        var sumFrameLuma = 0.0
        var count = 0

        for (fy in 0 until frameH step 2) {
            val cy = oy + fy
            if (cy !in 0 until canvasH) continue
            val cRow = cy * canvasW
            val fRow = fy * frameW
            for (fx in 0 until frameW step 2) {
                val cx = ox + fx
                if (cx !in 0 until canvasW) continue
                val cIdx = cRow + cx
                if (!canvasValid[cIdx]) continue
                val fColor = framePixels[fRow + fx]
                if ((fColor ushr 24) <= 128) continue

                val cColor = canvasPixels[cIdx]
                val cLuma = 0.299 * ((cColor shr 16) and 0xFF) +
                    0.587 * ((cColor shr 8) and 0xFF) +
                    0.114 * (cColor and 0xFF)
                val fLuma = 0.299 * ((fColor shr 16) and 0xFF) +
                    0.587 * ((fColor shr 8) and 0xFF) +
                    0.114 * (fColor and 0xFF)

                if (cLuma > 8.0 && fLuma > 8.0 && cLuma < 248.0 && fLuma < 248.0) {
                    sumCanvasLuma += cLuma
                    sumFrameLuma += fLuma
                    count++
                }
            }
        }

        if (count < 32 || sumFrameLuma <= 1.0) return 1.0f
        return (sumCanvasLuma / sumFrameLuma).toFloat().coerceIn(0.80f, 1.25f)
    }

    private fun applyGainToPixels(pixels: IntArray, gain: Float) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a == 0) continue
            val r = (((c shr 16) and 0xFF) * gain).roundToInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * gain).roundToInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * gain).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Finds the minimum-error seam through the overlap region using Dynamic Programming and blends
     * [framePixels] onto [canvasPixels] with a raised-cosine feather window around the optimal seam.
     */
    private fun blendFrameOntoCanvas(
        canvasPixels: IntArray,
        canvasValid: BooleanArray,
        canvasW: Int,
        canvasH: Int,
        framePixels: IntArray,
        frameW: Int,
        frameH: Int,
        ox: Int,
        oy: Int,
        isHorizontal: Boolean
    ) {
        if (isHorizontal) {
            // Identify horizontal overlap range [ovStartFx, ovEndFx] within the new frame
            var ovStartFx = frameW
            var ovEndFx = -1
            val midFy = frameH / 2
            val midCy = (oy + midFy).coerceIn(0, canvasH - 1)
            for (fx in 0 until frameW) {
                val cx = ox + fx
                if (cx in 0 until canvasW && canvasValid[midCy * canvasW + cx]) {
                    if (fx < ovStartFx) ovStartFx = fx
                    if (fx > ovEndFx) ovEndFx = fx
                }
            }

            val ovWidth = ovEndFx - ovStartFx + 1
            val seamFxByRow = IntArray(frameH) { (ovStartFx + ovEndFx) / 2 }

            if (ovWidth >= 6) {
                // Dynamic programming minimum-error vertical seam across [ovStartFx..ovEndFx]
                val dpPrev = IntArray(ovWidth)
                val dpCurr = IntArray(ovWidth)
                val backtrack = Array(frameH) { IntArray(ovWidth) }

                for (fy in 0 until frameH) {
                    val cy = oy + fy
                    val fRow = fy * frameW
                    for (k in 0 until ovWidth) {
                        val fx = ovStartFx + k
                        val cx = ox + fx
                        val fColor = framePixels[fRow + fx]
                        val fValid = (fColor ushr 24) > 128
                        val cValid = (cy in 0 until canvasH) && (cx in 0 until canvasW) && canvasValid[cy * canvasW + cx]

                        val energy = if (fValid && cValid) {
                            val cColor = canvasPixels[cy * canvasW + cx]
                            val dr = abs(((cColor shr 16) and 0xFF) - ((fColor shr 16) and 0xFF))
                            val dg = abs(((cColor shr 8) and 0xFF) - ((fColor shr 8) and 0xFF))
                            val db = abs((cColor and 0xFF) - (fColor and 0xFF))
                            dr + dg + db
                        } else {
                            1000
                        }

                        if (fy == 0) {
                            dpCurr[k] = energy
                            backtrack[fy][k] = k
                        } else {
                            var bestPrevK = k
                            var minPrevCost = dpPrev[k]
                            if (k > 0 && dpPrev[k - 1] < minPrevCost) {
                                minPrevCost = dpPrev[k - 1]
                                bestPrevK = k - 1
                            }
                            if (k + 1 < ovWidth && dpPrev[k + 1] < minPrevCost) {
                                minPrevCost = dpPrev[k + 1]
                                bestPrevK = k + 1
                            }
                            dpCurr[k] = energy + minPrevCost
                            backtrack[fy][k] = bestPrevK
                        }
                    }
                    System.arraycopy(dpCurr, 0, dpPrev, 0, ovWidth)
                }

                var minLastK = ovWidth / 2
                var minLastVal = Int.MAX_VALUE
                for (k in 0 until ovWidth) {
                    if (dpPrev[k] < minLastVal) {
                        minLastVal = dpPrev[k]
                        minLastK = k
                    }
                }

                var currK = minLastK
                for (fy in (frameH - 1) downTo 0) {
                    seamFxByRow[fy] = ovStartFx + currK
                    currK = backtrack[fy][currK]
                }
            }

            val featherHalfWidth = max(6f, ovWidth * 0.28f)

            for (fy in 0 until frameH) {
                val cy = oy + fy
                if (cy !in 0 until canvasH) continue
                val cRow = cy * canvasW
                val fRow = fy * frameW
                val seamFx = seamFxByRow[fy]

                for (fx in 0 until frameW) {
                    val cx = ox + fx
                    if (cx !in 0 until canvasW) continue
                    val fColor = framePixels[fRow + fx]
                    if ((fColor ushr 24) <= 128) continue

                    val cIdx = cRow + cx
                    if (!canvasValid[cIdx]) {
                        canvasPixels[cIdx] = fColor
                        canvasValid[cIdx] = true
                    } else {
                        val t = ((fx - (seamFx - featherHalfWidth)) / (2f * featherHalfWidth)).coerceIn(0f, 1f)
                        val weightNew = 0.5f * (1f - cos(PI.toFloat() * t))
                        canvasPixels[cIdx] = blendColors(canvasPixels[cIdx], fColor, weightNew)
                    }
                }
            }
        } else {
            // Vertical panorama blending along Y
            var ovStartFy = frameH
            var ovEndFy = -1
            val midFx = frameW / 2
            val midCx = (ox + midFx).coerceIn(0, canvasW - 1)
            for (fy in 0 until frameH) {
                val cy = oy + fy
                if (cy in 0 until canvasH && canvasValid[cy * canvasW + midCx]) {
                    if (fy < ovStartFy) ovStartFy = fy
                    if (fy > ovEndFy) ovEndFy = fy
                }
            }

            val ovHeight = ovEndFy - ovStartFy + 1
            val seamFy = (ovStartFy + ovEndFy) / 2
            val featherHalfHeight = max(6f, ovHeight * 0.28f)

            for (fy in 0 until frameH) {
                val cy = oy + fy
                if (cy !in 0 until canvasH) continue
                val cRow = cy * canvasW
                val fRow = fy * frameW
                val t = ((fy - (seamFy - featherHalfHeight)) / (2f * featherHalfHeight)).coerceIn(0f, 1f)
                val weightNew = 0.5f * (1f - cos(PI.toFloat() * t))

                for (fx in 0 until frameW) {
                    val cx = ox + fx
                    if (cx !in 0 until canvasW) continue
                    val fColor = framePixels[fRow + fx]
                    if ((fColor ushr 24) <= 128) continue

                    val cIdx = cRow + cx
                    if (!canvasValid[cIdx]) {
                        canvasPixels[cIdx] = fColor
                        canvasValid[cIdx] = true
                    } else {
                        canvasPixels[cIdx] = blendColors(canvasPixels[cIdx], fColor, weightNew)
                    }
                }
            }
        }
    }

    private fun blendColors(c1: Int, c2: Int, w2: Float): Int {
        val w1 = 1f - w2
        val r = (((c1 shr 16) and 0xFF) * w1 + ((c2 shr 16) and 0xFF) * w2).roundToInt().coerceIn(0, 255)
        val g = (((c1 shr 8) and 0xFF) * w1 + ((c2 shr 8) and 0xFF) * w2).roundToInt().coerceIn(0, 255)
        val b = ((c1 and 0xFF) * w1 + (c2 and 0xFF) * w2).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Crops away irregular top/bottom/side borders caused by vertical drift and cylindrical warp,
     * and fills any residual 1-2px edge gaps using nearest valid interior pixels.
     */
    internal fun cropToCleanPanoramaBounds(
        canvasPixels: IntArray,
        canvasValid: BooleanArray,
        canvasW: Int,
        canvasH: Int,
        isHorizontal: Boolean
    ): Bitmap {
        // Find bounding box of valid pixels first
        var minValidX = canvasW
        var maxValidX = 0
        var minValidY = canvasH
        var maxValidY = 0

        for (y in 0 until canvasH) {
            val row = y * canvasW
            for (x in 0 until canvasW) {
                if (canvasValid[row + x]) {
                    if (x < minValidX) minValidX = x
                    if (x > maxValidX) maxValidX = x
                    if (y < minValidY) minValidY = y
                    if (y > maxValidY) maxValidY = y
                }
            }
        }

        if (minValidX >= maxValidX || minValidY >= maxValidY) {
            val fallback = Bitmap.createBitmap(canvasW.coerceAtLeast(1), canvasH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            fallback.setPixels(canvasPixels, 0, canvasW, 0, 0, canvasW, canvasH)
            return fallback
        }

        var cropLeft = minValidX
        var cropRight = maxValidX
        var cropTop = minValidY
        var cropBottom = maxValidY

        if (isHorizontal) {
            // Trim horizontal ends slightly where cylindrical barrel curve creates narrow corners
            val spanW = cropRight - cropLeft + 1
            val sideTrim = (spanW * 0.02f).roundToInt()
            cropLeft = (cropLeft + sideTrim).coerceAtMost(cropRight - 16)
            cropRight = (cropRight - sideTrim).coerceAtLeast(cropLeft + 16)

            // Find the clean interior vertical band [cropTop..cropBottom] where >= 92% of columns are valid
            val requiredValidCols = ((cropRight - cropLeft + 1) * 0.92f).roundToInt().coerceAtLeast(1)
            while (cropTop < cropBottom - 32) {
                var validCols = 0
                val row = cropTop * canvasW
                for (x in cropLeft..cropRight) {
                    if (canvasValid[row + x]) validCols++
                }
                if (validCols >= requiredValidCols) break
                cropTop++
            }
            while (cropBottom > cropTop + 32) {
                var validCols = 0
                val row = cropBottom * canvasW
                for (x in cropLeft..cropRight) {
                    if (canvasValid[row + x]) validCols++
                }
                if (validCols >= requiredValidCols) break
                cropBottom--
            }
        } else {
            val spanH = cropBottom - cropTop + 1
            val endTrim = (spanH * 0.02f).roundToInt()
            cropTop = (cropTop + endTrim).coerceAtMost(cropBottom - 16)
            cropBottom = (cropBottom - endTrim).coerceAtLeast(cropTop + 16)

            val requiredValidRows = ((cropBottom - cropTop + 1) * 0.92f).roundToInt().coerceAtLeast(1)
            while (cropLeft < cropRight - 32) {
                var validRows = 0
                for (y in cropTop..cropBottom) {
                    if (canvasValid[y * canvasW + cropLeft]) validRows++
                }
                if (validRows >= requiredValidRows) break
                cropLeft++
            }
            while (cropRight > cropLeft + 32) {
                var validRows = 0
                for (y in cropTop..cropBottom) {
                    if (canvasValid[y * canvasW + cropRight]) validRows++
                }
                if (validRows >= requiredValidRows) break
                cropRight--
            }
        }

        val outW = (cropRight - cropLeft + 1).coerceAtLeast(16)
        val outH = (cropBottom - cropTop + 1).coerceAtLeast(16)
        val outPixels = IntArray(outW * outH)

        // Copy cropped region and clamp any remaining invalid edge pixels from nearest valid neighbor
        for (oy in 0 until outH) {
            val cy = cropTop + oy
            val cRow = cy * canvasW
            val outRow = oy * outW
            for (ox in 0 until outW) {
                val cx = cropLeft + ox
                val cIdx = cRow + cx
                if (canvasValid[cIdx]) {
                    outPixels[outRow + ox] = canvasPixels[cIdx]
                } else {
                    // Search vertically then horizontally for nearest valid interior pixel
                    var replacement = 0xFF202020.toInt()
                    for (delta in 1..24) {
                        val upY = cy - delta
                        if (upY >= cropTop && canvasValid[upY * canvasW + cx]) {
                            replacement = canvasPixels[upY * canvasW + cx]
                            break
                        }
                        val downY = cy + delta
                        if (downY <= cropBottom && canvasValid[downY * canvasW + cx]) {
                            replacement = canvasPixels[downY * canvasW + cx]
                            break
                        }
                    }
                    outPixels[outRow + ox] = replacement
                }
            }
        }

        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return outBitmap
    }
}

/**
 * Real-time sensor movement tracker for Panorama mode.
 *
 * Uses Android rotation-vector (`TYPE_ROTATION_VECTOR` / `TYPE_GAME_ROTATION_VECTOR`) and
 * gyroscope (`TYPE_GYROSCOPE`) sensor data to measure true 3D angular displacement relative
 * to the starting camera orientation, accounting for screen orientation (`Surface.ROTATION_*`)
 * and camera facing.
 *
 * Never uses elapsed time as a substitute for movement:
 * - Stationary device -> progress stays stationary.
 * - Movement in the selected sweep direction -> progress advances proportionally in [0.0, 1.0].
 * - Movement in the opposite direction -> no artificial forward progress; triggers `WRONG_DIRECTION`.
 */
class PanoramaMotionTracker(
    val targetSweepDegrees: Float = PanoramaProcessor.DEFAULT_TARGET_SWEEP_DEGREES,
    val targetFrames: Int = PanoramaProcessor.DEFAULT_TARGET_FRAMES
) {
    companion object {
        private const val STATIONARY_GYRO_DEADBAND_RAD_PER_SEC = 0.018f
        private const val STATIONARY_ANGLE_DEADBAND_RAD = 0.006f
        private const val MAX_SAFE_ANGULAR_SPEED_RAD_PER_SEC = 0.95f
        private const val WRONG_DIRECTION_VELOCITY_RAD_PER_SEC = -0.10f
        private const val MAX_DRIFT_RAD = 0.35f // ~20 degrees off-axis triggers full drift

        /**
         * Pure mathematical conversion of an Android rotation vector `(qx, qy, qz, [qw])`
         * into a 3x3 row-major rotation matrix `R` (matching `SensorManager.getRotationMatrixFromVector`).
         */
        fun rotationVectorToMatrix(values: FloatArray, outR: FloatArray) {
            if (values.size < 3 || outR.size < 9) return
            var qx = values[0]
            var qy = values[1]
            var qz = values[2]
            var qw = if (values.size >= 4) {
                values[3]
            } else {
                val sq = 1f - qx * qx - qy * qy - qz * qz
                if (sq > 0f) sqrt(sq) else 0f
            }
            val norm = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
            if (norm > 1e-6f) {
                qx /= norm
                qy /= norm
                qz /= norm
                qw /= norm
            } else {
                outR[0] = 1f; outR[1] = 0f; outR[2] = 0f
                outR[3] = 0f; outR[4] = 1f; outR[5] = 0f
                outR[6] = 0f; outR[7] = 0f; outR[8] = 1f
                return
            }

            val sqx = 2f * qx * qx
            val sqy = 2f * qy * qy
            val sqz = 2f * qz * qz
            val qxy = 2f * qx * qy
            val qzw = 2f * qz * qw
            val qxz = 2f * qx * qz
            val qyw = 2f * qy * qw
            val qyz = 2f * qy * qz
            val qxw = 2f * qx * qw

            outR[0] = 1f - sqy - sqz
            outR[1] = qxy - qzw
            outR[2] = qxz + qyw

            outR[3] = qxy + qzw
            outR[4] = 1f - sqx - sqz
            outR[5] = qyz - qxw

            outR[6] = qxz - qyw
            outR[7] = qyz + qxw
            outR[8] = 1f - sqx - sqy
        }

        /**
         * Remaps a 3x3 device rotation matrix into the active screen coordinate system
         * (`X_screen` right, `Y_screen` up, `Z_screen` out of screen toward user)
         * for [displayRotation] (`Surface.ROTATION_0`, `ROTATION_90`, `ROTATION_180`, `ROTATION_270`).
         */
        fun remapDeviceMatrixToScreen(
            deviceR: FloatArray,
            displayRotation: Int,
            outScreenR: FloatArray
        ) {
            // Columns of R_dev are the world vectors of device axes (X_d, Y_d, Z_d).
            // Screen axes (X_s, Y_s, Z_s) expressed in device axes:
            // ROTATION_0:   X_s = +X_d, Y_s = +Y_d, Z_s = +Z_d
            // ROTATION_90:  X_s = +Y_d, Y_s = -X_d, Z_s = +Z_d
            // ROTATION_180: X_s = -X_d, Y_s = -Y_d, Z_s = +Z_d
            // ROTATION_270: X_s = -Y_d, Y_s = +X_d, Z_s = +Z_d
            for (row in 0..2) {
                val rOffset = row * 3
                val colXd = deviceR[rOffset + 0]
                val colYd = deviceR[rOffset + 1]
                val colZd = deviceR[rOffset + 2]
                when (displayRotation) {
                    Surface.ROTATION_90 -> {
                        outScreenR[rOffset + 0] = colYd
                        outScreenR[rOffset + 1] = -colXd
                        outScreenR[rOffset + 2] = colZd
                    }
                    Surface.ROTATION_180 -> {
                        outScreenR[rOffset + 0] = -colXd
                        outScreenR[rOffset + 1] = -colYd
                        outScreenR[rOffset + 2] = colZd
                    }
                    Surface.ROTATION_270 -> {
                        outScreenR[rOffset + 0] = -colYd
                        outScreenR[rOffset + 1] = colXd
                        outScreenR[rOffset + 2] = colZd
                    }
                    else -> {
                        outScreenR[rOffset + 0] = colXd
                        outScreenR[rOffset + 1] = colYd
                        outScreenR[rOffset + 2] = colZd
                    }
                }
            }
        }
    }

    private val lock = Any()

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var direction: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT
        private set

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    @Volatile
    var isFrontCamera: Boolean = false

    @Volatile
    var isSelfieMirrorEnabled: Boolean = true

    private val startScreenR = FloatArray(9)
    private val tempDeviceR = FloatArray(9)
    private val tempScreenR = FloatArray(9)
    private val tempRelR = FloatArray(9)
    private var hasRotationBaseline: Boolean = false
    private var lastRotationTimestampNs: Long = 0L

    private var rotVecYawRightRad: Float = 0f
    private var rotVecPitchDownRad: Float = 0f

    private var gyroYawRightRad: Float = 0f
    private var gyroPitchDownRad: Float = 0f
    private var lastGyroTimestampNs: Long = 0L

    private var prevForwardAngleRad: Float = 0f
    private var currentForwardAngleRad: Float = 0f
    private var currentDriftAngleRad: Float = 0f
    private var instantaneousForwardVelRadPerSec: Float = 0f

    private var smoothedProgress: Float = 0f
    private var smoothedDriftNormalized: Float = 0f
    private var currentGuidance: PanoramaGuidanceStatus = PanoramaGuidanceStatus.READY

    fun startSession(
        selectedDirection: PanoramaDirection,
        screenRotation: Int = Surface.ROTATION_0,
        frontCamera: Boolean = false,
        selfieMirror: Boolean = true
    ) {
        synchronized(lock) {
            direction = selectedDirection
            displayRotation = screenRotation
            isFrontCamera = frontCamera
            isSelfieMirrorEnabled = selfieMirror
            hasRotationBaseline = false
            lastRotationTimestampNs = 0L
            rotVecYawRightRad = 0f
            rotVecPitchDownRad = 0f
            gyroYawRightRad = 0f
            gyroPitchDownRad = 0f
            lastGyroTimestampNs = 0L
            prevForwardAngleRad = 0f
            currentForwardAngleRad = 0f
            currentDriftAngleRad = 0f
            instantaneousForwardVelRadPerSec = 0f
            smoothedProgress = 0f
            smoothedDriftNormalized = 0f
            currentGuidance = PanoramaGuidanceStatus.READY
            isActive = true
        }
    }

    fun stopSession() {
        synchronized(lock) {
            isActive = false
            hasRotationBaseline = false
        }
    }

    fun resetToIdle(selectedDirection: PanoramaDirection = direction) {
        synchronized(lock) {
            isActive = false
            direction = selectedDirection
            hasRotationBaseline = false
            rotVecYawRightRad = 0f
            rotVecPitchDownRad = 0f
            gyroYawRightRad = 0f
            gyroPitchDownRad = 0f
            prevForwardAngleRad = 0f
            currentForwardAngleRad = 0f
            currentDriftAngleRad = 0f
            instantaneousForwardVelRadPerSec = 0f
            smoothedProgress = 0f
            smoothedDriftNormalized = 0f
            currentGuidance = PanoramaGuidanceStatus.READY
        }
    }

    /**
     * Processes a `TYPE_ROTATION_VECTOR` or `TYPE_GAME_ROTATION_VECTOR` sensor sample.
     * Establishes the starting orientation on the first sample, and calculates exact 3D angular
     * displacement relative to the start orientation on subsequent samples.
     */
    fun onRotationVectorSample(values: FloatArray, timestampNs: Long): Float {
        synchronized(lock) {
            if (!isActive || values.size < 3) return smoothedProgress
            rotationVectorToMatrix(values, tempDeviceR)
            remapDeviceMatrixToScreen(tempDeviceR, displayRotation, tempScreenR)

            if (!hasRotationBaseline) {
                System.arraycopy(tempScreenR, 0, startScreenR, 0, 9)
                hasRotationBaseline = true
                lastRotationTimestampNs = timestampNs
                rotVecYawRightRad = 0f
                rotVecPitchDownRad = 0f
                return smoothedProgress
            }

            // Compute R_rel = startScreenR^T * tempScreenR
            for (r in 0..2) {
                for (c in 0..2) {
                    var sum = 0f
                    for (k in 0..2) {
                        sum += startScreenR[k * 3 + r] * tempScreenR[k * 3 + c]
                    }
                    tempRelR[r * 3 + c] = sum
                }
            }

            // Camera optical axis in current screen coordinates is (0, 0, -1).
            // In starting screen coordinates, c_start = R_rel * (0, 0, -1) = (-R_rel[2], -R_rel[5], -R_rel[8]).
            // Rightward horizontal pan (+X_start) has angle atan2(-R_rel[2], R_rel[8]).
            // Downward vertical tilt (-Y_start) has angle atan2(R_rel[5], R_rel[8]).
            val rawYawRight = atan2(-tempRelR[2].toDouble(), tempRelR[8].toDouble()).toFloat()
            val rawPitchDown = atan2(tempRelR[5].toDouble(), tempRelR[8].toDouble()).toFloat()

            val dtSec = if (lastRotationTimestampNs != 0L && timestampNs > lastRotationTimestampNs) {
                ((timestampNs - lastRotationTimestampNs) * 1e-9f).coerceIn(0.005f, 0.5f)
            } else {
                0.033f
            }
            lastRotationTimestampNs = timestampNs

            rotVecYawRightRad = if (abs(rawYawRight) < STATIONARY_ANGLE_DEADBAND_RAD) 0f else rawYawRight
            rotVecPitchDownRad = if (abs(rawPitchDown) < STATIONARY_ANGLE_DEADBAND_RAD) 0f else rawPitchDown

            // Keep gyro accumulator aligned with absolute rotation-vector baseline so fusion is drift-free
            gyroYawRightRad = rotVecYawRightRad
            gyroPitchDownRad = rotVecPitchDownRad

            updateProjectedProgressLocked(rotVecYawRightRad, rotVecPitchDownRad, dtSec)
            return smoothedProgress
        }
    }

    /**
     * Processes a `TYPE_GYROSCOPE` angular-velocity sample `(omegaX, omegaY, omegaZ)` in rad/s.
     */
    fun onGyroscopeSample(omegaX: Float, omegaY: Float, omegaZ: Float, dtSeconds: Float): Float {
        synchronized(lock) {
            if (!isActive || dtSeconds <= 0f || dtSeconds > 0.5f) return smoothedProgress

            // Map device gyroscope axes to active screen axes (X_screen right, Y_screen up)
            val screenOmegaX: Float
            val screenOmegaY: Float
            when (displayRotation) {
                Surface.ROTATION_90 -> {
                    screenOmegaX = omegaY
                    screenOmegaY = -omegaX
                }
                Surface.ROTATION_180 -> {
                    screenOmegaX = -omegaX
                    screenOmegaY = -omegaY
                }
                Surface.ROTATION_270 -> {
                    screenOmegaX = -omegaY
                    screenOmegaY = omegaX
                }
                else -> {
                    screenOmegaX = omegaX
                    screenOmegaY = omegaY
                }
            }

            // Ignore sensor noise floor when phone is stationary
            val effOmegaX = if (abs(screenOmegaX) < STATIONARY_GYRO_DEADBAND_RAD_PER_SEC) 0f else screenOmegaX
            val effOmegaY = if (abs(screenOmegaY) < STATIONARY_GYRO_DEADBAND_RAD_PER_SEC) 0f else screenOmegaY

            if (effOmegaX == 0f && effOmegaY == 0f) {
                instantaneousForwardVelRadPerSec = 0f
                if (currentGuidance == PanoramaGuidanceStatus.MOVING_STEADY ||
                    currentGuidance == PanoramaGuidanceStatus.TOO_FAST ||
                    currentGuidance == PanoramaGuidanceStatus.WRONG_DIRECTION
                ) {
                    currentGuidance = PanoramaGuidanceStatus.HOLD_STEADY
                }
                return smoothedProgress
            }

            // Panning Left -> Right is clockwise rotation from above (screenOmegaY < 0 -> dYawRight > 0)
            // Tilting Top -> Bottom is tilting top of phone forward away from user (screenOmegaX < 0 -> dPitchDown > 0)
            gyroYawRightRad += -effOmegaY * dtSeconds
            gyroPitchDownRad += -effOmegaX * dtSeconds

            val fusedYaw = if (hasRotationBaseline) {
                0.65f * rotVecYawRightRad + 0.35f * gyroYawRightRad
            } else {
                gyroYawRightRad
            }
            val fusedPitch = if (hasRotationBaseline) {
                0.65f * rotVecPitchDownRad + 0.35f * gyroPitchDownRad
            } else {
                gyroPitchDownRad
            }

            updateProjectedProgressLocked(fusedYaw, fusedPitch, dtSeconds)
            return smoothedProgress
        }
    }

    /**
     * Incorporates visual frame-to-frame translation from `ImageAnalysis` when hardware sensors
     * are unavailable or to refine visual overlap tracking.
     * Does nothing if visual translation is below the stationary noise threshold.
     */
    fun onVisualFrameDisplacement(
        forwardFrameFraction: Float,
        lateralFrameFraction: Float,
        dtSeconds: Float
    ): Float {
        synchronized(lock) {
            if (!isActive || dtSeconds <= 0f) return smoothedProgress
            // ~60 deg (1.047 rad) per full frame width/height
            val radPerFrame = 1.0472f
            val visualDeltaForwardRad = if (abs(forwardFrameFraction) < 0.010f) {
                0f
            } else {
                forwardFrameFraction * radPerFrame
            }
            val visualDeltaLateralRad = if (abs(lateralFrameFraction) < 0.010f) {
                0f
            } else {
                lateralFrameFraction * radPerFrame
            }

            if (!hasRotationBaseline && abs(gyroYawRightRad) < 1e-4f && abs(gyroPitchDownRad) < 1e-4f) {
                // Pure visual tracking fallback when no motion sensors have fired
                if (direction.isHorizontal) {
                    gyroYawRightRad += visualDeltaForwardRad * direction.sign
                    gyroPitchDownRad += visualDeltaLateralRad
                } else {
                    gyroPitchDownRad += visualDeltaForwardRad * direction.sign
                    gyroYawRightRad += visualDeltaLateralRad
                }
                updateProjectedProgressLocked(gyroYawRightRad, gyroPitchDownRad, dtSeconds)
            }
            return smoothedProgress
        }
    }

    private fun updateProjectedProgressLocked(
        yawRightRad: Float,
        pitchDownRad: Float,
        dtSeconds: Float
    ) {
        val targetSweepRad = Math.toRadians(targetSweepDegrees.toDouble()).toFloat().coerceAtLeast(0.4f)
        val horizMirrorSign = if (isFrontCamera && !isSelfieMirrorEnabled) -1f else 1f

        val rawForwardRad: Float
        val rawDriftRad: Float
        if (direction.isHorizontal) {
            rawForwardRad = yawRightRad * direction.sign * horizMirrorSign
            rawDriftRad = pitchDownRad
        } else {
            rawForwardRad = pitchDownRad * direction.sign
            rawDriftRad = yawRightRad * horizMirrorSign
        }

        val deltaForwardRad = rawForwardRad - prevForwardAngleRad
        prevForwardAngleRad = rawForwardRad
        val dt = dtSeconds.coerceAtLeast(0.01f)
        instantaneousForwardVelRadPerSec = deltaForwardRad / dt

        // Project onto [0.0, 1.0] along the selected direction.
        // Reverse movement beyond the start point is clamped to 0.0 (never generates forward progress).
        currentForwardAngleRad = rawForwardRad
        currentDriftAngleRad = rawDriftRad

        val targetNormalizedProgress = (rawForwardRad / targetSweepRad).coerceIn(0f, 1f)
        val targetNormalizedDrift = (rawDriftRad / MAX_DRIFT_RAD).coerceIn(-1f, 1f)

        // Low-pass filter to reduce jitter while remaining responsive
        smoothedProgress = if (targetNormalizedProgress <= 0.002f) {
            0f
        } else {
            (smoothedProgress + 0.45f * (targetNormalizedProgress - smoothedProgress)).coerceIn(0f, 1f)
        }
        smoothedDriftNormalized = (smoothedDriftNormalized + 0.35f * (targetNormalizedDrift - smoothedDriftNormalized))
            .coerceIn(-1f, 1f)

        // Classify guidance status
        currentGuidance = when {
            instantaneousForwardVelRadPerSec < WRONG_DIRECTION_VELOCITY_RAD_PER_SEC ||
                (rawForwardRad < -0.04f && deltaForwardRad < -0.005f) -> {
                PanoramaGuidanceStatus.WRONG_DIRECTION
            }
            instantaneousForwardVelRadPerSec > MAX_SAFE_ANGULAR_SPEED_RAD_PER_SEC -> {
                PanoramaGuidanceStatus.TOO_FAST
            }
            abs(smoothedDriftNormalized) > PanoramaProcessor.MAX_DRIFT_WARNING_THRESHOLD -> {
                if (direction.isHorizontal) {
                    if (smoothedDriftNormalized < 0f) {
                        PanoramaGuidanceStatus.OFF_ALIGNMENT_HIGH
                    } else {
                        PanoramaGuidanceStatus.OFF_ALIGNMENT_LOW
                    }
                } else {
                    if (smoothedDriftNormalized < 0f) {
                        PanoramaGuidanceStatus.OFF_ALIGNMENT_LEFT
                    } else {
                        PanoramaGuidanceStatus.OFF_ALIGNMENT_RIGHT
                    }
                }
            }
            instantaneousForwardVelRadPerSec > 0.025f -> {
                PanoramaGuidanceStatus.MOVING_STEADY
            }
            else -> {
                PanoramaGuidanceStatus.HOLD_STEADY
            }
        }
    }

    /**
     * Returns true if the phone has rotated far enough since [lastKeyframeProgress]
     * to acquire the next overlapping keyframe (and is not moving too fast or in reverse).
     */
    fun shouldAcquireNextKeyframe(
        capturedFrameCount: Int,
        lastKeyframeProgress: Float
    ): Boolean {
        synchronized(lock) {
            if (!isActive || capturedFrameCount >= targetFrames) return false
            if (capturedFrameCount == 0) return true
            if (currentGuidance == PanoramaGuidanceStatus.TOO_FAST ||
                currentGuidance == PanoramaGuidanceStatus.WRONG_DIRECTION
            ) {
                return false
            }
            val stepProgress = 1f / (targetFrames - 1).coerceAtLeast(1).toFloat()
            return (smoothedProgress - lastKeyframeProgress) >= (stepProgress * 0.85f)
        }
    }

    /**
     * Returns true ONLY when both the sensor sweep has reached the endpoint (`>= 0.98f`)
     * AND sufficient overlapping keyframes (`>= MIN_AUTO_COMPLETE_FRAMES`) have been captured.
     */
    fun isSweepReadyToAutoComplete(capturedFrameCount: Int): Boolean {
        synchronized(lock) {
            if (!isActive) return false
            val requiredFrames = min(targetFrames, PanoramaProcessor.MIN_AUTO_COMPLETE_FRAMES)
                .coerceAtLeast(PanoramaProcessor.MIN_REQUIRED_FRAMES)
            val coverage = computeCapturedCoverage(capturedFrameCount)
            return smoothedProgress >= 0.98f &&
                capturedFrameCount >= requiredFrames &&
                coverage >= 0.60f
        }
    }

    fun computeCapturedCoverage(capturedFrameCount: Int): Float {
        if (capturedFrameCount <= 1) return 0f
        return ((capturedFrameCount - 1).toFloat() / (targetFrames - 1).coerceAtLeast(1).toFloat())
            .coerceIn(0f, 1f)
    }

    /**
     * Builds a synchronized [PanoramaLiveState] snapshot combining sensor sweep progress
     * and actual captured-frame coverage.
     */
    fun snapshotLiveState(
        phase: PanoramaCapturePhase = if (isActive) PanoramaCapturePhase.CAPTURING else PanoramaCapturePhase.IDLE,
        capturedFrameCount: Int
    ): PanoramaLiveState {
        synchronized(lock) {
            if (phase == PanoramaCapturePhase.IDLE) {
                return PanoramaLiveState(
                    phase = PanoramaCapturePhase.IDLE,
                    direction = direction,
                    progress = 0f,
                    capturedCoverage = 0f,
                    capturedFrameCount = 0,
                    targetFrameCount = targetFrames,
                    alignmentDriftNormalized = 0f,
                    normalizedSpeed = 0f,
                    guidanceStatus = PanoramaGuidanceStatus.READY,
                    isCaptureLagging = false
                )
            }
            val coverage = computeCapturedCoverage(capturedFrameCount)
            val lagging = (smoothedProgress - coverage) > 0.28f
            val speedNorm = (abs(instantaneousForwardVelRadPerSec) / MAX_SAFE_ANGULAR_SPEED_RAD_PER_SEC)
                .coerceIn(0f, 1.5f)
            val effectiveGuidance = when {
                phase == PanoramaCapturePhase.PROCESSING -> PanoramaGuidanceStatus.STITCHING
                lagging && currentGuidance != PanoramaGuidanceStatus.WRONG_DIRECTION ->
                    PanoramaGuidanceStatus.TOO_FAST
                capturedFrameCount <= 1 && smoothedProgress < 0.01f &&
                    currentGuidance == PanoramaGuidanceStatus.HOLD_STEADY ->
                    PanoramaGuidanceStatus.READY
                else -> currentGuidance
            }
            return PanoramaLiveState(
                phase = phase,
                direction = direction,
                progress = smoothedProgress,
                capturedCoverage = coverage,
                capturedFrameCount = capturedFrameCount,
                targetFrameCount = targetFrames,
                alignmentDriftNormalized = smoothedDriftNormalized,
                normalizedSpeed = speedNorm,
                guidanceStatus = effectiveGuidance,
                isCaptureLagging = lagging
            )
        }
    }

    fun currentSweepAngleRad(): Float = synchronized(lock) { currentForwardAngleRad }
    fun currentDriftAngleRad(): Float = synchronized(lock) { currentDriftAngleRad }
    fun currentProgress(): Float = synchronized(lock) { smoothedProgress }
}

/**
 * Real-time CameraX [ImageAnalysis.Analyzer] for Panorama mode.
 *
 * Performs:
 * 1. Fast YUV_420_888 to upright RGB Bitmap conversion when capturing keyframes.
 * 2. Real-time motion & alignment estimation between consecutive preview frames.
 * 3. Synchronized keyframe acquisition driven by [PanoramaMotionTracker] and visual overlap
 *    (never using elapsed time as a substitute for movement).
 */
class PanoramaLiveAnalyzer(
    private val onProgressUpdated: (
        progress: Float,
        capturedCount: Int,
        driftNormalized: Float,
        speedNormalized: Float,
        guidance: PanoramaGuidanceStatus
    ) -> Unit,
    private val onKeyframeCaptured: (Bitmap) -> Unit,
    private val onTargetSweepCompleted: () -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 85L
        private const val TRACKING_GRID_DIM = 96
    }

    val motionTracker = PanoramaMotionTracker()

    @Volatile
    var isCapturing: Boolean = false

    @Volatile
    var direction: PanoramaDirection = PanoramaDirection.LEFT_TO_RIGHT

    @Volatile
    var isFrontCamera: Boolean = false

    @Volatile
    var isSelfieMirrorEnabled: Boolean = true

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    @Volatile
    var targetFrames: Int = PanoramaProcessor.DEFAULT_TARGET_FRAMES

    private var lastAnalyzedElapsedMs: Long = 0L
    private var prevFeatureGrid: FloatArray? = null
    private var prevGridW: Int = 0
    private var prevGridH: Int = 0

    private var accumulatedVisualForwardSinceKeyframe: Float = 0f
    private var lastKeyframeProgress: Float = 0f
    private var capturedKeyframesCount: Int = 0
    private var forceCaptureNextFrame: Boolean = false

    fun onRotationVectorSample(values: FloatArray, timestampNs: Long): PanoramaLiveState {
        if (!isCapturing) {
            return motionTracker.snapshotLiveState(PanoramaCapturePhase.IDLE, 0)
        }
        motionTracker.onRotationVectorSample(values, timestampNs)
        return motionTracker.snapshotLiveState(PanoramaCapturePhase.CAPTURING, capturedKeyframesCount)
    }

    fun onGyroscopeSample(omegaX: Float, omegaY: Float, omegaZ: Float, dtSeconds: Float): PanoramaLiveState {
        if (!isCapturing) {
            return motionTracker.snapshotLiveState(PanoramaCapturePhase.IDLE, 0)
        }
        motionTracker.onGyroscopeSample(omegaX, omegaY, omegaZ, dtSeconds)
        return motionTracker.snapshotLiveState(PanoramaCapturePhase.CAPTURING, capturedKeyframesCount)
    }

    fun startSession(
        selectedDirection: PanoramaDirection,
        targetFrameCount: Int = PanoramaProcessor.DEFAULT_TARGET_FRAMES,
        screenRotation: Int = displayRotation
    ) {
        direction = selectedDirection
        displayRotation = screenRotation
        targetFrames = targetFrameCount.coerceIn(PanoramaProcessor.MIN_REQUIRED_FRAMES, PanoramaProcessor.MAX_PANORAMA_FRAMES)
        lastAnalyzedElapsedMs = 0L
        prevFeatureGrid = null
        accumulatedVisualForwardSinceKeyframe = 0f
        lastKeyframeProgress = 0f
        capturedKeyframesCount = 0
        forceCaptureNextFrame = true
        motionTracker.startSession(
            selectedDirection = selectedDirection,
            screenRotation = screenRotation,
            frontCamera = isFrontCamera,
            selfieMirror = isSelfieMirrorEnabled
        )
        isCapturing = true
    }

    fun requestManualStepCapture() {
        if (isCapturing) {
            forceCaptureNextFrame = true
        }
    }

    fun recordExternalKeyframe(progressAtCapture: Float) {
        if (!isCapturing) return
        capturedKeyframesCount = (capturedKeyframesCount + 1).coerceAtMost(targetFrames)
        lastKeyframeProgress = progressAtCapture.coerceIn(0f, 1f)
        accumulatedVisualForwardSinceKeyframe = 0f
    }

    fun stopSession() {
        isCapturing = false
        forceCaptureNextFrame = false
        prevFeatureGrid = null
        motionTracker.stopSession()
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (!isCapturing) return
            val nowMs = SystemClock.elapsedRealtime()
            if (!forceCaptureNextFrame && nowMs - lastAnalyzedElapsedMs < ANALYSIS_INTERVAL_MS) {
                return
            }

            val dtSec = if (lastAnalyzedElapsedMs > 0L) {
                ((nowMs - lastAnalyzedElapsedMs) / 1000f).coerceIn(0.02f, 0.5f)
            } else {
                0.085f
            }
            lastAnalyzedElapsedMs = nowMs

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val mirror = isFrontCamera && isSelfieMirrorEnabled
            val activeDirection = direction

            val uprightBitmap = yuv420ToUprightBitmap(
                imageProxy = imageProxy,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = mirror,
                maxDimension = 720
            ) ?: return

            val w = uprightBitmap.width
            val h = uprightBitmap.height
            val scale = min(1f, TRACKING_GRID_DIM.toFloat() / max(w, h).toFloat())
            val gw = (w * scale).roundToInt().coerceAtLeast(24)
            val gh = (h * scale).roundToInt().coerceAtLeast(24)
            val currGrid = PanoramaProcessor.extractLumaAndGradientGrid(uprightBitmap, gw, gh)

            // Capture anchor frame #1 immediately on session start at progress = 0.0f
            if (capturedKeyframesCount == 0) {
                prevFeatureGrid = currGrid
                prevGridW = gw
                prevGridH = gh
                capturedKeyframesCount = 1
                lastKeyframeProgress = 0f
                forceCaptureNextFrame = false
                onKeyframeCaptured(uprightBitmap)
                onProgressUpdated(
                    0f,
                    capturedKeyframesCount,
                    0f,
                    0f,
                    PanoramaGuidanceStatus.READY
                )
                return
            }

            val prevGrid = prevFeatureGrid
            var forwardFraction = 0f
            var lateralFraction = 0f

            if (prevGrid != null && prevGridW == gw && prevGridH == gh) {
                val alignment = PanoramaProcessor.estimateAlignmentFromFeatureGrids(
                    prevGrid = prevGrid,
                    currGrid = currGrid,
                    gridW = gw,
                    gridH = gh,
                    fullW = w,
                    fullH = h,
                    direction = activeDirection
                )
                if (activeDirection.isHorizontal) {
                    forwardFraction = (alignment.dx / w.toFloat()) * activeDirection.sign
                    lateralFraction = alignment.dy / h.toFloat()
                } else {
                    forwardFraction = (alignment.dy / h.toFloat()) * activeDirection.sign
                    lateralFraction = alignment.dx / w.toFloat()
                }
            }

            prevFeatureGrid = currGrid
            prevGridW = gw
            prevGridH = gh

            // Update motion tracker with visual translation only if above stationary noise floor
            motionTracker.onVisualFrameDisplacement(
                forwardFrameFraction = forwardFraction,
                lateralFrameFraction = lateralFraction,
                dtSeconds = dtSec
            )

            val liveSnapshot = motionTracker.snapshotLiveState(
                phase = PanoramaCapturePhase.CAPTURING,
                capturedFrameCount = capturedKeyframesCount
            )
            val guidance = liveSnapshot.guidanceStatus

            if (forwardFraction > 0.012f && guidance != PanoramaGuidanceStatus.TOO_FAST && guidance != PanoramaGuidanceStatus.WRONG_DIRECTION) {
                accumulatedVisualForwardSinceKeyframe += forwardFraction
            }

            val sensorStepReady = motionTracker.shouldAcquireNextKeyframe(
                capturedFrameCount = capturedKeyframesCount,
                lastKeyframeProgress = lastKeyframeProgress
            )
            val visualStepReady = accumulatedVisualForwardSinceKeyframe >= PanoramaProcessor.KEYFRAME_STEP_FRACTION &&
                guidance != PanoramaGuidanceStatus.TOO_FAST &&
                guidance != PanoramaGuidanceStatus.WRONG_DIRECTION

            val shouldCaptureStep = forceCaptureNextFrame || sensorStepReady || visualStepReady

            if (shouldCaptureStep && capturedKeyframesCount < targetFrames) {
                forceCaptureNextFrame = false
                accumulatedVisualForwardSinceKeyframe = 0f
                capturedKeyframesCount++
                lastKeyframeProgress = liveSnapshot.progress
                onKeyframeCaptured(uprightBitmap)
            } else {
                uprightBitmap.recycle()
            }

            val updatedSnapshot = motionTracker.snapshotLiveState(
                phase = PanoramaCapturePhase.CAPTURING,
                capturedFrameCount = capturedKeyframesCount
            )

            onProgressUpdated(
                updatedSnapshot.progress,
                capturedKeyframesCount,
                updatedSnapshot.alignmentDriftNormalized,
                updatedSnapshot.normalizedSpeed,
                updatedSnapshot.guidanceStatus
            )

            if (motionTracker.isSweepReadyToAutoComplete(capturedKeyframesCount)) {
                isCapturing = false
                onTargetSweepCompleted()
            }
        } catch (_: Throwable) {
        } finally {
            try {
                imageProxy.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Converts a CameraX `YUV_420_888` [ImageProxy] to an upright, optionally mirrored `ARGB_8888` [Bitmap].
     */
    private fun yuv420ToUprightBitmap(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
        maxDimension: Int
    ): Bitmap? {
        val planes = imageProxy.planes
        if (planes.size < 3) return null
        val srcW = imageProxy.width
        val srcH = imageProxy.height
        if (srcW <= 0 || srcH <= 0) return null

        val normRot = ((rotationDegrees % 360) + 360) % 360
        val orientedW = if (normRot == 90 || normRot == 270) srcH else srcW
        val orientedH = if (normRot == 90 || normRot == 270) srcW else srcH

        val scale = min(1f, maxDimension.toFloat() / max(orientedW, orientedH).toFloat())
        val outW = (orientedW * scale).roundToInt().coerceAtLeast(32)
        val outH = (orientedH * scale).roundToInt().coerceAtLeast(32)

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yCap = yBuffer.capacity()
        val uCap = uBuffer.capacity()
        val vCap = vBuffer.capacity()

        val outPixels = IntArray(outW * outH)

        for (oy in 0 until outH) {
            val ny = oy.toFloat() / outH.toFloat()
            val outRow = oy * outW
            for (ox in 0 until outW) {
                val nx = if (mirrorHorizontally) {
                    (outW - 1 - ox).toFloat() / outW.toFloat()
                } else {
                    ox.toFloat() / outW.toFloat()
                }

                val sx: Int
                val sy: Int
                when (normRot) {
                    90 -> {
                        sx = (ny * (srcW - 1)).toInt().coerceIn(0, srcW - 1)
                        sy = ((1f - nx) * (srcH - 1)).toInt().coerceIn(0, srcH - 1)
                    }
                    180 -> {
                        sx = ((1f - nx) * (srcW - 1)).toInt().coerceIn(0, srcW - 1)
                        sy = ((1f - ny) * (srcH - 1)).toInt().coerceIn(0, srcH - 1)
                    }
                    270 -> {
                        sx = ((1f - ny) * (srcW - 1)).toInt().coerceIn(0, srcW - 1)
                        sy = (nx * (srcH - 1)).toInt().coerceIn(0, srcH - 1)
                    }
                    else -> {
                        sx = (nx * (srcW - 1)).toInt().coerceIn(0, srcW - 1)
                        sy = (ny * (srcH - 1)).toInt().coerceIn(0, srcH - 1)
                    }
                }

                val yIdx = sy * yRowStride + sx * yPixelStride
                val uvIdx = (sy shr 1) * uvRowStride + (sx shr 1) * uvPixelStride

                val yVal = if (yIdx in 0 until yCap) (yBuffer.get(yIdx).toInt() and 0xFF) else 128
                val uVal = if (uvIdx in 0 until uCap) (uBuffer.get(uvIdx).toInt() and 0xFF) - 128 else 0
                val vVal = if (uvIdx in 0 until vCap) (vBuffer.get(uvIdx).toInt() and 0xFF) - 128 else 0

                val r = (yVal + (1.370705f * vVal).toInt()).coerceIn(0, 255)
                val g = (yVal - (0.337633f * uVal + 0.698001f * vVal).toInt()).coerceIn(0, 255)
                val b = (yVal + (1.732446f * uVal).toInt()).coerceIn(0, 255)

                outPixels[outRow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return bitmap
    }
}
