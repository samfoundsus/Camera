package com.example.core.camera

import com.example.core.model.CameraMode
import com.example.core.preferences.AspectRatioMode
import com.example.core.preferences.HdOutputOption
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Single source of truth for resolving the actual still-image capture dimensions (width x height)
 * and formatting megapixel labels across the entire app (Quick Settings header, HD mode button,
 * HD resolution card, Settings screen, hardware debug screen, and capture pipeline).
 *
 * Rules:
 * - Real values only: computed strictly from actual `(width, height)` pixel dimensions.
 * - Never estimates or falls back to hardcoded numbers; returns `null` if dimensions cannot be read.
 * - Shared formatting: rounds to 1 decimal place below 10 MP (e.g. `2.6 MP`, never rounding `2.58` to `3`),
 *   and to a whole number from 10 MP upward (e.g. `12 MP`, `108 MP`).
 */
object CaptureResolutionResolver {

    /**
     * Shared megapixel formatter used everywhere in the app.
     * - Returns `null` if [width] or [height] is missing or non-positive.
     * - Below 10 MP: 1 decimal place (e.g. `1856 x 1392` -> `2.6 MP`).
     * - 10 MP and above: whole number (e.g. `4000 x 3000` -> `12 MP`, `12000 x 9000` -> `108 MP`).
     */
    fun formatMegapixels(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val totalPixels = width.toLong() * height.toLong()
        if (totalPixels <= 0L) return null
        val mp = totalPixels / 1_000_000.0
        val roundedOneDecimal = round(mp * 10.0) / 10.0
        return if (roundedOneDecimal < 10.0) {
            String.format(Locale.US, "%.1f MP", roundedOneDecimal)
        } else {
            "${round(mp).toInt()} MP"
        }
    }

    /**
     * Convenience overload for formatting a [PhotoOutputSize] using the single shared format.
     */
    fun formatMegapixels(size: PhotoOutputSize?): String? {
        if (size == null) return null
        return formatMegapixels(size.width, size.height)
    }

    /**
     * Formats real megapixels together with the exact pixel dimensions, e.g. `"2.6 MP (1856 x 1392)"`.
     * Returns `null` if dimensions are unavailable or invalid.
     */
    fun formatMegapixelsWithDimensions(width: Int, height: Int): String? {
        val mpLabel = formatMegapixels(width, height) ?: return null
        return "$mpLabel ($width x $height)"
    }

    /**
     * Convenience overload for formatting a [PhotoOutputSize] with exact pixel dimensions.
     */
    fun formatMegapixelsWithDimensions(size: PhotoOutputSize?): String? {
        if (size == null) return null
        return formatMegapixelsWithDimensions(size.width, size.height)
    }

    /**
     * Returns the largest exposed hardware still JPEG size across all discovered streams
     * (standard, high-resolution, and API 31+ maximum-resolution / physical sub-camera streams).
     * Returns `null` if no valid positive dimensions were reported.
     */
    fun resolveHighestExposedStillSize(lensCaps: LensCapabilities?): PhotoOutputSize? {
        if (lensCaps == null) return null
        val hdSource = lensCaps.hdMaxPhotoCaptureSource?.takeIf { it.width > 0 && it.height > 0 }
        val nativeMax = lensCaps.highestNativeStillSize?.takeIf { it.width > 0 && it.height > 0 }
        val listMax = lensCaps.supportedPhotoSizes
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.totalPixels }

