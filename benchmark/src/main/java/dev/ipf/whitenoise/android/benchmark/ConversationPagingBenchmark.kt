package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * History paging as a reader experiences it: long flings that cross several 50-row page boundaries and
 * the 200-row window cap, in both directions, plus the two moments a bounded window is most visible —
 * jumping back to the newest row after the tail was evicted, and paging while the engine is still
 * catching up after a cold start. The fixture must hold at least 300 messages so the deep fling
 * saturates the window with margin; see `docs/performance.md`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationPagingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val journeys = WhiteNoiseJourneys()

    /** Twelve flicks into history without pausing: the backward half of #2789. */
    @Test
    fun deepOlderFling() =
        measurePaging(PAGING_DEEP_OLDER_TRACE) {
            journeys.flingConversation(DEEP_FLINGS, towardOlder = true)
        }

    /** The same distance back toward the newest row, through the newer-page path. */
    @Test
    fun returnFlingAfterDeepHistory() =
        measurePaging(
            PAGING_RETURN_NEWER_TRACE,
            prepare = { journeys.flingConversation(DEEP_FLINGS, towardOlder = true) },
        ) {
            journeys.flingConversation(DEEP_FLINGS, towardOlder = false)
        }

    /** Jump-to-newest once the deep fling has evicted the tail from the bounded window. */
    @Test
    fun jumpToNewestAfterSaturation() =
        measurePaging(
            PAGING_JUMP_TO_NEWEST_TRACE,
            prepare = { journeys.flingConversation(DEEP_FLINGS, towardOlder = true) },
        ) {
            journeys.jumpToNewest()
        }

    /** Flicks issued while the previous fling is still coasting (#2727). */
    @Test
    fun momentumHandoff() =
        measurePaging(PAGING_MOMENTUM_TRACE) {
            journeys.flingConversation(MOMENTUM_FLINGS, towardOlder = true, settleMs = MOMENTUM_GAP_MS)
        }

    /** The deep fling started right after a cold process launch, while sync catch-up owns the engine. */
    @Test
    fun olderFlingWhileEngineCatchesUp() =
        measurePaging(PAGING_BUSY_ENGINE_TRACE, coldProcess = true, iterations = COLD_ITERATIONS) {
            journeys.flingConversation(DEEP_FLINGS, towardOlder = true)
        }

    /**
     * Opens the fixture from the chat list, runs [prepare] unmeasured, then measures [journey]. With
     * [coldProcess] the app process is killed first so the measured block runs against a catching-up
     * engine rather than a settled one.
     *
     * A warm iteration opens and closes the conversation once and waits for its controller to be
     * released before the measured open. The previous iteration ends deep in history with a saturated
     * 200-row window, and the controller's short exit retention would otherwise hand that window back
     * to the next open — a fling that starts there crosses no page boundary and measures nothing.
     */
    private fun measurePaging(
        sectionName: String,
        coldProcess: Boolean = false,
        iterations: Int = WARM_ITERATIONS,
        prepare: MacrobenchmarkScope.() -> Unit = {},
        journey: MacrobenchmarkScope.() -> Unit,
    ) {
        val groupName = BenchmarkConfig.requireFixture(BenchmarkConfig.groupName, "groupName")
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = pagingMetrics(sectionName),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = iterations,
            setupBlock = {
                pressHome()
                if (coldProcess) killProcess()
                journeys.run { resumeToChatList() }
                if (!coldProcess) {
                    journeys.openGroup(groupName)
                    journeys.returnToChatList()
                    journeys.waitForConversationControllerReleased()
                }
                journeys.openGroup(groupName)
                journeys.waitForConversationRouteSettled()
                // The reopen restores the last reading position, which the previous iteration left
                // deep in history; every measured journey starts from the live tail.
                journeys.jumpToNewestIfVisible()
                prepare()
            },
            measureBlock = {
                tracedJourney(sectionName) { journey() }
            },
        )
    }

    private companion object {
        const val WARM_ITERATIONS = 10
        const val COLD_ITERATIONS = 5
        const val DEEP_FLINGS = 12
        const val MOMENTUM_FLINGS = 6
        const val MOMENTUM_GAP_MS = 150L
    }
}
