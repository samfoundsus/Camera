package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.example.core.camera.HdrSupportPath
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.HdrMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs

sealed interface HdrProcessingOutcome {
    data class ProcessedHdr(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val exifOrientation: Int,
        val framesMerged: Int,
        val durationMs: Long,
        val supportPath: HdrSupportPath,
        val alignmentShifts: List<Pair<Int, Int>> = emptyList()
    ) : HdrProcessingOutcome

    data class FallbackSingleFrame(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val exifOrientation: Int,
        val reason: String
    ) : HdrProcessingOutcome
}

/**
 * Genuine computational multi-frame HDR processor for Photo mode.
 *
 * Integrates:
 * 1. Multi-frame exposure bracket capture handling (e.g. [-1.5 EV, 0.0 EV, +1.5 EV]).
 * 2. Translation alignment across bracketed frames using mean-normalized luma shift estimation.
 * 3. Mertens multi-exposure fusion with local contrast, well-exposedness, and saturation weighting.
 * 4. Motion detection and ghosting rejection against the 0 EV reference frame.
 * 5. Reinhard highlight tone mapping and shadow lift to preserve dynamic range.
 * 6. Safe single-frame fallback on memory constraints, capture timeouts, or processing errors.
 */
object HdrProcessor {

    private const val TAG = "HdrProcessor"

    fun processHdrJpegBytes(
        sourceBytes: ByteArray,
        additionalFramesBytes: List<ByteArray> = emptyList(),
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean = false,
        hdrMode: HdrMode = HdrMode.AUTO,
        supportPath: HdrSupportPath = HdrSupportPath.SOFTWARE_MULTI_FRAME_HDR,
        jpegQuality: Int = 95
    ): HdrProcessingOutcome {
        val startTime = System.currentTimeMillis()

        if (hdrMode == HdrMode.OFF) {
            val fallbackPayload = AspectCropUtils.processCapturedJpegBytes(
                sourceBytes = sourceBytes,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                jpegQuality = jpegQuality
            )
            return if (fallbackPayload != null) {
                HdrProcessingOutcome.FallbackSingleFrame(
                    jpegBytes = fallbackPayload.jpegBytes,
                    width = fallbackPayload.width,
                    height = fallbackPayload.height,
                    exifOrientation = fallbackPayload.exifOrientation,
                    reason = "HDR mode is OFF"
                )
            } else {
                HdrProcessingOutcome.FallbackSingleFrame(
                    jpegBytes = sourceBytes,
                    width = 0,
                    height = 0,
                    exifOrientation = 1,
                    reason = "Failed to decode frame when HDR mode was OFF"
                )
            }
        }

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

            val additionalBitmaps = additionalFramesBytes.mapNotNull { bytes ->
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                }.getOrNull()
            }.filter { it.width == primaryBitmap.width && it.height == primaryBitmap.height }

            val allFrames = mutableListOf<Bitmap>()
            val evOffsets = mutableListOf<Float>()

            if (additionalBitmaps.size >= 2) {
                // Bracketed 3-frame sequence: [-1.5 EV, 0.0 EV, +1.5 EV]
                allFrames.add(additionalBitmaps[0])
                evOffsets.add(-1.5f)

                allFrames.add(primaryBitmap)
                evOffsets.add(0.0f)

                allFrames.add(additionalBitmaps[1])
                evOffsets.add(1.5f)
            } else if (additionalBitmaps.size == 1) {
                allFrames.add(primaryBitmap)
                evOffsets.add(0.0f)

                allFrames.add(additionalBitmaps[0])
                evOffsets.add(1.0f)
            } else {
                allFrames.add(primaryBitmap)
                evOffsets.add(0.0f)
            }

