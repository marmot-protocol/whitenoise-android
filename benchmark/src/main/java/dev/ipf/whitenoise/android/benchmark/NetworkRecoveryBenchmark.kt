package dev.ipf.whitenoise.android.benchmark

import android.os.SystemClock
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
 * Captures a same-device resource baseline for one validated network recovery.
 * The host runner must explicitly authorize connectivity changes and owns a
 * second cleanup fence around this test's local restoration.
 */
@RunWith(AndroidJUnit4::class)
class NetworkRecoveryBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Measures the bounded recovery window after a real offline-to-online edge. */
    @Test
    fun validatedNetworkRecoveryPower() {
        val originalAirplaneMode = BenchmarkConfig.requireNetworkToggle()
        val journeys = WhiteNoiseJourneys()
        try {
            benchmarkRule.measureRepeated(
                packageName = BenchmarkConfig.TARGET_PACKAGE,
                metrics = recoveryMetrics(),
                compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
                iterations = RECOVERY_ITERATIONS,
                setupBlock = {
                    setAirplaneMode(BenchmarkAirplaneMode.Disabled)
                    journeys.run { resumeToChatList() }
                    SystemClock.sleep(ONLINE_SETTLE_MS)
                    setAirplaneMode(BenchmarkAirplaneMode.Enabled)
                    SystemClock.sleep(OFFLINE_SETTLE_MS)
                },
                measureBlock = {
                    setAirplaneMode(BenchmarkAirplaneMode.Disabled, awaitState = false)
                    SystemClock.sleep(RECOVERY_OBSERVATION_MS)
                    check(currentAirplaneMode() == BenchmarkAirplaneMode.Disabled) {
                        "Airplane mode did not remain disabled during the recovery window."
                    }
                },
            )
        } finally {
            setAirplaneMode(originalAirplaneMode)
        }
    }

    /** Applies one fixed connectivity state and optionally waits for its system acknowledgement. */
    private fun setAirplaneMode(
        mode: BenchmarkAirplaneMode,
        awaitState: Boolean = true,
    ) {
        device.executeShellCommand(mode.command())
        if (!awaitState) return
        val deadline = SystemClock.elapsedRealtime() + AIRPLANE_MODE_TRANSITION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (currentAirplaneMode() == mode) return
            SystemClock.sleep(AIRPLANE_MODE_POLL_MS)
        }
        error("Timed out waiting for airplane mode ${mode.statusValue}.")
    }

    /** Reads the closed connectivity-shell state without accepting arbitrary output. */
    private fun currentAirplaneMode(): BenchmarkAirplaneMode? =
        BenchmarkAirplaneMode.fromStatusValue(
            device.executeShellCommand("cmd connectivity airplane-mode").trim(),
        )

    private companion object {
        const val RECOVERY_ITERATIONS = 5
        const val ONLINE_SETTLE_MS = 5_000L
        const val OFFLINE_SETTLE_MS = 2_000L
        const val RECOVERY_OBSERVATION_MS = 25_000L
        const val AIRPLANE_MODE_TRANSITION_TIMEOUT_MS = 5_000L
        const val AIRPLANE_MODE_POLL_MS = 100L
    }
}
