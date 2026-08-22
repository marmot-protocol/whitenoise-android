package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproducible warm-process fixture for #586.
 *
 * Prepare the non-debuggable `benchmark` target on an API 35 Pixel 8-class
 * device with two signed-in accounts and local conversation history. Leave the
 * source account active, put the app in the background, and deliver a message
 * notifications for the other account. Pass at least five unique notification
 * labels and exact message texts as `notificationTexts` / `notificationMessageTexts`,
 * separated by `;;`, plus `notificationSourceAccountRef`. Keep the device cool
 * and network-independent; relay readiness is intentionally outside the route.
 */
@RunWith(AndroidJUnit4::class)
class SecondaryAccountNotificationNavigationMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun warmProcessFirstConversationFrameWithinOneSecond() {
        val notificationTexts = BenchmarkConfig.notificationTexts
        val notificationMessageTexts = BenchmarkConfig.notificationMessageTexts
        val sourceAccountRef =
            BenchmarkConfig.requireFixture(
                BenchmarkConfig.notificationSourceAccountRef,
                "notificationSourceAccountRef",
            )
        check(notificationTexts.size >= MIN_SAMPLE_COUNT) {
            "Pass at least $MIN_SAMPLE_COUNT unique notificationTexts samples separated by ';;'."
        }
        check(notificationTexts.distinct().size == notificationTexts.size) {
            "notificationTexts must be unique so every iteration opens a fresh notification."
        }
        check(notificationMessageTexts.size == notificationTexts.size) {
            "notificationMessageTexts must have one exact target text per notificationTexts sample."
        }
        check(notificationMessageTexts.distinct().size == notificationMessageTexts.size) {
            "notificationMessageTexts must be unique so exact-target visibility is unambiguous."
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
                            targetMessageText = notificationMessageTexts[sampleIndex],
                        )
                    sampleIndex += 1
                }
            },
        )
        check(samples.size == notificationTexts.size) {
            "Expected ${notificationTexts.size} notification samples, recorded ${samples.size}."
        }
        val sorted = samples.map(NotificationRouteSample::durationMs).sorted()
        val median = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val maximum = sorted.last()
        val identityFailures = samples.count { !it.succeeded }
        val budgetFailures = samples.count { it.durationMs > WARM_ROUTE_BUDGET_MS }
        val failureReasons =
            buildList {
                if (identityFailures > 0) {
                    add("destinationIdentityFailures=$identityFailures/${sorted.size}")
                }
                if (budgetFailures > 0) {
                    add("latencyBudgetFailures=$budgetFailures/${sorted.size} over ${WARM_ROUTE_BUDGET_MS}ms")
                }
            }
        check(failureReasons.isEmpty()) {
            "Secondary-account notification route failed: ${failureReasons.joinToString()}. " +
                "median=${median}ms p95=${p95}ms max=${maximum}ms " +
                "Inspect targetedPreload, accountActivation, groupDetails, controllerBind, " +
                "initialAnchor, and firstConversationFrame slices."
        }
    }

    private fun percentile(
        sorted: List<Long>,
        percentile: Double,
    ): Long {
        val rank = kotlin.math.ceil(percentile * sorted.size).toInt()
        val index = rank.coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private companion object {
        const val WARM_ROUTE_BUDGET_MS = 1_000L
        const val MIN_SAMPLE_COUNT = 5
    }
}
