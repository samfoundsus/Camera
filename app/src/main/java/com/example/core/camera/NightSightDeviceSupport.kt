package com.example.core.camera

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.example.core.preferences.NightSightExposure

/**
 * Capture and processing lifecycle phases for Night Sight shutter ring progress feedback.
 */
enum class NightSightCapturePhase {
    IDLE,
    CAPTURING,
    PROCESSING,
    COMPLETED
}

/**
 * UI progress state for Night Sight capture and processing so the shutter button
 * clearly distinguishes active capture progress, background processing, cancellation, and completion.
 */
data class NightSightProgressState(
    val phase: NightSightCapturePhase = NightSightCapturePhase.IDLE,
    val currentFrame: Int = 0,
    val totalFrames: Int = 0,
    val progress: Float = 0f,
    val statusLabel: String = ""
) {
    val isActive: Boolean
        get() = phase == NightSightCapturePhase.CAPTURING || phase == NightSightCapturePhase.PROCESSING
}

/**
 * Dynamic device capability and memory-budgeting helper for Night Sight mode.
 *
 * Calculates safe burst frame counts and per-frame exposure durations directly from:
 * - [ActivityManager.getMemoryInfo] (`availMem`, `lowMemory`, `threshold`)
 * - [Runtime.getRuntime] (`maxMemory()`, currently allocated heap)
 * - Actual capture frame dimensions (`frameWidth` x `frameHeight`)
 *
 * For very long exposures ([NightSightExposure.MAX]), prefers fewer, longer-integration frames
 * rather than capturing excessive frames that could cause out-of-memory errors.
 */
object NightSightDeviceSupport {

    const val MIN_MULTI_FRAME_COUNT = 2
    const val DEFAULT_AUTO_FRAMES = 4
    const val DEFAULT_MAX_FRAMES = 6

    /**
     * Memory budget calculation result for a Night Sight capture sequence.
     */
    data class NightSightCapturePlan(
        val frameCount: Int,
        val targetPerFrameExposureMs: Long,
        val recommendedDecodeSampleSize: Int,
        val availableHeapBytes: Long,
        val systemAvailableRamBytes: Long,
        val isLowMemoryDevice: Boolean
    )

