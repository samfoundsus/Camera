package com.example.core.model

import android.os.Build

/**
 * System and device telemetry for runtime capability inspection and verification.
 */
data class SystemDeviceInfo(
    val androidVersion: String,
    val apiLevel: Int,
    val deviceModel: String,
    val manufacturer: String,
    val isMonetSupported: Boolean
) {
    companion object {
        fun current(): SystemDeviceInfo {
            return SystemDeviceInfo(
                androidVersion = Build.VERSION.RELEASE ?: "Unknown",
                apiLevel = Build.VERSION.SDK_INT,
                deviceModel = Build.MODEL ?: "Generic",
                manufacturer = Build.MANUFACTURER ?: "Android",
                isMonetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            )
        }
    }
}
