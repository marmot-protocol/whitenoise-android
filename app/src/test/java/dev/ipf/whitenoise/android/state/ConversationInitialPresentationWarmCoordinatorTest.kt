package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.ui.conversation.nostr.eventCardTimelineRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                coordinator(
                    profileBudgetMillis = 10_000L,
                    warmProfiles = { senders ->
                        when (senders.single()) {
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

    /** A slow local profile read on a page without cards releases navigation exactly at the profile budget. */
    @Test
    fun warmBudgetReleasesNavigationWhenLocalProfileReadsAreSlow() =
        runTest {
            var readyCount = 0
            val coordinator = coordinator(warmProfiles = { awaitCancellation() }, onReady = { readyCount += 1 })

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
            val coordinator = coordinator(warmProfiles = { awaitCancellation() }, onReady = { readyCount += 1 })

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
                coordinator(
                    warmEventCards = { work ->
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

    /** The profile budget expiring never cancels a card warm, which keeps the route until it settles. */
    @Test
    fun profileBudgetExpiryDoesNotCancelTheCardWarm() =
        runTest {
            var cardWarmFinishedAt: Long? = null
            var readyAt: Long? = null
            val coordinator =
                coordinator(
                    warmProfiles = { awaitCancellation() },
                    warmEventCards = {
                        delay(SLOW_CARD_READ_MILLIS)
                        cardWarmFinishedAt = currentTime
                    },
                    onReady = { readyAt = currentTime },
                )

            coordinator.prepare(InitialPresentationWarmWork(listOf("sender"), eventCardRecords = listOf(cardRecord())))
            advanceTimeBy(INITIAL_PRESENTATION_PROFILE_WARM_BUDGET_MILLIS + 1L)
            runCurrent()
            assertEquals(null, readyAt)
            advanceTimeBy(SLOW_CARD_READ_MILLIS)
            runCurrent()

            assertEquals(SLOW_CARD_READ_MILLIS, cardWarmFinishedAt)
            assertEquals(SLOW_CARD_READ_MILLIS, readyAt)
        }

    /** A card warm that never settles still opens the route, exactly at the card budget. */
    @Test
    fun cardBudgetBoundsANeverSettlingCardWarm() =
        runTest {
            var readyAt: Long? = null
            val coordinator =
                coordinator(warmEventCards = { awaitCancellation() }, onReady = { readyAt = currentTime })

            coordinator.prepare(InitialPresentationWarmWork(emptyList(), eventCardRecords = listOf(cardRecord())))
            advanceTimeBy(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS - 1L)
            runCurrent()
            assertEquals(null, readyAt)
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS, readyAt)
        }

    /** A caller waiting for readiness resumes only once onReady ran, so it sees what readiness published. */
    @Test
    fun awaitReadyResumesAfterReadinessIsPublished() =
        runTest {
            val warm = CompletableDeferred<Unit>()
            var published = false
            val coordinator = coordinator(warmProfiles = { warm.await() }, onReady = { published = true })
            coordinator.prepare(InitialPresentationWarmWork(listOf("slow")))
            var sawPublished: Boolean? = null
            launch {
                coordinator.awaitReady()
                sawPublished = published
            }
            runCurrent()
            assertEquals(null, sawPublished)

            warm.complete(Unit)
            runCurrent()

            assertEquals(true, sawPublished)
        }

    /** A caller waiting for readiness is released when the coordinator's scope ends, since nothing can publish it. */
    @Test
    fun awaitReadyReturnsWhenTheScopeEnds() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val coordinator =
                ConversationInitialPresentationWarmCoordinator(
                    scope = owner,
                    warmProfiles = { awaitCancellation() },
                    warmEventCards = {},
                    onReady = {},
                )
            coordinator.prepare(InitialPresentationWarmWork(listOf("slow")))
            var released = false
            launch {
                coordinator.awaitReady()
                released = true
            }
            runCurrent()
            assertFalse(released)

            owner.cancel()
            runCurrent()

            assertTrue(released)
        }

    /** A coordinator over [this] test scope with the production budgets unless a test overrides them. */
    private fun TestScope.coordinator(
        warmProfiles: suspend (List<String>) -> Unit = {},
        warmEventCards: suspend (InitialPresentationWarmWork) -> Unit = {},
        onReady: () -> Unit,
        profileBudgetMillis: Long = INITIAL_PRESENTATION_PROFILE_WARM_BUDGET_MILLIS,
    ) = ConversationInitialPresentationWarmCoordinator(
        scope = this as CoroutineScope,
        warmProfiles = warmProfiles,
        warmEventCards = warmEventCards,
        onReady = onReady,
        profileBudgetMillis = profileBudgetMillis,
    )

    /** A timeline record whose text names an event card. */
    private fun cardRecord(): TimelineMessageRecordFfi = eventCardTimelineRecord("m", "nostr:note1example")

    private companion object {
        /** Slower than the profile budget, within the card budget. */
        const val SLOW_CARD_READ_MILLIS = 400L
    }
}
