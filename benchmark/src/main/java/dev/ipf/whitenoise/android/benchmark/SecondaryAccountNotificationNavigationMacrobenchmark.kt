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
 *
 * Each sample also reads the app's own rendered-surface markers, so a loading or chat-list surface drawn
 * between the tap and the conversation fails the run even when the total stays inside its budget. The
 * cold-process, runtime-not-ready and app-lock journeys live in [NotificationRouteLifecycleBenchmark].
 */
@RunWith(AndroidJUnit4::class)
class SecondaryAccountNotificationNavigationMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** Measures the warm-task journey and fails on its budget, identity or any intermediate surface. */
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
        val stats = routeLatencyStats(samples.map(NotificationRouteSample::durationMs))
        val identityFailures = samples.count { !it.expectedConversationVisible || !it.transcriptVisible }
        val intermediateFailures = samples.count { it.intermediateSurfaces.isNotEmpty() }
        val budgetFailures = samples.count { it.durationMs > WARM_ROUTE_BUDGET_MS }
        val report =
            "journey=${NotificationRouteJourney.WARM_TASK.label} ${stats.report()} " +
                "identityFailures=$identityFailures intermediateSurfaceFailures=$intermediateFailures " +
                "budgetFailures=$budgetFailures"
        Log.i(BENCHMARK_LOG_TAG, report)
        check(identityFailures == 0 && intermediateFailures == 0 && budgetFailures == 0) {
            "Secondary-account notification route failed: $report. " +
                "Inspect accountActivation, targetProjection, targetTimeline, initialAnchor, " +
                "controllerBind, firstConversationFrame and startupSwap slices."
        }
    }

    private companion object {
        const val BENCHMARK_LOG_TAG = "NotificationRouteBenchmark"
        const val WARM_ROUTE_BUDGET_MS = 1_000L
        const val MIN_SAMPLE_COUNT = 5
    }
}