    /**
     * Computes a safe, memory-bounded Night Sight capture plan from real runtime memory telemetry
     * and the expected frame dimensions.
     *
     * Streaming accumulator memory footprint (ShortArray R/G/B/Weight + 1 active ARGB_8888 bitmap
     * + 1/4-res alignment luma buffer + strip scratch):
     *   ~ 8 bytes/pixel (ShortArray x4) + 4 bytes/pixel (current frame bitmap) + ~1 byte/pixel (alignment/strip)
     *   = ~13 bytes/pixel peak.
     *
     * Because frames are merged into one running accumulator and immediately closed/recycled,
     * peak RAM does NOT scale linearly with frame count; however we still cap frame count by
     * available memory and thermal/capture duration budgets, and for [NightSightExposure.MAX]
     * we favor fewer, longer frames over excessive burst counts.
     */
    fun computeSafeCapturePlan(
        context: Context? = null,
        frameWidth: Int = 4000,
        frameHeight: Int = 3000,
        exposure: NightSightExposure = NightSightExposure.AUTO,
        maxSensorExposureNanos: Long? = null
    ): NightSightCapturePlan {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory().coerceAtLeast(64L * 1024L * 1024L)
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val freeHeap = (maxHeap - usedHeap).coerceAtLeast(16L * 1024L * 1024L)

        var sysAvailRam = freeHeap * 4L
        var isLowRam = false
        var memoryClassMb = (maxHeap / (1024L * 1024L)).toInt()

        if (context != null) {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(memInfo)
                    sysAvailRam = memInfo.availMem
                    isLowRam = memInfo.lowMemory || am.isLowRamDevice
                    memoryClassMb = maxOf(am.largeMemoryClass, am.memoryClass)
                }
            }
        }

        val safeW = frameWidth.coerceAtLeast(320)
        val safeH = frameHeight.coerceAtLeast(240)
        val pixels = safeW.toLong() * safeH.toLong()

        // Streaming accumulator (~8 B/px) + 1 active decoded bitmap (~4 B/px) + alignment & strip scratch (~2 B/px)
        val estimatedPeakWorkingBytes = pixels * 14L + 8L * 1024L * 1024L
        val safeHeapBudget = (freeHeap * 0.65).toLong().coerceAtLeast(24L * 1024L * 1024L)

        // Choose decode inSampleSize (power of 2) only if full-res streaming buffer would threaten heap OOM
        var sampleSize = 1
        while (sampleSize < 4 && (estimatedPeakWorkingBytes / (sampleSize.toLong() * sampleSize.toLong())) > safeHeapBudget) {
            sampleSize *= 2
        }

        if (exposure == NightSightExposure.OFF) {
            return NightSightCapturePlan(
                frameCount = 1,
                targetPerFrameExposureMs = 0L,
                recommendedDecodeSampleSize = sampleSize,
                availableHeapBytes = freeHeap,
                systemAvailableRamBytes = sysAvailRam,
                isLowMemoryDevice = isLowRam
            )
        }

        // Memory-tier frame ceiling
        val memoryCappedFrames = when {
            isLowRam || freeHeap < 64L * 1024L * 1024L || sysAvailRam < 256L * 1024L * 1024L -> 2
            memoryClassMb < 192 || freeHeap < 110L * 1024L * 1024L -> 3
            memoryClassMb < 320 || freeHeap < 180L * 1024L * 1024L -> 5
            else -> DEFAULT_MAX_FRAMES
        }

        val maxSensorExpMs = ((maxSensorExposureNanos ?: 0L) / 1_000_000L).coerceAtLeast(0L)

        // For MAX (longest exposure), prefer fewer, longer-integration frames rather than a huge burst
        val (targetFrames, perFrameExpMs) = when (exposure) {
            NightSightExposure.OFF -> 1 to 0L
            NightSightExposure.AUTO -> {
                val count = minOf(DEFAULT_AUTO_FRAMES, memoryCappedFrames).coerceAtLeast(MIN_MULTI_FRAME_COUNT)
                val expMs = if (maxSensorExpMs >= 150L) minOf(maxSensorExpMs, 350L) else 0L
                count to expMs
            }
            NightSightExposure.MAX -> {
                if (maxSensorExpMs >= 500L) {
                    // Use fewer, longer frames (e.g. 3-4 frames of up to ~1.2s each) instead of 8+ short frames
                    val longFrameExp = minOf(maxSensorExpMs, 1200L)
                    val fewerFrames = minOf(4, memoryCappedFrames).coerceAtLeast(MIN_MULTI_FRAME_COUNT)
                    fewerFrames to longFrameExp
                } else {
                    val count = minOf(DEFAULT_MAX_FRAMES, memoryCappedFrames).coerceAtLeast(MIN_MULTI_FRAME_COUNT)
                    val expMs = if (maxSensorExpMs > 0L) minOf(maxSensorExpMs, 500L) else 0L
                    count to expMs
                }
            }
        }

        return NightSightCapturePlan(
            frameCount = targetFrames.coerceIn(1, DEFAULT_MAX_FRAMES),
            targetPerFrameExposureMs = perFrameExpMs,
            recommendedDecodeSampleSize = sampleSize,
            availableHeapBytes = freeHeap,
            systemAvailableRamBytes = sysAvailRam,
            isLowMemoryDevice = isLowRam
        )
    }

    /**
     * Backward-compatible helper returning the maximum safe burst frame count for the device.
     */
    fun getMaxSupportedFrames(context: Context? = null): Int {
        return computeSafeCapturePlan(
            context = context,
            frameWidth = 4000,
            frameHeight = 3000,
            exposure = NightSightExposure.MAX
        ).frameCount.coerceIn(MIN_MULTI_FRAME_COUNT, DEFAULT_MAX_FRAMES)
    }

    /**
     * Resolves the genuine Night Sight support path for a specific camera lens.
     */
    fun resolveNightSightSupportPath(
        isHardwareNightSceneSupported: Boolean,
        context: Context? = null
    ): NightSightSupportPath {
        if (isHardwareNightSceneSupported) {
            return NightSightSupportPath.HARDWARE_SCENE_NIGHT
        }
        val maxFrames = getMaxSupportedFrames(context)
        return if (maxFrames >= MIN_MULTI_FRAME_COUNT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            NightSightSupportPath.MULTI_FRAME_LOW_LIGHT_FUSION
        } else {
            NightSightSupportPath.SENSOR_ENHANCED_INTEGRATION
        }
    }
}
