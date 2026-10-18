package com.example.core.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.media.FaceDetector
import android.util.Base64
import com.example.core.camera.PortraitBlurDeviceSupport
import com.example.core.camera.PortraitDepthSupportPath
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.PortraitBlurLevel
import com.example.core.preferences.PortraitRetouch
import java.io.ByteArrayOutputStream
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
    val isHardwareDepthDataUsed: Boolean = false
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
        val processingDurationMs: Long
    ) : PortraitBitmapResult

    data class FallbackOriginal(
        val originalBitmap: Bitmap,
        val reason: String
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
        val processingDurationMs: Long
    ) : PortraitProcessingOutcome

    data class FallbackOriginal(
        override val jpegBytes: ByteArray,
        override val width: Int,
        override val height: Int,
        override val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        val reason: String
    ) : PortraitProcessingOutcome
}

/**
 * On-device Portrait Mode segmentation, edge-preserving guided-filter alpha matting,
 * optional hardware Dynamic Depth fusion, and halo-free foreground-excluded aperture bokeh processor.
 *
 * Compatible with minimum Android API 29 without requiring external cloud calls:
 * - Uses Android's on-device [FaceDetector] + anatomical head/hair/shoulder YCbCr modeling for people.
 * - Uses multi-modal quantized perimeter border color palettes and multi-cluster foreground appearance
 *   modeling + spatial acutance energy for general subjects (immune to border variance inflation on
 *   textured or multi-colored backgrounds).
 * - Uses embedded hardware Dynamic Depth / GDepth closeness maps when genuinely present in the capture stream.
 * - Refines subject boundaries around hair, shoulders, and complex edges using a Fast 2D Box-Window
 *   Guided Filter (`alpha = a * I + b`) evaluated at full native resolution.
 * - Eliminates boundary halos by weighting background blur exclusively with `(1 - alpha)^3`, preventing
 *   foreground subject pixels from bleeding into the blurred background.
 */
object PortraitProcessor {

    private const val MAX_ANALYSIS_DIMENSION = 160
    private const val MAX_BOKEH_BUFFER_DIMENSION = 640
    private const val MAX_DETECTED_FACES = 5
    private const val MAX_BORDER_PALETTE_CLUSTERS = 8
    private const val MAX_FOREGROUND_SEED_CLUSTERS = 4
    const val MIN_SUBJECT_CONFIDENCE = 0.22f

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

