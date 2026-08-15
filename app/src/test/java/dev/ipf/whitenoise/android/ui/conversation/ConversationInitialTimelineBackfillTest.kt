package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInitialTimelineBackfillTest {
    @Test
    fun editOnlyPagesAreLoadedUntilRenderableTargetAppears() =
        runTest {
            val windows =
                ArrayDeque(
                    listOf(
                        snapshot(ids = editIds(50), hasMore = true),
                        snapshot(ids = editIds(100), hasMore = true),
                        snapshot(ids = editIds(150), hasMore = true),
                        snapshot(ids = editIds(150) + "target", hasMore = false, renderable = true),
                    ),
                )
            var current = windows.removeFirst()
            var loads = 0

            val result =
                backfillInitialConversationTimeline(
                    snapshot = { current },
                    loadOlder = {
                        loads += 1
                        current = windows.removeFirst()
                        true
                    },
                )

            assertEquals(ConversationInitialTimelineBackfillResult.Renderable, result)
            assertEquals(3, loads)
        }

    @Test
    fun editOnlyHistoryExhaustionTerminatesAsFilteredEmpty() =
        runTest {
            var current = snapshot(ids = editIds(50), hasMore = true)

            val result =
                backfillInitialConversationTimeline(
                    snapshot = { current },
                    loadOlder = {
                        current = snapshot(ids = editIds(75), hasMore = false)
                        true
                    },
                )

            assertEquals(ConversationInitialTimelineBackfillResult.Exhausted, result)
        }

    @Test
    fun unchangedWindowStopsWithoutBusyLoop() =
        runTest {
            val current = snapshot(ids = editIds(50), hasMore = true)
            var loads = 0

            val result =
                backfillInitialConversationTimeline(
                    snapshot = { current },
                    loadOlder = {
                        loads += 1
                        false
                    },
                )

            assertEquals(ConversationInitialTimelineBackfillResult.NoProgress, result)
            assertEquals(1, loads)
        }

    @Test
    fun repeatingWindowCycleStopsWithoutBusyLoop() =
        runTest {
            val first = snapshot(ids = editIds(50), hasMore = true)
            val second = snapshot(ids = editIds(50).map { "older-$it" }, hasMore = true)
            var current = first
            var loads = 0

            val result =
                backfillInitialConversationTimeline(
                    snapshot = { current },
                    loadOlder = {
                        loads += 1
                        current = if (current === first) second else first
                        true
                    },
                )

            assertEquals(ConversationInitialTimelineBackfillResult.NoProgress, result)
            assertEquals(2, loads)
        }

    @Test
    fun pageFailureStopsForVisibleRetry() =
        runTest {
            var current = snapshot(ids = editIds(50), hasMore = true)

            val result =
                backfillInitialConversationTimeline(
                    snapshot = { current },
                    loadOlder = {
                        current = current.copy(hasLoadFailure = true)
                        false
                    },
                )

            assertEquals(ConversationInitialTimelineBackfillResult.Failed, result)
        }

    @Test
    fun cancellationStopsTheActiveBackfill() =
        runTest {
            val loadStarted = CompletableDeferred<Unit>()
            val neverFinishes = CompletableDeferred<Boolean>()
            val job =
                async {
                    backfillInitialConversationTimeline(
                        snapshot = { snapshot(ids = editIds(50), hasMore = true) },
                        loadOlder = {
                            loadStarted.complete(Unit)
                            neverFinishes.await()
                        },
                    )
                }

            loadStarted.await()
            job.cancel()

            assertTrue(job.isCancelled)
            assertFalse(neverFinishes.isCompleted)
        }

    @Test
    fun controllerReplacementRejectsTheOldCompletion() =
        runTest {
            var current = snapshot(ids = editIds(50), hasMore = true)
            var controllerCurrent = true
            var loads = 0

            val result =
                backfillInitialConversationTimeline(
                    snapshot = { current },
                    loadOlder = {
                        loads += 1
                        current = snapshot(ids = editIds(100), hasMore = true)
                        controllerCurrent = false
                        true
                    },
                    isCurrent = { controllerCurrent },
                )

            assertEquals(ConversationInitialTimelineBackfillResult.Superseded, result)
            assertEquals(1, loads)
        }

    private fun snapshot(
        ids: List<String>,
        hasMore: Boolean,
        renderable: Boolean = false,
    ) = ConversationInitialTimelineBackfillSnapshot(
        hasRenderableRows = renderable,
        hasMoreBefore = hasMore,
        loadInFlight = false,
        hasLoadFailure = false,
        rawWindowMessageIds = ids,
    )

    private fun editIds(count: Int) = List(count) { index -> "edit-$index" }
}
