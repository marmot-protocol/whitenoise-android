package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsScreenUnreadReadLifetimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private val mounted = mutableStateOf(true)
    private var fixture: OffWindowActionFixture? = null
    private var releaseRead: CompletableDeferred<Unit>? = null

    @After fun close() {
        releaseRead?.complete(Unit)
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        val model = fixture
        rule.runOnIdle { model?.state?.mutationsScope?.cancel() }
        try {
            awaitProgress {
                model
                    ?.state
                    ?.mutationsScope
                    ?.coroutineContext
                    ?.get(Job)
                    ?.isCompleted != false
            }
        } finally {
            rule.runOnIdle { model?.controller?.onCleared() }
            scope.cancel()
        }
    }

    /** Incoming projection bursts cannot cancel the first read or enqueue more than one latest follow-up. */
    @Test fun mountedUnreadReadFinishesDuringProjectionBurstAndCoalescesLatestRefresh() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        val release = CompletableDeferred<Unit>().also { releaseRead = it }
        var cancelled = 0
        model.beforeSnapshotRead = {
            if (model.snapshotReads == 1) {
                try {
                    release.await()
                } catch (failure: CancellationException) {
                    cancelled++
                    throw failure
                }
            }
        }
        mount(model)
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads == 1 }
        repeat(4) { index ->
            rule.runOnIdle {
                model.controller.applyChatListRow(model.retainedRow.get().copy(activitySortAt = (index + 1).toULong()))
            }
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
        }
        assertEquals(0, cancelled)
        assertEquals(1, model.snapshotReads)
        release.complete(Unit)
        awaitProgress { model.snapshotReads == 2 }
        rule.waitForIdle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        rule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        assertEquals(2, model.snapshotReads)
    }

    /** The production list activates its temporary filter only after a deliberate top pull is released. */
    @Test fun mountedChatsTopPullActivatesOnReleaseAndIncludesOffWindowUnread() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        model.state.swipePreferences.setPullUnread(true)
        mount(model)
        val row = rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR")
        awaitProgress { row.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick) }
        val distance = with(rule.density) { 100.dp.toPx() }
        row.performTouchInput {
            down(Offset(width * 0.6f, 10f))
            moveTo(Offset(width * 0.6f, 10f + distance), 120)
        }
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        assertEquals(0, model.snapshotReads)
        row.performTouchInput { up() }
        awaitProgress { model.snapshotReads == 1 }
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_filtered)).assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        assertTrue("pulling must not issue a read, pin, archive or mute command", model.calls.isEmpty())
        rule.onNodeWithTag("chats.unread_surface").performTouchInput {
            down(Offset(width * 0.6f, height * 0.3f))
            moveBy(Offset(0f, distance), 120)
            up()
        }
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
    }

    /** The persisted preference gates fresh pulls and cancels an armed pull before a later re-enabled gesture. */
    @Test fun mountedPullUnreadPreferenceCancelsHeldGestureAndRecovers() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        model.state.swipePreferences.setPullUnread(false)
        mount(model)
        val surface = rule.onNodeWithTag("chats.unread_surface")
        val row = rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR")
        val distance = with(rule.density) { 100.dp.toPx() }

        fun pull(release: Boolean = true) {
            awaitProgress { row.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick) }
            row.performTouchInput {
                down(Offset(width * 0.6f, 10f))
                moveTo(Offset(width * 0.6f, 10f + distance), 120)
                if (release) up()
            }
        }
        pull()
        settle()
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        assertEquals(0, model.snapshotReads)
        rule.runOnIdle { model.state.swipePreferences.setPullUnread(true) }
        rule.waitForIdle()
        pull()
        awaitProgress { model.snapshotReads == 1 }
        rule.onNodeWithTag("chats.unread_filter").assertExists()
        surface.performUnreadFilterAction(rule)
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        val readsBeforeCancelledPull = model.snapshotReads
        val restingTop = row.fetchSemanticsNode().boundsInRoot.top
        pull(release = false)
        assertTrue(row.fetchSemanticsNode().boundsInRoot.top > restingTop)
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        rule.runOnIdle { model.state.swipePreferences.setPullUnread(false) }
        rule.waitForIdle()
        row.performTouchInput { up() }
        settle()
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        assertEquals(readsBeforeCancelledPull, model.snapshotReads)
        rule.runOnIdle { model.state.swipePreferences.setPullUnread(true) }
        rule.waitForIdle()
        pull()
        awaitProgress { model.snapshotReads > readsBeforeCancelledPull }
        rule.onNodeWithTag("chats.unread_filter").assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        assertTrue(model.calls.isEmpty())
    }

    /** A successful zero-match read uses the caught-up state while keeping Show all available. */
    @Test fun mountedPullFilterWithOnlyReadChatsShowsUnreadEmptyState() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        model.state.swipePreferences.setPullUnread(true)
        model.row.updateAndGet { it.copy(manuallyMarkedUnread = false) }
        model.retainedRow.updateAndGet { it.copy(manuallyMarkedUnread = false) }
        model.controller.applyChatListRow(model.retainedRow.get())
        settle()
        mount(model)
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads >= 1 }
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_unread_title)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_unread_detail)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_filtered)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_chats_title)).assertDoesNotExist()
        val distance = with(rule.density) { 100.dp.toPx() }
        rule.onNodeWithTag("chats.unread_surface").performTouchInput {
            down(Offset(width * 0.6f, height * 0.3f))
            moveBy(Offset(0f, distance), 120)
            up()
        }
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
    }

    /** An unrelated read chat with no roster cannot fail an unread participant-folder request. */
    @Test fun mountedParticipantFolderDoesNotResolveUnrelatedReadRoster() {
        val member = "d8".repeat(32)
        val unrelated = "e8".repeat(16)
        val requested = mutableListOf<String>()
        val model =
            OffWindowActionFixture(scope, memberLoader = { _, id ->
                requested += id
                if (id == ACTION_TARGET) {
                    listOf(
                        AppGroupMemberRecordFfi(ACTION_ACCOUNT_ID, null, true),
                        AppGroupMemberRecordFfi(member, null, false),
                    )
                } else {
                    emptyList()
                }
            }).also { fixture = it }
        model.snapshotExtras = listOf(chatRow(unrelated))
        model.retainedRow.updateAndGet { it.copy(manuallyMarkedUnread = false) }
        model.controller.applyChatListRow(model.retainedRow.get())
        settle()
        val folder = model.state.chatFolderPreferences.createFolder(ACTION_ACCOUNT, "Selected participant")!!
        model.state.chatFolderPreferences.setFolderRule(
            ACTION_ACCOUNT,
            folder.id,
            ChatFolderRule(includeMemberPubkeys = setOf(member), includeMuted = true),
        )
        mount(model, folder.id)
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads >= 1 && model.controller.hasMemberSnapshot(ACTION_TARGET) }
        settle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_load_failed)).assertDoesNotExist()
        assertTrue(ACTION_TARGET in requested)
        assertTrue("the unrelated off-window read chat must never require a roster", unrelated !in requested)
    }

    /** A genuinely unknown possible match retains Retry without withholding other established matches. */
    @Test fun mountedParticipantFolderKeepsMatchingRowsWhenAnotherCandidateRosterFails() {
        val member = "d9".repeat(32)
        val unknown = "e9".repeat(16)
        val model =
            OffWindowActionFixture(scope, memberLoader = { _, id ->
                if (id == ACTION_TARGET) {
                    listOf(
                        AppGroupMemberRecordFfi(ACTION_ACCOUNT_ID, null, true),
                        AppGroupMemberRecordFfi(member, null, false),
                    )
                } else {
                    emptyList()
                }
            }).also { fixture = it }
        model.snapshotExtras = listOf(chatRow(unknown).copy(manuallyMarkedUnread = true))
        model.retainedRow.updateAndGet { it.copy(manuallyMarkedUnread = false) }
        model.controller.applyChatListRow(model.retainedRow.get())
        settle()
        val folder = model.state.chatFolderPreferences.createFolder(ACTION_ACCOUNT, "Selected participant")!!
        model.state.chatFolderPreferences.setFolderRule(
            ACTION_ACCOUNT,
            folder.id,
            ChatFolderRule(includeMemberPubkeys = setOf(member), includeMuted = true),
        )
        mount(model, folder.id)
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.controller.hasMemberSnapshot(ACTION_TARGET) }
        settle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_retry)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_unread_title)).assertDoesNotExist()
        rule.onNodeWithTag(dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
    }

    /** Binding retirement is unavailability, not a failed read that can be retried under another binding. */
    @Test fun mountedUnreadBindingChangeDoesNotOfferFailureRetry() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        val release = CompletableDeferred<Unit>().also { releaseRead = it }
        model.beforeSnapshotRead = { if (model.snapshotReads == 1) release.await() }
        mount(model)
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads == 1 }
        val rebound = scope.launch { model.controller.bind(null) }
        awaitProgress { rebound.isCompleted }
        release.complete(Unit)
        settle()
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_load_failed)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_retry)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_filtered)).assertDoesNotExist()
    }

    /** Each production dismissal path restores read pinned predecessors above the surviving unread row. */
    @Test fun mountedUnreadPillDismissalRestoresPinnedHead() = assertPinnedHeadRestored(Exit.Pill)

    @Test fun mountedUnreadSecondPullRestoresPinnedHead() = assertPinnedHeadRestored(Exit.Pull)

    @Test fun mountedUnreadAccessibilityDismissalRestoresPinnedHead() = assertPinnedHeadRestored(Exit.Accessibility)

    private fun assertPinnedHeadRestored(exit: Exit) {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        model.state.swipePreferences.setPullUnread(true)
        model.retainedRow.updateAndGet { it.copy(manuallyMarkedUnread = false, pinned = true, pinnedPosition = 0u) }
        model.controller.applyChatListRow(model.retainedRow.get())
        model.controller.applyChatListRow(model.row.get())
        mount(model)
        val listId = rule.onNodeWithTag("chats.list").fetchSemanticsNode().id
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads == 1 }
        val row = rule.onNodeWithTag("chat.row.$ACTION_TARGET")
        awaitProgress { row.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick) }
        when (exit) {
            Exit.Pill -> rule.onNodeWithTag("chats.unread_filter").performClick()
            Exit.Accessibility -> rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
            Exit.Pull -> {
                val distance = with(rule.density) { 100.dp.toPx() }
                row.performTouchInput {
                    down(Offset(width * 0.6f, 10f))
                    moveBy(Offset(0f, distance), 120)
                    up()
                }
            }
        }
        rule.waitForIdle()
        val pinned = rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR")
        awaitProgress {
            pinned.fetchSemanticsNode().boundsInRoot.top ==
                rule
                    .onNodeWithTag("chats.list")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
        }
        assertEquals(listId, rule.onNodeWithTag("chats.list").fetchSemanticsNode().id)
        assertTrue(pinned.fetchSemanticsNode().boundsInRoot.top < row.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(model.calls.isEmpty())
    }

    /** A pending read preserves known unread rows and cannot reinstate filtering after dismissal. */
    @Test fun mountedPendingUnreadKeepsListAndRejectsLateResultAfterClear() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        val release = CompletableDeferred<Unit>().also { releaseRead = it }
        model.beforeSnapshotRead = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                release.await()
            }
        }
        mount(model)
        val listId = rule.onNodeWithTag("chats.list").fetchSemanticsNode().id
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads == 1 }
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        rule.onNodeWithTag("chats.unread_refresh").assertExists()
        assertEquals(listId, rule.onNodeWithTag("chats.list").fetchSemanticsNode().id)
        rule.onNodeWithTag("chats.unread_filter").performClick()
        release.complete(Unit)
        settle()
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        rule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        assertEquals(listId, rule.onNodeWithTag("chats.list").fetchSemanticsNode().id)
        assertTrue(model.calls.isEmpty())
    }

    /** Ordinary empty folder overlays retain sole ownership of recovery and the list stays unmounted. */
    @Test fun mountedEmptyFolderHasOneInlineErrorRecovery() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        val folder = model.state.chatFolderPreferences.createFolder(ACTION_ACCOUNT, "Empty selection")!!
        model.controller.publishInitialLoadFailureForTest(
            ErrorPresentation(AppText.Resource(R.string.error_try_again), "Synthetic list failure"),
        )
        mount(model, folder.id)
        rule.onNodeWithTag("chats.list").assertDoesNotExist()
        assertEquals(
            1,
            rule
                .onAllNodesWithText(rule.activity.getString(R.string.error_try_again))
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(1, rule.onAllNodesWithText(rule.activity.getString(R.string.retry)).fetchSemanticsNodes().size)
    }

    /** Per-chat search failures complete as no matches without a top-level recovery action. */
    @Test fun mountedPerChatBodySearchFailureCompletesEmptySearch() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        mount(model, globalSearchState = GlobalSearchState(isOpen = true, query = "no-matching-conversation"))
        awaitProgress {
            rule.onAllNodesWithText(rule.activity.getString(R.string.no_results)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_results_detail)).assertExists()
        rule.onNodeWithTag("chats.list").assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.retry)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.couldnt_load_chats)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.search_message_content_failed)).assertDoesNotExist()
    }

    /** No known matches remain pending until native completion; failure must never look caught up. */
    @Test fun mountedZeroLocalUnreadPreservesPendingAndFailureStates() {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        val release = CompletableDeferred<Unit>().also { releaseRead = it }
        model.row.updateAndGet { it.copy(manuallyMarkedUnread = false) }
        model.retainedRow.updateAndGet { it.copy(manuallyMarkedUnread = false) }
        model.controller.applyChatListRow(model.retainedRow.get())
        model.beforeSnapshotRead = {
            release.await()
            error("Synthetic unread read refusal")
        }
        mount(model)
        val listId = rule.onNodeWithTag("chats.list").fetchSemanticsNode().id
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads == 1 }
        rule.onNodeWithTag(dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_unread_title)).assertDoesNotExist()
        assertEquals(listId, rule.onNodeWithTag("chats.list").fetchSemanticsNode().id)
        release.complete(Unit)
        awaitProgress {
            rule
                .onAllNodesWithText(rule.activity.getString(R.string.pull_unread_load_failed))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(
            1,
            rule
                .onAllNodesWithText(rule.activity.getString(R.string.pull_unread_retry))
                .fetchSemanticsNodes()
                .size,
        )
        rule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        rule.onNodeWithTag(dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_unread_title)).assertDoesNotExist()
        assertEquals(listId, rule.onNodeWithTag("chats.list").fetchSemanticsNode().id)
    }

    private enum class Exit { Pill, Pull, Accessibility }

    private fun mount(
        model: OffWindowActionFixture,
        folderId: String? = null,
        globalSearchState: GlobalSearchState = GlobalSearchState(),
    ) {
        rule.setContent {
            if (mounted.value) {
                WhiteNoiseTheme {
                    ChatsScreen(
                        model.state,
                        model.controller,
                        {},
                        { _, _, _, _ -> },
                        selectedFolderId = folderId,
                        globalSearchState = globalSearchState,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun awaitProgress(ready: () -> Boolean) {
        rule.waitUntil {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            scope.testScheduler.runCurrent()
            ready()
        }
    }

    private fun settle() {
        scope.testScheduler.runCurrent()
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.waitForIdle()
        scope.testScheduler.runCurrent()
    }
}
