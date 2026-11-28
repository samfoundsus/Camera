package com.example.core.camera

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import com.example.core.model.CameraMode
import com.example.core.preferences.CameraFilterPreset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Snapshot of UI rendering frame performance and main-thread stall (>16ms) telemetry.
 */
data class UiFrameTimingSnapshot(
    val totalFrames: Long = 0L,
    val jankyFrames: Long = 0L,
    val mainThreadStallCount: Long = 0L,
    val avgFrameMs: Double = 0.0,
    val p95FrameMs: Double = 0.0,
    val maxFrameMs: Double = 0.0,
    val recentStalls: List<MainThreadStallEvent> = emptyList()
) {
    val jankPercent: Double
        get() = if (totalFrames > 0L) (jankyFrames * 100.0) / totalFrames.toDouble() else 0.0

    fun formatMultilineSummary(): String = buildString {
        if (totalFrames == 0L && recentStalls.isEmpty()) {
            append("No UI frames sampled yet in this session.")
            return@buildString
        }
        appendLine(
            String.format(
                Locale.US,
                "Frames: %d • Avg: %.1fms • P95: %.1fms • Max: %.1fms",
                totalFrames,
                avgFrameMs,
                p95FrameMs,
                maxFrameMs
            )
        )
        append(
            String.format(
                Locale.US,
                "Janky (>16.6ms): %d (%.1f%%) • Main-Thread Stalls (>16ms): %d",
                jankyFrames,
                jankPercent,
                mainThreadStallCount
            )
        )
        if (recentStalls.isNotEmpty()) {
            appendLine()
            append("Recent stalls:")
            recentStalls.take(5).forEach { stall ->
                appendLine()
                append(
                    String.format(
                        Locale.US,
                        "  • %.1fms on %s (%s)",
                        stall.durationMs,
                        stall.screenName,
                        stall.source
                    )
                )
            }
        }
    }
}

/**
 * Recorded main-thread stall where UI execution exceeded the 16 ms frame budget.
 */
data class MainThreadStallEvent(
    val timestampMs: Long,
    val durationMs: Double,
    val screenName: String,
    val source: String
)

/**
 * Low-overhead UI frame timing & main-thread stall (>16 ms) monitor.
 *
 * Uses [Window.OnFrameMetricsAvailableListener] on API 24+ (receiving exact hardware frame timing
 * on a dedicated background handler thread with zero main-thread overhead) and a [Choreographer]
 * frame interval tracker to detect main-thread stalls exceeding 16 ms and log them under Logcat tag
 * `MainThreadStall`.
 */
object UiPerformanceMonitor {
    const val STALL_LOG_TAG = "MainThreadStall"
    const val MAIN_THREAD_STALL_THRESHOLD_MS = 16.0
    private const val JANK_FRAME_THRESHOLD_MS = 16.67
    private const val MAX_FRAME_SAMPLES = 240
    private const val MAX_STALL_RECORDS = 15
    private const val MIN_STALL_LOG_INTERVAL_MS = 250L

    private val lock = Any()
    private val frameDurationsMs = ArrayDeque<Double>(MAX_FRAME_SAMPLES)
    private val recentStalls = ArrayDeque<MainThreadStallEvent>(MAX_STALL_RECORDS)
    private var totalFrames = 0L
    private var jankyFrames = 0L
    private var mainThreadStallCount = 0L
    private var maxFrameMs = 0.0
    private var lastStallLogAtMs = 0L

    @Volatile
    private var activeScreenName: String = "CAMERA"

    @Volatile
    private var isMonitoring: Boolean = false

