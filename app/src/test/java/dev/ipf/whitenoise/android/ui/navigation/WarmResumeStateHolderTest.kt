package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class WarmResumeStateHolderTest {
    /** Verifies that account replacement cannot replay the cold-process indicator. */
    @Test
    fun onlyTheFirstAccountControllerInAProcessPresentsItsInitialConnectionAttempt() {
        val state = appState()
        val processState = MainShellProcessState(state)

        val coldController = processState.chatsController(ACCOUNT_REF, runtimeGeneration = 4)

        assertTrue(coldController.claimInitialConnectionPresentation())
        val warmController = processState.chatsController("second-account", runtimeGeneration = 4)
        assertFalse(warmController.claimInitialConnectionPresentation())
        processState.release()
    }

    /** Verifies that releasing shell state does not recreate a consumed cold-start claim. */
    @Test
    fun releasingTheShellDoesNotRearmColdConnectionPresentationInTheSameProcess() {
        val state = appState()
        val processState = MainShellProcessState(state)
        val first = processState.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        assertTrue(first.claimInitialConnectionPresentation())

        processState.release()
        val replacement = processState.chatsController(ACCOUNT_REF, runtimeGeneration = 4)

        assertFalse(replacement.claimInitialConnectionPresentation())
        processState.release()
    }

    @Test
    fun terminalNonAuthenticatedPhaseDropsTheProcessRetainedShell() {
        val state = appState()
        val processState = MainShellProcessState(state)
        val holder = MainShellStateHolder(state, SavedStateHandle(), processState)
        holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        val snapshotController =
            ChatsController(
                appState = state,
                initialAccountRef = ACCOUNT_REF,
                initialLocalSnapshot = localSnapshot(),
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        holder.selectedChat.value = snapshotController.items.single()

        val onboardingFrameReady =
            holder.prepareFirstUsefulFrame(
                phase = AppPhase.Onboarding,
                activeAccountRef = null,
                runtimeGeneration = 4,
                appLockScreenVisible = false,
            )

        assertTrue(onboardingFrameReady)
        assertNull(holder.selectedChat.value)
        assertFalse(holder.localProjectionAvailable(ACCOUNT_REF, runtimeGeneration = 4))
        snapshotController.onCleared()
    }

    @Test
    fun appLockDoesNotMaterializeTheProtectedLocalProjection() {
        val state = appState()
        val holder = MainShellStateHolder(state, SavedStateHandle())

        val lockFrameReady =
            holder.prepareFirstUsefulFrame(
                phase = AppPhase.Ready,
                activeAccountRef = ACCOUNT_REF,
                runtimeGeneration = 4,
                appLockScreenVisible = true,
            )

        assertTrue(lockFrameReady)
        assertFalse(holder.localProjectionAvailable(ACCOUNT_REF, runtimeGeneration = 4))
        holder.release()
    }

    @Test
    fun freshActivityInSameProcessReusesLoadedControllerAndConversationRoute() {
        val state = appState()
        val processState = MainShellProcessState(state)
        val firstActivityHolder = MainShellStateHolder(state, SavedStateHandle(), processState)
        val loadedController = firstActivityHolder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        val snapshotController =
            ChatsController(
                appState = state,
                initialAccountRef = ACCOUNT_REF,
                initialLocalSnapshot = localSnapshot(),
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        firstActivityHolder.selectedChat.value = snapshotController.items.single()

        val freshActivityHolder = MainShellStateHolder(state, SavedStateHandle(), processState)

        assertSame(loadedController, freshActivityHolder.chatsController(ACCOUNT_REF, runtimeGeneration = 4))
        assertSame(firstActivityHolder.selectedChat.value, freshActivityHolder.selectedChat.value)
        freshActivityHolder.release()
        snapshotController.onCleared()
    }

    @Test
    fun freshActivityHolderDoesNotInheritAnotherTasksPendingShare() {
        val state = appState()
        val processState = MainShellProcessState(state)
        val firstActivityHolder = MainShellStateHolder(state, SavedStateHandle(), processState)
        val freshActivityHolder = MainShellStateHolder(state, SavedStateHandle(), processState)
        val request =
            ShareRequest(
                payload = SharePayload("pending", emptyList(), "text/plain"),
                shortcutId = null,
                requestId = "pending-share",
            )

        firstActivityHolder.inboundShareRequest.value = request

        assertSame(request, firstActivityHolder.inboundShareRequest.value)
        assertNull(freshActivityHolder.inboundShareRequest.value)
        freshActivityHolder.release()
    }

    /** Promotion keeps one stable visible request while acknowledging its one-shot intent. */
    @Test
    fun inboundSharePromotionKeepsThePickerRouteAndPersistsOnlyItsToken() {
        val savedState = SavedStateHandle()
        val holder = MainShellStateHolder(appState(), savedState)
        val request = shareRequest("pending-share")

        holder.acceptInboundShareRequest(request)
        assertSame(request, holder.visibleShareRequest)

        assertTrue(holder.promoteInboundShareRequest(request, persisted = true))

        assertNull(holder.inboundShareRequest.value)
        assertSame(request, holder.pendingShareRequest.value)
        assertSame(request, holder.visibleShareRequest)
        assertEquals(request.requestId, savedState.get<String>("main_shell_pending_share_request_id"))
        assertFalse(savedState.keys().any { key -> savedState.get<Any>(key) is SharePayload })
        holder.release()
    }

    /** Persistence before Direct Share resolution survives recreation without promoting a picker prematurely. */
    @Test
    fun persistedInboundShareCanRestoreAfterProcessDeathBeforePromotion() {
        val savedState = SavedStateHandle()
        val request = shareRequest("persisted-direct")
        val holder = MainShellStateHolder(appState(), savedState)
        holder.acceptInboundShareRequest(request)

        assertTrue(holder.markInboundSharePersisted(request.requestId))
        assertNull(holder.pendingShareRequest.value)
        assertEquals(request.requestId, holder.pendingShareRequestId)

        val restoredHolder =
            MainShellStateHolder(
                appState(),
                SavedStateHandle(mapOf("main_shell_pending_share_request_id" to request.requestId)),
            )
        assertTrue(restoredHolder.restorePendingShareRequest(request.requestId, request))
        assertSame(request, restoredHolder.visibleShareRequest)
        holder.release()
        restoredHolder.release()
    }

    /** An in-memory picker without encrypted persistence never advertises a process-restore token. */
    @Test
    fun unpersistedPickerDoesNotExposeAProcessRestoreToken() {
        val holder = MainShellStateHolder(appState(), SavedStateHandle())
        val request = shareRequest("memory-only")
        holder.acceptInboundShareRequest(request)

        assertTrue(holder.promoteInboundShareRequest(request, persisted = false))

        assertSame(request, holder.visibleShareRequest)
        assertNull(holder.pendingShareRequestId)
        holder.release()
    }

    /** Process restoration accepts only the request named by the saved encrypted-store token. */
    @Test
    fun processRestorationRejectsMismatchedShareAndRestoresTheExpectedRequestOnce() {
        val expected = shareRequest("expected")
        val holder =
            MainShellStateHolder(
                appState(),
                SavedStateHandle(mapOf("main_shell_pending_share_request_id" to expected.requestId)),
            )

        assertFalse(holder.restorePendingShareRequest(expected.requestId, shareRequest("wrong")))
        assertFalse(holder.hasPendingShareRoute)

        val restoredHolder =
            MainShellStateHolder(
                appState(),
                SavedStateHandle(mapOf("main_shell_pending_share_request_id" to expected.requestId)),
            )
        assertTrue(restoredHolder.restorePendingShareRequest(expected.requestId, expected))
        assertSame(expected, restoredHolder.visibleShareRequest)
        assertFalse(restoredHolder.restorePendingShareRequest(expected.requestId, expected.copy()))
        restoredHolder.clearPendingShareRequest(expected.requestId)
        assertFalse(restoredHolder.hasPendingShareRoute)
        holder.release()
        restoredHolder.release()
    }

    /** A newer system intent replaces picker state without retaining the previous identity. */
    @Test
    fun newerInboundShareSupersedesPromotedRequestAndClearsItsRestoreToken() {
        val savedState = SavedStateHandle()
        val holder = MainShellStateHolder(appState(), savedState)
        val first = shareRequest("first")
        val second = shareRequest("second")
        holder.acceptInboundShareRequest(first)
        assertTrue(holder.promoteInboundShareRequest(first, persisted = true))

        holder.acceptInboundShareRequest(second)

        assertSame(second, holder.visibleShareRequest)
        assertNull(holder.pendingShareRequest.value)
        assertNull(savedState.get<String>("main_shell_pending_share_request_id"))
        holder.release()
    }

    @Test
    fun taskRemovalDropsConversationStateButKeepsTheWarmChatListProjection() {
        val state = appState()
        val processState = MainShellProcessState(state)
        val holder = MainShellStateHolder(state, SavedStateHandle(), processState)
        val loadedChats = holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        val snapshotController =
            ChatsController(
                appState = state,
                initialAccountRef = ACCOUNT_REF,
                initialLocalSnapshot = localSnapshot(),
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        val selected = snapshotController.items.single()
        holder.selectedChat.value = selected
        val retainedConversation =
            holder.conversationController(
                chatId = GROUP_ID,
                accountRef = ACCOUNT_REF,
                runtimeGeneration = 4,
                presentationKey = 0,
            ) {
                ConversationController(appState = state, initialGroup = selected.group)
            }

        processState.onTaskRemoved()

        assertNull(holder.selectedChat.value)
        assertSame(loadedChats, holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4))
        val replacementConversation =
            holder.conversationController(
                chatId = GROUP_ID,
                accountRef = ACCOUNT_REF,
                runtimeGeneration = 4,
                presentationKey = 0,
            ) {
                ConversationController(appState = state, initialGroup = selected.group)
            }
        assertNotSame(retainedConversation, replacementConversation)
        holder.release()
        snapshotController.onCleared()
    }

    @Test
    fun replacementRuntimeDropsTheProcessRetainedConversationBeforeControllerPublication() {
        val state = appState()
        val processState = MainShellProcessState(state)
        val holder = MainShellStateHolder(state, SavedStateHandle(), processState)
        holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        val snapshotController =
            ChatsController(
                appState = state,
                initialAccountRef = ACCOUNT_REF,
                initialLocalSnapshot = localSnapshot(),
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        holder.selectedChat.value = snapshotController.items.single()

        holder.chatsController(ACCOUNT_REF, runtimeGeneration = 5)

        assertNull(holder.selectedChat.value)
        holder.release()
        snapshotController.onCleared()
    }

    @Test
    fun activityRecreationReusesTheSameLocalProjectionController() {
        val holder = MainShellStateHolder(appState(), SavedStateHandle())

        val beforeRecreation = holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        val afterRecreation = holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)

        assertSame(beforeRecreation, afterRecreation)
        holder.release()
    }

    @Test
    fun runtimeReplacementCannotReuseAnotherAccountsDecryptedProjection() {
        val holder = MainShellStateHolder(appState(), SavedStateHandle())

        val firstRuntime = holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        val replacedRuntime = holder.chatsController(ACCOUNT_REF, runtimeGeneration = 5)

        assertNotSame(firstRuntime, replacedRuntime)
        holder.release()
    }

    @Test
    fun processRestorationResolvesOnlyLightweightSavedRouteKeysFromLocalProjection() {
        val state = appState()
        val savedState =
            SavedStateHandle(
                mapOf(
                    "main_shell_selected_account_ref" to ACCOUNT_REF,
                    "main_shell_selected_group_id" to GROUP_ID,
                ),
            )
        val holder = MainShellStateHolder(state, savedState)
        val controller =
            ChatsController(
                appState = state,
                initialAccountRef = ACCOUNT_REF,
                initialLocalSnapshot = localSnapshot(),
                memberSnapshotLoader = { _, _ -> emptyList() },
            )

        holder.restoreConversationIfReady(controller, ACCOUNT_REF)

        assertSame(controller.items.single(), holder.selectedChat.value)
        holder.release()
        controller.onCleared()
    }

    @Test
    fun processRestorationNeverCrossesAccountBoundary() {
        val state = appState()
        val holder =
            MainShellStateHolder(
                state,
                SavedStateHandle(
                    mapOf(
                        "main_shell_selected_account_ref" to "another-account",
                        "main_shell_selected_group_id" to GROUP_ID,
                    ),
                ),
            )
        val controller =
            ChatsController(
                appState = state,
                initialAccountRef = ACCOUNT_REF,
                initialLocalSnapshot = localSnapshot(),
                memberSnapshotLoader = { _, _ -> emptyList() },
            )

        holder.restoreConversationIfReady(controller, ACCOUNT_REF)

        assertNull(holder.selectedChat.value)
        assertFalse(holder.hasSavedConversationRoute)
        holder.release()
        controller.onCleared()
    }

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = "a".repeat(64),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Creates one identity-distinct text request for lifecycle ownership tests. */
    private fun shareRequest(requestId: String) =
        ShareRequest(
            payload = SharePayload("pending", emptyList(), "text/plain"),
            shortcutId = null,
            requestId = requestId,
        )

    private fun localSnapshot() =
        AccountSwitchLocalSnapshot(
            accountRef = ACCOUNT_REF,
            activeAccountIdHex = "a".repeat(64),
            rows = listOf(chatListRow()),
            groups = emptyList(),
            memberIds = emptyList(),
            profiles = emptyList(),
        )

    private fun chatListRow() =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = "Restored conversation",
            groupName = "Restored conversation",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 1uL,
            activitySortAt = 1uL,
            updatedAt = 1uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.GROUP,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "warm-resume-account"
        val GROUP_ID = "1".repeat(64)
    }
}
