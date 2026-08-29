package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chat list is the app's default surface and the one that rebuilds every
 * row on every engine update, but until now only the conversation transcript
 * had a scroll benchmark. This establishes the device-specific frame-timing
 * and memory baseline for it so a regression in row composition, avatar cache
 * churn, or folder-chip derivation has somewhere to show up.
 *
 * No fixed budget is asserted: like [WarmResumeBenchmark], the numbers are a
 * per-device baseline until CI has representative samples.
 */
@RunWith(AndroidJUnit4::class)
class ChatListScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun chatListScrollBaselineProfile() {
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = scrollMetrics(SCROLL_CHAT_LIST_TRACE),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 10,
            setupBlock = {
                pressHome()
                journeys.run { resumeToChatList() }
            },
            measureBlock = {
                tracedJourney(SCROLL_CHAT_LIST_TRACE) {
                    journeys.scrollChatList()
                }
            },
        )
    }
}
