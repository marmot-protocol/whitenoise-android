package dev.ipf.whitenoise.android.benchmark

import android.Manifest
import android.content.pm.PackageManager
import android.os.Process
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
 * Every measured window puts the screen to sleep first: a kept-awake, screen-on idle reads mostly
 * display power, which swamps any delivery-mode-specific signal and reads nearly identical across
 * postures regardless of what the app is doing. Real background battery drain happens with the
 * screen off, so that is what these measure.
 *
 * The product never resolves to a true third "nothing" delivery mode (see
 * `NativePushDelivery.resolvedNotificationDeliveryMode`), so [idleWithDeliveryDisabledPower]
 * measures a controlled fixture floor: Push selected (so Local is off), the fixture FCM receiver
 * disabled and notification permission revoked. Both platform overrides are restored afterward.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundIdleBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val fixture = BackgroundFixtureGuard(device)

    /** Fixture floor: Local off, FCM ingress disabled and permission revoked, with a live background process. */
    @Test
    fun idleWithDeliveryDisabledPower() {
        fixture.requireForeground()
        val journeys = WhiteNoiseJourneys()
        val permissionWasGranted = notificationPermissionGranted()
        try {
            BackgroundPushReceiverControl(device).withReceiverDisabled {
                benchmarkRule.measureRepeated(
                    packageName = BenchmarkConfig.TARGET_PACKAGE,
                    metrics = idleMetrics(),
                    compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
                    iterations = IDLE_ITERATIONS,
                    setupBlock = {
                        fixture.requireForeground()
                        journeys.run { resumeToChatList() }
                        journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Fcm)
                        revokeNotificationPermission()
                        // Revoking a granted runtime permission can kill the app's process; resume
                        // and settle again so the measured window samples a live, backgrounded app
                        // rather than one that never came back up after the revoke.
                        journeys.run { resumeToChatList() }
                        pressHome()
                    },
                    measureBlock = {
                        device.sleep()
                        val pid = fixture.requireScreenOffProcess()
                        SystemClock.sleep(idleWindowMs())
                        fixture.verify(pid)
                    },
                )
            }
        } finally {
            restoreNotificationPermission(permissionWasGranted)
        }
    }

    /** Native push (Fcm) selected, idle in the background. */
    @Test
    fun idleWithNativePushPower() {
        fixture.requireForeground()
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = idleMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = IDLE_ITERATIONS,
            setupBlock = {
                fixture.requireForeground()
                journeys.run { resumeToChatList() }
                journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Fcm)
                pressHome()
            },
            measureBlock = {
                device.sleep()
                val pid = fixture.requireScreenOffProcess()
                SystemClock.sleep(idleWindowMs())
                fixture.verify(pid)
            },
        )
    }

    /** Local/keep-connected selected, idle in the background. */
    @Test
    fun idleWithKeepConnectedPower() {
        fixture.requireForeground()
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = idleMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = IDLE_ITERATIONS,
            setupBlock = {
                fixture.requireForeground()
                journeys.run { resumeToChatList() }
                journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Local)
                pressHome()
            },
            measureBlock = {
                device.sleep()
                val pid = fixture.requireScreenOffProcess()
                SystemClock.sleep(idleWindowMs())
                fixture.verify(pid)
            },
        )
    }

    /**
     * Observes background resource cost while a burst of pre-arranged messages arrives.
     *
     * Unlike `SecondaryAccountNotificationNavigationMacrobenchmark`, which measures UI reaction
     * time against notifications already sitting in the tray, this measures the receive-side
     * push-wake/catch-up cost, so the burst must arrive *during* the measured window, screen
     * asleep. Runs a single iteration; coordinate a burst of several messages from a second
     * account, sent within the first few seconds after the logged "send now" line, before the
     * window closes.
     */
    @Test
    fun pushBurstPower() {
        fixture.requireForeground()
        val expectedTexts = BenchmarkConfig.notificationTexts
        require(expectedTexts.size >= 5 && expectedTexts.distinct().size == expectedTexts.size) {
            "Pass at least five distinct disposable fixture texts in notificationTexts."
        }
        val journeys = WhiteNoiseJourneys()
        val receipts = BackgroundDeliveryReceiptProbe(device, expectedTexts)
        receipts.withListener {
            benchmarkRule.measureRepeated(
                packageName = BenchmarkConfig.TARGET_PACKAGE,
                metrics = idleMetrics(),
                compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
                iterations = BURST_ITERATIONS,
                setupBlock = {
                    fixture.requireForeground()
                    journeys.run { resumeToChatList() }
                    journeys.setNotificationDeliveryMode(BenchmarkDeliveryMode.Fcm)
                    receipts.requireFreshFixture()
                    pressHome()
                },
                measureBlock = {
                    device.sleep()
                    val pid = fixture.requireScreenOffProcess()
                    receipts.beginWindow(burstWindowMs())
                    Log.i(BURST_LOG_TAG, "Send the push burst now; observing for ${burstWindowMs()}ms.")
                    SystemClock.sleep(burstWindowMs())
                    fixture.verify(pid)
                    receipts.finishWindow()
                },
            )
        }
    }

    /** The idle methods' sleep duration, overridable via `idleWindowMs` for a short smoke run. */
    private fun idleWindowMs(): Long = BenchmarkConfig.idleWindowMs ?: IDLE_WINDOW_MS

    /** The burst method's observation window, sharing the same smoke-run override as idle. */
    private fun burstWindowMs(): Long = BenchmarkConfig.idleWindowMs ?: BURST_WINDOW_MS

    /** Revokes POST_NOTIFICATIONS so the OS cannot surface anything during the disabled baseline. */
    private fun revokeNotificationPermission() {
        device.executeShellCommand(
            "pm revoke --user ${fixtureUserId()} ${BenchmarkConfig.TARGET_PACKAGE} android.permission.POST_NOTIFICATIONS",
        )
    }

    /** Reads the original fixture grant rather than assuming setup always started granted. */
    private fun notificationPermissionGranted(): Boolean =
        InstrumentationRegistry.getInstrumentation().context.packageManager.checkPermission(
            Manifest.permission.POST_NOTIFICATIONS,
            BenchmarkConfig.TARGET_PACKAGE,
        ) == PackageManager.PERMISSION_GRANTED

    /** Restores the exact original grant after success, failure or a permission-killed process. */
    private fun restoreNotificationPermission(wasGranted: Boolean) {
        val action = if (wasGranted) "grant" else "revoke"
        device.executeShellCommand(
            "pm $action --user ${fixtureUserId()} ${BenchmarkConfig.TARGET_PACKAGE} android.permission.POST_NOTIFICATIONS",
        )
        check(notificationPermissionGranted() == wasGranted) {
            "The fixture notification permission was not restored."
        }
    }

    /** Applies shell permission changes to this instrumentation's profile, never the Owner implicitly. */
    private fun fixtureUserId(): Int = Process.myUid() / 100_000

    private companion object {
        // A genuinely slept screen re-engages a secure keyguard that `wm dismiss-keyguard`
        // cannot clear, so a second iteration can't autonomously re-enter the app on a device
        // with a real lock method. One iteration keeps this measurement honest without needing
        // the operator to unlock between samples.
        const val IDLE_ITERATIONS = 1
        const val BURST_ITERATIONS = 1
        const val IDLE_WINDOW_MS = 60_000L
        const val BURST_WINDOW_MS = 30_000L
        const val BURST_LOG_TAG = "BackgroundIdleBenchmark"
    }
}
