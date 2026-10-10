package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Cold projection failure must retain its owner and route without admitting the protected shell. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class StartupRecoveryStateHolderTest {
    /** Recovery keeps one unfinished controller while account/runtime fences and protected-shell gates hold. */
    @Test
    fun initialLoadFailureIsRecoverableWithoutGrantingASnapshotOrDroppingTheSavedRoute() {
        val state = appState()
        state.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> throw IllegalStateException("synthetic initial read failure") },
                openChats = { _, _ -> error("Window failure must precede the paired stream") },
            )
        val savedState =
            SavedStateHandle(
                mapOf(
                    "main_shell_selected_account_ref" to ACCOUNT_REF,
                    "main_shell_selected_group_id" to GROUP_ID,
                ),
            )
        val holder = MainShellStateHolder(state, savedState)
        val controller = holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            scope.launch { controller.bind(ACCOUNT_REF) }
            repeat(100) {
                if (controller.error == null) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            }
            assertNotNull(controller.error)
            holder.restoreConversationIfReady(controller, ACCOUNT_REF)

            assertSame(controller, holder.startupRecoveryController(ACCOUNT_REF, runtimeGeneration = 4))
            assertFalse(holder.localProjectionAvailable(ACCOUNT_REF, runtimeGeneration = 4))
            assertFalse(holder.firstUsefulFrameReady(AppPhase.Ready, ACCOUNT_REF, 4, appLockScreenVisible = false))
            assertNull(holder.startupRecoveryController("another-account", runtimeGeneration = 4))
            assertNull(holder.startupRecoveryController(ACCOUNT_REF, runtimeGeneration = 5))
            assertTrue(holder.hasSavedConversationRoute)
            assertNull(holder.selectedChat.value)
            assertEquals(GROUP_ID, savedState.get<String>("main_shell_selected_group_id"))
            assertSame(controller, holder.chatsController(ACCOUNT_REF, runtimeGeneration = 4))
        } finally {
            holder.release()
            scope.cancel()
        }
    }

    /** Uses the common empty draft fixture so only chat-projection ownership is exercised. */
    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi(ACCOUNT_REF, "a".repeat(64), true, false, false, true)),
            activeAccountRef = ACCOUNT_REF,
        )

    private companion object {
        const val ACCOUNT_REF = "warm-resume-account"
        val GROUP_ID = "1".repeat(64)
    }
}
