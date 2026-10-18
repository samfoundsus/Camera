package com.example.core.camera

import android.app.ActivityManager
import android.content.Context

/**
 * Dynamically evaluates runtime device constraints (available memory heap, low-RAM flags, and CPU cores)
 * to safely bound software Portrait background blur strength within the [1.0f, 16.0f] range.
 *
 * Never claims unverified hardware capabilities; explicitly models software blur limits.
 */
object PortraitBlurDeviceSupport {
    const val MIN_BLUR_STRENGTH = 1.0f
    const val MAX_THEORETICAL_STRENGTH = 16.0f

    /**
     * Detects dynamic device and memory constraints to determine the maximum blur strength
     * that this device can reliably and safely process.
     */
    fun getMaxSupportedBlurStrength(context: Context? = null): Float {
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = (runtime.maxMemory() / (1024 * 1024)).toInt()
        val availableCores = runtime.availableProcessors()

        val isLowRam = if (context != null) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice ?: false
        } else false

        return when {
            isLowRam || maxMemoryMb < 96 -> 8.0f
            maxMemoryMb < 192 || availableCores <= 2 -> 12.0f
            else -> MAX_THEORETICAL_STRENGTH
        }
    }
}
