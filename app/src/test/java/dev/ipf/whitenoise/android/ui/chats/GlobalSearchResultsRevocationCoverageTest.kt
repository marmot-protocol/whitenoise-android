package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.NOW_MILLIS
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.StoreReader
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.controller
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.deliver
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.holdingFirstRead
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.keys
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.record
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant gate: once a loaded message result expires, is removed, deleted or locally hidden, it leaves the
 * results and never returns, however far below the bounded refresh it sits. Rechecks stay bounded per pass and
 * chain until every loaded row was covered; valid rows keep their stable keys.
 */
class GlobalSearchResultsRevocationCoverageTest {
    /** The oldest of 300 loaded rows expires: it drops on the next trigger without any reread reaching it. */
    @Test
    fun anExpiredRowBelowTheRefreshCapDropsAtOnceAndStaysGone() =
        runTest {
            var now = NOW_MILLIS
            val store = StoreReader(rows(expiring = "m0000", expiresAt = NOW_MILLIS / 1_000 + 60))
            val controller = controller(store, nowMillis = { now })
            loadAll(controller)
            assertTrue("g:m0000" in keys(controller))

            now += 60_000
            val readsBefore = store.queries.size
            controller.revoke()

            assertFalse("expired below the cap", "g:m0000" in keys(controller))
            assertEquals("no reread was needed", readsBefore, store.queries.size)
            repeat(3) {
                controller.refresh()
                advanceUntilIdle()
            }
            assertFalse("never restored by a later recheck", "g:m0000" in keys(controller))
            assertEquals(LOADED - 1, controller.state.results.size)
        }

    /** A Remove reported for a loaded row withdraws it at once; a reread that still returns it cannot restore it. */
    @Test
    fun aRemovedRowLeavesAtOnceAndARereadCannotRestoreIt() =
        runTest {
            val store = StoreReader(rows())
            val controller = controller(store)
            loadAll(controller)
            val removal =
                update("g", TimelineMessageChangeFfi.Remove("m0000", TimelineRemoveReasonFfi.entries.first()))

            val removed = globalSearchKeyChanges(removal).removed
            assertEquals(setOf("g:m0000"), removed)
            controller.revoke(removed)
            assertFalse("withdrawn before any reread", "g:m0000" in keys(controller))

            // The store has not caught up yet: rereads still return the row.
            repeat(3) {
                controller.refresh()
                advanceUntilIdle()
            }
            assertFalse("a stale reread never restores it", "g:m0000" in keys(controller))
            assertEquals(LOADED - 1, controller.state.results.size)
        }

    /**
     * A Remove that arrives while the first page is still being read withdraws the row that page holds, so it never
     * lists, and rereads of a store that has not caught up cannot restore it. The session's main-thread revoke has not
     * run yet when the page lands, so the read itself must withdraw the reported key before it publishes.
     */
    @Test
    fun aRemovalDuringAReadInFlightKeepsTheHeldRowOut() =
        runTest {
            val store = StoreReader(rows())
            val (reader, gate) = holdingFirstRead(store)
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertTrue("the first page is held", controller.state.inFlight != null)
            val removal =
                update("g", TimelineMessageChangeFfi.Remove("m0299", TimelineRemoveReasonFfi.entries.first()))
            assertFalse("withdrawn directly, with no reread", controller.deliver(removal, drain = false))

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue("the held page landed", controller.state.results.isNotEmpty())
            // The page is applied and withdrawn in one main-thread step, so no state in between is ever published.
            assertFalse("the held row never lists", "g:m0299" in keys(controller))

            repeat(3) {
                controller.refresh()
                advanceUntilIdle()
            }
            assertFalse("a lagging reread never restores it", "g:m0299" in keys(controller))
        }

