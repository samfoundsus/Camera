package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
        val processingDurationMs: Long
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
 * Astrophotography processor implementing multi-frame star alignment, outlier rejection noise reduction,
 * and safe fallback.
 */
object AstroProcessor {
    private const val TAG = "AstroProcessor"

    fun processAstroJpegBytes(
        sourceBytes: ByteArray,
        additionalFramesBytes: List<ByteArray>,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean,
        duration: AstroDuration,
        perFrameExposureMs: Long = 0L,
        totalAcquisitionMs: Long = 0L,
        jpegQuality: Int = 95
    ): AstroProcessingOutcome {
        val startTime = System.currentTimeMillis()
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
                val outBytes = compressBitmapToJpeg(processedBmp, jpegQuality)
                if (processedBmp != baseBitmap) {
                    runCatching { baseBitmap.recycle() }
                }
                val procTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Astro Single Frame Processed: Duration=${duration.displayName}, " +
                    "Rotation=$rotationDegrees, Output=${processedBmp.width}x${processedBmp.height}, " +
                    "PerFrameExpMs=${perFrameExposureMs}ms, AcquisitionMs=${totalAcquisitionMs}ms, ProcessingMs=${procTime}ms")
                return AstroProcessingOutcome.ProcessedAstro(
                    jpegBytes = outBytes,
                    width = processedBmp.width,
                    height = processedBmp.height,
                    framesStacked = 1,
                    durationLabel = duration.displayName,
                    perFrameExposureMs = perFrameExposureMs,
                    totalAcquisitionMs = totalAcquisitionMs,
                    processingDurationMs = procTime
                )
            }

            // Decode additional companion frames (unrotated raw frames for star alignment)
            val companionBitmaps = mutableListOf<Bitmap>()
            for (frameBytes in additionalFramesBytes) {
                decodeBitmapFromBytes(frameBytes)?.let {
                    if (it.width == width && it.height == height) {
                        companionBitmaps.add(it)
                    } else {
                        runCatching { it.recycle() }
                    }
                }
            }

            if (companionBitmaps.isEmpty()) {
                val processedBmp = applyCropAndOrientation(baseBitmap, rotationDegrees, aspectRatioMode, mirrorHorizontally)
                val outBytes = compressBitmapToJpeg(processedBmp, jpegQuality)
                if (processedBmp != baseBitmap) {
                    runCatching { baseBitmap.recycle() }
                }
                val procTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Astro Fallback Single Frame Processed: Duration=${duration.displayName}, " +
                    "Rotation=$rotationDegrees, Output=${processedBmp.width}x${processedBmp.height}, " +
                    "PerFrameExpMs=${perFrameExposureMs}ms, AcquisitionMs=${totalAcquisitionMs}ms, ProcessingMs=${procTime}ms")
                return AstroProcessingOutcome.ProcessedAstro(
                    jpegBytes = outBytes,
                    width = processedBmp.width,
                    height = processedBmp.height,
                    framesStacked = 1,
                    durationLabel = duration.displayName,
                    perFrameExposureMs = perFrameExposureMs,
                    totalAcquisitionMs = totalAcquisitionMs,
                    processingDurationMs = procTime
                )
            }

            // Multi-frame stacking with star alignment and outlier rejection
            val allBitmaps = listOf(baseBitmap) + companionBitmaps
            val stackedBmp = stackAstroBitmapsWithOutlierRejection(allBitmaps)

            val finalBmp = applyCropAndOrientation(stackedBmp, rotationDegrees, aspectRatioMode, mirrorHorizontally)
            val jpegOut = compressBitmapToJpeg(finalBmp, jpegQuality)

            val procTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Astro Multi-Frame Stacked: Duration=${duration.displayName}, " +
                "FramesStacked=${allBitmaps.size}, Rotation=$rotationDegrees, " +
                "Output=${finalBmp.width}x${finalBmp.height}, PerFrameExpMs=${perFrameExposureMs}ms, " +
                "AcquisitionMs=${totalAcquisitionMs}ms, ProcessingMs=${procTime}ms")

            // Cleanup bitmaps
            runCatching { baseBitmap.recycle() }
            companionBitmaps.forEach { runCatching { it.recycle() } }
            if (stackedBmp != baseBitmap) {
                runCatching { stackedBmp.recycle() }
            }
            if (finalBmp != stackedBmp) {
                runCatching { finalBmp.recycle() }
            }

            return AstroProcessingOutcome.ProcessedAstro(
                jpegBytes = jpegOut,
                width = finalBmp.width,
                height = finalBmp.height,
                framesStacked = allBitmaps.size,
                durationLabel = duration.displayName,
                perFrameExposureMs = perFrameExposureMs,
                totalAcquisitionMs = totalAcquisitionMs,
                processingDurationMs = procTime
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Astrophotography processing failed, falling back", e)
            return AstroProcessingOutcome.FallbackNightSight(
                jpegBytes = sourceBytes,
                width = 0,
                height = 0,
                reason = "Astrophotography exception: ${e.localizedMessage}"
            )
        }
    }

    private fun decodeBitmapFromBytes(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    data class StarPoint(val x: Float, val y: Float, val brightness: Float)

    /**
     * Identifies bright point-like star features in a frame.
     */
    fun findStarPoints(bitmap: Bitmap, maxStars: Int = 20): List<StarPoint> {
        val width = bitmap.width
        val height = bitmap.height
        val step = max(1, min(width, height) / 80)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val stars = mutableListOf<StarPoint>()
        for (y in step until height - step step step) {
            for (x in step until width - step step step) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val brightness = 0.299f * r + 0.587f * g + 0.114f * b

                if (brightness > 120f) {
                    val pLeft = pixels[y * width + (x - step)]
                    val bLeft = 0.299f * ((pLeft shr 16) and 0xFF) + 0.587f * ((pLeft shr 8) and 0xFF) + 0.114f * (pLeft and 0xFF)
                    val pRight = pixels[y * width + (x + step)]
                    val bRight = 0.299f * ((pRight shr 16) and 0xFF) + 0.587f * ((pRight shr 8) and 0xFF) + 0.114f * (pRight and 0xFF)

                    if (brightness > bLeft && brightness > bRight) {
                        stars.add(StarPoint(x.toFloat(), y.toFloat(), brightness))
                    }
                }
            }
        }
        return stars.sortedByDescending { it.brightness }.take(maxStars)
    }

    /**
     * Estimates translational shift (dx, dy) to align companion bitmap to base bitmap.
     * Rejects shift if motion is excessive (> 50px).
     */
    fun estimateStarAlignmentShift(baseBitmap: Bitmap, companionBitmap: Bitmap): Pair<Float, Float>? {
        val baseStars = findStarPoints(baseBitmap, 15)
        val compStars = findStarPoints(companionBitmap, 15)
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
     * Multi-frame stacking with star alignment, outlier rejection (sigma clipping / min-max trimming)
     * for satellite trail and noise rejection, preserving star detail.
     */
    fun stackAstroBitmapsWithOutlierRejection(bitmaps: List<Bitmap>): Bitmap {
        if (bitmaps.isEmpty()) throw IllegalArgumentException("No bitmaps to stack")
        if (bitmaps.size == 1) return bitmaps.first()

        val base = bitmaps.first()
        val width = base.width
        val height = base.height
        val count = bitmaps.size

        val alignedBitmaps = mutableListOf<Bitmap>()
        alignedBitmaps.add(base)

        for (i in 1 until count) {
            val comp = bitmaps[i]
            val shift = estimateStarAlignmentShift(base, comp)
            if (shift != null && (shift.first != 0f || shift.second != 0f)) {
                val matrix = Matrix().apply { postTranslate(shift.first, shift.second) }
                val aligned = Bitmap.createBitmap(comp, 0, 0, width, height, matrix, true)
                alignedBitmaps.add(aligned)
            } else if (shift != null) {
                alignedBitmaps.add(comp)
            } else {
                Log.w(TAG, "Frame $i rejected due to excessive alignment shift (unstable movement)")
            }
        }

        val numAligned = alignedBitmaps.size
        val result = base.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val frameBuffers = Array(numAligned) { idx ->
            val buf = IntArray(width * height)
            alignedBitmaps[idx].getPixels(buf, 0, width, 0, 0, width, height)
            buf
        }

        val rVals = IntArray(numAligned)
        val gVals = IntArray(numAligned)
        val bVals = IntArray(numAligned)

        for (i in pixels.indices) {
            for (f in 0 until numAligned) {
                val p = frameBuffers[f][i]
                rVals[f] = (p shr 16) and 0xFF
                gVals[f] = (p shr 8) and 0xFF
                bVals[f] = p and 0xFF
            }

            val rAvg: Int
            val gAvg: Int
            val bAvg: Int

            if (numAligned >= 3) {
                rVals.sort()
                gVals.sort()
                bVals.sort()
                var rSum = 0
                var gSum = 0
                var bSum = 0
                val validCount = numAligned - 2
                for (j in 1 until numAligned - 1) {
                    rSum += rVals[j]
                    gSum += gVals[j]
                    bSum += bVals[j]
                }
                rAvg = rSum / validCount
                gAvg = gSum / validCount
                bAvg = bSum / validCount
            } else {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (j in 0 until numAligned) {
                    rSum += rVals[j]
                    gSum += gVals[j]
                    bSum += bVals[j]
                }
                rAvg = rSum / numAligned
                gAvg = gSum / numAligned
                bAvg = bSum / numAligned
            }

            // Star details & sky contrast enhancement
            val rEnh = (rAvg * 1.08f).roundToInt().coerceIn(0, 255)
            val gEnh = (gAvg * 1.08f).roundToInt().coerceIn(0, 255)
            val bEnh = (bAvg * 1.12f).roundToInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (rEnh shl 16) or (gEnh shl 8) or bEnh
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)

        for (i in 1 until numAligned) {
            if (alignedBitmaps[i] != bitmaps.getOrNull(i)) {
                runCatching { alignedBitmaps[i].recycle() }
            }
        }

        return result
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