        val candidates = buildList {
            if (hdSource != null) {
                add(PhotoOutputSize(hdSource.width, hdSource.height, isSensorMaxResolution = true))
            }
            if (nativeMax != null) {
                add(nativeMax)
            }
            if (listMax != null) {
                add(listMax)
            }
        }
        return candidates.maxByOrNull { it.totalPixels }
    }

    /**
     * Resolves the hardware JPEG stream size selected for capture before post-capture aspect cropping.
     * Used by the capture pipeline and as the base for [resolveActualCaptureSize].
     */
    fun resolveHardwareCaptureStreamSize(
        lensCaps: LensCapabilities?,
        activeMode: CameraMode,
        aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3,
        hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE
    ): PhotoOutputSize? {
        if (lensCaps == null || activeMode.isVideo) return null
        val highestExposed = resolveHighestExposedStillSize(lensCaps)

        return when (activeMode) {
            CameraMode.HD -> {
                if (hdOutputOption == HdOutputOption.MAX_NATIVE) {
                    highestExposed
                        ?: lensCaps.selectOptimalPhotoOutputSize(aspectRatioMode, preferMaxNative = true)
                            ?.takeIf { it.width > 0 && it.height > 0 }
                } else {
                    lensCaps.selectOptimalPhotoOutputSize(aspectRatioMode, preferMaxNative = false)
                        ?.takeIf { it.width > 0 && it.height > 0 }
                        ?: highestExposed
                }
            }
            CameraMode.PANORAMA -> {
                lensCaps.selectOptimalPhotoOutputSize(AspectRatioMode.RATIO_4_3, preferMaxNative = false)
                    ?.takeIf { it.width > 0 && it.height > 0 }
                    ?: highestExposed
            }
            CameraMode.PHOTO,
            CameraMode.PORTRAIT,
            CameraMode.NIGHT_SIGHT,
            CameraMode.PRO,
            CameraMode.ASTRO -> {
                lensCaps.selectOptimalPhotoOutputSize(aspectRatioMode, preferMaxNative = false)
                    ?.takeIf { it.width > 0 && it.height > 0 }
                    ?: highestExposed
            }
            CameraMode.VIDEO,
            CameraMode.SLOW_MOTION,
            CameraMode.TIME_LAPSE -> null
        }
    }

    /**
     * Single source of truth for the actual still-image dimensions `(width x height)` that will be saved
     * for the given camera, mode, aspect ratio, and HD Capture Quality option.
     *
     * Accounts for post-capture aspect-ratio cropping using the exact same formula as
     * `AspectCropUtils.calculateCropRect` and `AspectCropUtils.processCapturedJpegBytes` whenever the
     * chosen hardware JPEG stream does not natively match [aspectRatioMode], so every UI indicator
     * matches the exact saved file dimensions.
     *
     * Returns `null` if the camera has no readable JPEG size or if [activeMode] is a video mode.
     */
    fun resolveActualCaptureSize(
        lensCaps: LensCapabilities?,
        activeMode: CameraMode,
        aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3,
        hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE
    ): PhotoOutputSize? {
        val rawStream = resolveHardwareCaptureStreamSize(
            lensCaps = lensCaps,
            activeMode = activeMode,
            aspectRatioMode = aspectRatioMode,
            hdOutputOption = hdOutputOption
        ) ?: return null

        if (activeMode == CameraMode.PANORAMA) {
            return rawStream
        }

        val srcWidth = rawStream.width
        val srcHeight = rawStream.height
        val isPortrait = srcHeight >= srcWidth
        val targetRatio = when (aspectRatioMode) {
            AspectRatioMode.RATIO_1_1 -> 1.0f
            AspectRatioMode.RATIO_4_3 -> if (isPortrait) 3f / 4f else 4f / 3f
            AspectRatioMode.RATIO_9_16 -> if (isPortrait) 9f / 16f else 16f / 9f
        }
        val currentRatio = srcWidth.toFloat() / srcHeight.toFloat()

        val cropWidth: Int
        val cropHeight: Int
        if (currentRatio > targetRatio) {
            cropHeight = srcHeight
            cropWidth = (srcHeight * targetRatio).roundToInt().coerceAtMost(srcWidth)
        } else {
            cropWidth = srcWidth
            cropHeight = (srcWidth / targetRatio).roundToInt().coerceAtMost(srcHeight)
        }

        val isNativeAspectMatch = abs(cropWidth - srcWidth) <= 2 && abs(cropHeight - srcHeight) <= 2
        if (isNativeAspectMatch || cropWidth <= 0 || cropHeight <= 0) {
            return rawStream
        }
        return rawStream.copy(width = cropWidth, height = cropHeight)
    }

    /**
     * Builds the HD mode button/pill label from [resolveActualCaptureSize] and [formatMegapixels],
     * e.g. `"HD · 2.6 MP"`, `"HD · 12 MP"`, `"HD · 108 MP"`, or `"HD"` when size cannot be read.
     */
    fun formatHdModeButtonLabel(
        lensCaps: LensCapabilities?,
        aspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3,
        hdOutputOption: HdOutputOption = HdOutputOption.MAX_NATIVE
    ): String {
        val captureSize = resolveActualCaptureSize(
            lensCaps = lensCaps,
            activeMode = CameraMode.HD,
            aspectRatioMode = aspectRatioMode,
            hdOutputOption = hdOutputOption
        )
        val mpLabel = formatMegapixels(captureSize)
        return if (mpLabel != null) "HD · $mpLabel" else "HD"
    }
}
