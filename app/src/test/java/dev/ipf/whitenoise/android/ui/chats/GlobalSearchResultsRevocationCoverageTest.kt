package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.NOW_MILLIS
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.StoreReader
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.controller
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.keys
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.record
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

            val removed = controller.removedLoadedKeys(removal)
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
