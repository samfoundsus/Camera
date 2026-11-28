package com.example

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-thread-safe per-test timeout rule for JUnit 4 + Robolectric + Jetpack Compose tests.
 *
 * Unlike JUnit's built-in [org.junit.rules.Timeout] or `@Test(timeout = ...)`, which move test
 * execution onto a background worker thread and can break Android main-looper / Compose UI rules,
 * this rule keeps execution on the calling thread while running a daemon watchdog timer that
 * interrupts and fails any test exceeding [timeoutMs].
 */
class MainThreadSafeTimeoutRule(
    private val timeoutMs: Long = DEFAULT_TEST_TIMEOUT_MS
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val testThread = Thread.currentThread()
                val timedOut = AtomicBoolean(false)
                val stackDumpRef = AtomicReference("")

                val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "test-timeout-watchdog-${description.methodName}").apply {
                        isDaemon = true
                    }
                }
                val watchdog = scheduler.schedule(
                    {
                        if (timedOut.compareAndSet(false, true)) {
                            val trace = testThread.stackTrace.joinToString(separator = "\n    at ")
                            stackDumpRef.set(trace)
                            testThread.interrupt()
                        }
                    },
                    timeoutMs,
                    TimeUnit.MILLISECONDS
                )

                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    if (timedOut.get()) {
                        throw AssertionError(
                            "Test '${description.displayName}' timed out after ${timeoutMs}ms.\n" +
                                "Stack trace at timeout:\n    at ${stackDumpRef.get()}",
                            t
                        )
                    }
                    throw t
                } finally {
                    watchdog.cancel(false)
                    scheduler.shutdownNow()
                    // Clear interrupt status if the watchdog fired right as the test finished
                    Thread.interrupted()
                }

                if (timedOut.get()) {
                    throw AssertionError(
                        "Test '${description.displayName}' exceeded timeout of ${timeoutMs}ms.\n" +
                            "Stack trace at timeout:\n    at ${stackDumpRef.get()}"
                    )
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TEST_TIMEOUT_MS: Long = 30_000L
    }
}
