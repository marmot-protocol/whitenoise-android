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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** A session over [store] on [scope], reading on an immediate dispatcher. */
    private fun controller(
        store: StoreReader,
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
