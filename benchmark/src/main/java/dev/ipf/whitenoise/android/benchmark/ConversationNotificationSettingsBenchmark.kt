package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class ConversationNotificationSettingsBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** Repeated launches after a warm app resume must dispatch below the issue p95 budget. */
    @Test
    fun warmClickToStartActivityP95() = measureSettingsLaunch(coldScreenEntry = false)

    /** First notification-screen entry after process death must retain the same dispatch budget. */
    @Test
    fun coldClickToStartActivityP95() = measureSettingsLaunch(coldScreenEntry = true)

    /** Collects 20 exact-target samples and enforces the issue's app-dispatch p95. */
    private fun measureSettingsLaunch(coldScreenEntry: Boolean) {
        val groupName = BenchmarkConfig.requireFixture(BenchmarkConfig.groupName, "groupName")
        val journeys = WhiteNoiseJourneys()
        val samples = mutableListOf<ConversationSettingsLaunchSample>()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = conversationSettingsMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = SAMPLE_COUNT,
            setupBlock = {
                pressHome()
                if (coldScreenEntry) killProcess()
                journeys.run {
                    resumeToChatList()
                    openGroupNotificationSettings(groupName)
                }
            },
            measureBlock = {
                samples += journeys.openPreparedGroupMessageNotificationSettings()
            },
        )
        val p95 = percentile95(samples.map(ConversationSettingsLaunchSample::appDispatchDurationMs))
        assertTrue("click-to-startActivity p95 was ${p95}ms; budget is <${APP_DISPATCH_BUDGET_MS}ms", p95 < APP_DISPATCH_BUDGET_MS)
    }

    /** Returns the nearest-rank 95th percentile used by the acceptance budget. */
    private fun percentile95(samples: List<Long>): Long {
        require(samples.isNotEmpty())
        val sorted = samples.sorted()
        return sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)]
    }

    private companion object {
        const val SAMPLE_COUNT = 20
        const val APP_DISPATCH_BUDGET_MS = 100L
    }
}
