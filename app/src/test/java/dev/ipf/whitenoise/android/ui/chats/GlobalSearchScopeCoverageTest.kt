package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Invariant gate for #3720: global search covers every chat of the account, not the bounded Home window. Chat-name
 * matches, and the chat scope of a filtered message search, come from complete membership: an off-window title
 * match shows, Groups, Direct and folder filters include off-window chats, an explicitly chosen chat is never
 * dropped, and a scope that is not ready never runs a window-only search or reports an empty result. Only the
 * window chat is retained; every other chat enters solely through the account-wide snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchScopeCoverageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private val mounted = mutableStateOf(true)
    private val search = mutableStateOf(GlobalSearchState())
    private var fixture: OffWindowActionFixture? = null

    @After fun close() {
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        rule.runOnIdle { fixture?.controller?.onCleared() }
        scope.cancel()
    }

    /** A chat beyond the retained window is found by its title with no filter. */
    @Test fun offWindowTitleMatchShowsAsAChatRow() {
        prepared()
        openSearch(GlobalSearchState(isOpen = true, query = "b7b7"))
        awaitReads { rule.onAllNodesWithTag("chat.row.$ACTION_TARGET").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertDoesNotExist()
    }

    /** Groups scopes the message search to every group, including off-window ones, and never a direct chat. */
    @Test fun groupsFilterScopesMessageSearchToCompleteGroups() {
        val model = prepared()
        openSearch(
            GlobalSearchState(isOpen = true, query = "hello", chatTypeFilters = setOf(GlobalSearchChatType.GROUPS)),
        )
        awaitReads { scopedGroups(model).size >= 3 }
        assertEquals(setOf(ACTION_TARGET, ACTION_SURVIVOR, GROUP_EXTRA), scopedGroups(model))
    }

    /** Direct scopes the message search to the off-window direct chat. */
    @Test fun directFilterScopesMessageSearchToCompleteDirectChats() {
        val model = prepared()
        openSearch(
            GlobalSearchState(isOpen = true, query = "hello", chatTypeFilters = setOf(GlobalSearchChatType.DIRECT)),
        )
        awaitReads { scopedGroups(model).isNotEmpty() }
        assertEquals(setOf(DIRECT_EXTRA), scopedGroups(model))
    }

    /** A folder whose native query names an off-window member scopes the message search to it. */
    @Test fun folderFilterIncludesOffWindowMember() {
        val model = prepared()
        model.chatListQueryIds = { _, _ -> listOf(ACTION_TARGET) }
        val folder = createFolder(model, ChatFolderRule(includeMuted = true))
        openSearch(GlobalSearchState(isOpen = true, query = "hello", folderFilters = setOf(folder)))
        awaitReads { scopedGroups(model).isNotEmpty() }
        assertEquals(setOf(ACTION_TARGET), scopedGroups(model))
    }

    /** Explicitly chosen chats stay in scope, whether in the snapshot or in no row at all. */
    @Test fun explicitlySelectedChatsAreNeverDropped() {
        val model = prepared()
        val chosen =
            setOf(GlobalSearchChatFilter(ACTION_TARGET, "Target"), GlobalSearchChatFilter(EVICTED_CHAT, "Evicted"))
        openSearch(GlobalSearchState(isOpen = true, query = "hello", chatFilters = chosen))
        awaitReads { scopedGroups(model).size >= 2 }
        assertEquals(setOf(ACTION_TARGET, EVICTED_CHAT), scopedGroups(model))
    }

    /** An unready folder scope shows the results boundary progress, never No Results or a window-only search. */
    @Test fun notReadyScopeShowsLoadingThenResolvesOnTheNextRevision() {
        val model = prepared()
        var ready = false
        model.chatListQueryIds = { _, _ ->
            if (!ready) throw MarmotKitException.ChatPresentationNotReady()
            listOf(ACTION_TARGET)
        }
        val folder = createFolder(model, ChatFolderRule(includeMuted = true))
        openSearch(GlobalSearchState(isOpen = true, query = "hello", folderFilters = setOf(folder)))
        repeat(40) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
        }
        assertTrue("no query may run on an unproven scope", model.timelineQueries.isEmpty())
        rule.onNodeWithTag(GLOBAL_SEARCH_RESULTS_LOADING_TAG).assertExists()
        assertTrue(
            rule.onAllNodesWithText(rule.activity.getString(R.string.no_results)).fetchSemanticsNodes().isEmpty(),
        )
        ready = true
        rule.runOnIdle { model.controller.applyChatListRow(chatRow(ARRIVED_CHAT)) }
        awaitReads { scopedGroups(model).isNotEmpty() }
        assertEquals(setOf(ACTION_TARGET), scopedGroups(model))
    }

    /** A refresh that cannot prove the folder again withdraws the published scope instead of searching with it. */
    @Test fun failedRefreshWithdrawsThePublishedScope() {
        val model = prepared()
        var ready = true
        model.chatListQueryIds = { _, _ ->
            if (!ready) throw MarmotKitException.ChatPresentationNotReady()
            listOf(ACTION_TARGET)
        }
        val folder = createFolder(model, ChatFolderRule(includeMuted = true))
        openSearch(GlobalSearchState(isOpen = true, query = "hello", folderFilters = setOf(folder)))
        awaitReads { scopedGroups(model).isNotEmpty() }
        ready = false
        rule.runOnIdle { model.controller.applyChatListRow(chatRow(ARRIVED_CHAT)) }
        settle()
        model.timelineQueries.clear()
        openSearch(GlobalSearchState(isOpen = true, query = "hello again", folderFilters = setOf(folder)))
        settle()
        assertTrue("a withdrawn scope must not search: ${model.timelineQueries}", model.timelineQueries.isEmpty())
        rule.onNodeWithTag(GLOBAL_SEARCH_RESULTS_LOADING_TAG).assertExists()
    }

    /** A scope answered after the account changed is dropped, so no query runs for the old account's chats. */
    @Test fun lateScopeAfterAccountSwitchIsDropped() {
        val model = prepared()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        model.chatListQueryIds = { _, _ ->
            entered.complete(Unit)
            release.await()
            listOf(ACTION_TARGET)
        }
        val folder = createFolder(model, ChatFolderRule(includeMuted = true))
        openSearch(GlobalSearchState(isOpen = true, query = "hello", folderFilters = setOf(folder)))
        awaitReads { entered.isCompleted }
        rule.runOnIdle { model.replaceAccount() }
        rule.waitForIdle()
        release.complete(Unit)
        repeat(40) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
        }
        assertTrue("a late scope must not search: ${model.timelineQueries}", model.timelineQueries.isEmpty())
    }

    /** An unfiltered search stays account-wide: a null scope, and it never waits for the complete snapshot. */
    @Test fun unfilteredSearchPassesANullScope() {
        val model = prepared()
        openSearch(GlobalSearchState(isOpen = true, query = "hello"))
        awaitReads { model.timelineQueries.isNotEmpty() }
        assertTrue(model.timelineQueries.all { it.second.groupIdHex == null })
    }

    private fun scopedGroups(model: OffWindowActionFixture): Set<String> = model.timelineQueries
        .mapNotNull { it.second.groupIdHex }
        .toSet()

    private fun createFolder(
        model: OffWindowActionFixture,
        folderRule: ChatFolderRule,
    ): String {
        val preferences = model.state.chatFolderPreferences
        return requireNotNull(preferences.createFolder(ACTION_ACCOUNT, "Scoped")).id.also {
            preferences.setFolderRule(ACTION_ACCOUNT, it, folderRule)
        }
    }

    private fun openSearch(state: GlobalSearchState) {
        rule.runOnIdle { search.value = state }
        rule.waitForIdle()
    }

    private fun settle() {
        repeat(40) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
        }
    }

    private fun awaitReads(ready: () -> Boolean) {
        rule.waitUntil(timeoutMillis = 10_000) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            ready()
        }
        scope.testScheduler.runCurrent()
        rule.waitForIdle()
    }

    /** Mounts production ChatsScreen with one window chat and off-window group, direct and unknown chats. */
    private fun prepared(): OffWindowActionFixture {
        val model = OffWindowActionFixture(scope, nativeFolderReads = true).also { fixture = it }
        model.snapshotExtras =
            listOf(
                chatRow(GROUP_EXTRA),
                chatRow(DIRECT_EXTRA).copy(conversationKind = ChatConversationKindFfi.DIRECT),
            )
        rule.setContent {
            if (mounted.value) {
                WhiteNoiseTheme {
                    ChatsScreen(
                        model.state,
                        model.controller,
                        {},
                        { _, _, _, _ -> },
                        selectedFolderId = null,
                        globalSearchState = search.value,
                        onGlobalSearchStateChange = { update -> search.value = update(search.value) },
                    )
                }
            }
        }
        awaitReads { model.snapshotReads >= 1 }
        return model
    }
}

private val GROUP_EXTRA = "d7".repeat(16)
private val DIRECT_EXTRA = "e7".repeat(16)
private val ARRIVED_CHAT = "f7".repeat(16)
private val EVICTED_CHAT = "9a".repeat(16)
