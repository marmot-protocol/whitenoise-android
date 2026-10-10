package dev.ipf.whitenoise.android.benchmark

import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The non-warm lifecycle journeys of the notification-route contract (#586): a killed process, a suspended
 * target-account runtime, and an app lock that defers the route to a person.
 *
 * UNMEASURED: these journeys compile and are wired to their budgets, but no device run backs them in the
 * change that added them, so none of their budgets is evidence yet. Each needs the fixture below, and each
 * skips itself with an explanation rather than passing when the fixture is absent. Every journey requires
 * [REQUIRED_ITERATIONS] fresh notifications (`notificationTexts`, one per iteration, with matching
 * `notificationConversationTitles`) and reports median, P95 and maximum against its own limits. Setup is
 * reported on its own line and is never dropped from the measured set after its latency is seen.
 */
@RunWith(AndroidJUnit4::class)
class NotificationRouteLifecycleBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** Process creation is part of the route: the process is killed before every tap. */
    @Test
    fun coldProcessFirstConversationFrame() {
        runJourney(NotificationRouteJourney.COLD_PROCESS) { journeys -> journeys.killAppAndGoHome() }
    }

    /** Needs a fixture that leaves the target account's runtime suspended before each sample. */
    @Test
    fun targetRuntimeNotReadyFirstConversationFrame() {
        assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.runtimeNotReadyFixture=true after preparing a " +
                "fixture that leaves the target account runtime suspended before every sample.",
            BenchmarkConfig.runtimeNotReadyFixture,
        )
        runJourney(NotificationRouteJourney.RUNTIME_NOT_READY) { journeys -> journeys.prepareSourceAccountAtHome() }
    }

    /**
     * Measures the two active-processing segments around a human unlock: tap to the lock surface, and
     * unlock to the first readable frame. The credential check itself is driven by the host's unlock
     * command, which is why this journey is the least reproducible without that hand-off.
     */
    @Test
    fun appLockDeferredRouteSplitsAroundTheUnlock() {
        val unlockCommand =
            BenchmarkConfig.requireFixture(BenchmarkConfig.appLockUnlockCommand, "appLockUnlockCommand")
        val texts = requireSamples()
        val titles = BenchmarkConfig.notificationConversationTitles
        val toLockSurface = mutableListOf<Long>()
        val afterUnlock = mutableListOf<Long>()
        val setup = RouteSetupLedger()
        val journeys = WhiteNoiseJourneys()
        var sampleIndex = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = secondaryAccountNotificationMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = texts.size,
            setupBlock = { setup.run { journeys.prepareSourceAccountAtHome() } },
            measureBlock = {
                tracedJourney(SECONDARY_ACCOUNT_NOTIFICATION_TRACE) {
                    toLockSurface += journeys.openNotificationToAppLock(texts[sampleIndex])
                    afterUnlock += journeys.unlockToConversation(unlockCommand, titles[sampleIndex])
                    sampleIndex += 1
                }
            },
        )
        reportAppLock(toLockSurface, afterUnlock, setup)
    }

    /** Runs one journey for [REQUIRED_ITERATIONS] iterations and fails on its P95 and maximum budgets. */
    private fun runJourney(
        journey: NotificationRouteJourney,
        prepare: (WhiteNoiseJourneys) -> Unit,
    ) {
        val texts = requireSamples()
        val titles = BenchmarkConfig.notificationConversationTitles
        val samples = mutableListOf<NotificationRouteSample>()
        val setup = RouteSetupLedger()
        val journeys = WhiteNoiseJourneys()
        var sampleIndex = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = secondaryAccountNotificationMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = texts.size,
            setupBlock = { setup.run { prepare(journeys) } },
            measureBlock = {
                tracedJourney(SECONDARY_ACCOUNT_NOTIFICATION_TRACE) {
                    samples +=
                        journeys.openSecondaryAccountNotification(
                            notificationText = texts[sampleIndex],
                            expectedConversationTitle = titles[sampleIndex],
                        )
                    sampleIndex += 1
                }
            },
        )
        val stats = routeLatencyStats(samples.map(NotificationRouteSample::durationMs))
        val identityFailures = samples.count { !it.expectedConversationVisible || !it.transcriptVisible }
        val intermediateFailures = samples.count { it.intermediateSurfaces.isNotEmpty() }
        val budgetFailure = stats.p95Ms > journey.p95BudgetMs || stats.maxMs > journey.maxBudgetMs
        val report =
            "journey=${journey.label} measured=${journey.measured} ${stats.report()} " +
                "p95Budget=${journey.p95BudgetMs}ms maxBudget=${journey.maxBudgetMs}ms " +
                "identityFailures=$identityFailures intermediateSurfaceFailures=$intermediateFailures " +
                setup.report()
        Log.i(BENCHMARK_LOG_TAG, report)
        check(identityFailures == 0 && intermediateFailures == 0 && !budgetFailure) {
            "Notification route journey failed: $report"
        }
    }

    /** Checks both app-lock segments and their combined active-processing time against the issue's limits. */
    private fun reportAppLock(
        toLockSurface: List<Long>,
        afterUnlock: List<Long>,
        setup: RouteSetupLedger,
    ) {
        check(toLockSurface.size == afterUnlock.size) { "Every iteration must record both app-lock segments." }
        val first = routeLatencyStats(toLockSurface)
        val second = routeLatencyStats(afterUnlock)
        val combined = routeLatencyStats(toLockSurface.zip(afterUnlock) { a, b -> a + b })
        val journey = NotificationRouteJourney.APP_LOCK_DEFERRED
        val failed =
            first.p95Ms > journey.p95BudgetMs ||
                first.maxMs > journey.maxBudgetMs ||
                second.p95Ms > AppLockRouteBudgets.POST_UNLOCK_P95_MS ||
                second.maxMs > AppLockRouteBudgets.POST_UNLOCK_MAX_MS ||
                combined.p95Ms > AppLockRouteBudgets.COMBINED_P95_MS ||
                combined.maxMs > AppLockRouteBudgets.COMBINED_MAX_MS
        val report =
            "journey=${journey.label} measured=${journey.measured} toLockSurface[${first.report()}] " +
                "afterUnlock[${second.report()}] combined[${combined.report()}] ${setup.report()}"
        Log.i(BENCHMARK_LOG_TAG, report)
        check(!failed) { "App-lock notification route failed: $report" }
    }

    /** Requires exactly the contract's iteration count of fresh, uniquely titled notifications. */
    private fun requireSamples(): List<String> {
        val texts = BenchmarkConfig.notificationTexts
        val titles = BenchmarkConfig.notificationConversationTitles
        assumeTrue(
            "Pass $REQUIRED_ITERATIONS fresh notificationTexts and matching notificationConversationTitles " +
                "separated by ';;', plus notificationSourceAccountRef.",
            texts.size >= REQUIRED_ITERATIONS && titles.size == texts.size,
        )
        check(texts.distinct().size == texts.size && titles.distinct().size == titles.size) {
            "Notification texts and conversation titles must each be unique across samples."
        }
        return texts
    }

    private companion object {
        const val BENCHMARK_LOG_TAG = "NotificationRouteBenchmark"
        const val REQUIRED_ITERATIONS = 30
    }
}
