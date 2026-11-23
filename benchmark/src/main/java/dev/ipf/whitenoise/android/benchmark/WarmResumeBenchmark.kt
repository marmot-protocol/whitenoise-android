package dev.ipf.whitenoise.android.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.onElementOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Establishes separate device-specific median/P90 baselines for the three
 * lifecycle classes in issue #812. No fixed budget is asserted until CI and
 * representative-device samples exist.
 */
@RunWith(AndroidJUnit4::class)
class WarmResumeBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun sameActivityWarmResumeToFirstUsefulFrame() {
        val journeys = WhiteNoiseJourneys()
        lateinit var expectedSurface: BenchmarkUsefulSurface
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = warmResumeMetrics(),
            compilationMode = CompilationMode.Partial(),
            iterations = 10,
            setupBlock = {
                expectedSurface = journeys.run { resumeToUsefulSurface() }
                pressHome()
            },
            measureBlock = {
                startActivityAndWait()
                journeys.run { waitForUsefulSurface(expectedSurface) }
            },
        )
    }

    /**
     * Taps the fixture row as soon as the task is brought back, without waiting for launch
     * completion, and retries every [TAP_RETRY_MS] until the conversation opens; ignored taps
     * are counted in the time (#3780). Chat activity that arrives while the app is hidden is not
     * arranged here, so this samples the plain resume path unless the fixture receives messages
     * during the iteration.
     */
    @Test
    fun sameActivityWarmResumeToFirstAcceptedInput() {
        val groupName = BenchmarkConfig.requireFixture(BenchmarkConfig.groupName, "groupName")
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = warmResumeInputMetrics(),
            compilationMode = CompilationMode.Partial(),
            iterations = 10,
            setupBlock = {
                journeys.run { resumeToChatList() }
                pressHome()
            },
            measureBlock = {
                device.executeShellCommand("am start -n ${BenchmarkConfig.TARGET_PACKAGE}/$MAIN_ACTIVITY")
                device.tapRowUntilOpened(groupName)
            },
        )
    }

    @Test
    fun activityRecreationToFirstUsefulFrame() {
        val journeys = WhiteNoiseJourneys()
        lateinit var expectedSurface: BenchmarkUsefulSurface
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = warmResumeMetrics(),
            compilationMode = CompilationMode.Partial(),
            iterations = 10,
            setupBlock = {
                expectedSurface = journeys.run { resumeToUsefulSurface() }
            },
            measureBlock = {
                journeys.run { recreateActivityAndWait(expectedSurface) }
            },
        )
    }

    @Test
    fun processRestorationToFirstUsefulFrame() {
        val journeys = WhiteNoiseJourneys()
        lateinit var expectedSurface: BenchmarkUsefulSurface
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = warmResumeMetrics(),
            compilationMode = CompilationMode.Partial(),
            iterations = 10,
            setupBlock = {
                expectedSurface = journeys.run { resumeToUsefulSurface() }
                pressHome()
                // `killProcess` preserves the Android task and app data. Do not
                // use StartupMode.COLD here: its force-stop is a cold launch,
                // not process-death/task restoration.
                killProcess()
            },
            measureBlock = {
                journeys.run { launchRestoredTaskAndWait(expectedSurface) }
            },
        )
    }
}

private const val MAIN_ACTIVITY = "dev.ipf.whitenoise.android.MainActivity"

/** A short, fixed retry so a closed input gate costs about its own length, not a navigation timeout. */
private fun UiDevice.tapRowUntilOpened(groupName: String) {
    // UI Automator otherwise waits for an accessibility-quiet window before each lookup.
    val configurator = Configurator.getInstance()
    val idleTimeout = configurator.waitForIdleTimeout
    configurator.waitForIdleTimeout = 0L
    try {
        tapUntilOpened(groupName)
    } finally {
        configurator.waitForIdleTimeout = idleTimeout
    }
}

private fun UiDevice.tapUntilOpened(groupName: String) {
    val deadline = SystemClock.uptimeMillis() + OPEN_ROW_TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
        val opened =
            onElementOrNull(timeoutMs = 0) {
                viewIdResourceName == PerformanceTags.OPEN_GROUP_DETAILS ||
                    extras.getCharSequence(COMPOSE_TEST_TAG)?.toString() == PerformanceTags.OPEN_GROUP_DETAILS
            }
        if (opened != null) return
        try {
            findObject(By.pkg(BenchmarkConfig.TARGET_PACKAGE).text(groupName))?.click()
        } catch (_: StaleObjectException) {
            // The row recomposed between lookup and tap; the next attempt finds it again.
        }
        SystemClock.sleep(TAP_RETRY_MS)
    }
    error("Timed out tapping fixture row '$groupName' after resume.")
}

private const val COMPOSE_TEST_TAG = "androidx.compose.ui.semantics.testTag"
private const val TAP_RETRY_MS = 50L
private const val OPEN_ROW_TIMEOUT_MS = 15_000L
