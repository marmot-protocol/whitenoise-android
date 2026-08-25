package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class WarmResumeStateHolderTest {
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
