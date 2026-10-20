package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.ui.conversation.nostr.eventCardTimelineRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationInitialPresentationWarmCoordinatorTest {
    /** A superseded page's warm finishing later never publishes readiness a second time. */
    @Test
    fun onlyTheNewestPageWarmCanPublishReadiness() =
        runTest {
            val firstWarm = CompletableDeferred<Unit>()
            val secondWarm = CompletableDeferred<Unit>()
            var readyCount = 0
            val coordinator =
                ConversationInitialPresentationWarmCoordinator(
                    scope = this,
                    budgetMillis = 10_000L,
                    warm = { work ->
                        when (work.senders.single()) {
                            "first" -> firstWarm.await()
                            "second" -> secondWarm.await()
                        }
                    },
                    onReady = { readyCount += 1 },
                )

            coordinator.prepare(InitialPresentationWarmWork(listOf("first")))
            runCurrent()
            coordinator.prepare(InitialPresentationWarmWork(listOf("second")))
            runCurrent()
            secondWarm.complete(Unit)
            runCurrent()
            firstWarm.complete(Unit)
            runCurrent()

            assertEquals(1, readyCount)
        }

    /** A slow local read releases navigation exactly at the budget. */
    @Test
    fun warmBudgetReleasesNavigationWhenLocalProfileReadsAreSlow() =
        runTest {
            var readyCount = 0
            val coordinator =
                ConversationInitialPresentationWarmCoordinator(
                    scope = this,
                    budgetMillis = 100L,
                    warm = { awaitCancellation() },
                    onReady = { readyCount += 1 },
                )

            coordinator.prepare(InitialPresentationWarmWork(listOf("slow")))
            runCurrent()
            advanceTimeBy(99L)
            runCurrent()
            assertEquals(0, readyCount)
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(1, readyCount)
        }

    /** A newer page does not restart the route's total warm budget. */
    @Test
    fun supersededWarmCannotRestartTheTotalNavigationBudget() =
        runTest {
            var readyCount = 0
            val coordinator =
                ConversationInitialPresentationWarmCoordinator(
                    scope = this,
                    budgetMillis = 100L,
                    warm = { awaitCancellation() },
                    onReady = { readyCount += 1 },
                )

            coordinator.prepare(InitialPresentationWarmWork(listOf("first")))
            runCurrent()
            advanceTimeBy(90L)
            coordinator.prepare(InitialPresentationWarmWork(listOf("second")))
            runCurrent()
            advanceTimeBy(10L)
            runCurrent()

            assertEquals(1, readyCount)
        }

    /** A page with event cards but no senders still waits for its card warm before publishing readiness. */
    @Test
    fun eventCardRecordsAloneHoldReadinessUntilTheirWarmCompletes() =
        runTest {
            val cardWarm = CompletableDeferred<Unit>()
            var warmed: InitialPresentationWarmWork? = null
            var readyCount = 0
            val coordinator =
                ConversationInitialPresentationWarmCoordinator(
                    scope = this,
                    budgetMillis = 10_000L,
                    warm = { work ->
                        warmed = work
                        cardWarm.await()
                    },
                    onReady = { readyCount += 1 },
                )
            val work = InitialPresentationWarmWork(senders = emptyList(), eventCardRecords = listOf(cardRecord()))

            coordinator.prepare(work)
            runCurrent()
            assertEquals(0, readyCount)
            cardWarm.complete(Unit)
            runCurrent()

            assertEquals(work, warmed)
            assertEquals(1, readyCount)
        }

    /** A timeline record whose text names an event card. */
    private fun cardRecord(): TimelineMessageRecordFfi = eventCardTimelineRecord("m", "nostr:note1example")
}