    private var metricsThread: HandlerThread? = null
    private var metricsHandler: Handler? = null
    private var attachedWindow: Window? = null
    private var frameMetricsListener: Any? = null
    private var lastChoreographerFrameNs: Long = 0L

    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isMonitoring) return
            val prevNs = lastChoreographerFrameNs
            lastChoreographerFrameNs = frameTimeNanos
            if (prevNs > 0L) {
                val frameDeltaMs = (frameTimeNanos - prevNs) / 1_000_000.0
                // Ignore long pauses (>2000ms) caused by app backgrounding or screen off
                if (frameDeltaMs in 1.0..2000.0) {
                    val hasFrameMetrics = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && frameMetricsListener != null
                    if (!hasFrameMetrics) {
                        recordFrameDuration(frameDeltaMs, source = "Choreographer")
                    } else if (frameDeltaMs > MAIN_THREAD_STALL_THRESHOLD_MS + 4.0) {
                        // Detect main-thread Looper/Choreographer gaps even when FrameMetrics wasn't emitted yet
                        recordMainThreadStallOnly(frameDeltaMs, source = "MainLooperGap")
                    }
                }
            }
            runCatching { Choreographer.getInstance().postFrameCallback(this) }
        }
    }

    fun setActiveScreen(screenName: String) {
        activeScreenName = screenName
    }

    private fun isRobolectricRuntime(): Boolean {
        val fingerprint = Build.FINGERPRINT?.lowercase(Locale.US).orEmpty()
        val hardware = Build.HARDWARE?.lowercase(Locale.US).orEmpty()
        val product = Build.PRODUCT?.lowercase(Locale.US).orEmpty()
        return fingerprint.contains("robolectric") ||
            hardware.contains("robolectric") ||
            product.contains("robolectric")
    }

    /**
     * Attaches lightweight frame timing & main-thread stall monitoring to [activity].
     */
    fun attach(activity: Activity) {
        if (isRobolectricRuntime()) return
        if (isMonitoring && attachedWindow === activity.window) return
        detach()
        isMonitoring = true
        lastChoreographerFrameNs = 0L
        val window = activity.window
        attachedWindow = window

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && window != null) {
            attachFrameMetricsListenerApi24(window)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { Choreographer.getInstance().postFrameCallback(choreographerCallback) }
        } else {
            Handler(Looper.getMainLooper()).post {
                if (isMonitoring) {
                    runCatching { Choreographer.getInstance().postFrameCallback(choreographerCallback) }
                }
            }
        }
    }

    /**
     * Detaches listeners when the activity pauses so background power draw is zero.
     */
    fun detach() {
        isMonitoring = false
        lastChoreographerFrameNs = 0L
        runCatching { Choreographer.getInstance().removeFrameCallback(choreographerCallback) }

        val window = attachedWindow
        val listener = frameMetricsListener
        attachedWindow = null
        frameMetricsListener = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && window != null && listener != null) {
            detachFrameMetricsListenerApi24(window, listener)
        }

        runCatching { metricsThread?.quitSafely() }
        metricsThread = null
        metricsHandler = null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun attachFrameMetricsListenerApi24(window: Window) {
        runCatching {
            val thread = HandlerThread("UiFrameMetricsThread").apply {
                priority = Thread.MIN_PRIORITY
                start()
            }
            val handler = Handler(thread.looper)
            metricsThread = thread
            metricsHandler = handler
            val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                if (!isMonitoring) return@OnFrameMetricsAvailableListener
                val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val uiNs = frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                    frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                    frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                    frameMetrics.getMetric(FrameMetrics.DRAW_DURATION) +
                    frameMetrics.getMetric(FrameMetrics.SYNC_DURATION)
                val totalMs = totalNs / 1_000_000.0
                val uiMs = uiNs / 1_000_000.0
                if (totalMs in 0.1..2000.0) {
                    recordFrameDuration(totalMs, mainThreadMs = uiMs, source = "FrameMetrics")
                }
            }
            frameMetricsListener = listener
            window.addOnFrameMetricsAvailableListener(listener, handler)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun detachFrameMetricsListenerApi24(window: Window, listener: Any) {
        runCatching {
            val typed = listener as? Window.OnFrameMetricsAvailableListener ?: return@runCatching
            window.removeOnFrameMetricsAvailableListener(typed)
        }
    }

    /**
     * Records a measured frame duration (and optional main-thread work time) and logs any main-thread
     * stall exceeding 16 ms to Logcat under `MainThreadStall`.
     */
    fun recordFrameDuration(
        totalFrameMs: Double,
        mainThreadMs: Double = totalFrameMs,
        source: String = "FrameMetrics"
    ) {
        if (!totalFrameMs.isFinite() || totalFrameMs <= 0.0) return
        val nowMs = System.currentTimeMillis()
        var shouldLogStall = false
        val stallDuration = maxOf(mainThreadMs, if (source == "Choreographer") totalFrameMs else 0.0)
        val screen = activeScreenName

        synchronized(lock) {
            totalFrames++
            if (frameDurationsMs.size >= MAX_FRAME_SAMPLES) {
                frameDurationsMs.removeFirst()
            }
            frameDurationsMs.addLast(totalFrameMs)
            if (totalFrameMs > maxFrameMs) {
                maxFrameMs = totalFrameMs
            }
            if (totalFrameMs > JANK_FRAME_THRESHOLD_MS) {
                jankyFrames++
            }
            if (stallDuration > MAIN_THREAD_STALL_THRESHOLD_MS) {
                mainThreadStallCount++
                if (recentStalls.size >= MAX_STALL_RECORDS) {
                    recentStalls.removeFirst()
                }
                recentStalls.addLast(
                    MainThreadStallEvent(
                        timestampMs = nowMs,
                        durationMs = stallDuration,
                        screenName = screen,
                        source = source
                    )
                )
                if (nowMs - lastStallLogAtMs >= MIN_STALL_LOG_INTERVAL_MS) {
                    lastStallLogAtMs = nowMs
                    shouldLogStall = true
                }
            }
        }

        if (shouldLogStall) {
            Log.w(
                STALL_LOG_TAG,
                String.format(
                    Locale.US,
                    "Main thread blocked for %.1fms (>16ms budget) on screen=%s [%s, totalFrame=%.1fms]",
                    stallDuration,
                    screen,
                    source,
                    totalFrameMs
                )
            )
        }
    }

    private fun recordMainThreadStallOnly(durationMs: Double, source: String) {
        val nowMs = System.currentTimeMillis()
        var shouldLog = false
        val screen = activeScreenName
        synchronized(lock) {
            mainThreadStallCount++
            if (recentStalls.size >= MAX_STALL_RECORDS) {
                recentStalls.removeFirst()
            }
            recentStalls.addLast(
                MainThreadStallEvent(
                    timestampMs = nowMs,
                    durationMs = durationMs,
                    screenName = screen,
                    source = source
                )
            )
            if (nowMs - lastStallLogAtMs >= MIN_STALL_LOG_INTERVAL_MS) {
                lastStallLogAtMs = nowMs
                shouldLog = true
            }
        }
        if (shouldLog) {
            Log.w(
                STALL_LOG_TAG,
                String.format(
                    Locale.US,
                    "Main thread blocked for %.1fms (>16ms budget) on screen=%s [%s]",
                    durationMs,
                    screen,
                    source
                )
            )
        }
    }

    fun getSnapshot(): UiFrameTimingSnapshot = synchronized(lock) {
        val samples = frameDurationsMs.toList()
        val avg = if (samples.isNotEmpty()) samples.average() else 0.0
        val p95 = if (samples.isNotEmpty()) {
            val sorted = samples.sorted()
            val idx = ((sorted.size - 1) * 0.95).roundToInt().coerceIn(0, sorted.lastIndex)
            sorted[idx]
        } else 0.0
        UiFrameTimingSnapshot(
            totalFrames = totalFrames,
            jankyFrames = jankyFrames,
            mainThreadStallCount = mainThreadStallCount,
            avgFrameMs = avg,
            p95FrameMs = p95,
            maxFrameMs = maxFrameMs,
            recentStalls = recentStalls.toList().reversed()
        )
    }

    fun clear() = synchronized(lock) {
        frameDurationsMs.clear()
        recentStalls.clear()
        totalFrames = 0L
        jankyFrames = 0L
        mainThreadStallCount = 0L
        maxFrameMs = 0.0
        lastStallLogAtMs = 0L
    }
}

