package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the actual Ready/no-snapshot app gate, with the native subscription held open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class StartupReadyRecoveryRouteTest {
    @get:Rule val composeRule = createComposeRule()

    /** Recovery stays visible across Retry without re-opening native work or consuming a restored route. */
    @Test
    fun rootRendersRecoveryWithoutRemountingTheBindOrRestoringProtectedContent() {
        val pending = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        val visible = mutableStateOf(true)
        val app = readyState()
        app.liveSubscriptionOverrides.chatList = heldInitialSubscriptions(pending, opens)
        val savedState = pendingSavedRoute()
        val shell = MainShellStateHolder(app, savedState)
        val controller = shell.chatsController(app.activeAccountRef, app.runtimeGeneration)
        composeRule.setContent { WhiteNoiseTheme { if (visible.value) WhiteNoiseApp(app, shell, 0, 0) } }
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) { opens.get() == 1 }
            repeat(3) {
                // The controller integration tests exercise the full timed wait; here its deadline
                // publisher drives the real root, rather than supplying a fabricated ErrorPresentation.
                composeRule.runOnIdle { controller.publishInitialLoadTimeout(controller.bindEpoch) }
                composeRule.onNodeWithTag(STARTUP_FAILURE_TEST_TAG).assertExists()
                composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertDoesNotExist()
                composeRule.onNodeWithTag(STARTUP_RETRY_TEST_TAG).performClick()
                composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertExists()
                assertEquals(1, opens.get())
                assertEquals(0L, controller.retryGeneration)
                assertFalse(controller.hasLoadedLocalSnapshot)
                assertTrue(shell.hasSavedConversationRoute)
                assertNull(shell.selectedChat.value)
            }
            composeRule.runOnIdle {
                controller.publishInitialLoadTimeout(controller.bindEpoch)
                app.setAppLockScreenVisibleForTest(true)
            }
            composeRule.onNodeWithTag(STARTUP_FAILURE_TEST_TAG).assertDoesNotExist()
            assertTrue(shell.hasSavedConversationRoute)
        } finally {
            composeRule.runOnIdle {
                visible.value = false
                shell.release()
            }
            pending.complete(Unit)
            composeRule.waitForIdle()
        }
    }

    /** A held native open makes any accidental preparation/shell remount observable. */
    private fun heldInitialSubscriptions(
        pending: CompletableDeferred<Unit>,
        opens: AtomicInteger,
    ) = ChatListLiveSubscriptions(
        openChatListWindow = { _, _ ->
            opens.incrementAndGet()
            pending.await()
            error("synthetic initial read failure")
        },
        openChats = { _, _ -> error("initial window has not completed") },
    )

    /** Lightweight process-restored keys must survive recovery until an authoritative snapshot arrives. */
    private fun pendingSavedRoute() =
        SavedStateHandle(
            mapOf(
                "main_shell_selected_account_ref" to "startup-account",
                "main_shell_selected_group_id" to "ab".repeat(32),
            ),
        )

    /** Seeds only completed bootstrap; the first chat projection still runs through the actual bind. */
    private fun readyState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { "aa".repeat(32) },
            accounts = listOf(AccountSummaryFfi("startup-account", "aa".repeat(32), true, false, false, true)),
            activeAccountRef = "startup-account",
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setPhase", AppPhase::class.java)
                .apply { isAccessible = true }
                .invoke(state, AppPhase.Ready)
            listOf("bootstrapCompleted", "networkNotificationRecoverySuppressed").forEach { name ->
                WhiteNoiseAppState::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(state, true)
            }
            state.markDefaultNotificationsEnableAttempted()
        }
}
