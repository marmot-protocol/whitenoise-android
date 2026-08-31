package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupNoCompilation() = measureStartup(CompilationMode.None())

    @Test
    fun coldStartupBaselineProfile() =
        measureStartup(
            CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
            ),
        )

    /** Measures a cold explicit share through the first visible recipient picker frame. */
    @Test
    fun coldInboundShareBaselineProfile() = measureInboundShare(StartupMode.COLD)

    /** Measures warm `onNewIntent` delivery without an intermediate app route frame. */
    @Test
    fun warmInboundShareBaselineProfile() = measureInboundShare(StartupMode.WARM)

    private fun measureStartup(compilationMode: CompilationMode) {
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = startupMetrics(),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                journeys.run { resumeToChatList() }
                pressHome()
            },
            measureBlock = { journeys.run { launchForStartupMeasurement() } },
        )
    }

    /** Reuses the authenticated fixture while making the share route the measured startup target. */
    private fun measureInboundShare(startupMode: StartupMode) {
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = startupMetrics(),
            compilationMode =
                CompilationMode.Partial(
                    baselineProfileMode = BaselineProfileMode.Require,
                ),
            startupMode = startupMode,
            iterations = 10,
            setupBlock = {
                journeys.run { resumeToChatList() }
                pressHome()
            },
            measureBlock = { journeys.run { launchSharePickerForStartupMeasurement() } },
        )
    }
}
