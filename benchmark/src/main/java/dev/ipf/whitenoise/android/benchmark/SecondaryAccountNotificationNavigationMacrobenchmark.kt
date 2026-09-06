package dev.ipf.whitenoise.android.benchmark

import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproducible warm-process fixture for inactive-account notification opens.
 *
 * Prepare the non-debuggable `benchmark` target on an API 35 Pixel 8-class
 * device with two signed-in accounts and local conversation history. Deliver at
 * least five fresh notifications for uniquely titled target conversations and
 * pass their notification labels / conversation titles separated by `;;`, plus
 * the non-target source account ref. Each sample restores that source account.
 * Keep the device cool and network-independent; relay readiness is intentionally
 * outside the measured route.
 */
@RunWith(AndroidJUnit4::class)
class SecondaryAccountNotificationNavigationMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun warmProcessFirstConversationFrameWithinOneSecond() {
        val notificationTexts = BenchmarkConfig.notificationTexts
        val conversationTitles = BenchmarkConfig.notificationConversationTitles
        val sourceAccountRef =
            BenchmarkConfig.requireFixture(
                BenchmarkConfig.notificationSourceAccountRef,
                "notificationSourceAccountRef",
            )
        check(notificationTexts.size >= MIN_SAMPLE_COUNT) {
            "Pass at least $MIN_SAMPLE_COUNT unique notificationTexts samples separated by ';;'."
        }
        check(notificationTexts.distinct().size == notificationTexts.size) {
            "notificationTexts must identify fresh notifications."
        }
        check(conversationTitles.size == notificationTexts.size) {
            "notificationConversationTitles must contain one title per notification sample."
        }
        check(conversationTitles.distinct().size == conversationTitles.size) {
            "notificationConversationTitles must be unique across target conversations."
        }
        val journeys = WhiteNoiseJourneys()
        val samples = mutableListOf<NotificationRouteSample>()
        var sampleIndex = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = secondaryAccountNotificationMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = notificationTexts.size,
            setupBlock = {
                journeys.run { resumeToChatList() }
                journeys.activateNotificationSourceAccount(sourceAccountRef)
                pressHome()
            },
            measureBlock = {
                tracedJourney(SECONDARY_ACCOUNT_NOTIFICATION_TRACE) {
                    samples +=
                        journeys.openSecondaryAccountNotification(
                            notificationText = notificationTexts[sampleIndex],
                            expectedConversationTitle = conversationTitles[sampleIndex],
                        )
                    sampleIndex += 1
                }
            },
        )
        check(samples.size == notificationTexts.size) {
            "Expected ${notificationTexts.size} samples, recorded ${samples.size}."
        }
        val sorted = samples.map(NotificationRouteSample::durationMs).sorted()
        val median = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val maximum = sorted.last()
        val identityFailures = samples.count { !it.succeeded }
        val budgetFailures = samples.count { it.durationMs > WARM_ROUTE_BUDGET_MS }
        val report =
            "samples=${samples.size} median=${median}ms p95=${p95}ms max=${maximum}ms " +
                "identityFailures=$identityFailures budgetFailures=$budgetFailures"
        Log.i(BENCHMARK_LOG_TAG, report)
        check(identityFailures == 0 && budgetFailures == 0) {
            "Secondary-account notification route failed: $report. " +
                "Inspect accountActivation, targetProjection, targetTimeline, initialAnchor, " +
                "controllerBind, and firstConversationFrame slices."
        }
    }

    private fun percentile(
        sorted: List<Long>,
        percentile: Double,
    ): Long {
        val rank = kotlin.math.ceil(percentile * sorted.size).toInt()
        return sorted[rank.coerceIn(1, sorted.size) - 1]
    }

    private companion object {
        const val BENCHMARK_LOG_TAG = "NotificationRouteBenchmark"
        const val WARM_ROUTE_BUDGET_MS = 1_000L
        const val MIN_SAMPLE_COUNT = 5
    }
}
