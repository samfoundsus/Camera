package com.example.core.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.MainActivity

/**
 * Play-policy compliant camera foreground service (`foregroundServiceType="camera"`) used exclusively
 * while an active Time Lapse capture/processing session is in flight so brief backgrounding or
 * screen turn-off does not interrupt recording or lose captured frames.
 */
class TimeLapseRecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
            ?: "Time Lapse recording in progress"
        acquireWakeLock()
        startForegroundSafely(statusText)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_WAKELOCK_DURATION_MS)
            }
            wakeLock = wl
        }.onFailure { err ->
            Log.w(TAG, "Unable to acquire partial wake lock for time-lapse", err)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun startForegroundSafely(statusText: String) {
        runCatching {
            ensureNotificationChannel()
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val notification = builder
                .setContentTitle("Time Lapse Active")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { err ->
            Log.w(TAG, "Unable to promote TimeLapseRecordingService to foreground", err)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Time Lapse Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Time Lapse recording active if the screen turns off briefly."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun stopSelfCleanly() {
        releaseWakeLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "TimeLapseRecService"
        private const val CHANNEL_ID = "camera_timelapse_recording"
        private const val NOTIFICATION_ID = 4102
        private const val WAKE_LOCK_TAG = "PixelCamera:TimeLapseRecording"
        private const val MAX_WAKELOCK_DURATION_MS = 4L * 60L * 60L * 1000L
        private const val ACTION_START = "com.example.action.TIMELAPSE_START"
        private const val ACTION_STOP = "com.example.action.TIMELAPSE_STOP"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun start(context: Context, statusText: String = "Time Lapse recording in progress") {
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, TimeLapseRecordingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS_TEXT, statusText)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to start TimeLapseRecordingService", err)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, TimeLapseRecordingService::class.java).apply {
                    action = ACTION_STOP
                }
                appCtx.stopService(intent)
            }.onFailure { err ->
                Log.w(TAG, "Unable to stop TimeLapseRecordingService", err)
            }
        }
    }
}