    /**
     * Only Remove changes and upserts MDK marks deleted are removals, and only live upserts are restorations, each
     * scoped to its group; the last change to a message in one update decides, and an invalidated upsert that is
     * not deleted leaves the key in neither set.
     */
    @Test
    fun keyChangesCoverEveryRemoveDeletionAndLiveUpsertAndNothingElse() {
        val edited = TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED
        val removal =
            update(
                "g",
                TimelineMessageChangeFfi.Remove("a", TimelineRemoveReasonFfi.entries.first()),
                TimelineMessageChangeFfi.Remove("b", TimelineRemoveReasonFfi.entries.last()),
                TimelineMessageChangeFfi.Upsert(
                    TimelineUpdateTriggerFfi.MESSAGE_DELETED,
                    record("g", "c", at = 1uL, deleted = true),
                ),
                TimelineMessageChangeFfi.Upsert(edited, record("g", "d", at = 1uL)),
                TimelineMessageChangeFfi.Upsert(
                    edited,
                    record("g", "e", at = 1uL, invalidation = "local_publish_failed"),
                ),
                TimelineMessageChangeFfi.Remove("f", TimelineRemoveReasonFfi.entries.first()),
                TimelineMessageChangeFfi.Upsert(edited, record("g", "f", at = 1uL)),
                TimelineMessageChangeFfi.Upsert(edited, record("g", "h", at = 1uL)),
                TimelineMessageChangeFfi.Remove("h", TimelineRemoveReasonFfi.entries.first()),
                TimelineMessageChangeFfi.Upsert(edited, record("g", "i", at = 1uL)),
                TimelineMessageChangeFfi.Upsert(
                    edited,
                    record("g", "i", at = 1uL, invalidation = "local_publish_failed"),
                ),
                TimelineMessageChangeFfi.Remove("j", TimelineRemoveReasonFfi.entries.first()),
                TimelineMessageChangeFfi.Upsert(
                    edited,
                    record("g", "j", at = 1uL, invalidation = "local_publish_failed"),
                ),
            )
        val changes = globalSearchKeyChanges(removal)
        assertEquals(setOf("g:a", "g:b", "g:c", "g:h"), changes.removed)
        assertEquals(setOf("g:d", "g:f"), changes.restored)
    }

    /**
     * MDK undoes a delete for everyone it invalidates (a losing branch, or a failed local publish) by sending the
     * message live again. The row that left on the deletion lists again after the recheck that change asks for, even
     * the oldest of a long scrolled list.
     */
    @Test
    fun aDeletionMdkUndoesListsTheRowAgain() =
        runTest {
            val store = StoreReader(rows())
            val controller = controller(store)
            loadAll(controller)
            store.replace("m0000") { it.copy(deleted = true) }
            val deleted = TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.MESSAGE_DELETED, store.row("m0000"))
            controller.deliver(update("g", deleted))
            assertFalse("withdrawn on the deletion", "g:m0000" in keys(controller))