            if (allFrames.size < 2) {
                val fallbackPayload = AspectCropUtils.processCapturedJpegBytes(
                    sourceBytes = sourceBytes,
                    rotationDegrees = rotationDegrees,
                    aspectRatioMode = aspectRatioMode,
                    mirrorHorizontally = mirrorHorizontally,
                    jpegQuality = jpegQuality
                )
                return if (fallbackPayload != null) {
                    HdrProcessingOutcome.FallbackSingleFrame(
                        jpegBytes = fallbackPayload.jpegBytes,
                        width = fallbackPayload.width,
                        height = fallbackPayload.height,
                        exifOrientation = fallbackPayload.exifOrientation,
                        reason = "Single frame available (no additional bracketed frames captured)"
                    )
                } else {
                    HdrProcessingOutcome.FallbackSingleFrame(
                        jpegBytes = sourceBytes,
                        width = primaryBitmap.width,
                        height = primaryBitmap.height,
                        exifOrientation = 1,
                        reason = "Fallback single frame process failed"
                    )
                }
            }

            val processor = SoftwareHdrProcessor()
            val fusionResult = processor.fuseExposureBracketedFrames(
                frames = allFrames,
                exposureEvOffsets = evOffsets
            )

            val fusedBitmap = when (fusionResult) {
                is ProcessingResult.Success -> fusionResult.outputBitmap
                else -> processor.fuseWithSafeFallback(allFrames, evOffsets)
            }

            val transformedBitmap = transformBitmap(
                bitmap = fusedBitmap,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally
            )

            val baos = ByteArrayOutputStream()
            transformedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            val outputBytes = baos.toByteArray()

            val duration = System.currentTimeMillis() - startTime
            val shifts = if (fusionResult is ProcessingResult.Success) fusionResult.frameAlignmentsPx else emptyList()

            HdrProcessingOutcome.ProcessedHdr(
                jpegBytes = outputBytes,
                width = transformedBitmap.width,
                height = transformedBitmap.height,
                exifOrientation = 1,
                framesMerged = allFrames.size,
                durationMs = duration,
                supportPath = supportPath,
                alignmentShifts = shifts
            )
        } catch (t: Throwable) {
            Log.e(TAG, "HDR fusion exception: ${t.localizedMessage}", t)
            fallbackToStandardProcessing(
                sourceBytes = sourceBytes,
                rotationDegrees = rotationDegrees,
                aspectRatioMode = aspectRatioMode,
                mirrorHorizontally = mirrorHorizontally,
                jpegQuality = jpegQuality,
                reason = "HDR fusion threw exception: ${t.localizedMessage}"
            )
        }
    }

    private fun fallbackToStandardProcessing(
        sourceBytes: ByteArray,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean,
        jpegQuality: Int,
        reason: String
    ): HdrProcessingOutcome {
        val payload = AspectCropUtils.processCapturedJpegBytes(
            sourceBytes = sourceBytes,
            rotationDegrees = rotationDegrees,
            aspectRatioMode = aspectRatioMode,
            mirrorHorizontally = mirrorHorizontally,
            jpegQuality = jpegQuality
        )
        return if (payload != null) {
            HdrProcessingOutcome.FallbackSingleFrame(
                jpegBytes = payload.jpegBytes,
                width = payload.width,
                height = payload.height,
                exifOrientation = payload.exifOrientation,
                reason = reason
            )
        } else {
            HdrProcessingOutcome.FallbackSingleFrame(
                jpegBytes = sourceBytes,
                width = 0,
                height = 0,
                exifOrientation = 1,
                reason = "Primary frame fallback failed"
            )
        }
    }

    private fun transformBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        aspectRatioMode: AspectRatioMode,
        mirrorHorizontally: Boolean
    ): Bitmap {
        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (mirrorHorizontally) {
            matrix.postScale(-1f, 1f)
        }

        val rotated = if (!matrix.isIdentity) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val targetRatio = aspectRatioMode.ratioFloat ?: return rotated
        val currentRatio = rotated.width.toFloat() / rotated.height.toFloat()

        if (abs(currentRatio - targetRatio) < 0.02f) {
            return rotated
        }

        var cropWidth = rotated.width
        var cropHeight = rotated.height

        if (currentRatio > targetRatio) {
            cropWidth = (rotated.height * targetRatio).toInt().coerceAtMost(rotated.width)
        } else {
            cropHeight = (rotated.width / targetRatio).toInt().coerceAtMost(rotated.height)
        }

        val cropX = (rotated.width - cropWidth) / 2
        val cropY = (rotated.height - cropHeight) / 2

        return Bitmap.createBitmap(rotated, cropX, cropY, cropWidth, cropHeight)
    }
}
