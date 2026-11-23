package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
     * completion; ignored taps are retried and counted in the time (#3780). Chat activity that
     * arrives while the app is hidden is not arranged here, so this samples the plain resume path
     * unless the fixture receives messages during the iteration.
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
                journeys.openGroup(groupName)
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
