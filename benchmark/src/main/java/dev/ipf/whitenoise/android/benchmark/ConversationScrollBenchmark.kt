package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun messageListScrollBaselineProfile() {
        val groupName = BenchmarkConfig.requireFixture(BenchmarkConfig.groupName, "groupName")
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = scrollMetrics(SCROLL_CONVERSATION_TRACE),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 10,
            setupBlock = {
                pressHome()
                journeys.run { resumeToChatList() }
                journeys.openGroup(groupName)
            },
            measureBlock = {
                tracedJourney(SCROLL_CONVERSATION_TRACE) {
                    journeys.scrollConversation()
                }
            },
        )
    }
}