/**
 * Debug record verifying which filter was selected and whether it was applied to the live preview
 * or to a saved photo/video file.
 */
data class FilterVerificationRecord(
    val timestampMs: Long = System.currentTimeMillis(),
    val target: String, // "Preview", "Photo", "Video"
    val modeName: String,
    val selectedFilter: CameraFilterPreset,
    val effectiveFilter: CameraFilterPreset,
    val wasApplied: Boolean,
    val backend: String,
    val durationMs: Long = 0L
) {
    fun formatSummaryLine(): String {
        val appliedLabel = if (wasApplied) "APPLIED" else "BYPASS(Original)"
        val durPart = if (durationMs > 0L) " (${durationMs}ms)" else ""
        return "[$target | $modeName] selected=${selectedFilter.displayName} -> effective=${effectiveFilter.displayName} • $appliedLabel via $backend$durPart"
    }
}

/**
 * Thread-safe debug tracker and Logcat logger (`CameraFilterDebug`) for verifying that selected
 * filters reach both the live GPU preview and saved photo/video pipelines.
 */
object FilterDebugTracker {
    const val LOG_TAG = "CameraFilterDebug"
    private const val MAX_RECORDS = 14
    private val lock = Any()
    private val savedFileRecords = ArrayDeque<FilterVerificationRecord>(MAX_RECORDS)

