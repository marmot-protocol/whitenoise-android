package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.chats.ChatListDatasetKey
import dev.ipf.whitenoise.android.ui.chats.ChatListSearchTopResetEffect
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchAccountScope
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchGridResetEffect
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchRecent
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchRecentsOwner
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchSelectedResult
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchSenderFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.chats.viewportFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MainShellGlobalSearchStateRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Broader chat search restores its origin and independent home filters only within the same account. */
    @Test
    fun conversationSearchHandoffPreservesOriginAndIndependentHomeFilters() {
        val account = mutableStateOf("personal")
        var holder: MainShellGlobalSearchStateHolder? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val owner = rememberMainShellGlobalSearchState(account.value, 1)
            SideEffect { holder = owner }
        }
        var home: GlobalSearchState? = null
        composeRule.runOnIdle {
            requireNotNull(holder).update { it.copy(query = "home", folderFilters = setOf("saved")) }
        }
        composeRule.runOnIdle {
            home = requireNotNull(holder).scopedState
            requireNotNull(holder).beginConversationSearch(
                "group",
                requireNotNull(home).copy(isOpen = true, query = "chat", folderFilters = emptySet()),
                "saved",
            )
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertEquals("chat", requireNotNull(holder).scopedState.query)
            assertEquals("group", requireNotNull(holder).conversationReturn?.groupId)
            assertEquals("saved", requireNotNull(holder).finishConversationSearch()?.previousFolderId)
        }
        composeRule.runOnIdle { assertEquals(home, requireNotNull(holder).scopedState) }
        composeRule.runOnIdle {
            requireNotNull(holder).beginConversationSearch("group", requireNotNull(home).copy(isOpen = true), null)
        }
        composeRule.runOnIdle { account.value = "work" }
        composeRule.runOnIdle {
            assertEquals(null, requireNotNull(holder).conversationReturn)
            assertFalse(requireNotNull(holder).beginConversationSearch("old", requireNotNull(home), null))
        }
        composeRule.runOnIdle { assertEquals(null, requireNotNull(holder).conversationReturn) }
    }

    /**
     * Recent search destinations (#4004) live in memory beside the shell search: Back keeps them and the
     * active search, a new account starts empty, an old account's holder cannot add to it, and a restart forgets them.
     */
    @Test
    fun recentDestinationsStayWithTheAccountSessionOnly() {
        val account = mutableStateOf("personal")
        var holder: MainShellGlobalSearchStateHolder? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val owner = rememberMainShellGlobalSearchState(account.value, 1)
            SideEffect { holder = owner }
        }
        val chat = GlobalSearchRecent.Chat("group")
        val profile = GlobalSearchRecent.Profile("npub1alice")
        composeRule.runOnIdle {
            val owner = requireNotNull(holder)
            owner.update { it.copy(isOpen = true, query = "alice") }
            assertTrue(owner.recents.record(owner.scopedState.accountScopeToken, chat))
        }
        composeRule.runOnIdle {
            val owner = requireNotNull(holder)
            assertTrue(owner.beginConversationSearch("group", owner.scopedState.copy(query = "inside"), null))
        }
        composeRule.runOnIdle {
            val owner = requireNotNull(holder)
            assertEquals("alice", owner.finishConversationSearch()?.previousSearch?.query)
        }
        composeRule.runOnIdle {
            val owner = requireNotNull(holder)
            assertEquals("Back restores the active search", "alice", owner.scopedState.query)
            assertEquals(listOf(chat), owner.recents.entries)
        }
        val personal = requireNotNull(holder)
        val personalToken = personal.scopedState.accountScopeToken
        composeRule.runOnIdle { account.value = "work" }
        composeRule.runOnIdle {
            val work = requireNotNull(holder)
            assertTrue("a new account starts empty", work.recents.entries.isEmpty())
            // A profile lookup that finishes for the old account writes only to that account's holder.
            personal.recents.record(personalToken, profile)
            assertFalse(work.recents.record(personalToken, profile))
            assertTrue(work.recents.entries.isEmpty())
            assertTrue(work.recents.record(work.scopedState.accountScopeToken, profile))
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertTrue("process restart forgets the list", requireNotNull(holder).recents.entries.isEmpty())
        }
    }

    /** The shell's retained owner keeps recents across Activity recreation in the same process. */
    @Test
    fun recentDestinationsSurviveActivityRecreationThroughTheRetainedOwner() {
        val owner = GlobalSearchRecentsOwner()
        var holder: MainShellGlobalSearchStateHolder? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val state = rememberMainShellGlobalSearchState("personal", 1, owner)
            SideEffect { holder = state }
        }
        val chat = GlobalSearchRecent.Chat("group")
        composeRule.runOnIdle {
            val state = requireNotNull(holder)
            state.recents.record(state.scopedState.accountScopeToken, chat)
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(listOf(chat), requireNotNull(holder).recents.entries) }
    }

    /** Selection persists only as identifiers; rotation retains it and a new account drops it. */
    @Test
    fun returnedResultSelectionSurvivesRotationButNotAccountReplacement() {
        val account = mutableStateOf("personal")
        var holder: MainShellGlobalSearchStateHolder? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val owner = rememberMainShellGlobalSearchState(account.value, 1)
            SideEffect { holder = owner }
        }
        val selected = GlobalSearchSelectedResult("group", "message", 2)
        composeRule.runOnIdle { requireNotNull(holder).viewport.selection.selected = selected }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(selected, requireNotNull(holder).viewport.selection.selected) }
        composeRule.runOnIdle { account.value = "work" }
        composeRule.runOnIdle {
            assertEquals(GlobalSearchSelectedResult(), requireNotNull(holder).viewport.selection.selected)
        }
    }

    /** Pending body matches must not measure a shorter title-only list or replay a top reset. */
    @Test
    fun conversationBackAndRotationKeepCoordinatesAndAppliedReset() {
        val showSearch = mutableStateOf(true)
        val resultsReady = mutableStateOf(true)
        val titleHits = listOf("Title hit one", "Title hit two")
        var holder: MainShellGlobalSearchStateHolder? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val owner = rememberMainShellGlobalSearchState("personal", 1)
            SideEffect { holder = owner }
            if (showSearch.value) {
                val list = owner.viewport.listState(false)
                ChatListSearchTopResetEffect(list, dataset(owner.scopedState), true, owner.viewport.resetState(false))
                if (resultsReady.value) {
                    LazyColumn(state = list) {
                        items(titleHits.size, key = { "title-$it" }) { Text(titleHits[it], Modifier.height(48.dp)) }
                        items(50, key = { "result-$it" }) { Text("Result $it", Modifier.height(48.dp)) }
                    }
                } else {
                    // These title hits already exist, but measuring only them would clamp index 12.
                    Text("Waiting for message results (${titleHits.size} title hits)")
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(holder).viewport.listState(false).requestScrollToItem(12, 13) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showSearch.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            resultsReady.value = false
            showSearch.value = true
        }
        composeRule.waitForIdle()
        assertListCoordinates({ holder }, 12, 13)
        composeRule.runOnIdle { resultsReady.value = true }
        composeRule.waitForIdle()
        assertListCoordinates({ holder }, 12, 13)
        restoration.emulateSavedInstanceStateRestore()
        assertListCoordinates({ holder }, 12, 13)
    }

    /** A filter edit owns one new top reset, but label hydration and route reentry do not. */
    @Test
    fun filterChangeResetsOnceAndAccountChangeDropsOldCoordinates() {
        val account = mutableStateOf("personal")
        var holder: MainShellGlobalSearchStateHolder? = null
        composeRule.setContent {
            val owner = rememberMainShellGlobalSearchState(account.value, 1)
            SideEffect { holder = owner }
            val list = owner.viewport.listState(false)
            ChatListSearchTopResetEffect(list, dataset(owner.scopedState), true, owner.viewport.resetState(false))
            LazyColumn(state = list) {
                items(50, key = { "result-$it" }) { Text("Result $it", Modifier.height(48.dp)) }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(holder).viewport.listState(false).requestScrollToItem(12, 13) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            requireNotNull(holder).update {
                it.copy(senderFilters = setOf(GlobalSearchSenderFilter("sender", "Alice")))
            }
        }
        composeRule.waitForIdle()
        assertListCoordinates({ holder }, 0, 0)
        composeRule.runOnIdle { requireNotNull(holder).viewport.listState(false).requestScrollToItem(12, 13) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            requireNotNull(holder).update {
                it.copy(senderFilters = setOf(GlobalSearchSenderFilter("sender", "Renamed")))
            }
        }
        composeRule.waitForIdle()
        assertListCoordinates({ holder }, 12, 13)
        composeRule.runOnIdle { account.value = "work" }
        composeRule.waitForIdle()
        assertListCoordinates({ holder }, 0, 0)
    }

    /** Attachment coordinates outlive the disposable results grid; no retained attachment body is required. */
    @Test
    fun attachmentGridRetainsPositionThroughConversationNavigation() {
        val showSearch = mutableStateOf(true)
        var holder: MainShellGlobalSearchStateHolder? = null
        composeRule.setContent {
            val owner = rememberMainShellGlobalSearchState("personal", 1)
            SideEffect { holder = owner }
            if (showSearch.value) {
                GlobalSearchGridResetEffect(owner.viewport, dataset(owner.scopedState), true)
                LazyVerticalGrid(columns = GridCells.Fixed(1), state = owner.viewport.attachmentGrid) {
                    items(50, key = { "attachment-$it" }) { Text("Attachment $it", Modifier.height(48.dp)) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { requireNotNull(holder).viewport.attachmentGrid.requestScrollToItem(12, 13) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showSearch.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showSearch.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(12, requireNotNull(holder).viewport.attachmentGrid.firstVisibleItemIndex)
            assertEquals(13, requireNotNull(holder).viewport.attachmentGrid.firstVisibleItemScrollOffset)
        }
    }

    private fun dataset(state: GlobalSearchState): ChatListDatasetKey =
        ChatListDatasetKey(
            false,
            null,
            "needle",
            searchFilters = state.viewportFilters(),
        )

    private fun assertListCoordinates(
        holder: () -> MainShellGlobalSearchStateHolder?,
        index: Int,
        offset: Int,
    ) {
        composeRule.runOnIdle {
            val list = requireNotNull(holder()).viewport.listState(false)
            assertEquals(index, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun openQueryAndFiltersSurviveSavedStateRecreation() {
        val accountRef = mutableStateOf("personal")
        val runtimeGeneration = mutableIntStateOf(1)
        var holder: MainShellGlobalSearchStateHolder? = null
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            GlobalSearchStateOwnerProbe(
                accountRef = accountRef.value,
                runtimeGeneration = runtimeGeneration.intValue,
                onHolderReady = { holder = it },
            )
        }

        composeRule.runOnIdle {
            requireNotNull(holder).update(::populateSearch)
        }
        assertHolderState({ holder }, expectedSearchState(runtimeGeneration = 1))

        restorationTester.emulateSavedInstanceStateRestore()

        assertHolderState({ holder }, expectedSearchState(runtimeGeneration = 1))
    }

    @Test
    fun restoredStateReconcilesAChangedAccountScope() {
        val accountRef = mutableStateOf("personal")
        val runtimeGeneration = mutableIntStateOf(1)
        var holder: MainShellGlobalSearchStateHolder? = null
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            GlobalSearchStateOwnerProbe(
                accountRef = accountRef.value,
                runtimeGeneration = runtimeGeneration.intValue,
                onHolderReady = { holder = it },
            )
        }

        composeRule.runOnIdle {
            requireNotNull(holder).update(::populateSearch)
        }

        restorationTester.emulateSavedInstanceStateRestore()
        assertHolderState({ holder }, expectedSearchState(runtimeGeneration = 1))

        composeRule.runOnIdle {
            runtimeGeneration.intValue = 2
        }
        composeRule.waitForIdle()

        assertHolderState(
            { holder },
            expectedSearchState(
                runtimeGeneration = 2,
                includeAccountOwnedFilters = false,
            ),
        )
    }

    private fun assertHolderState(
        holder: () -> MainShellGlobalSearchStateHolder?,
        expected: GlobalSearchState,
    ) {
        composeRule.runOnIdle {
            assertEquals(expected, requireNotNull(holder()).scopedState)
        }
    }
}

@Composable
private fun GlobalSearchStateOwnerProbe(
    accountRef: String?,
    runtimeGeneration: Int,
    onHolderReady: (MainShellGlobalSearchStateHolder) -> Unit,
) {
    val holder = rememberMainShellGlobalSearchState(accountRef, runtimeGeneration)
    SideEffect {
        onHolderReady(holder)
    }
}

private fun populateSearch(state: GlobalSearchState): GlobalSearchState =
    state.copy(
        isOpen = true,
        query = "needle",
        chatFilters = setOf(GlobalSearchChatFilter("g1", "Alice")),
        senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Bob")),
        dateFilterSelection = GlobalSearchDateFilterSelection.Today,
        contentFilterSelection =
            GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.TEXT)),
    )

private fun expectedSearchState(
    runtimeGeneration: Int,
    includeAccountOwnedFilters: Boolean = true,
): GlobalSearchState =
    GlobalSearchState(
        isOpen = true,
        query = "needle",
        accountScopeToken = GlobalSearchAccountScope.from("personal", runtimeGeneration).encodeToken(),
        chatFilters =
            if (includeAccountOwnedFilters) {
                setOf(GlobalSearchChatFilter("g1", "Alice"))
            } else {
                emptySet()
            },
        senderFilters =
            if (includeAccountOwnedFilters) {
                setOf(GlobalSearchSenderFilter("npub1", "Bob"))
            } else {
                emptySet()
            },
        dateFilterSelection = GlobalSearchDateFilterSelection.Today,
        contentFilterSelection =
            GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.TEXT)),
    )
