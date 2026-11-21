package com.example.core.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/**
 * Unified lifecycle phases for the shared shutter progress component used by
 * Night Sight, Astrophotography, and Time Lapse modes.
 */
enum class ShutterProgressPhase {
    IDLE,
    CAPTURING,
    PROCESSING,
    COMPLETED
}

/**
 * Camera modes that drive the shared shutter progress ring and status banner.
 */
enum class ShutterProgressMode {
    NIGHT_SIGHT,
    ASTRO,
    TIME_LAPSE,
    PORTRAIT
}

/**
 * Shared UI progress state consumed by the unified shutter progress component across
 * Night Sight, Astrophotography, Time Lapse, and Portrait modes.
 */
data class ShutterProgressState(
    val mode: ShutterProgressMode = ShutterProgressMode.NIGHT_SIGHT,
    val phase: ShutterProgressPhase = ShutterProgressPhase.IDLE,
    val currentFrame: Int = 0,
    val totalFrames: Int = 0,
    val progress: Float = 0f,
    val isIndeterminateCapture: Boolean = false,
    val framePulseCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val remainingSeconds: Long? = null,
    val statusLabel: String = "",
    val detailLabel: String = "",
    val canStopAndSave: Boolean = false,
    val allowConcurrentCapture: Boolean = false
) {
    val isActive: Boolean
        get() = phase == ShutterProgressPhase.CAPTURING || phase == ShutterProgressPhase.PROCESSING
}

/**
 * Converts legacy [NightSightProgressState] into the unified [ShutterProgressState]
 * so existing callers and tests stay seamlessly compatible.
 */
fun NightSightProgressState.toShutterProgressState(): ShutterProgressState {
    val mappedPhase = when (phase) {
        NightSightCapturePhase.IDLE -> ShutterProgressPhase.IDLE
        NightSightCapturePhase.CAPTURING -> ShutterProgressPhase.CAPTURING
        NightSightCapturePhase.PROCESSING -> ShutterProgressPhase.PROCESSING
        NightSightCapturePhase.COMPLETED -> ShutterProgressPhase.COMPLETED
    }
    val computedDetail = when (mappedPhase) {
        ShutterProgressPhase.CAPTURING -> if (totalFrames > 0) {
            "Frame $currentFrame of $totalFrames"
        } else {
            statusLabel.ifBlank { "Capturing Night Sight…" }
        }
        ShutterProgressPhase.PROCESSING -> {
            val pct = (progress * 100f).toInt().coerceIn(0, 99)
            if (pct > 0) "Processing… $pct%" else "Processing…"
        }
        ShutterProgressPhase.COMPLETED -> "Saved"
        ShutterProgressPhase.IDLE -> ""
    }
    return ShutterProgressState(
        mode = ShutterProgressMode.NIGHT_SIGHT,
        phase = mappedPhase,
        currentFrame = currentFrame,
        totalFrames = totalFrames,
        progress = progress,
        statusLabel = statusLabel,
        detailLabel = computedDetail,
        canStopAndSave = false
    )
}

/**
 * Formats a duration in seconds into `MM:SS` or `H:MM:SS`.
 */
