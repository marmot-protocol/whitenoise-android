package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollVoteFfi
import dev.ipf.marmotkit.PollVotePageFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native per-voter paging: cursors, totals, empty and error states, and stale-result rejection. */
@OptIn(ExperimentalCoroutinesApi::class)
class PollVotesPagerTest {
    private data class Call(
        val afterVotedAt: ULong?,
        val afterVoter: String?,
        val limit: UInt,
    )

    /** Builds a native vote whose time and voter id follow [index], so ordering is deterministic. */
    private fun vote(
        index: Int,
        vararg options: String,
    ) = PollVoteFfi("%064x".format(index), options.toList(), (100 + index).toULong())

    /** A scripted reader that records each cursor and replays queued pages or failures. */
    private class ScriptedReader(
        private val steps: ArrayDeque<() -> PollVotePageFfi>,
    ) : PollVotesReader {
        val calls = mutableListOf<Call>()

        /** Records the cursor, then replays the next scripted page or failure. */
        override suspend fun read(
            afterVotedAt: ULong?,
            afterVoterAccountIdHex: String?,
            limit: UInt,
        ): PollVotePageFfi {
            calls += Call(afterVotedAt, afterVoterAccountIdHex, limit)
            return steps.removeFirst()()
        }
    }

    /** Builds a reader that answers each call with the next scripted step. */
    private fun reader(vararg steps: () -> PollVotePageFfi) = ScriptedReader(ArrayDeque(steps.toList()))

    /** Pages follow the previous page's last vote as the cursor and their selections sum to the tally. */
    @Test
    fun multiPageResultsAppendInOrderAndSumToTheTally() =
        runTest {
            val first = listOf(vote(1, "a"), vote(2, "b"))
            val second = listOf(vote(3, "a"), vote(4, "a", "b"))
            val source = reader({ PollVotePageFfi(first, true) }, { PollVotePageFfi(second, false) })
            val pager = PollVotesPager(source, { true }, pageSize = 2u)

            pager.refresh()
            assertEquals(PollVotesPhase.READY, pager.phase)
            assertTrue(pager.hasMore)
            pager.loadMore()

            assertEquals(first + second, pager.votes)
            assertFalse(pager.hasMore)
            assertEquals(Call(null, null, 2u), source.calls[0])
            assertEquals(Call(102uL, first.last().voterAccountIdHex, 2u), source.calls[1])
            val tally =
                pager.votes
                    .flatMap { it.optionIds }
                    .groupingBy { it }
                    .eachCount()
            assertEquals(mapOf("a" to 3, "b" to 2), tally)
        }

    /** A poll nobody answered renders the empty state, not a failure. */
    @Test
    fun emptyFirstPageIsReadyWithNoVotes() =
        runTest {
            val pager = PollVotesPager(reader({ PollVotePageFfi(emptyList(), false) }), { true })

            pager.refresh()

            assertEquals(PollVotesPhase.READY, pager.phase)
            assertTrue(pager.votes.isEmpty())
            assertFalse(pager.hasMore)
        }

    /** A failed first read shows an error that Retry can recover from. */
    @Test
    fun firstPageFailureRetriesFromTheStart() =
        runTest {
            val source = reader({ error("native failure") }, { PollVotePageFfi(listOf(vote(1, "a")), false) })
            val pager = PollVotesPager(source, { true })

            pager.refresh()
            assertEquals(PollVotesPhase.FAILED, pager.phase)
            pager.refresh()

            assertEquals(PollVotesPhase.READY, pager.phase)
            assertEquals(1, pager.votes.size)
            assertEquals(Call(null, null, POLL_VOTES_PAGE_SIZE), source.calls[1])
        }

    /** A failed later page keeps the loaded votes and Retry resumes from the same cursor. */
    @Test
    fun laterPageFailureKeepsLoadedVotesAndRetriesTheCursor() =
        runTest {
            val first = listOf(vote(1, "a"))
            val source =
                reader(
                    { PollVotePageFfi(first, true) },
                    { error("native failure") },
                    { PollVotePageFfi(listOf(vote(2, "b")), false) },
                )
            val pager = PollVotesPager(source, { true })
            pager.refresh()

            pager.loadMore()
            assertEquals(PollVotesPhase.MORE_FAILED, pager.phase)
            assertEquals(first, pager.votes)
            pager.loadMore()

            assertEquals(PollVotesPhase.READY, pager.phase)
            assertEquals(2, pager.votes.size)
            assertEquals(source.calls[1], source.calls[2])
        }

    /** A page that arrives after the account or chat changed is dropped, leaving a retryable failure. */
    @Test
    fun resultAfterOwnerChangeIsRejected() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var current = true
            val source =
                ScriptedReader(
                    ArrayDeque(
                        listOf({ PollVotePageFfi(listOf(vote(1, "a")), false) }),
                    ),
                )
            val pager =
                PollVotesPager(
                    reader = { after, voter, limit ->
                        gate.await()
                        source.read(after, voter, limit)
                    },
                    isCurrent = { current },
                )

            val job = launch { pager.refresh() }
            runCurrent()
            current = false
            gate.complete(Unit)
            job.join()

            assertTrue(pager.votes.isEmpty())
            assertEquals(PollVotesPhase.FAILED, pager.phase)
        }

    /** Reprojection restarts from the first page, and the superseded in-flight read cannot overwrite it. */
    @Test
    fun refreshSupersedesAnInFlightRead() =
        runTest {
            val staleGate = CompletableDeferred<Unit>()
            var calls = 0
            val pager =
                PollVotesPager(
                    reader = { _, _, _ ->
                        if (calls++ == 0) {
                            staleGate.await()
                            PollVotePageFfi(listOf(vote(9, "old")), false)
                        } else {
                            PollVotePageFfi(listOf(vote(1, "new")), false)
                        }
                    },
                    isCurrent = { true },
                )

            val stale = launch { pager.refresh() }
            runCurrent()
            pager.refresh()
            staleGate.complete(Unit)
            stale.join()

            assertEquals(listOf("new"), pager.votes.single().optionIds)
        }

    /** Loading more is a no-op while a read is in flight or after MDK reported the last page. */
    @Test
    fun loadMoreIsIgnoredWithoutAFollowingPage() =
        runTest {
            val source = reader({ PollVotePageFfi(listOf(vote(1, "a")), false) })
            val pager = PollVotesPager(source, { true })
            pager.refresh()

            pager.loadMore()

            assertEquals(1, source.calls.size)
        }

    /** Rows keep blocked voters, mark them, and label choices from the card's own options. */
    @Test
    fun rowsMarkBlockedVotersAndLabelChoices() {
        val options = listOf(PollOptionResultFfi("a", "Soup", 2uL), PollOptionResultFfi("b", "Salad", 1uL))
        val votes = listOf(vote(1, "a"), vote(2, "a", "b"))
        val blocked = votes[1].voterAccountIdHex

        val rows =
            pollVoteRows(votes, options, { "name-$it".take(8) }, { null }, { it == blocked })

        assertEquals(listOf("Soup"), rows[0].choices)
        assertEquals(listOf("Soup", "Salad"), rows[1].choices)
        assertEquals(listOf(false, true), rows.map { it.blocked })
    }
}
