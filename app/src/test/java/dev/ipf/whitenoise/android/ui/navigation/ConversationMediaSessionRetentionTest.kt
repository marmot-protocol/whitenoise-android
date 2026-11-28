package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollSnapshot
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Retained conversation ownership across account changes that occur before the shell recomposes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationMediaSessionRetentionTest {
    /** Coalesced account switches retire the old media owner while keeping the visible route and scroll anchor. */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun coalescedAccountSwitchesReplaceTheConversationSession() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val accounts =
                listOf(activeAccount(), activeAccount().copy(label = SECOND_ACCOUNT_REF, accountIdHex = "b".repeat(64)))
            val state =
                WhiteNoiseAppState(
                    context = ApplicationProvider.getApplicationContext<Context>(),
                    draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                    accountIdHexResolver = { ref -> accounts.firstOrNull { it.label == ref }?.accountIdHex },
                    accounts = accounts,
                    activeAccountRef = ACCOUNT_REF,
                )
            val holder = MainShellStateHolder(state, SavedStateHandle(), MainShellProcessState(state))
            try {
                val chats = holder.chatsController(ACCOUNT_REF, state.runtimeGeneration)
                val selected =
                    ChatListItem(
                        group = conversationTimelineTestGroup(),
                        latest = null,
                        otherMemberAccount = null,
                        memberCount = 1,
                        memberSnapshot = null,
                    )
                holder.openForTest(selected)
                val scrollKey = conversationScrollKey(ACCOUNT_REF, selected.group.groupIdHex)
                val scroll =
                    ConversationScrollSnapshot(
                        firstVisibleItemIndex = 3,
                        firstVisibleItemScrollOffset = 11,
                        anchorItemId = "message:anchor",
                        anchorMessageIdHex = "anchor",
                    )
                holder.conversationScrollSnapshots[scrollKey] = scroll
                val old =
                    holder.conversationController(selected.group.groupIdHex, ACCOUNT_REF, state.runtimeGeneration, 0) {
                        ConversationController(state, selected.group)
                    }
                val preload = AccountSwitchPreloadPolicy.STARTUP_RESTORATION
                assertTrue(state.setActiveAccount(SECOND_ACCOUNT_REF, preloadPolicy = preload))
                assertTrue(state.setActiveAccount(ACCOUNT_REF, preloadPolicy = preload))

                assertSame(chats, holder.chatsController(ACCOUNT_REF, state.runtimeGeneration))
                val replacement =
                    holder.conversationController(selected.group.groupIdHex, ACCOUNT_REF, state.runtimeGeneration, 0) {
                        ConversationController(state, selected.group)
                    }
                assertNotSame(old, replacement)
                assertSame(selected, holder.selectedChat.value)
                assertEquals(scroll, holder.conversationScrollSnapshots[scrollKey])
            } finally {
                try {
                    holder.release()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }

    /** Builds a running local signer for the account-switch boundary. */
    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = "a".repeat(64),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
            unlinked = false,
        )

    private companion object {
        const val ACCOUNT_REF = "warm-resume-account"
        const val SECOND_ACCOUNT_REF = "second-account"
    }
}
