package com.example

import android.app.Application
import com.example.core.camera.CrashLogManager

/**
 * Custom [Application] entry point that installs [CrashLogManager] before any Activity,
 * CameraX, or GPU code runs so startup crashes are always captured to `files/last_crash_log.txt`.
 */
class CameraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogManager.install(this)
    }
}
