package com.example.core.camera

import android.app.ActivityManager
import android.content.Context

/**
 * Dynamically evaluates runtime device constraints (available memory heap, low-RAM flags, and CPU cores)
 * to safely determine multi-frame low-light capture limits and fallback thresholds for Night Sight.
 *
 * Never claims unverified hardware capabilities; explicitly separates hardware scene support
 * from software multi-frame fusion.
 */
object NightSightDeviceSupport {
    const val MAX_FRAMES_ALLOWED = 4
    const val DEFAULT_AUTO_FRAMES = 3
    const val MIN_FRAMES = 1

    /**
     * Detects dynamic device and memory constraints to determine the maximum number of frames
     * that this device can reliably buffer and fuse simultaneously without risk of OOM.
     */
    fun getMaxSupportedFrames(context: Context? = null): Int {
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = (runtime.maxMemory() / (1024 * 1024)).toInt()
        val availableCores = runtime.availableProcessors()

        val isLowRam = if (context != null) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice ?: false
        } else false

        return when {
            isLowRam || maxMemoryMb < 96 -> 1 // Single frame low-light enhancement only
            maxMemoryMb < 192 || availableCores <= 2 -> 2 // Lightweight 2-frame merge
            maxMemoryMb < 384 -> 3
            else -> MAX_FRAMES_ALLOWED
        }
    }

    fun isMultiFrameSupported(context: Context? = null): Boolean =
        getMaxSupportedFrames(context) > 1

    fun resolveNightSightSupportPath(
        isHardwareNightSceneSupported: Boolean,
        context: Context? = null
    ): NightSightSupportPath {
        return when {
            isHardwareNightSceneSupported -> NightSightSupportPath.HARDWARE_SCENE_NIGHT
            isMultiFrameSupported(context) -> NightSightSupportPath.MULTI_FRAME_LOW_LIGHT_FUSION
            else -> NightSightSupportPath.SENSOR_ENHANCED_INTEGRATION
        }
    }
}
