package dev.ipf.whitenoise.android.benchmark

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val fixture = BackgroundFixtureGuard(device)

    /** Measures one verified internet recovery while the push fixture remains screen-off. */
    @Test
    fun backgroundValidatedNetworkRecoveryPower() {
        requireFixtureForeground()
        val originalAirplaneMode = BenchmarkConfig.requireNetworkToggle()
        val originalWifiEnabled =
            requireNotNull(BenchmarkConfig.originalWifiEnabled) {
                "The host must capture the original Wi-Fi state before qualification."
            }
        require(originalAirplaneMode == BenchmarkAirplaneMode.Disabled) {
            "Prepare an online fixture before the background recovery measurement."
        }
        val journeys = WhiteNoiseJourneys()
        try {
            benchmarkRule.measureRepeated(
                packageName = BenchmarkConfig.TARGET_PACKAGE,
                metrics = idleMetrics(),
                compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
                iterations = 1,
                setupBlock = {
                    requireFixtureForeground()
                    check(hasValidatedInternet()) { "The fixture is not on validated internet." }
                    journeys.run { resumeToChatList() }
                    journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Fcm)
                    SystemClock.sleep(ONLINE_SETTLE_MS)
                    pressHome()
                    device.sleep()
                    setWifiEnabled(false)
                    setAirplaneMode(BenchmarkAirplaneMode.Enabled)
                    awaitInternet(validated = false)
                    SystemClock.sleep(OFFLINE_SETTLE_MS)
                    check(!device.isScreenOn && !hasValidatedInternet()) {
                        "The fixture did not stay screen-off and offline."
                    }
                },
                measureBlock = {
                    requireFixtureForeground()
                    val pid = fixture.requireScreenOffProcess()
                    val deadline = SystemClock.elapsedRealtime() + RECOVERY_OBSERVATION_MS
                    setAirplaneMode(BenchmarkAirplaneMode.Disabled, awaitState = false)
                    setWifiEnabled(originalWifiEnabled)
                    awaitInternet(validated = true)
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "Validated internet recovery exceeded the measured window." }
                    SystemClock.sleep(remaining)
                    check(!device.isScreenOn && hasValidatedInternet()) {
                        "The fixture did not stay screen-off and online during recovery."
                    }
                    requireFixtureForeground()
                    fixture.verify(pid)
                },
            )
        } finally {
            try {
                setAirplaneMode(originalAirplaneMode)
            } finally {
                setWifiEnabled(originalWifiEnabled)
            }
        }
    }

    /** Prevents device-wide navigation from crossing into a personal Android user. */
    private fun requireFixtureForeground() {
        fixture.requireForeground()
    }

    /** Reads default-network validation, rather than equating a radio setting with connectivity. */
    private fun hasValidatedInternet(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Bounds both the offline acknowledgement and the genuine online recovery edge. */
    private fun awaitInternet(validated: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + NETWORK_TRANSITION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasValidatedInternet() == validated) return
            SystemClock.sleep(AIRPLANE_MODE_POLL_MS)
        }
        error("Timed out waiting for validated internet state $validated.")
    }

    /** Restores the exact Wi-Fi switch, including devices that retain Wi-Fi in airplane mode. */
    private fun setWifiEnabled(enabled: Boolean) {
        val action = if (enabled) "enabled" else "disabled"
        device.executeShellCommand("cmd wifi set-wifi-enabled $action")
        val manager = context.getSystemService(WifiManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + NETWORK_TRANSITION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (manager.isWifiEnabled == enabled) return
            SystemClock.sleep(AIRPLANE_MODE_POLL_MS)
        }
        error("Timed out waiting for Wi-Fi state $enabled.")
    }

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
        const val NETWORK_TRANSITION_TIMEOUT_MS = 10_000L
    }
}
