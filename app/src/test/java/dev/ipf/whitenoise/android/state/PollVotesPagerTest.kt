package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollVoteFfi
import dev.ipf.marmotkit.PollVotePageFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native per-voter paging: cursors, totals, empty and error states, and stale-result rejection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

    /** Pages follow the previous page's last vote as the cursor and append in MDK's order. */
    @Test
    fun multiPageResultsAppendInOrderUsingTheNativeCursor() =
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

    /** Rows label choices from the card's own options and ignore ids the card does not know. */
    @Test
    fun rowsLabelChoicesAndIgnoreUnknownIds() {
        val options = listOf(PollOptionResultFfi("a", "Soup", 2uL), PollOptionResultFfi("b", "Salad", 1uL))
        val votes = listOf(vote(1, "a"), vote(2, "a", "b", "zz"))

        val rows = pollVoteRows(votes, options)

        assertEquals(listOf("Soup"), rows[0].choices)
        assertEquals(listOf("Soup", "Salad"), rows[1].choices)
        assertEquals(votes.map { it.voterAccountIdHex }, rows.map { it.voterAccountIdHex })
    }

    /** A voter who re-voted between pages reappears later, and only the newest entry is kept. */
    @Test
    fun repeatedVoterOnALaterPageKeepsOnlyTheNewestEntry() =
        runTest {
            val first = listOf(vote(1, "a"), vote(2, "b"))
            val moved = PollVoteFfi(first[0].voterAccountIdHex, listOf("b"), 500uL)
            val secondPage = PollVotePageFfi(listOf(vote(3, "a"), moved), false)
            val source = reader({ PollVotePageFfi(first, true) }, { secondPage })
            val pager = PollVotesPager(source, { true }, pageSize = 2u)
            pager.refresh()

            pager.loadMore()

            val voters = pager.votes.map { it.voterAccountIdHex }
            assertEquals(voters.distinct(), voters)
            val expected = listOf(first[1], vote(3, "a"), moved).map { it.voterAccountIdHex }
            assertEquals(expected, voters)
            assertEquals(moved, pager.votes.last())
        }

    /** A second load-more while the first is in flight is ignored. */
    @Test
    fun loadMoreWhileInFlightIsIgnored() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val pager =
                PollVotesPager(
                    reader = { _, _, _ ->
                        if (calls++ == 0) {
                            PollVotePageFfi(listOf(vote(1, "a")), true)
                        } else {
                            gate.await()
                            PollVotePageFfi(listOf(vote(2, "a")), false)
                        }
                    },
                    isCurrent = { true },
                )
            pager.refresh()

            val first = launch { pager.loadMore() }
            runCurrent()
            pager.loadMore()
            gate.complete(Unit)
            first.join()

            assertEquals(2, calls)
            assertEquals(2, pager.votes.size)
        }

    /** A refresh supersedes an in-flight load-more, whose late page must not be appended. */
    @Test
    fun refreshSupersedesAnInFlightLoadMore() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val pager =
                PollVotesPager(
                    reader = { _, _, _ ->
                        when (calls++) {
                            0 -> PollVotePageFfi(listOf(vote(1, "a")), true)
                            1 -> {
                                gate.await()
                                PollVotePageFfi(listOf(vote(9, "stale")), false)
                            }
                            else -> PollVotePageFfi(listOf(vote(1, "fresh")), false)
                        }
                    },
                    isCurrent = { true },
                )
            pager.refresh()
            val more = launch { pager.loadMore() }
            runCurrent()

            pager.refresh()
            gate.complete(Unit)
            more.join()

            assertEquals(listOf(listOf("fresh")), pager.votes.map { it.optionIds })
            assertEquals(PollVotesPhase.READY, pager.phase)
        }

    /** A refresh keeps the loaded votes visible while it reads, and a failed refresh keeps them too. */
    @Test
    fun refreshKeepsTheListVisibleWhileReloadingAndWhenItFails() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val pager =
                PollVotesPager(
                    reader = { _, _, _ ->
                        when (calls++) {
                            0 -> PollVotePageFfi(listOf(vote(1, "a")), false)
                            1 -> {
                                gate.await()
                                PollVotePageFfi(listOf(vote(1, "b")), false)
                            }
                            else -> error("native failure")
                        }
                    },
                    isCurrent = { true },
                )
            pager.refresh()

            val reload = launch { pager.refresh() }
            runCurrent()
            assertEquals(listOf("a"), pager.votes.single().optionIds)
            assertEquals(PollVotesPhase.READY, pager.phase)
            gate.complete(Unit)
            reload.join()
            assertEquals(listOf("b"), pager.votes.single().optionIds)
            pager.refresh()

            assertEquals(listOf("b"), pager.votes.single().optionIds)
            assertEquals(PollVotesPhase.READY, pager.phase)
        }

    /** Only a projection update for this account, group and poll counts as touching it. */
    @Test
    fun onlyMatchingProjectionUpdatesTouchThePoll() {
        fun update(
            account: String = "acct",
            group: String = "grp",
            changes: List<TimelineMessageChangeFfi>,
        ) = MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                account,
                "label",
                TimelineProjectionUpdateFfi(group, emptyList(), changes, null, ChatListUpdateTriggerFfi.NEW_GROUP),
            ),
        )
        val remove = listOf(TimelineMessageChangeFfi.Remove("poll", TimelineRemoveReasonFfi.INVALIDATED))
        val other = listOf(TimelineMessageChangeFfi.Remove("other", TimelineRemoveReasonFfi.INVALIDATED))

        assertTrue(pollProjectionTouched(update(changes = remove), "ACCT", "grp", "poll"))
        assertFalse(pollProjectionTouched(update(changes = other), "acct", "grp", "poll"))
        assertFalse(pollProjectionTouched(update(group = "x", changes = remove), "acct", "grp", "poll"))
        assertFalse(pollProjectionTouched(update(account = "x", changes = remove), "acct", "grp", "poll"))
        assertFalse(pollProjectionTouched(MarmotEventFfi.GroupJoined("a", "b", "c"), "acct", "grp", "poll"))
    }

    /** The watcher reports only touching events and stops when the stream ends. */
    @Test
    fun observerReportsTouchingEventsUntilTheStreamEnds() =
        runTest {
            val events =
                ArrayDeque<MarmotEventFfi?>(
                    listOf(MarmotEventFfi.GroupJoined("a", "b", "c"), MarmotEventFfi.GroupJoined("x", "y", "z"), null),
                )
            var touchedCount = 0

            observePollProjection(
                nextEvent = { events.removeFirst() },
                touched = { (it as MarmotEventFfi.GroupJoined).accountIdHex == "x" },
                onTouched = { touchedCount++ },
            )

            assertEquals(1, touchedCount)
        }
}
