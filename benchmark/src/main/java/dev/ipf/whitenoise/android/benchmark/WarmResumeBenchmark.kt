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