    @Volatile
    private var latestPreviewRecord: FilterVerificationRecord? = null

    fun recordPreviewFilter(
        mode: CameraMode,
        selectedFilter: CameraFilterPreset,
        effectiveFilter: CameraFilterPreset,
        wasAppliedToPreview: Boolean,
        backend: String
    ): FilterVerificationRecord {
        val record = FilterVerificationRecord(
            target = "Preview",
            modeName = mode.displayName,
            selectedFilter = selectedFilter,
            effectiveFilter = effectiveFilter,
            wasApplied = wasAppliedToPreview,
            backend = backend
        )
        latestPreviewRecord = record
        Log.i(LOG_TAG, record.formatSummaryLine())
        return record
    }

    fun recordSavedFileFilter(
        mediaType: String,
        modeName: String,
        selectedFilter: CameraFilterPreset,
        effectiveFilter: CameraFilterPreset,
        wasAppliedToFile: Boolean,
        backend: String,
        durationMs: Long = 0L
    ): FilterVerificationRecord {
        val record = FilterVerificationRecord(
            target = mediaType,
            modeName = modeName,
            selectedFilter = selectedFilter,
            effectiveFilter = effectiveFilter,
            wasApplied = wasAppliedToFile,
            backend = backend,
            durationMs = durationMs
        )
        synchronized(lock) {
            if (savedFileRecords.size >= MAX_RECORDS) {
                savedFileRecords.removeFirst()
            }
            savedFileRecords.addLast(record)
        }
        Log.i(LOG_TAG, record.formatSummaryLine())
        return record
    }

    fun getLatestPreviewRecord(): FilterVerificationRecord? = latestPreviewRecord

    fun getRecentSavedFileRecords(): List<FilterVerificationRecord> = synchronized(lock) {
        savedFileRecords.toList().reversed()
    }

    fun formatMultilineSummary(): String = buildString {
        val preview = latestPreviewRecord
        if (preview != null) {
            appendLine("Live Preview: ${preview.formatSummaryLine()}")
        } else {
            appendLine("Live Preview: No preview filter update recorded yet.")
        }
        val saved = getRecentSavedFileRecords()
        if (saved.isEmpty()) {
            append("Saved Files: No filtered/original captures recorded yet in this session.")
        } else {
            append("Recent Saved Files:")
            saved.take(6).forEach { rec ->
                appendLine()
                append("  • ${rec.formatSummaryLine()}")
            }
        }
    }

    fun clear() = synchronized(lock) {
        latestPreviewRecord = null
        savedFileRecords.clear()
    }
}
