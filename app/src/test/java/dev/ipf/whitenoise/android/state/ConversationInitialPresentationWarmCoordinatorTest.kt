package dev.ipf.whitenoise.android.state

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
                    warm = { senders ->
                        when (senders.single()) {
                            "first" -> firstWarm.await()
                            "second" -> secondWarm.await()
                        }
                    },
                    onReady = { readyCount += 1 },
                )

            coordinator.prepare(listOf("first"))
            runCurrent()
            coordinator.prepare(listOf("second"))
            runCurrent()
            secondWarm.complete(Unit)
            runCurrent()
            firstWarm.complete(Unit)
            runCurrent()

            assertEquals(1, readyCount)
        }

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

            coordinator.prepare(listOf("slow"))
            runCurrent()
            advanceTimeBy(99L)
            runCurrent()
            assertEquals(0, readyCount)
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(1, readyCount)
        }
}