fun formatShutterElapsedTime(totalSeconds: Long): String {
    val safeSec = totalSeconds.coerceAtLeast(0L)
    val hours = safeSec / 3600L
    val minutes = (safeSec % 3600L) / 60L
    val seconds = safeSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Builds the concise status caption displayed near the shutter during Astrophotography capture.
 */
fun formatAstroCaptureCaption(
    currentFrame: Int,
    totalFrames: Int,
    elapsedSeconds: Long,
    remainingSeconds: Long?
): String {
    val framePart = if (totalFrames > 0) {
        "Frame $currentFrame of $totalFrames"
    } else {
        "Frame $currentFrame"
    }
    val elapsedPart = "${formatShutterElapsedTime(elapsedSeconds)} elapsed"
    return if (remainingSeconds != null) {
        "$framePart • $elapsedPart · ${formatShutterElapsedTime(remainingSeconds)} left"
    } else {
        "$framePart • $elapsedPart"
    }
}

/**
 * Builds the concise status caption displayed near the shutter during Time Lapse recording.
 */
fun formatTimeLapseCaptureCaption(
    elapsedSeconds: Long,
    framesCaptured: Int,
    targetDurationSeconds: Long? = null,
    targetFrames: Int? = null
): String {
    val timePart = if (targetDurationSeconds != null && targetDurationSeconds > 0L) {
        "${formatShutterElapsedTime(elapsedSeconds)} / ${formatShutterElapsedTime(targetDurationSeconds)}"
    } else {
        formatShutterElapsedTime(elapsedSeconds)
    }
    val framesWord = if (framesCaptured == 1) "frame" else "frames"
    val framePart = if (targetFrames != null && targetFrames > 0) {
        "$framesCaptured/$targetFrames frames"
    } else {
        "$framesCaptured $framesWord"
    }
    return "$timePart • $framePart"
}

/**
 * Result of checking free device storage and battery level before starting a long capture
 * such as Time Lapse.
 */
data class RecordingReadinessWarning(
    val isLowStorage: Boolean,
    val freeStorageMb: Long,
    val isLowBattery: Boolean,
    val batteryPercent: Int,
    val isCharging: Boolean
) {
    val hasWarning: Boolean
        get() = isLowStorage || isLowBattery

    fun buildWarningTitle(): String = when {
        isLowStorage && isLowBattery -> "Low Storage & Battery"
        isLowStorage -> "Low Storage Space"
        isLowBattery -> "Low Battery"
        else -> "Ready to Record"
    }

    fun buildWarningMessage(): String {
        val reasons = buildList {
            if (isLowStorage) {
                add("Free storage is low (${freeStorageMb} MB available). Long time-lapse recordings may stop early if space runs out.")
            }
            if (isLowBattery) {
                add("Battery level is low ($batteryPercent%). Plug in a charger to avoid interruption during a long time-lapse.")
            }
        }
        return reasons.joinToString("\n\n")
    }
}

/**
 * Inspects real device free storage ([StatFs]) and battery telemetry ([BatteryManager])
 * prior to starting a Time Lapse session.
 */
object RecordingReadinessChecker {
    const val DEFAULT_MIN_FREE_STORAGE_MB = 500L
    const val DEFAULT_MIN_BATTERY_PERCENT = 15

    fun checkReadiness(
        context: Context,
        minStorageMb: Long = DEFAULT_MIN_FREE_STORAGE_MB,
        minBatteryPercent: Int = DEFAULT_MIN_BATTERY_PERCENT
    ): RecordingReadinessWarning {
        val freeStorageMb = queryFreeStorageMb(context)
        val (batteryPercent, isCharging) = queryBatteryStatus(context)
        return evaluateReadiness(
            freeStorageMb = freeStorageMb,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            minStorageMb = minStorageMb,
            minBatteryPercent = minBatteryPercent
        )
    }

    fun evaluateReadiness(
        freeStorageMb: Long,
        batteryPercent: Int,
        isCharging: Boolean,
        minStorageMb: Long = DEFAULT_MIN_FREE_STORAGE_MB,
        minBatteryPercent: Int = DEFAULT_MIN_BATTERY_PERCENT
    ): RecordingReadinessWarning {
        val lowStorage = freeStorageMb in 0L until minStorageMb
        val lowBattery = batteryPercent in 0..minBatteryPercent && !isCharging
        return RecordingReadinessWarning(
            isLowStorage = lowStorage,
            freeStorageMb = freeStorageMb,
            isLowBattery = lowBattery,
            batteryPercent = batteryPercent,
            isCharging = isCharging
        )
    }

    private fun queryFreeStorageMb(context: Context): Long {
        return runCatching {
            val targetDir = context.cacheDir ?: Environment.getDataDirectory()
            val statFs = StatFs(targetDir.absolutePath)
            statFs.availableBytes / (1024L * 1024L)
        }.getOrDefault(-1L)
    }

    private fun queryBatteryStatus(context: Context): Pair<Int, Boolean> {
        return runCatching {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) {
                ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
            } else {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: -1
            }
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct to charging
        }.getOrDefault(-1 to false)
    }
}
