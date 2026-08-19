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
 * notification for the other account. Pass unique visible notification text as
 * `notificationText`. Keep the device cool and network-independent; relay
 * readiness is intentionally outside the measured route.
 */
@RunWith(AndroidJUnit4::class)
class SecondaryAccountNotificationNavigationMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun warmProcessFirstConversationFrameWithinOneSecond() {
        val notificationText =
            BenchmarkConfig.requireFixture(BenchmarkConfig.notificationText, "notificationText")
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = secondaryAccountNotificationMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            // A notification is consumable and the target becomes active, so
            // every iteration requires a newly delivered inverse-account tap.
            iterations = 1,
            setupBlock = {
                journeys.run { resumeToChatList() }
                pressHome()
            },
            measureBlock = {
                tracedJourney(SECONDARY_ACCOUNT_NOTIFICATION_TRACE) {
                    val durationMs = journeys.openSecondaryAccountNotification(notificationText)
                    check(durationMs <= WARM_ROUTE_BUDGET_MS) {
                        "Secondary-account notification route took ${durationMs}ms; " +
                            "budget is ${WARM_ROUTE_BUDGET_MS}ms. Inspect named phase slices."
                    }
                }
            },
        )
    }

    private companion object {
        const val WARM_ROUTE_BUDGET_MS = 1_000L
    }
}
