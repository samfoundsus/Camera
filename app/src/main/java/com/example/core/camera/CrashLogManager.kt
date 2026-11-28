package com.example.core.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.core.processing.GpuCameraFilterEngine
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global uncaught exception logger and non-fatal startup diagnostic recorder.
 *
 * Writes the full crash stack trace (including all "Caused by" chains, device/API info,
 * and throwing file/line summary) to `files/last_crash_log.txt` so crashes on an emulator
 * or device can be inspected directly from the hidden Hardware Debug screen on the next launch.
 */
object CrashLogManager {
    private const val TAG = "CameraCrashLogger"
    const val CRASH_LOG_FILE_NAME = "last_crash_log.txt"
    private const val MAX_NON_FATAL_WARNINGS = 12
    private const val MAX_CRASH_LOG_BYTES = 64 * 1024

    private val installed = AtomicBoolean(false)
    @Volatile
    private var appContextRef: Context? = null
    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val warningsLock = Any()
    private val nonFatalWarnings = ArrayDeque<String>()

    /**
     * Installs the global [Thread.UncaughtExceptionHandler] once per process.
     * Safe to call from both `Application.onCreate()` and `MainActivity.onCreate()`.
     */
    fun install(context: Context) {
        val appCtx = context.applicationContext ?: context
        appContextRef = appCtx
        if (!installed.compareAndSet(false, true)) return

        val existing = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = existing

        var installedHandler: Thread.UncaughtExceptionHandler? = null
        installedHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            runCatching {
                recordCrashSync(
                    context = appContextRef ?: appCtx,
                    threadName = thread.name,
                    throwable = throwable
                )
            }
            runCatching {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            }
            val delegate = previousHandler
            if (delegate != null && delegate !== installedHandler) {
                delegate.uncaughtException(thread, throwable)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(installedHandler)
    }

    /**
     * Synchronously formats and persists a crash report to `files/last_crash_log.txt`.
     */
    fun recordCrashSync(
        context: Context,
        threadName: String,
        throwable: Throwable
    ): File? {
        val formatted = formatCrashReport(threadName = threadName, throwable = throwable)
        return runCatching {
            val file = getCrashLogFile(context)
            file.parentFile?.mkdirs()
            val bounded = if (formatted.length > MAX_CRASH_LOG_BYTES) {
                formatted.take(MAX_CRASH_LOG_BYTES) + "\n... [truncated]"
            } else {
                formatted
            }
            file.writeText(bounded, Charsets.UTF_8)
            file
        }.onFailure { err ->
            runCatching { Log.e(TAG, "Failed to write crash log file", err) }
        }.getOrNull()
    }

    /**
     * Records a caught non-fatal startup, GPU, or camera fallback warning so it is visible in
     * the hidden Hardware Debug screen and logcat without crashing the app.
     */
    fun recordNonFatalError(area: String, throwable: Throwable) {
        val timestamp = formatUtcTimestamp(System.currentTimeMillis())
        val topFrame = throwable.stackTrace.firstOrNull()?.let {
            "${it.fileName ?: it.className}:${it.lineNumber}"
        } ?: "unknown"
        val summary = "[$timestamp] $area — ${throwable.javaClass.simpleName}: " +
            "${throwable.message ?: "no message"} ($topFrame)"
        synchronized(warningsLock) {
            if (nonFatalWarnings.size >= MAX_NON_FATAL_WARNINGS) {
                nonFatalWarnings.removeFirst()
            }
            nonFatalWarnings.addLast(summary)
        }
        runCatching {
            Log.w(TAG, "Recovered non-fatal error in $area: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
        }
    }

    fun getNonFatalWarnings(): List<String> = synchronized(warningsLock) {
        nonFatalWarnings.toList()
    }

    fun clearNonFatalWarnings() {
        synchronized(warningsLock) {
            nonFatalWarnings.clear()
        }
    }

    fun getCrashLogFile(context: Context): File {
        val baseDir = context.applicationContext?.filesDir ?: context.filesDir
        return File(baseDir, CRASH_LOG_FILE_NAME)
    }

    fun readLastCrashLog(context: Context): String? {
        return runCatching {
            val file = getCrashLogFile(context)
            if (file.exists() && file.length() > 0L) {
                file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
    }

    fun clearLastCrashLog(context: Context) {
        runCatching {
            val file = getCrashLogFile(context)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun formatCrashReport(
        threadName: String,
        throwable: Throwable,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val rootCause = findRootCause(throwable)
        val topThrowingFrame = rootCause.stackTrace.firstOrNull()
            ?: throwable.stackTrace.firstOrNull()
        val firstAppFrame = findFirstAppFrame(rootCause) ?: findFirstAppFrame(throwable)

        val stackTraceWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTraceWriter))
        val fullStackTrace = stackTraceWriter.toString().trimEnd()

        return buildString {
            appendLine("=== FATAL EXCEPTION REPORT ===")
            appendLine("Timestamp (UTC): ${formatUtcTimestamp(timestampMs)}")
            appendLine("Thread: $threadName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT}, hardware=${Build.HARDWARE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | Emulator=${GpuCameraFilterEngine.isEmulatorEnvironment()}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message ?: "<no message>"}")
            if (rootCause !== throwable) {
                appendLine("Root Cause: ${rootCause.javaClass.name}: ${rootCause.message ?: "<no message>"}")
            }
            if (topThrowingFrame != null) {
                appendLine("Throwing Frame: ${topThrowingFrame.className}.${topThrowingFrame.methodName}(${topThrowingFrame.fileName ?: "Unknown"}:${topThrowingFrame.lineNumber})")
            }
            if (firstAppFrame != null && firstAppFrame != topThrowingFrame) {
                appendLine("App Frame: ${firstAppFrame.className}.${firstAppFrame.methodName}(${firstAppFrame.fileName ?: "Unknown"}:${firstAppFrame.lineNumber})")
            }
            appendLine()
            appendLine("--- Full Stack Trace ---")
            appendLine(fullStackTrace)
        }
    }

    private fun findRootCause(throwable: Throwable): Throwable {
        var current: Throwable = throwable
        val visited = HashSet<Throwable>()
        while (current.cause != null && visited.add(current)) {
            current = current.cause!!
        }
        return current
    }

    private fun findFirstAppFrame(throwable: Throwable): StackTraceElement? {
        return throwable.stackTrace.firstOrNull { frame ->
            frame.className.startsWith("com.example.")
        }
    }

    private fun formatUtcTimestamp(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestampMs))
    }
}
