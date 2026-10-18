package com.example.core.preferences

import com.example.core.camera.CameraLens
import com.example.core.camera.LensFacing
import com.example.core.camera.ManualSensorCapabilities
import com.example.core.camera.parseShutterSpeedToNanos

/**
 * Immutable settings state for Pro manual camera mode.
 * Maintained completely separate from automatic camera modes to guarantee strict state isolation.
 */
data class ProCameraSettings(
    val iso: String = "AUTO",
    val shutterSpeed: String = "AUTO",
    val focusMode: ProFocusMode = ProFocusMode.AUTO,
    val focusDistance: Float = 0.0f,
    val whiteBalance: String = "AUTO",
    val ev: Float = 0.0f,
    val rawFormat: ProRawFormat = ProRawFormat.JPEG_ONLY,
    val histogramEnabled: Boolean = false,
    val focusPeakingEnabled: Boolean = false,
    val zoomRatio: Float = 1.0f
) {
    val isAutoExposure: Boolean
        get() = iso.equals("AUTO", ignoreCase = true) && shutterSpeed.equals("AUTO", ignoreCase = true)

    val isManualIso: Boolean
        get() = !iso.equals("AUTO", ignoreCase = true)

    val isManualShutter: Boolean
        get() = !shutterSpeed.equals("AUTO", ignoreCase = true)

    val isManualFocus: Boolean
        get() = focusMode == ProFocusMode.MANUAL

    val isManualWhiteBalance: Boolean
        get() = !whiteBalance.equals("AUTO", ignoreCase = true)

    /**
     * Validates and re-aligns this Pro settings instance against the active lens's verified hardware capabilities.
     * Prevents invalid or out-of-range sensor values when switching between front and rear cameras.
     */
    fun validateAgainstCapabilities(caps: ManualSensorCapabilities?): ProCameraSettings {
        if (caps == null) return this

        // 1. Revalidate ISO
        val validatedIso = if (iso.equals("AUTO", ignoreCase = true)) {
            "AUTO"
        } else {
            val isoVal = iso.toIntOrNull()
            if (caps.isManualIsoSupported && caps.isoRange != null && isoVal != null) {
                isoVal.coerceIn(caps.isoRange.start, caps.isoRange.endInclusive).toString()
            } else {
                "AUTO"
            }
        }

        // 2. Revalidate Shutter Speed
        val validatedShutter = if (shutterSpeed.equals("AUTO", ignoreCase = true)) {
            "AUTO"
        } else {
            val nanos = parseShutterSpeedToNanos(shutterSpeed)
            if (caps.isManualShutterSupported && caps.shutterSpeedRangeNanos != null && nanos != null) {
                if (nanos in caps.shutterSpeedRangeNanos) {
                    shutterSpeed
                } else {
                    // Clamp to closest supported limit
                    val clampedNanos = nanos.coerceIn(caps.shutterSpeedRangeNanos.start, caps.shutterSpeedRangeNanos.endInclusive)
                    com.example.core.camera.formatShutterSpeedFromNanos(clampedNanos)
                }
            } else {
                "AUTO"
            }
        }

        // 3. Revalidate Manual Focus
        val validatedFocusMode = if (caps.isManualFocusSupported) {
            focusMode
        } else {
            ProFocusMode.AUTO
        }
        val validatedFocusDistance = if (caps.isManualFocusSupported) {
            focusDistance.coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }

        // 4. Revalidate White Balance
        val validatedWb = if (whiteBalance.equals("AUTO", ignoreCase = true)) {
            "AUTO"
        } else {
            whiteBalance
        }

        // 5. Revalidate EV
        val validatedEv = if (caps.isExposureCompensationSupported && caps.evRange != null) {
            ev
        } else {
            0.0f
        }

        // 6. Revalidate Zoom
        val safeMinZoom = caps.minZoomRatio
        val safeMaxZoom = caps.maxZoomRatio.coerceAtLeast(safeMinZoom)
        val validatedZoom = zoomRatio.coerceIn(safeMinZoom, safeMaxZoom)

        return copy(
            iso = validatedIso,
            shutterSpeed = validatedShutter,
            focusMode = validatedFocusMode,
            focusDistance = validatedFocusDistance,
            whiteBalance = validatedWb,
            ev = validatedEv,
            zoomRatio = validatedZoom
        )
    }
}
