package dev.ipf.whitenoise.android.ui.chats

import android.os.Looper
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.StoreReader
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.keys
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.record
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What survives a return from an opened result: Back, rotation, account switch and process recreation (#1945). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlobalSearchReturnStateTest {
    @get:Rule val composeRule = createComposeRule()

    /** Back reattaches to the held session: loaded pages, cursor and query are there without another read. */
    @Test
    fun backReattachesToTheHeldSessionWithoutRereading() {
        val store = StoreReader((0 until 60).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) })
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val opened = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        opened.search(query)
        idleMain()
        opened.loadMore()
        idleMain()
        val reads = store.queries.size
        val loaded = keys(opened)
        val cursor = opened.state.nextCursor

        // Opening a result disposes the chat list; Back composes it again for the same account and query.
        val returned = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        returned.search(query)
        idleMain()

        assertSame(opened, returned)
        assertEquals(loaded, keys(returned))
        assertEquals(cursor, returned.state.nextCursor)
        assertEquals("no page is read again on return", reads, store.queries.size)
        holder.close()
    }

    /** A row deleted while the reader was in the opened chat never shows on Back: rows wait for the recheck. */
    @Test
    fun deletedRowNeverShowsOnBackBeforeItsRecheck() {
        val store = StoreReader((0 until 3).map { record("g", "m$it", at = 100uL + it.toULong()) })
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        session.search(query)
        idleMain()
        holder.detach()
        store.replace("m1") { it.copy(deleted = true) }

        val returned = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        assertEquals("unverified rows are not presented", null, returned.shownState(query))
        returned.refresh()
        idleMain()

        val shown = requireNotNull(returned.shownState(query)).results.map { it.key }
        assertEquals(listOf("g:m2", "g:m0"), shown)
        holder.close()
    }

    /**
     * A refresh that read the store before the reader left must not clear the stale mark: the reader may delete
     * the opened message meanwhile, and only a recheck launched after the return can confirm the rows.
     */
    @Test
    fun aRefreshInFlightAtDetachCannotConfirmTheRows() {
        val store = StoreReader((0 until 3).map { record("g", "m$it", at = 100uL + it.toULong()) })
        val gate = CompletableDeferred<Unit>()
        var holdNext = false
        val reader =
            GlobalSearchTimelineReader { query ->
                val page = store.page(query)
                if (holdNext) {
                    holdNext = false
                    gate.await()
                }
                page
            }
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(reader, scope) }
        session.search(query)
        idleMain()
        holdNext = true
        session.refresh()
        idleMain()

        holder.detach()
        store.replace("m1") { it.copy(deleted = true) }
        gate.complete(Unit)
        idleMain()

        assertTrue("the earlier refresh did not confirm the rows", session.stale)
        assertEquals(null, session.shownState(query))
        session.refresh()
        idleMain()
        assertEquals(listOf("g:m2", "g:m0"), requireNotNull(session.shownState(query)).results.map { it.key })
        holder.close()
    }

    /**
     * A blocking native read cannot be cancelled. When it returns after the reader came back and a new recheck
     * started, it must neither clear that recheck nor let a rejected recheck confirm the rows.
     */
    @Test
    fun anUncancellableReadReturningLateCannotConfirmOrVoidTheRecheck() {
        val store = StoreReader((0 until 3).map { record("g", "m$it", at = 100uL + it.toULong()) })
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val reader =
            GlobalSearchTimelineReader { query ->
                val page = store.page(query)
                gates.removeFirstOrNull()?.let { gate -> withContext(NonCancellable) { gate.await() } }
                page
            }
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(reader, scope) }
        session.search(query)
        idleMain()
        val oldRead = CompletableDeferred<Unit>()
        val newRead = CompletableDeferred<Unit>()
        gates += oldRead
        session.refresh()
        idleMain()

        holder.detach()
        store.replace("m1") { it.copy(deleted = true) }
        gates += newRead
        session.refresh()
        idleMain()
        oldRead.complete(Unit)
        idleMain()
        assertTrue("the late old read did not confirm the rows", session.stale)
        newRead.complete(Unit)
        idleMain()

        assertEquals(false, session.stale)
        assertEquals(listOf("g:m2", "g:m0"), requireNotNull(session.shownState(query)).results.map { it.key })
        holder.close()
    }

    /**
     * A recheck on Back is capped at a few batches, so in a long scrolled session it cannot reach every loaded row.
     * Rows it did not reread are never shown unconfirmed: they return only as fresh pages, read before the stale
     * mark clears, so a message deleted far down the list stays gone and the rest come back once each.
     */
    @Test
    fun aCappedRecheckNeverShowsUnconfirmedRowsBeyondItsReach() {
        val store = StoreReader((0 until 400).map { record("g", "m%03d".format(it), at = 1_000uL + it.toULong()) })
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        session.search(query)
        idleMain()
        while (!session.state.endReached) {
            session.loadMore()
            idleMain()
        }
        assertEquals(400, session.state.results.size)
        holder.detach()
        // The oldest rows sit far beyond what a capped recheck rereads from the head.
        store.replace("m005") { it.copy(deleted = true) }

        val returned = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        returned.refresh()
        idleMain()

        val shown = requireNotNull(returned.shownState(query)).results.map { it.key }
        assertTrue("g:m005" !in shown)
        assertEquals(399, shown.size)
        assertEquals(shown.size, shown.toSet().size)
        assertEquals(false, returned.stale)
        holder.close()
    }

    /**
     * A held session that never reached MDK's end stays hidden, read by read, until verification reaches its old
     * continuation, and then shows every surviving loaded row once without the row deleted beyond the cap.
     */
    @Test
    fun aCappedRecheckShortOfTheEndStaysHiddenUntilVerificationReachesTheOldContinuation() {
        val store = StoreReader((0 until 600).map { record("g", "m%03d".format(it), at = 1_000uL + it.toULong()) })
        val permits = Channel<Unit>(Channel.UNLIMITED)
        var gated = false
        val reader =
            GlobalSearchTimelineReader { query ->
                if (gated) permits.receive()
                store.page(query)
            }
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(reader, scope) }
        session.search(query)
        idleMain()
        while (session.state.results.size < 400) {
            session.loadMore()
            idleMain()
        }
        val loaded = session.state.results.map { it.key }
        assertTrue(!session.state.endReached && session.state.nextCursor != null)
        holder.detach()
        // The oldest loaded rows sit beyond what a capped recheck rereads from the head.
        store.replace(loaded[390].removePrefix("g:")) { it.copy(deleted = true) }

        gated = true
        session.refresh()
        idleMain()
        var reads = 0
        while (session.stale) {
            assertEquals(null, session.shownState(query))
            permits.trySend(Unit)
            idleMain()
            reads++
            assertTrue("verification never finished", reads < 100)
        }

        // The capped reread alone could not reach the old continuation, so paging had to carry it there.
        assertTrue(reads > GLOBAL_SEARCH_REFRESH_MAX_BATCHES)
        val shown = requireNotNull(session.shownState(query)).results.map { it.key }
        assertTrue(loaded[390] !in shown)
        assertTrue(shown.containsAll(loaded - loaded[390]))
        assertEquals(shown.size, shown.toSet().size)
        assertTrue(!session.state.endReached)
        holder.close()
    }

    /** Retry after a failed verification page resumes where verification stopped instead of rereading the head. */
    @Test
    fun retryAfterAFailedVerificationPageResumesWhereItStopped() {
        val store = StoreReader((0 until 600).map { record("g", "m%03d".format(it), at = 1_000uL + it.toULong()) })
        var failDeep = false
        val reader =
            GlobalSearchTimelineReader { query ->
                // Only verification paging reaches this deep: the capped reread stops well above it.
                val deep = query.before?.let { it <= 1_300uL } == true
                if (failDeep && deep) {
                    failDeep = false
                    error("relay went away")
                }
                store.page(query)
            }
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(reader, scope) }
        session.search(query)
        idleMain()
        while (session.state.results.size < 400) {
            session.loadMore()
            idleMain()
        }
        val loaded = session.state.results.map { it.key }
        holder.detach()

        failDeep = true
        session.refresh()
        idleMain()
        assertTrue(session.recheckFailed)
        // The unverified rows stay hidden behind the Retry boundary.
        val failed = requireNotNull(session.shownState(query))
        assertTrue(failed.failed && failed.results.isEmpty())
        val readsBeforeRetry = store.queries.size
        session.retry()
        idleMain()

        assertTrue(store.queries[readsBeforeRetry].before != null)
        assertEquals(false, session.stale)
        assertEquals(loaded, requireNotNull(session.shownState(query)).results.map { it.key })
        holder.close()
    }

    /** A refresh queued behind a page in flight at detach is dropped with it, so it cannot confirm the rows. */
    @Test
    fun aQueuedRefreshAtDetachIsDropped() {
        val store = StoreReader((0 until 60).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) })
        val gate = CompletableDeferred<Unit>()
        var holdNext = false
        val reader =
            GlobalSearchTimelineReader { query ->
                val page = store.page(query)
                if (holdNext) {
                    holdNext = false
                    gate.await()
                }
                page
            }
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(reader, scope) }
        session.search(query)
        idleMain()
        holdNext = true
        session.loadMore()
        idleMain()
        session.refresh()

        holder.detach()
        gate.complete(Unit)
        idleMain()

        assertTrue(session.stale)
        assertEquals(null, session.shownState(query))
        holder.close()
    }

    /** A failed recheck after Back shows a failure boundary with no rows, and Retry rechecks again. */
    @Test
    fun failedRecheckShowsNoUnverifiedRowsAndRetries() {
        var failing = false
        val store = StoreReader((0 until 2).map { record("g", "m$it", at = 100uL + it.toULong()) })
        val reader =
            GlobalSearchTimelineReader { query ->
                check(!failing) { "local read failed" }
                store.page(query)
            }
        val holder = GlobalSearchResultsHolder()
        val query = GlobalSearchResultsQuery(needle = "hello")
        val session = holder.obtain(ACCOUNT_A) { scope -> controller(reader, scope) }
        session.search(query)
        idleMain()
        holder.detach()
        failing = true

        session.refresh()
        idleMain()
        val failed = requireNotNull(session.shownState(query))
        assertTrue(failed.failed)
        assertTrue("no unverified row is shown", failed.results.isEmpty())

        failing = false
        session.retry()
        idleMain()
        assertEquals(listOf("g:m1", "g:m0"), requireNotNull(session.shownState(query)).results.map { it.key })
        holder.close()
    }

    /** An account or runtime switch replaces the held session, and the old one can no longer publish. */
    @Test
    fun accountSwitchReplacesTheHeldSession() {
        val store = StoreReader(listOf(record("g", "m", at = 1uL)))
        val holder = GlobalSearchResultsHolder()
        val first = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        first.search(GlobalSearchResultsQuery(needle = "hello"))
        idleMain()

        val second = holder.obtain(ACCOUNT_B) { scope -> controller(store, scope) }

        assertNotSame(first, second)
        assertEquals(null, first.query)
        assertTrue(second.state.results.isEmpty())
        holder.close()
    }

    /** Leaving message results closes the held session, so the next search starts fresh. */
    @Test
    fun closingTheHolderEndsTheSession() {
        val store = StoreReader(listOf(record("g", "m", at = 1uL)))
        val holder = GlobalSearchResultsHolder()
        val first = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }
        first.search(GlobalSearchResultsQuery(needle = "hello"))
        idleMain()

        holder.close()
        val next = holder.obtain(ACCOUNT_A) { scope -> controller(store, scope) }

        assertNotSame(first, next)
        assertEquals(null, first.query)
        holder.close()
    }

    /** The query and filters survive process recreation through the saved search state. */
    @Test
    fun queryAndFiltersSurviveProcessRecreation() {
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "gm",
                accountScopeToken = "token",
                senderFilters = setOf(GlobalSearchSenderFilter("aa".repeat(32), "Alice")),
                dateFilterSelection = GlobalSearchDateFilterSelection.Last7Days,
                contentFilterSelection = GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.TEXT)),
            )

        assertEquals(state, decodeGlobalSearchState(encodeGlobalSearchState(state)))
    }

    /** An account switch keeps the query and account-independent filters and drops account-scoped ones. */
    @Test
    fun accountSwitchKeepsQueryAndDropsAccountScopedFilters() {
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "gm",
                accountScopeToken = GlobalSearchAccountScope.from("a", 1).encodeToken(),
                senderFilters = setOf(GlobalSearchSenderFilter("aa".repeat(32), "Alice")),
                dateFilterSelection = GlobalSearchDateFilterSelection.Today,
            )

        val switched = GlobalSearchTransitions.reconcileAccountScope(state, GlobalSearchAccountScope.from("b", 1))

        assertEquals("gm", switched.query)
        assertEquals(GlobalSearchDateFilterSelection.Today, switched.dateFilterSelection)
        assertTrue(switched.senderFilters.isEmpty())
    }

    /** The result list's scroll anchor and the selected result survive saved-state restoration and rotation. */
    @Test
    fun scrollAnchorAndSelectedResultSurviveRestoration() {
        val restoration = StateRestorationTester(composeRule)
        var viewport: GlobalSearchViewport? = null
        restoration.setContent {
            val current = rememberGlobalSearchViewport().also { viewport = it }
            LazyColumn(state = current.listState(showArchived = false), modifier = Modifier.height(200.dp)) {
                items(100, key = { it }) { Text("Row $it", Modifier.height(48.dp)) }
            }
        }
        composeRule.runOnIdle {
            val current = requireNotNull(viewport)
            current.selection.selected = GlobalSearchSelectedResult("group", "message")
            current.listState(showArchived = false).requestScrollToItem(12, 30)
        }
        composeRule.waitForIdle()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            val restored = requireNotNull(viewport)
            assertEquals(GlobalSearchSelectedResult("group", "message"), restored.selection.selected)
            assertEquals(12, restored.listState(showArchived = false).firstVisibleItemIndex)
            assertEquals(30, restored.listState(showArchived = false).firstVisibleItemScrollOffset)
        }
    }

    /** Runs the main looper, where held sessions apply their pages. */
    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A session reading [store] on [scope], with an immediate dispatcher. */
    private fun controller(
        store: GlobalSearchTimelineReader,
        scope: CoroutineScope,
    ) = GlobalSearchResultsController(
        reader = store,
        scope = scope,
        environment = GlobalSearchResultsEnvironment(ioDispatcher = Dispatchers.Unconfined),
    )

    private companion object {
        val ACCOUNT_A = "account-a" to 1
        val ACCOUNT_B = "account-b" to 1
    }
}
