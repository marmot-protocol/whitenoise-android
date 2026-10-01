package dev.ipf.whitenoise.android.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Background-delivery energy baselines for #2786: idle cost under each delivery posture the app
 * can actually reach, plus a representative push-burst window. Companion to
 * [NetworkRecoveryBenchmark], which covers the repeated-reconnect scenario — together they cover
 * the five scenarios #2786's acceptance criteria call for.
 *
 * The product never resolves to a true third "nothing" delivery mode (see
 * `NativePushDelivery.resolvedNotificationDeliveryMode`), so [idleWithDeliveryDisabledPower]
 * approximates the floor a user who disabled notifications entirely would see: push mode selected
 * (so the always-on local/keep-connected stream is off) with the OS notification permission
 * revoked.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundIdleBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Floor baseline: push mode selected, OS notification permission revoked, app backgrounded. */
    @Test
    fun idleWithDeliveryDisabledPower() {
        val journeys = WhiteNoiseJourneys()
        try {
            benchmarkRule.measureRepeated(
                packageName = BenchmarkConfig.TARGET_PACKAGE,
                metrics = idleMetrics(),
                compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
                iterations = IDLE_ITERATIONS,
                setupBlock = {
                    journeys.run { resumeToChatList() }
                    journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Fcm)
                    revokeNotificationPermission()
                    pressHome()
                },
                measureBlock = {
                    SystemClock.sleep(idleWindowMs())
                },
            )
        } finally {
            grantNotificationPermission()
        }
    }

    /** Native push (Fcm) selected, idle in the background. */
    @Test
    fun idleWithNativePushPower() {
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = idleMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = IDLE_ITERATIONS,
            setupBlock = {
                journeys.run { resumeToChatList() }
                journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Fcm)
                pressHome()
            },
            measureBlock = {
                SystemClock.sleep(idleWindowMs())
            },
        )
    }

    /** Local/keep-connected selected, idle in the background. */
    @Test
    fun idleWithKeepConnectedPower() {
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = idleMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = IDLE_ITERATIONS,
            setupBlock = {
                journeys.run { resumeToChatList() }
                journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Local)
                pressHome()
            },
            measureBlock = {
                SystemClock.sleep(idleWindowMs())
            },
        )
    }

    /**
     * Observes background resource cost while a burst of pre-arranged messages arrives.
     *
     * Unlike `SecondaryAccountNotificationNavigationMacrobenchmark`, which measures UI reaction
     * time against notifications already sitting in the tray, this measures the receive-side
     * push-wake/catch-up cost, so the burst must arrive *during* the measured window. Runs a
     * single iteration; coordinate a burst of several messages from a second account, sent within
     * the first few seconds after the logged "send now" line, before the window closes.
     */
    @Test
    fun pushBurstPower() {
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = idleMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = BURST_ITERATIONS,
            setupBlock = {
                journeys.run { resumeToChatList() }
                pressHome()
            },
            measureBlock = {
                Log.i(BURST_LOG_TAG, "Send the push burst now; observing for ${burstWindowMs()}ms.")
                SystemClock.sleep(burstWindowMs())
            },
        )
    }

    /** The idle methods' sleep duration, overridable via `idleWindowMs` for a short smoke run. */
    private fun idleWindowMs(): Long = BenchmarkConfig.idleWindowMs ?: IDLE_WINDOW_MS

    /** The burst method's observation window, sharing the same smoke-run override as idle. */
    private fun burstWindowMs(): Long = BenchmarkConfig.idleWindowMs ?: BURST_WINDOW_MS

    /** Revokes POST_NOTIFICATIONS so the OS cannot surface anything during the disabled baseline. */
    private fun revokeNotificationPermission() {
        device.executeShellCommand(
            "pm revoke ${BenchmarkConfig.TARGET_PACKAGE} android.permission.POST_NOTIFICATIONS",
        )
    }

    /** Restores the notification permission grant after the disabled-baseline iterations finish. */
    private fun grantNotificationPermission() {
        device.executeShellCommand(
            "pm grant ${BenchmarkConfig.TARGET_PACKAGE} android.permission.POST_NOTIFICATIONS",
        )
    }

    private companion object {
        const val IDLE_ITERATIONS = 3
        const val BURST_ITERATIONS = 1
        const val IDLE_WINDOW_MS = 60_000L
        const val BURST_WINDOW_MS = 30_000L
        const val BURST_LOG_TAG = "BackgroundIdleBenchmark"
    }
}
