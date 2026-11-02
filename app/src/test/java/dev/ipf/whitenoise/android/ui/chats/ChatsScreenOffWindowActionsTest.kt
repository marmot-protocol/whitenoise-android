package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ChatsScreenOffWindowActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private var fixture: OffWindowActionFixture? = null
    private val folder = mutableStateOf<String?>(null)

    /** Releases held native calls, then waits for the app's mutation jobs to finish cancelling. */
    @After fun close() {
        val model = fixture
        model?.releaseLookup?.complete(Unit)
        model?.releaseArchive?.complete(Unit)
        try {
            // The app's mutation jobs are owned by AppState, not by the fixture's IO TestScope.
            settle()
        } finally {
            rule.runOnIdle {
                model?.state?.mutationsScope?.cancel()
                model?.controller?.onCleared()
            }
            try {
                rule.waitUntil(WAIT_MILLIS) {
                    scope.testScheduler.runCurrent()
                    model
                        ?.state
                        ?.mutationsScope
                        ?.coroutineContext
                        ?.get(Job)
                        ?.isCompleted != false
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test fun unreadSnapshotRowSwipeReadCommitsAndLeavesOnlyOtherUnreadChat() {
        val model = mount(SwipeAction.ReadUnread)
        swipeTarget()
        awaitProgress { "read:false" in model.calls }
        settle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        assertFalse(model.row.get().manuallyMarkedUnread)
        assertEquals(listOf("read:false"), model.calls.toList())
        assertOffWindow(model)
    }

    @Test fun unreadSnapshotRowSwipePinRefreshesNativeStatusAndUnpinsAgain() {
        val model = mount(SwipeAction.PinUnpin)
        val reads = model.snapshotReads
        swipeTarget()
        awaitProgress { "pin:true" in model.calls && model.snapshotReads > reads }
        settle()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_pinned_badge)).assertExists()
        assertOffWindow(model)
        val pinnedReads = model.snapshotReads
        swipeTarget()
        awaitProgress { "pin:false" in model.calls && model.snapshotReads > pinnedReads }
        settle()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_pinned_badge)).assertDoesNotExist()
        assertEquals(listOf("pin:true", "pin:false"), model.calls.toList())
        assertOffWindow(model)
    }

    @Test fun unreadSnapshotRowSwipeMuteRefreshesNativeStatusAndUnmutesAgain() {
        val model = mount(SwipeAction.MuteUnmute)
        val reads = model.snapshotReads
        swipeTarget()
        awaitProgress { "mute:true" in model.calls && model.snapshotReads > reads }
        settle()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_muted_badge)).assertExists()
        assertOffWindow(model)
        val mutedReads = model.snapshotReads
        swipeTarget()
        awaitProgress { "mute:false" in model.calls && model.snapshotReads > mutedReads }
        settle()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_muted_badge)).assertDoesNotExist()
        assertEquals(listOf("mute:true", "mute:false"), model.calls.toList())
        assertOffWindow(model)
    }

    @Test fun unreadSnapshotRowMenuArchiveCommitsAndRefreshesActiveUnreadRows() {
        val model = mount()
        val reads = model.snapshotReads
        openMenu()
        rule.onNodeWithTag("chat.action.Archive").performClick()
        awaitProgress { "archive:true" in model.calls && model.snapshotReads > reads }
        settle()
        assertTrue(model.row.get().archived)
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        assertEquals(listOf("archive:true"), model.calls.toList())
        assertOffWindow(model)
    }

    @Test fun archivedUnreadSnapshotRowMenuUnarchiveRefreshesArchivedScope() {
        val model = mount(archived = true)
        val reads = model.snapshotReads
        openMenu("Unarchive")
        rule.onNodeWithTag("chat.action.Unarchive").performClick()
        awaitProgress { "archive:false" in model.calls && model.snapshotReads > reads }
        settle()
        assertFalse(model.row.get().archived)
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        assertEquals(listOf("archive:false"), model.calls.toList())
        assertOffWindow(model)
    }

    @Test fun selectionReadRunsWithoutDialogAsItsUnreadRowDisappears() {
        val model = selectTarget()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_row_action_mark_read)).performClick()
        awaitProgress { "read:false" in model.calls }
        awaitDirectBatch(model)
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        assertEquals(listOf("read:false"), model.calls.toList())
    }

    @Test fun selectionMuteAndPinRunWithoutDialogAndKeepSelectionUsable() {
        val model = selectTarget()
        for ((label, call) in listOf(
            R.string.chat_row_action_mute to "mute:true",
            R.string.chat_row_action_unmute to "mute:false",
            R.string.chat_row_action_pin to "pin:true",
            R.string.chat_row_action_unpin to "pin:false",
        )) {
            rule.onNodeWithContentDescription(rule.activity.getString(label)).performClick()
            awaitProgress { call in model.calls }
            awaitDirectBatch(model)
        }
        assertEquals(listOf("mute:true", "mute:false", "pin:true", "pin:false"), model.calls.toList())
    }

    private fun selectTarget(): OffWindowActionFixture {
        val model = mount()
        openMenu()
        rule.onNodeWithTag("chat.action.Select").performClick()
        return model
    }

    @Test fun bulkArchiveCombinesRetainedAndOffWindowUnreadRows() {
        val model = mount()
        openMenu()
        rule.onNodeWithTag("chat.action.Select").performClick()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.archive)).performClick()
        awaitProgress { "archive:true" in model.calls && "archive-survivor:true" in model.calls }
        awaitDirectBatch(model)
        assertTrue(model.row.get().archived)
        assertTrue(model.retainedRow.get().archived)
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertDoesNotExist()
        assertEquals(2, model.calls.size)
        assertOffWindow(model)
    }

    /** A fresh native capability denial finishes silently and leaves the selected bar usable. */
    @Test fun skippedMuteFinishesSilentlyWithoutRecoveryOrDisabledActions() {
        val model = selectTarget()
        val noticeBefore = model.state.transientNotice?.id
        model.targetMuteEligible = false
        val readsBefore = model.keyedReads
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_row_action_mute)).performClick()
        awaitProgress { model.keyedReads > readsBefore }
        awaitDirectBatch(model)
        rule.onNodeWithTag("chats.batchRecovery").assertDoesNotExist()
        assertEquals(noticeBefore, model.state.transientNotice?.id)
        assertTrue(model.calls.isEmpty())
        rule
            .onNodeWithContentDescription(rule.activity.getString(R.string.chat_row_action_pin))
            .assertIsEnabled()
            .performClick()
        awaitProgress { "pin:true" in model.calls }
        awaitDirectBatch(model)
        assertEquals(listOf("pin:true"), model.calls.toList())
    }

    @Test fun uncertainMuteIsCheckedWithoutRepeatingNativeMutation() {
        val model = selectTarget()
        model.loseMuteReadback = true
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_row_action_mute)).performClick()
        awaitProgress { "mute:true" in model.calls }
        awaitDirectBatch(model)
        rule.onNodeWithTag("chats.batchRecovery").assertIsDisplayed()
        rule.onNodeWithTag("chats.batchRecovery.check").assertIsDisplayed()
        rule.onNodeWithTag("chats.batchRecovery.retry").assertDoesNotExist()
        model.targetReadUnavailable = false
        val reads = model.keyedReads
        rule.onNodeWithTag("chats.batchRecovery.check").performClick()
        awaitProgress { model.keyedReads > reads }
        awaitDirectBatch(model)
        rule.onNodeWithTag("chats.batchRecovery").assertDoesNotExist()
        assertEquals(listOf("mute:true"), model.calls.toList())
        assertTrue(model.row.get().muted)
    }

    @Test fun failedArchiveRetainsRecoveryAfterSelectionClosesAndRetriesOnlyFailure() {
        val model = selectTarget()
        model.archiveFails = true
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.archive)).performClick()
        awaitProgress { "archive:true" in model.calls && "archive-survivor:true" in model.calls }
        awaitDirectBatch(model)
        rule.onNodeWithTag("chats.batchRecovery").assertIsDisplayed()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertDoesNotExist()
        // The captured request must remain reachable when selection UI is explicitly closed.
        rule.onNodeWithTag("chats.selection.close").performClick()
        rule.onNodeWithTag("chats.batchRecovery").assertIsDisplayed()
        model.archiveFails = false
        rule.onNodeWithTag("chats.batchRecovery.retry").performClick()
        awaitProgress { model.calls.count { it == "archive:true" } == 2 }
        awaitDirectBatch(model)
        rule.onNodeWithTag("chats.batchRecovery").assertDoesNotExist()
        assertEquals(1, model.calls.count { it == "archive-survivor:true" })
        assertTrue(model.row.get().archived)
        assertTrue(model.retainedRow.get().archived)
    }

    @Test fun mixedArchivedBulkSelectionArchivesThenUnarchivesBothRetentionKinds() {
        val model = mount(mixedArchived = true)
        openMenu("Unarchive")
        rule.onNodeWithTag("chat.action.Select").performClick()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.archive)).performClick()
        awaitProgress { "archive-survivor:true" in model.calls }
        awaitDirectBatch(model)
        assertTrue(model.row.get().archived)
        assertTrue(model.retainedRow.get().archived)
        assertFalse("already archived target is not redundantly written", "archive:true" in model.calls)
        assertOffWindow(model)
        // Completed batches retain selection; the next explicit direction reuses those same two chats.
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.unarchive)).performClick()
        awaitProgress { "archive:false" in model.calls && "archive-survivor:false" in model.calls }
        awaitDirectBatch(model)
        assertFalse(model.row.get().archived)
        assertFalse(model.retainedRow.get().archived)
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        assertEquals(3, model.calls.size)
        assertOffWindow(model)
    }

    @Test fun rejectedOffWindowArchiveRestoresUnreadRowAfterPendingOverlay() {
        val model = mount()
        model.archiveFails = true
        model.archiveEntered = CompletableDeferred()
        model.releaseArchive = CompletableDeferred()
        openMenu()
        rule.onNodeWithTag("chat.action.Archive").performClick()
        awaitProgress { model.archiveEntered!!.isCompleted }
        settle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        assertOffWindow(model)
        val pendingReads = model.snapshotReads
        model.releaseArchive!!.complete(Unit)
        awaitProgress { model.snapshotReads > pendingReads }
        settle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        assertFalse(model.row.get().archived)
        assertEquals(listOf("archive:true"), model.calls.toList())
        assertOffWindow(model)
    }

    @Test fun heldOffWindowSwipeLookupCannotCommitAfterFolderScopeChanges() {
        val model = mount(SwipeAction.PinUnpin)
        model.lookupEntered = CompletableDeferred()
        model.releaseLookup = CompletableDeferred()
        swipeTarget()
        awaitProgress { model.lookupEntered!!.isCompleted }
        assertTrue(model.calls.isEmpty())
        rule.runOnIdle { folder.value = "replacement-folder" }
        rule.waitForIdle()
        model.releaseLookup!!.complete(Unit)
        settle()
        assertTrue("a replaced scope cannot mutate the old unread row", model.calls.isEmpty())
        assertFalse(model.row.get().pinned)
        assertOffWindow(model)
    }

    @Test fun heldOffWindowSwipeLookupCannotCommitAfterAccountChanges() {
        rejectHeldLookup { it.replaceAccount() }
    }

    @Test fun heldOffWindowSwipeLookupCannotCommitAfterRuntimeGenerationChanges() {
        rejectHeldLookup { it.replaceRuntimeGeneration() }
    }

    private fun rejectHeldLookup(replaceOwner: (OffWindowActionFixture) -> Unit) {
        val model = mount(SwipeAction.PinUnpin)
        model.lookupEntered = CompletableDeferred()
        model.releaseLookup = CompletableDeferred()
        swipeTarget()
        awaitProgress { model.lookupEntered!!.isCompleted }
        assertTrue(model.calls.isEmpty())
        rule.runOnIdle { replaceOwner(model) }
        rule.waitForIdle()
        model.releaseLookup!!.complete(Unit)
        settle()
        assertTrue("a replaced account/runtime cannot mutate the old unread row", model.calls.isEmpty())
        assertFalse(model.row.get().pinned)
        assertOffWindow(model)
    }

    private fun mount(
        action: SwipeAction = SwipeAction.Off,
        archived: Boolean = false,
        mixedArchived: Boolean = false,
    ): OffWindowActionFixture {
        val model = OffWindowActionFixture(scope)
        fixture = model
        if (archived || mixedArchived) {
            model.row.updateAndGet { it.copy(archived = true) }
            val store = model.state.chatFolderPreferences
            val archivedFolder = store.createFolder(ACTION_ACCOUNT, "Archived chats")!!
            val folderRule =
                if (mixedArchived) {
                    ChatFolderRule(
                        includeMuted = true,
                        smartFilter =
                            SmartFolderCodec.encode(
                                SmartFolderFilter.Group(
                                    children = listOf(SmartFolderFilter.Condition(FolderField.UNREAD)),
                                ),
                            ),
                    )
                } else {
                    ChatFolderRule(archivedOnly = true, includeMuted = true)
                }
            store.setFolderRule(ACTION_ACCOUNT, archivedFolder.id, folderRule)
            folder.value = archivedFolder.id
        }
        model.state.swipePreferences.set(SwipeBinding.ChatRight, action)
        settle()
        assertOffWindow(model)
        rule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(
                    model.state,
                    model.controller,
                    {},
                    { _, _, _, _ -> },
                    selectedFolderId = folder.value,
                )
            }
        }
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads >= 1 }
        settle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        assertOffWindow(model)
        return model
    }

    private fun swipeTarget() {
        awaitTargetInteraction()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").performTouchInput {
            // Start on the row body, clear of the independently clickable avatar.
            down(Offset(width * 0.35f, height / 2f))
            moveTo(Offset(width * 0.8f, height / 2f), 250)
            up()
        }
        rule.waitForIdle()
    }

    private fun openMenu(action: String = "Archive") {
        awaitTargetInteraction()
        val row = rule.onNodeWithTag("chat.row.$ACTION_TARGET")
        // Flush the won hold while the pointer is still down, as the production range/menu tests do.
        row.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(center)
        }
        rule.onNodeWithTag("chat.menu.$ACTION_TARGET").assertIsDisplayed()
        row.performTouchInput { up() }
        rule.onNodeWithTag("chat.action.$action").assertExists()
    }

    private fun awaitTargetInteraction() {
        val row = rule.onNodeWithTag("chat.row.$ACTION_TARGET")
        // Row membership can publish before its placement/input gate reopens.
        awaitProgress { row.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick) }
        row.assertIsDisplayed().assertIsEnabled()
    }

    private fun assertOffWindow(model: OffWindowActionFixture) {
        assertFalse("unread actions must not grow the retained window", model.controller.containsGroup(ACTION_TARGET))
    }

    private fun awaitDirectBatch(model: OffWindowActionFixture) {
        awaitProgress { !model.controller.chatListBatchInProgress }
        settle()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
    }

    /** Drives the test scheduler and Android Main until [ready] holds or [WAIT_MILLIS] passes. */
    private fun awaitProgress(ready: () -> Boolean) {
        rule.waitUntil(WAIT_MILLIS) {
            scope.testScheduler.runCurrent()
            // Controller/native continuations post to Android Main; Compose frames do not run its 16ms debounce.
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
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.waitForIdle()
    }

    private companion object {
        // Wall-clock budget. These waits settle in under 250 ms locally, but CI runs six 2 GiB forks
        // and the 1 s default timed out at four different waits across CI and local runs.
        const val WAIT_MILLIS = 10_000L
    }
}
