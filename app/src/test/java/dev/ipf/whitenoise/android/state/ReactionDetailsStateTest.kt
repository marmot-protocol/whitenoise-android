package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Complete reaction reads and optimistic changes must never regress to bounded chip previews. */
class ReactionDetailsStateTest {
    /** Three identical reactions and a separate emoji survive own-removal outside the preview. */
    @Test
    fun completeMixedEmojiSnapshotPreservesAllParticipantsAndOwnRemoval() {
        val confirmed = listOf(row("alice", "👍"), row("bob", "👍"), row("me", "👍"), row("dave", "🔥"))
        assertEquals(4, reactionDetailsParticipants(confirmed, "me", emptyList(), 2uL).size)
        val removed =
            reactionDetailsParticipants(
                confirmed,
                "me",
                listOf(OptimisticReactionChange("target", "👍", false)),
                2uL,
            )
        assertEquals(listOf("alice", "bob", "dave"), removed.map { it.sender })
        val added =
            reactionDetailsParticipants(
                confirmed,
                "me",
                listOf(OptimisticReactionChange("target", "👍", true)),
                2uL,
            )
        assertEquals(4, added.size)
        assertEquals("me", added.first().sender)
    }

    /** No preview appears while loading; failures permit an explicit retry to a complete snapshot. */
    @Test
    fun failedInitialReadDoesNotBecomeAnEmptySuccessAndCanRetry() =
        runTest {
            val state = ReactionDetailsState()
            state.refresh { error("unavailable") }
            assertTrue(state.failed)
            assertFalse(state.loading)
            assertNull(state.participants)
            assertFalse(state.ready)
            state.refresh { listOf(row("alice", "👍"), row("bob", "👍"), row("carol", "👍")) }
            assertFalse(state.failed)
            assertEquals(3, state.participants!!.size)
            assertTrue(state.ready)
        }

    /** A slower prior revision cannot overwrite a newer complete snapshot. */
    @Test
    fun latestReadOwnsTheSnapshotEvenWhenAnOlderReadFinishesLast() =
        runTest {
            val state = ReactionDetailsState()
            val oldRead = CompletableDeferred<List<ReactionParticipant>>()
            val old = launch { state.refresh { oldRead.await() } }
            testScheduler.runCurrent()
            state.refresh { listOf(row("new", "👍")) }
            oldRead.complete(listOf(row("old", "👍")))
            old.join()
            assertEquals("new", state.participants!!.single().sender)
        }

    /** Dismissal cancels the read rather than accepting a result into the next sheet's state. */
    @Test
    fun cancelledReadCannotPublishParticipants() =
        runTest {
            val state = ReactionDetailsState()
            val read = CompletableDeferred<List<ReactionParticipant>>()
            val job = launch { state.refresh { read.await() } }
            testScheduler.runCurrent()
            job.cancelAndJoin()
            read.complete(listOf(row("late", "👍")))
            assertNull(state.participants)
            assertFalse(state.ready)
        }

    /** Revisions can swap an identity without changing counts; refresh replaces the entire list. */
    @Test
    fun sameCountReplacementAndFailedRefreshKeepCompleteSnapshots() =
        runTest {
            val state = ReactionDetailsState()
            state.refresh { listOf(row("alice", "👍"), row("bob", "👍"), row("carol", "👍")) }
            state.refresh { listOf(row("alice", "👍"), row("bob", "👍"), row("dave", "👍")) }
            assertEquals(listOf("alice", "bob", "dave"), state.participants!!.map { it.sender })
            state.refresh { error("refresh failed") }
            assertTrue(state.failed)
            assertEquals(3, state.participants!!.size)
        }

    /** Creates a deterministic complete reactor row. */
    private fun row(
        sender: String,
        emoji: String,
    ) = ReactionParticipant(sender, emoji, 1uL)
}