            store.replace("m0000") { it.copy(deleted = false) }
            val live =
                TimelineMessageChangeFfi.Upsert(
                    TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
                    store.row("m0000"),
                )
            assertTrue("the restoration asks for a recheck", controller.deliver(update("g", live)))
            repeat(3) {
                controller.refresh()
                advanceUntilIdle()
            }
            assertTrue("listed again", "g:m0000" in keys(controller))
            assertEquals(LOADED, controller.state.results.size)
        }

    /**
     * A removal still queued when a new query starts carries over to it: the new query's first read, from a store that
     * has not caught up, cannot list the row.
     */
    @Test
    fun aRemovalQueuedBeforeANewQueryStaysOutOfIt() =
        runTest {
            val store = StoreReader(rows())
            val controller = controller(store)
            loadAll(controller)
            val removal =
                update("g", TimelineMessageChangeFfi.Remove("m0299", TimelineRemoveReasonFfi.entries.first()))
            controller.deliver(removal, drain = false)

            controller.search(GlobalSearchResultsQuery(needle = "hell"))
            advanceUntilIdle()
            assertTrue("the new query read its first page", controller.state.results.isNotEmpty())
            assertFalse("the queued removal applies to the new query", "g:m0299" in keys(controller))
            assertTrue("and stays withdrawn", "g:m0299" in controller.state.withdrawn)
        }

    /**
     * A queued removal survives any number of query changes before the store catches up: each new query starts with
     * the key withdrawn, so its first read still cannot list the row.
     */
    @Test
    fun aQueuedRemovalStaysOutAcrossSeveralQueryChanges() =
        runTest {
            val store = StoreReader(rows())
            val controller = controller(store)
            loadAll(controller)
            val removal =
                update("g", TimelineMessageChangeFfi.Remove("m0299", TimelineRemoveReasonFfi.entries.first()))
            controller.deliver(removal, drain = false)

            controller.search(GlobalSearchResultsQuery(needle = "hell"))
            advanceUntilIdle()
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertTrue("the third query read its first page", controller.state.results.isNotEmpty())
            assertFalse("the removal still applies", "g:m0299" in keys(controller))
            assertTrue("and stays withdrawn", "g:m0299" in controller.state.withdrawn)
        }

    /** Changes queued before the main thread drains them apply in arrival order, so the last one decides. */
    @Test
    fun queuedChangesApplyInArrivalOrder() =
        runTest {
            val store = StoreReader(rows())
            val controller = controller(store)
            loadAll(controller)

            fun upsert(
                id: String,
                deleted: Boolean,
            ) = update(
                "g",
                TimelineMessageChangeFfi.Upsert(
                    TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
                    store.row(id).copy(deleted = deleted),
                ),
            )
            // m0299 is restored after its deletion; m0298 is deleted again after its restoration.
            controller.deliver(upsert("m0299", deleted = true), drain = false)
            controller.deliver(upsert("m0298", deleted = false), drain = false)
            controller.deliver(upsert("m0299", deleted = false), drain = false)
            controller.deliver(upsert("m0298", deleted = true), drain = false)
            controller.revoke()

            assertFalse("the last change deleted it", "g:m0298" in controller.state.results.map { it.key })
            assertEquals(setOf("g:m0298"), controller.state.withdrawn)
        }

    /**
     * A delete for everyone arrives as an upsert MDK marks deleted. Through the session's hand-off it withdraws a
     * loaded row far below the refresh cap at once, with no reread, and a store that has not caught up cannot
     * restore it.
     */
    @Test
    fun aDeletedUpsertThroughTheSessionHandOffLeavesAtOnce() =
        runTest {
            val store = StoreReader(rows())
            val controller = controller(store)
            loadAll(controller)
            val readsBefore = store.queries.size
            val deleted = record("g", "m0000", at = 1uL, deleted = true)
            val change = TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.MESSAGE_DELETED, deleted)

            assertFalse("no reread is asked for", controller.deliver(update("g", change)))
            assertFalse("withdrawn before any reread", "g:m0000" in keys(controller))
            assertEquals(readsBefore, store.queries.size)
            repeat(3) {
                controller.refresh()
                advanceUntilIdle()
            }
            assertFalse("a stale reread never restores it", "g:m0000" in keys(controller))
        }

    /** The session's deadline wait drops the expired row the moment it expires, without starting a reread. */
    @Test
    fun theDeadlineWaitDropsTheExpiredRowWithoutAReread() =
        runTest {
            var start: Long? = null
            val store = StoreReader(rows(expiring = "m0000", expiresAt = NOW_MILLIS / 1_000 + 60))
            val controller =
                controller(store, nowMillis = { NOW_MILLIS + (start?.let { testScheduler.currentTime - it } ?: 0L) })
            loadAll(controller)
            start = testScheduler.currentTime
            val readsBefore = store.queries.size
            val launchesBefore = controller.state.launches

            val waiting = launch { controller.revokeAtDeadline(controller.state.nextExpiry) }
            advanceTimeBy(59_999)
            runCurrent()
            assertTrue("still listed before its deadline", "g:m0000" in keys(controller))
            advanceTimeBy(2)
            runCurrent()

            assertFalse("dropped at its deadline", "g:m0000" in keys(controller))
            assertTrue(waiting.isCompleted)
            assertEquals("no reread", readsBefore, store.queries.size)
            assertEquals("no recheck pass", launchesBefore, controller.state.launches)
        }

    /** While a failed page shows, a change still rereads the newest rows instead of waiting behind the chain. */
    @Test
    fun aChangeWhileAPageFailureShowsRereadsTheHead() =
        runTest {
            var failNext = false
            val store =
                StoreReader((0 until LOADED * 2).map { record("g", "m%04d".format(it), at = (it + 1).toULong()) })
            val reader =
                GlobalSearchTimelineReader { query ->
                    if (failNext) {
                        failNext = false
                        error("read failed")
                    }
                    store.page(query)
                }
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            while (controller.state.results.size < LOADED) {
                controller.loadMore()
                advanceUntilIdle()
            }
            failNext = true
            controller.loadMore()
            advanceUntilIdle()
            assertTrue("the page failure shows", controller.state.failed)
            controller.refresh()
            advanceUntilIdle()
            assertTrue("the capped chain waits for Retry", controller.state.validateFrom != null)

            store.add(record("g", "newest", at = 10_000uL))
            controller.refresh()
            advanceUntilIdle()
            assertEquals("g:newest", keys(controller).first())
            assertTrue("the failure still shows", controller.state.failed)
        }

    /** A deletion and a local hide far below the cap are reached by chained bounded passes and dropped. */
    @Test
    fun deletedAndHiddenRowsBelowTheCapAreReachedByChainedBoundedPasses() =
        runTest {
            val hidden = mutableSetOf<String>()
            val store = StoreReader(rows())
            val controller = controller(store, hidden = { hidden.toSet() })
            loadAll(controller)
            val stableKeys = keys(controller).filterNot { it == "g:m0000" || it == "g:m0001" }
            store.replace("m0000") { it.copy(deleted = true) }
            hidden += "m0001"
            val readsBefore = store.queries.size
            val launchesBefore = controller.state.launches

            controller.refresh()
            advanceUntilIdle()

            val passes = (controller.state.launches - launchesBefore).toInt()
            val reads = store.queries.size - readsBefore
            assertTrue("$passes passes chain", passes > 1)
            assertTrue("$reads reads in $passes passes", reads <= passes * PASS_PAGES)
            assertEquals("the chain finished", null, controller.state.validateFrom)
            assertEquals(stableKeys, keys(controller))
        }

    /** A failed pass stops the chain without looping; the next trigger finishes it and the row stays gone. */
    @Test
    fun aFailedPassResumesOnTheNextTriggerAndNeverRestoresRows() =
        runTest {
            val store = StoreReader(rows())
            val failing = FailingReader(store)
            val controller = controller(failing)
            loadAll(controller)
            store.replace("m0000") { it.copy(deleted = true) }
            // The first pass covers the newest rows; a later pass reading the oldest ones fails.
            failing.failBelow = 100uL

            controller.refresh()
            advanceUntilIdle()
            assertTrue("the failure stops the chain", controller.state.validateFrom != null)
            assertTrue("g:m0000" in keys(controller))
            assertEquals("one failed read, no retry loop", 1, failing.failures)

            failing.failBelow = null
            controller.refresh()
            advanceUntilIdle()
            assertFalse("g:m0000" in keys(controller))
            assertEquals(null, controller.state.validateFrom)
            assertEquals(LOADED - 1, controller.state.results.size)
        }

    /** A capped pass that keeps failing is tried once per trigger: the owed head reread does not retry it at once. */
    @Test
    fun aPersistentlyFailingPassIsTriedOncePerTrigger() =
        runTest {
            val store = StoreReader(rows())
            val failing = FailingReader(store)
            val controller = controller(failing)
            loadAll(controller)
            failing.failBelow = 100uL

            controller.refresh()
            advanceUntilIdle()
            assertEquals(1, failing.failures)
            controller.refresh()
            advanceUntilIdle()
            assertEquals("the second trigger tries the continuation once", 2, failing.failures)
            assertTrue("the chain still waits", controller.state.validateFrom != null)

            failing.failBelow = null
            controller.refresh()
            advanceUntilIdle()
            assertEquals(null, controller.state.validateFrom)
            assertEquals(LOADED, controller.state.results.size)
        }

    /** A removal outside the query's scope is still withdrawn, so a later query covering it never lists it. */
    @Test
    fun aRemovalOutsideTheScopeIsRetainedForLaterQueries() =
        runTest {
            val controller = controller(StoreReader(rows()))
            controller.search(GlobalSearchResultsQuery(needle = "hello", groups = setOf("g")))
            advanceUntilIdle()
            val elsewhere =
                update("other", TimelineMessageChangeFfi.Remove("x", TimelineRemoveReasonFfi.entries.first()))
            val inside = update("g", TimelineMessageChangeFfi.Remove("m0299", TimelineRemoveReasonFfi.entries.first()))

            controller.deliver(elsewhere)
            controller.deliver(inside)

            assertEquals(setOf("other:x", "g:m0299"), controller.state.withdrawn)
            assertFalse("g:m0299" in keys(controller))

            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertEquals(setOf("other:x", "g:m0299"), controller.state.withdrawn)
        }

    /** Fails every read that continues below [failBelow]. */
    private class FailingReader(
        private val store: StoreReader,
    ) : GlobalSearchTimelineReader {
        var failBelow: ULong? = null
        var failures = 0

        override suspend fun page(query: TimelineMessageQueryFfi): TimelinePageFfi {
            val bound = failBelow
            val before = query.before
            if (bound != null && before != null && before < bound) {
                failures++
                error("read failed")
            }
            return store.page(query)
        }
    }

    private suspend fun TestScope.loadAll(controller: GlobalSearchResultsController) {
        controller.search(GlobalSearchResultsQuery(needle = "hello"))
        advanceUntilIdle()
        while (!controller.state.endReached) {
            controller.loadMore()
            advanceUntilIdle()
        }
        assertEquals(LOADED, controller.state.results.size)
    }

    private fun rows(
        expiring: String? = null,
        expiresAt: Long = 0,
    ) = (0 until LOADED).map {
        val id = "m%04d".format(it)
        record("g", id, at = it.toULong() + 1uL, expiresAt = expiresAt.toULong().takeIf { id == expiring })
    }

    private companion object {
        const val LOADED = 300
        const val PASS_PAGES = GLOBAL_SEARCH_REFRESH_MAX_BATCHES * GLOBAL_SEARCH_BATCH_PAGES
    }
}