    /**
     * Processes raw camera JPEG bytes through the complete Portrait pipeline:
     * 1. Decodes, rotates (`rotationDegrees`), crops (`aspectRatioMode`), and mirrors (`mirrorHorizontally` for front camera).
     * 2. Extracts embedded hardware depth map when supported and present, segments the foreground subject,
     *    and refines boundaries via Guided Filter alpha matting.
     * 3. Applies controllable halo-free background blur (`blurLevel` / `blurStrengthOverride`) and optional skin retouching (`retouch`).
     * 4. If blur is Off (`effectiveBlurStrength == 0f`), saves an unblurred image.
     * 5. If no subject is detected or any error occurs, preserves and returns the valid oriented/cropped original JPEG.
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
        jpegQuality: Int = 95
    ): PortraitProcessingOutcome? {
        if (sourceBytes.isEmpty()) return null

        val rawStrength = blurStrengthOverride ?: blurLevel.blurStrength
        val effectiveStrength = if (blurLevel == PortraitBlurLevel.OFF && blurStrengthOverride == null) {
            0.0f
        } else {
            PortraitBlurLevel.normalizeBlurStrength(rawStrength)
        }
        val effectiveLevel = PortraitBlurLevel.fromBlurStrength(effectiveStrength)

        // Always prepare the valid oriented & cropped baseline payload first so fallback is guaranteed
        val baselinePayload = AspectCropUtils.processCapturedJpegBytes(
            sourceBytes = sourceBytes,
            rotationDegrees = rotationDegrees,
            aspectRatioMode = aspectRatioMode,
            mirrorHorizontally = mirrorHorizontally,
            jpegQuality = jpegQuality
        ) ?: return null

        // If user turned Portrait Blur OFF (and retouch is OFF), return the clean unblurred baseline directly
        if (effectiveStrength <= 0.0f && retouch == PortraitRetouch.OFF) {
            return PortraitProcessingOutcome.ProcessedPortrait(
                jpegBytes = baselinePayload.jpegBytes,
                width = baselinePayload.width,
                height = baselinePayload.height,
                exifOrientation = baselinePayload.exifOrientation,
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
                processingDurationMs = 1L
            )
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val rawDecoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOptions)
            ?: return PortraitProcessingOutcome.FallbackOriginal(
                jpegBytes = baselinePayload.jpegBytes,
                width = baselinePayload.width,
                height = baselinePayload.height,
                exifOrientation = baselinePayload.exifOrientation,
                reason = "Decoded bitmap unavailable; saved original photo."
            )

        var orientedCropped: Bitmap? = null
        try {
            orientedCropped = AspectCropUtils.cropAndOrientBitmap(
                sourceBitmap = rawDecoded,
                aspectRatioMode = aspectRatioMode,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = mirrorHorizontally
            )
            if (orientedCropped != rawDecoded && !rawDecoded.isRecycled) {
                rawDecoded.recycle()
            }

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
                hardwareDepthH = extractedDepthMap?.third ?: 0
            )

            return when (bitmapResult) {
                is PortraitBitmapResult.Success -> {
                    try {
                        val outStream = ByteArrayOutputStream(baselinePayload.jpegBytes.size.coerceAtLeast(65536))
                        val safeQuality = jpegQuality.coerceIn(85, 100)
                        val ok = bitmapResult.outputBitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, outStream)
                        if (!ok) {
                            PortraitProcessingOutcome.FallbackOriginal(
                                jpegBytes = baselinePayload.jpegBytes,
                                width = baselinePayload.width,
                                height = baselinePayload.height,
                                exifOrientation = baselinePayload.exifOrientation,
                                reason = "JPEG encoding failed; saved original photo."
                            )
                        } else {
                            PortraitProcessingOutcome.ProcessedPortrait(
                                jpegBytes = outStream.toByteArray(),
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
                                processingDurationMs = bitmapResult.processingDurationMs
                            )
                        }
                    } finally {
                        if (bitmapResult.outputBitmap != orientedCropped && !bitmapResult.outputBitmap.isRecycled) {
                            bitmapResult.outputBitmap.recycle()
                        }
                    }
                }
                is PortraitBitmapResult.FallbackOriginal -> {
                    // If user explicitly set Blur = Off, saving the unblurred baseline is the intended result
                    if (effectiveStrength <= 0.0f) {
                        PortraitProcessingOutcome.ProcessedPortrait(
                            jpegBytes = baselinePayload.jpegBytes,
                            width = baselinePayload.width,
                            height = baselinePayload.height,
                            exifOrientation = baselinePayload.exifOrientation,
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
                            processingDurationMs = 1L
                        )
                    } else {
                        PortraitProcessingOutcome.FallbackOriginal(
                            jpegBytes = baselinePayload.jpegBytes,
                            width = baselinePayload.width,
                            height = baselinePayload.height,
                            exifOrientation = baselinePayload.exifOrientation,
                            reason = bitmapResult.reason
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            return PortraitProcessingOutcome.FallbackOriginal(
                jpegBytes = baselinePayload.jpegBytes,
                width = baselinePayload.width,
                height = baselinePayload.height,
                exifOrientation = baselinePayload.exifOrientation,
                reason = "Portrait processing error (${t.javaClass.simpleName}); saved original photo."
            )
        } finally {
            if (orientedCropped != null && !orientedCropped.isRecycled) {
                orientedCropped.recycle()
            }
            if (!rawDecoded.isRecycled) {
                rawDecoded.recycle()
            }
        }
    }

    /**
     * Segments the foreground subject and applies halo-free background blur and optional portrait retouching
     * to [sourceBitmap]. Never modifies [sourceBitmap] in place.
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
        hardwareDepthH: Int = 0
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
            val mask = segmentSubject(
                sourceBitmap = sourceBitmap,
                focusNormX = focusNormX.coerceIn(0.1f, 0.9f),
                focusNormY = focusNormY.coerceIn(0.1f, 0.9f),
                hardwareDepthNormalized = hardwareDepthNormalized,
                hardwareDepthW = hardwareDepthW,
                hardwareDepthH = hardwareDepthH
            )

            val fullW = sourceBitmap.width
            val fullH = sourceBitmap.height

            // When Blur is OFF, always return an unblurred image (applying optional skin retouch if subject is present)
            if (effectiveStrength <= 0.0f) {
                val fullPixels = IntArray(fullW * fullH)
                sourceBitmap.getPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)
                if (retouch != PortraitRetouch.OFF &&
                    mask.subjectType != DetectedPortraitSubjectType.NONE &&
                    mask.confidence >= MIN_SUBJECT_CONFIDENCE
                ) {
                    applySubtleSkinRetouchInPlace(
                        pixels = fullPixels,
                        width = fullW,
                        height = fullH,
                        mask = mask,
                        retouch = retouch
                    )
                }
                val unblurredOut = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
                unblurredOut.setPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)
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
                    processingDurationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                )
            }

            if (mask.subjectType == DetectedPortraitSubjectType.NONE || mask.confidence < MIN_SUBJECT_CONFIDENCE) {
                return PortraitBitmapResult.FallbackOriginal(
                    originalBitmap = sourceBitmap,
                    reason = "No distinct subject detected for Portrait blur; preserved original photo."
                )
            }

            // Compute bokeh buffer dimensions (caps background blur buffer at 640px for fast processing while
            // keeping the foreground subject at 100% native resolution)
            val maxDim = max(fullW, fullH)
            val bokehScale = if (maxDim > MAX_BOKEH_BUFFER_DIMENSION) {
                MAX_BOKEH_BUFFER_DIMENSION.toFloat() / maxDim.toFloat()
            } else {
                1.0f
            }
            val bokehW = (fullW * bokehScale).roundToInt().coerceAtLeast(4)
            val bokehH = (fullH * bokehScale).roundToInt().coerceAtLeast(4)

            val maxSupported = PortraitBlurDeviceSupport.getMaxSupportedBlurStrength()
            val clampedStrength = effectiveStrength.coerceIn(0.0f, maxSupported)

            val maxRadius = (min(bokehW, bokehH) * 0.08f).coerceIn(16f, 36f)
            val minRadius = 2.0f
            val norm = if (clampedStrength <= 1.0f) {
                (0.08f * clampedStrength).coerceIn(0.02f, 0.08f)
            } else {
                (0.08f + 0.92f * ((clampedStrength - 1.0f) / 15.0f).coerceIn(0f, 1f))
            }
            val bokehRadiusPx = (minRadius + (maxRadius - minRadius) * norm).roundToInt().coerceAtLeast(2)
            val fullEquivalentRadiusPx = (bokehRadiusPx / bokehScale).roundToInt().coerceAtLeast(bokehRadiusPx)

            // Build halo-free blurred background buffer with foreground pixels excluded (weight = (1 - alpha)^3)
            val blurredBgPixels = try {
                computeHaloFreeBlurredBackground(
                    sourceBitmap = sourceBitmap,
                    mask = mask,
                    bokehW = bokehW,
                    bokehH = bokehH,
                    blurRadius = bokehRadiusPx,
                    blurStrength = clampedStrength
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

            // Composite at full native resolution using Guided Filter edge-refined alpha matte
            val fullPixels = IntArray(fullW * fullH)
            sourceBitmap.getPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)

            // Optional edge-preserving skin retouching for human portrait subjects
            if (retouch != PortraitRetouch.OFF) {
                applySubtleSkinRetouchInPlace(
                    pixels = fullPixels,
                    width = fullW,
                    height = fullH,
                    mask = mask,
                    retouch = retouch
                )
            }

            val maskW = mask.width
            val maskH = mask.height
            val coeffA = mask.guideCoeffA
            val coeffB = mask.guideCoeffB

            for (y in 0 until fullH) {
                val rowOffset = y * fullW
                val my = ((y + 0.5f) * maskH / fullH.toFloat() - 0.5f).coerceIn(0f, (maskH - 1).toFloat())
                val by = ((y * (bokehH - 1)) / (fullH - 1).coerceAtLeast(1)).coerceIn(0, bokehH - 1)
                val bokehRow = by * bokehW

                // Subtle ground-plane depth taper at the very bottom 10% of the frame
                val verticalDepthWeight = if (y > fullH * 0.90f) {
                    val t = (y - fullH * 0.90f) / (fullH * 0.10f).coerceAtLeast(1f)
                    (1f - 0.20f * t).coerceIn(0.80f, 1f)
                } else {
                    1.0f
                }

                for (x in 0 until fullW) {
                    val idx = rowOffset + x
                    val fgColor = fullPixels[idx]
                    val fgR = (fgColor shr 16) and 0xFF
                    val fgG = (fgColor shr 8) and 0xFF
                    val fgB = fgColor and 0xFF

                    // Full-resolution pixel luminance I_full in [0..1]
                    val lumaFull = (0.299f * fgR + 0.587f * fgG + 0.114f * fgB) / 255f
                    val mx = ((x + 0.5f) * maskW / fullW.toFloat() - 0.5f).coerceIn(0f, (maskW - 1).toFloat())

                    // Bilinearly sample Guided Filter coefficients (a, b) and evaluate exact edge-aligned alpha
                    val aVal = sampleBilinear(coeffA, maskW, maskH, mx, my)
                    val bVal = sampleBilinear(coeffB, maskW, maskH, mx, my)
                    val rawAlpha = (aVal * lumaFull + bVal).coerceIn(0f, 1f)

                    // Smoothstep contrast refinement on alpha so subject core is 1.0 and background is 0.0
                    // while hair & shoulder transitions remain smooth and halo-free
                    val normT = ((rawAlpha - 0.14f) / 0.72f).coerceIn(0f, 1f)
                    val refinedAlpha = normT * normT * (3f - 2f * normT)

                    if (refinedAlpha >= 0.97f) {
                        // Subject core: 100% sharp original/retouched foreground pixel
                        continue
                    }

                    val bx = ((x * (bokehW - 1)) / (fullW - 1).coerceAtLeast(1)).coerceIn(0, bokehW - 1)
                    val bgColor = blurredBgPixels[bokehRow + bx]
                    val bgR = (bgColor shr 16) and 0xFF
                    val bgG = (bgColor shr 8) and 0xFF
                    val bgB = bgColor and 0xFF

                    val effectiveBgMix = ((1f - refinedAlpha) * verticalDepthWeight).coerceIn(0f, 1f)
                    val effectiveFgMix = 1f - effectiveBgMix

                    val outR = (fgR * effectiveFgMix + bgR * effectiveBgMix).roundToInt().coerceIn(0, 255)
                    val outG = (fgG * effectiveFgMix + bgG * effectiveBgMix).roundToInt().coerceIn(0, 255)
                    val outB = (fgB * effectiveFgMix + bgB * effectiveBgMix).roundToInt().coerceIn(0, 255)

                    fullPixels[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }

            val outputBitmap = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
            outputBitmap.setPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)

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
                processingDurationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        } catch (t: Throwable) {
            PortraitBitmapResult.FallbackOriginal(
                originalBitmap = sourceBitmap,
                reason = "Portrait effect failed (${t.localizedMessage ?: t.javaClass.simpleName}); preserved original."
            )
        }
    }

    /**
     * Performs on-device subject detection and segmentation on [sourceBitmap], returning a
     * [SubjectSegmentationMask] with Guided Filter linear matting coefficients (`guideCoeffA`, `guideCoeffB`).
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
        if (workW % 2 != 0) workW++
        val workH = (srcH * scale).roundToInt().coerceAtLeast(8)
        val totalPixels = workW * workH

        // Resample directly from sourceBitmap's pixel array without allocating an intermediate scaled Bitmap
        val workPixels = resampleBitmapPixels(sourceBitmap, workW, workH)

        val luma = FloatArray(totalPixels)
        val cbArr = FloatArray(totalPixels)
        val crArr = FloatArray(totalPixels)

        for (i in 0 until totalPixels) {
            val c = workPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val yVal = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            luma[i] = yVal
            cbArr[i] = cb
            crArr[i] = cr
        }

        // 1. Build multi-modal quantized perimeter border background color palette
        val borderClusters = buildBorderBackgroundClusters(workW, workH, luma, cbArr, crArr)

        // 2. Detect human faces via Android FaceDetector + biometric YCbCr skin/head cluster analysis
        val faceAnchors = detectFacesAndBiometricClusters(
            workW = workW,
            workH = workH,
            workPixels = workPixels,
            luma = luma,
            cbArr = cbArr,
            crArr = crArr,
            borderClusters = borderClusters
        )

        // 3. Evaluate Human Subject Probability Field if one or more faces/biometric anchors are present
        val personProb = FloatArray(totalPixels)
        if (faceAnchors.isNotEmpty()) {
            computePersonProbabilityMask(
                workW = workW,
                workH = workH,
                luma = luma,
                cbArr = cbArr,
                crArr = crArr,
                faceAnchors = faceAnchors,
                borderClusters = borderClusters,
                outPersonProb = personProb
            )
        }

        // 4. Evaluate General Salient Foreground Subject Probability Field around focus/center seed
        val salientProb = FloatArray(totalPixels)
        val contrastScore = computeSalientSubjectProbabilityMask(
            workW = workW,
            workH = workH,
            luma = luma,
            cbArr = cbArr,
            crArr = crArr,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            borderClusters = borderClusters,
            outSalientProb = salientProb
        )

        // 5. Fuse person, salient subject, and optional hardware depth closeness map
        val hasValidHardwareDepth = hardwareDepthNormalized != null &&
            hardwareDepthW >= 4 &&
            hardwareDepthH >= 4 &&
            hardwareDepthNormalized.size >= hardwareDepthW * hardwareDepthH

        val coarseMask = FloatArray(totalPixels)
        if (hasValidHardwareDepth && hardwareDepthNormalized != null) {
            for (y in 0 until workH) {
                val row = y * workW
                val dy = ((y + 0.5f) * hardwareDepthH / workH.toFloat() - 0.5f).coerceIn(0f, (hardwareDepthH - 1).toFloat())
                for (x in 0 until workW) {
                    val dx = ((x + 0.5f) * hardwareDepthW / workW.toFloat() - 0.5f).coerceIn(0f, (hardwareDepthW - 1).toFloat())
                    val depthCloseness = sampleBilinear(hardwareDepthNormalized, hardwareDepthW, hardwareDepthH, dx, dy).coerceIn(0f, 1f)
                    val segProb = max(personProb[row + x], salientProb[row + x])
                    coarseMask[row + x] = (0.62f * segProb + 0.38f * depthCloseness).coerceIn(0f, 1f)
                }
            }
        } else {
            for (i in 0 until totalPixels) {
                coarseMask[i] = max(personProb[i], salientProb[i]).coerceIn(0f, 1f)
            }
        }

        val cleanedMask = cleanupCoarseMask(coarseMask, workW, workH)

        // 6. Compute Guided Filter linear coefficients (a, b) & refined alpha matte on working grid
        val guideRadius = max(2, min(workW, workH) / 28)
        val (coeffA, coeffB, refinedAlpha) = computeGuidedFilterMatte(
            guidanceLuma = luma,
            coarseMask = cleanedMask,
            width = workW,
            height = workH,
            radius = guideRadius,
            eps = 0.0018f
        )

        // 7. Compute subject bounding box, coverage ratio, and overall subject confidence
        var fgCount = 0
        var minX = workW
        var minY = workH
        var maxX = -1
        var maxY = -1
        var fgConfidenceSum = 0f

        for (y in 0 until workH) {
            val row = y * workW
            for (x in 0 until workW) {
                val a = refinedAlpha[row + x]
                if (a >= 0.45f) {
                    fgCount++
                    fgConfidenceSum += a
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val coverage = fgCount.toFloat() / totalPixels.coerceAtLeast(1)
        val hasValidCoverage = coverage in 0.012f..0.94f
        val meanFgStrength = if (fgCount > 0) fgConfidenceSum / fgCount else 0f

        val subjectType: DetectedPortraitSubjectType
        val overallConfidence: Float
        if (faceAnchors.isNotEmpty() && hasValidCoverage) {
            subjectType = DetectedPortraitSubjectType.PERSON
            val maxFaceConf = faceAnchors.maxOf { it.confidence }
            overallConfidence = (0.45f * maxFaceConf + 0.35f * meanFgStrength + 0.20f * contrastScore.coerceIn(0f, 1f))
                .coerceIn(0f, 1f)
        } else if (hasValidCoverage && contrastScore >= 0.14f) {
            subjectType = DetectedPortraitSubjectType.GENERAL_SUBJECT
            overallConfidence = (0.55f * contrastScore + 0.45f * meanFgStrength).coerceIn(0f, 1f)
        } else {
            subjectType = DetectedPortraitSubjectType.NONE
            overallConfidence = 0f
        }

        val boundsNorm = if (maxX >= minX && maxY >= minY) {
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
            subjectType = subjectType,
            confidence = overallConfidence,
            subjectCoverageRatio = coverage,
            detectedFaceCount = faceAnchors.size,
            subjectBoundsNormalized = boundsNorm,
            isHardwareDepthDataUsed = hasValidHardwareDepth
        )
    }

    /**
     * Resamples [sourceBitmap] directly into a [dstW] x [dstH] pixel array using bilinear interpolation.
     * Avoids allocating intermediate [Bitmap] objects on the heap and works reliably across Android & JVM tests.
     */
    internal fun resampleBitmapPixels(sourceBitmap: Bitmap, dstW: Int, dstH: Int): IntArray {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        val srcPixels = IntArray(srcW * srcH)
        sourceBitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        if (srcW == dstW && srcH == dstH) {
            return srcPixels
        }

        val dstPixels = IntArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val sy = ((dy + 0.5f) * srcH / dstH.toFloat() - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = dy * dstW

            for (dx in 0 until dstW) {
                val sx = ((dx + 0.5f) * srcW / dstW.toFloat() - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - x0

                val c00 = srcPixels[row0 + x0]
                val c10 = srcPixels[row0 + x1]
                val c01 = srcPixels[row1 + x0]
                val c11 = srcPixels[row1 + x1]

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

        // 1. Run Android SDK FaceDetector on an even-width RGB_565 bitmap populated from workPixels
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

        // 2. Perceptual YCbCr skin-chrominance cluster detection with head-crown vs. shoulder localization
        val marginX = (workW * 0.08f).toInt().coerceAtLeast(2)
        val marginY = (workH * 0.06f).toInt().coerceAtLeast(2)
        val maxScanY = (workH * 0.82f).toInt().coerceAtMost(workH - marginY)

        // Check if the outer border itself is predominantly skin-colored (e.g. a warm wood/tan wall)
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
                // Localize the upper head/face lobe (top 45% of skin vertical span) so bare/warm shoulders
                // do not pull the face center downward into the chest
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
        if (yVal !in 0.16f..0.92f) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (r <= g || r <= b || (r - min(g, b)) < 18) return false
        return cb in 77f..127f && cr in 133f..175f
    }

    /**
     * Builds a multi-modal quantized perimeter border color palette (up to [MAX_BORDER_PALETTE_CLUSTERS] modes).
     * Unlike a single global border average, quantizing perimeter border pixels into tight color modes prevents
     * multi-color or checkerboard backgrounds from inflating cluster variance (`stdY`, `stdCb`, `stdCr`).
     */
    private fun buildBorderBackgroundClusters(
        workW: Int,
        workH: Int,
        luma: FloatArray,
        cbArr: FloatArray,
        crArr: FloatArray
    ): List<ColorCluster> {
        val strip = max(2, min(workW, workH) / 12)
        val maxSideY = (workH * 0.78f).toInt().coerceAtLeast(strip + 1)

        // Quantize border samples into 6 x 6 x 6 YCbCr histogram bins (216 bins)
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

        // Top border strip
        for (y in 0 until strip) {
            val row = y * workW
            for (x in 0 until workW) {
                addBorderSample(row + x)
            }
        }
        // Left & right upper-to-mid border strips
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

            // Keep tight perceptual bandwidths per mode so distinct foreground colors never collapse into background
            val stdY = sqrt(varY).coerceIn(0.045f, 0.085f)
            val stdCb = sqrt(varCb).coerceIn(4.5f, 8.0f)
            val stdCr = sqrt(varCr).coerceIn(4.5f, 8.0f)

            // Merge if very close to an already selected dominant border cluster
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

    /**
     * Extracts up to [MAX_FOREGROUND_SEED_CLUSTERS] dominant color modes inside a foreground seed rectangle.
     */
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
                // Prefer seed pixels that are not identical to the outer border palette
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
            // Head & hair ellipse center and radii
            val headCx = anchor.centerX
            val headCy = anchor.centerY - 0.06f * dEye
            val headRx = (1.38f * dEye).coerceAtLeast(6f)
            val headRy = (1.78f * dEye).coerceAtLeast(8f)

            // Torso / shoulders center and radii extending downward from neck
            val torsoCx = anchor.centerX
            val torsoTopY = anchor.centerY + 1.00f * dEye
            val torsoCy = anchor.centerY + 2.95f * dEye
            val torsoRx = (2.95f * dEye).coerceAtLeast(12f)
            val torsoRy = (2.75f * dEye).coerceAtLeast(12f)

            // Sample face, hair-crown, and torso appearance clusters
            val faceSeed = sampleRegionColorCluster(
                workW = workW,
                workH = workH,
                luma = luma,
                cbArr = cbArr,
                crArr = crArr,
                cx = headCx,
                cy = anchor.centerY,
                rx = dEye * 0.72f,
                ry = dEye * 0.80f
            )
            val hairSeed = sampleRegionColorCluster(
                workW = workW,
                workH = workH,
                luma = luma,
                cbArr = cbArr,
                crArr = crArr,
                cx = headCx,
                cy = (headCy - headRy * 0.68f).coerceAtLeast(1f),
                rx = dEye * 0.85f,
                ry = dEye * 0.35f
            )
            val torsoSeed = sampleRegionColorCluster(
                workW = workW,
                workH = workH,
                luma = luma,
                cbArr = cbArr,
                crArr = crArr,
                cx = torsoCx,
                cy = min((workH - 2).toFloat(), torsoTopY + dEye * 1.15f),
                rx = dEye * 1.35f,
                ry = dEye * 1.05f
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

                    // Spatial anatomical prior
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

                    // Appearance similarity vs multi-modal border palette and person foreground clusters
                    val bgDist = minNormalizedColorDistance(luma[idx], cbArr[idx], crArr[idx], borderClusters)
                    val fgDist = minNormalizedColorDistance(luma[idx], cbArr[idx], crArr[idx], fgPersonClusters)

                    // Inner face core (including eyes, brows, nose, mouth) is protected unconditionally
                    if (distSqHead <= 0.48f) {
                        outPersonProb[idx] = max(outPersonProb[idx], 0.98f)
                        continue
                    }

                    // If pixel outside the inner face core strongly matches a border background cluster, reject it
                    if (bgDist < 0.85f && fgDist > bgDist * 1.15f) {
                        continue
                    }

                    val colorLikelihood = ((bgDist - 0.45f) / (bgDist + fgDist + 0.30f)).coerceIn(0f, 1f)
                    val combined = if (distSqHead <= 0.88f || distSqTorso <= 0.62f) {
                        (0.65f * spatialPrior + 0.35f * colorLikelihood).coerceIn(0f, 1f)
                    } else {
                        (spatialPrior * (0.25f + 0.75f * colorLikelihood)).coerceIn(0f, 1f)
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
            workW = workW,
            workH = workH,
            luma = luma,
            cbArr = cbArr,
            crArr = crArr,
            cx = seedCx,
            cy = seedCy,
            rx = seedRx,
            ry = seedRy,
            borderClusters = borderClusters
        )

        // Check how distinct the foreground seed clusters are from the multi-modal perimeter border palette
        var weightedSeedToBorderDist = 0f
        var weightSum = 0f
        for (fgCluster in fgSeedClusters) {
            val d = minNormalizedColorDistance(
                fgCluster.meanY,
                fgCluster.meanCb,
                fgCluster.meanCr,
                borderClusters
            )
            weightedSeedToBorderDist += d * fgCluster.weight
            weightSum += fgCluster.weight
        }
        val seedToBorderDist = if (weightSum > 0f) weightedSeedToBorderDist / weightSum else 0f

        // Compute local 3x3 Sobel gradient magnitude to capture in-focus subject acutance
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

        // Measure interior vs border gradient energy
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

        // If the seed region has virtually identical color AND low gradient relative to the border,
        // the scene is flat/uniform without a distinct subject
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

                // Pixels that closely match any perimeter border mode are background (0f)
                if (bgDist < 0.80f && bgDist < fgDist * 0.90f) {
                    outSalientProb[idx] = 0f
                    continue
                }

                // Color contrast ratio: high when pixel is far from border background and close to foreground seed
                val colorRatio = ((bgDist - 0.55f) / (bgDist + fgDist + 0.35f)).coerceIn(0f, 1f)
                val edgeBoost = if (colorRatio > 0.35f) (gradMag[idx] / maxGrad).coerceIn(0f, 1f) * 0.14f else 0f

                // Suppress outer perimeter ring from being classified as salient foreground
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
            val d = normalizedSingleClusterDistance(yVal, cb, cr, c)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    private fun normalizedSingleClusterDistance(
        yVal: Float,
        cb: Float,
        cr: Float,
        cluster: ColorCluster
    ): Float {
        val dy = abs(yVal - cluster.meanY) / cluster.stdY.coerceAtLeast(0.045f)
        val dcb = abs(cb - cluster.meanCb) / cluster.stdCb.coerceAtLeast(4.5f)
        val dcr = abs(cr - cluster.meanCr) / cluster.stdCr.coerceAtLeast(4.5f)
        return sqrt(0.35f * dy * dy + 0.325f * dcb * dcb + 0.325f * dcr * dcr)
    }

    /**
     * Performs 3x3 morphological closing (dilation followed by erosion) and interior smoothing
     * to solidify the subject core and remove isolated single-pixel background noise.
     */
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

    /**
     * Fast 2D Box-Window Guided Filter (He et al.) using separable sliding-window passes.
     * Aligns the coarse probability mask [coarseMask] with the exact luminance edges of [guidanceLuma].
     * Returns `(meanA, meanB, refinedAlpha)`.
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

        val meanI = boxFilter2D(guidanceLuma, width, height, radius)
        val meanP = boxFilter2D(coarseMask, width, height, radius)
        val meanII = boxFilter2D(iSq, width, height, radius)
        val meanIP = boxFilter2D(iTimesP, width, height, radius)

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

        val meanA = boxFilter2D(a, width, height, radius)
        val meanB = boxFilter2D(b, width, height, radius)
        val refined = FloatArray(n)
        for (idx in 0 until n) {
            val raw = (meanA[idx] * guidanceLuma[idx] + meanB[idx]).coerceIn(0f, 1f)
            val t = ((raw - 0.14f) / 0.72f).coerceIn(0f, 1f)
            refined[idx] = t * t * (3f - 2f * t)
        }
        return Triple(meanA, meanB, refined)
    }

    private fun boxFilter2D(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val horiz = FloatArray(width * height)
        val out = FloatArray(width * height)
        val r = radius.coerceIn(1, max(1, min(width, height) / 2))

        // Horizontal pass
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(width - 1)
                var sum = 0f
                for (kx in x0..x1) {
                    sum += src[row + kx]
                }
                horiz[row + x] = sum / (x1 - x0 + 1)
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            for (y in 0 until height) {
                val y0 = (y - r).coerceAtLeast(0)
                val y1 = (y + r).coerceAtMost(height - 1)
                var sum = 0f
                for (ky in y0..y1) {
                    sum += horiz[ky * width + x]
                }
                out[y * width + x] = sum / (y1 - y0 + 1)
            }
        }
        return out
    }

    /**
     * Computes a halo-free blurred background image of dimensions [bokehW] x [bokehH] using
     * foreground-excluded normalized convolution (`weight = (1 - alpha)^3 * bokehHighlightBoost`).
     * Because foreground subject pixels have near-zero weight, foreground colors never bleed
     * into the blurred background around hair or shoulders.
     */
    private fun computeHaloFreeBlurredBackground(
        sourceBitmap: Bitmap,
        mask: SubjectSegmentationMask,
        bokehW: Int,
        bokehH: Int,
        blurRadius: Int,
        blurStrength: Float
    ): IntArray {
        val n = bokehW * bokehH
        val srcPixels = resampleBitmapPixels(sourceBitmap, bokehW, bokehH)

        val wR = FloatArray(n)
        val wG = FloatArray(n)
        val wB = FloatArray(n)
        val wSum = FloatArray(n)

        val maskW = mask.width
        val maskH = mask.height
        val alphaMatte = mask.alphaMatte

        for (y in 0 until bokehH) {
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

                // Strictly exclude solid foreground pixels from contributing to background blur
                val bgFactor = (1.0f - fgAlpha).coerceIn(0f, 1f)
                val baseWeight = if (fgAlpha >= 0.85f) {
                    0.0001f
                } else {
                    bgFactor * bgFactor * bgFactor + 0.0005f
                }

                // Subtle specular bokeh highlight emphasis on bright out-of-focus background highlights
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val boostFactor = if (blurStrength <= 1.0f) blurStrength.coerceIn(0.1f, 1.0f) else (blurStrength / 16.0f).coerceIn(0.1f, 1.0f)
                val highlightBoost = if (fgAlpha < 0.30f && lum > 0.78f) {
                    1.0f + 1.25f * boostFactor * ((lum - 0.78f) / 0.22f)
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

        // Separable sliding-window passes approximate a smooth circular/Gaussian aperture bokeh kernel
        var curWR = wR
        var curWG = wG
        var curWB = wB
        var curWSum = wSum
        val passRadius = max(1, (blurRadius * 0.80f).roundToInt())
        val numPasses = if (blurStrength >= 2.0f || (blurStrength in 0.75f..1.0f)) 3 else 2

        repeat(numPasses) {
            curWR = boxFilter2D(curWR, bokehW, bokehH, passRadius)
            curWG = boxFilter2D(curWG, bokehW, bokehH, passRadius)
            curWB = boxFilter2D(curWB, bokehW, bokehH, passRadius)
            curWSum = boxFilter2D(curWSum, bokehW, bokehH, passRadius)
        }

        val outPixels = IntArray(n)
        for (i in 0 until n) {
            val denom = curWSum[i]
            if (denom > 1e-4f) {
                val r = (curWR[i] / denom).roundToInt().coerceIn(0, 255)
                val g = (curWG[i] / denom).roundToInt().coerceIn(0, 255)
                val b = (curWB[i] / denom).roundToInt().coerceIn(0, 255)
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                outPixels[i] = srcPixels[i]
            }
        }
        return outPixels
    }

    /**
     * Attempts to extract an embedded Android Dynamic Depth / GDepth closeness map from [sourceBytes]
     * when captured on a hardware depth-capable camera. Returns `(normalizedClosenessGrid, gridW, gridH)`
     * or `null` if the JPEG does not contain an embedded depth image.
     */
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

            // 1. Check for XMP GDepth:Data base64 payload
            val asciiWindow = String(
                sourceBytes,
                0,
                min(sourceBytes.size, 65536),
                Charsets.ISO_8859_1
            )
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

            // 2. Check for appended Dynamic Depth secondary JPEG stream after primary EOI (0xFF 0xD9 ... 0xFF 0xD8)
            if (depthImageBytes == null) {
                var eoiIndex = -1
                for (i in 2 until sourceBytes.size - 4) {
                    if (sourceBytes[i] == 0xFF.toByte() && sourceBytes[i + 1] == 0xD9.toByte()) {
                        // Look ahead for secondary SOI (0xFF 0xD8 0xFF)
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
            val orientedDepthBmp = try {
                AspectCropUtils.cropAndOrientBitmap(
                    sourceBitmap = rawDepthBmp,
                    aspectRatioMode = aspectRatioMode,
                    rotationDegrees = rotationDegrees,
                    mirrorHorizontally = mirrorHorizontally
                )
            } finally {
                if (!rawDepthBmp.isRecycled) {
                    // Recycle below if orientedDepthBmp != rawDepthBmp
                }
            }

            val gw = targetW.coerceAtLeast(8)
            val gh = targetH.coerceAtLeast(8)
            val depthPixels = resampleBitmapPixels(orientedDepthBmp, gw, gh)
            if (orientedDepthBmp != rawDepthBmp && !orientedDepthBmp.isRecycled) {
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

    /**
     * Applies subtle edge-preserving skin smoothing strictly within foreground skin-tone pixels
     * when [PortraitRetouch.NATURAL] or [PortraitRetouch.SMOOTH] is active.
     */
    private fun applySubtleSkinRetouchInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: SubjectSegmentationMask,
        retouch: PortraitRetouch
    ) {
        val blendStrength = when (retouch) {
            PortraitRetouch.OFF -> return
            PortraitRetouch.NATURAL -> 0.28f
            PortraitRetouch.SMOOTH -> 0.52f
        }
        val copy = pixels.clone()
        val maskW = mask.width
        val maskH = mask.height
        val alphaMatte = mask.alphaMatte

        for (y in 1 until height - 1) {
            val row = y * width
            val my = ((y * (maskH - 1)) / (height - 1).coerceAtLeast(1)).coerceIn(0, maskH - 1)
            val maskRow = my * maskW
            for (x in 1 until width - 1) {
                val mx = ((x * (maskW - 1)) / (width - 1).coerceAtLeast(1)).coerceIn(0, maskW - 1)
                if (alphaMatte[maskRow + mx] < 0.65f) continue

                val idx = row + x
                val c = copy[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yVal = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
                if (!isSkinTonePixel(c, yVal, cb, cr)) continue

                // Bilateral 3x3 neighborhood average (only averages neighbors with similar luminance to preserve eyes/brows)
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var wTotal = 0f
                for (ny in (y - 1)..(y + 1)) {
                    val nRow = ny * width
                    for (nx in (x - 1)..(x + 1)) {
                        val nc = copy[nRow + nx]
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
                if (wTotal > 0f) {
                    val smoothR = sumR / wTotal
                    val smoothG = sumG / wTotal
                    val smoothB = sumB / wTotal
                    val outR = (r * (1f - blendStrength) + smoothR * blendStrength).roundToInt().coerceIn(0, 255)
                    val outG = (g * (1f - blendStrength) + smoothG * blendStrength).roundToInt().coerceIn(0, 255)
                    val outB = (b * (1f - blendStrength) + smoothB * blendStrength).roundToInt().coerceIn(0, 255)
                    pixels[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
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
