package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real background-runtime entry point while the app root owns a post-Ready recovery surface. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class StartupPostReadyBackgroundRecoveryTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The background-runtime entry point cannot reclaim a post-Ready projection failure as bootstrap loading. */
    @Test
    fun backgroundRecoveryPreservesPostReadyProjectionFailure() {
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                accounts = listOf(account()),
                chatListRows = listOf(row()),
                emitStartupNotification = false,
            )
        runBlocking { fixture.bootstrap() }
        val app = fixture.appState
        // Remove only the transient handoff so this bind must obtain its own authoritative local snapshot.
        app.consumeAccountSwitchLocalSnapshot(ACCOUNT)
        val pending = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        app.liveSubscriptionOverrides.chatList = heldSubscriptions(pending, opens)
        app.markDefaultNotificationsEnableAttempted()
        val visible = mutableStateOf(true)
        val shell = MainShellStateHolder(app, SavedStateHandle())
        val controller = shell.chatsController(app.activeAccountRef, app.runtimeGeneration)
        composeRule.setContent { WhiteNoiseTheme { if (visible.value) WhiteNoiseApp(app, shell, 0, 0) } }
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) { opens.get() == 1 }
            composeRule.runOnIdle { controller.publishInitialLoadTimeout(controller.bindEpoch) }
            val failure = controller.error
            composeRule.onNodeWithTag(STARTUP_FAILURE_TEST_TAG).assertIsDisplayed()
            assertBackgroundRuntimePreservesReady(fixture)
            composeRule.onNodeWithTag(STARTUP_FAILURE_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.runOnIdle {
                assertEquals(AppPhase.Ready, app.phase)
                assertSame(failure, controller.error)
                assertFalse(controller.hasLoadedLocalSnapshot)
                assertEquals(1, opens.get())
            }
        } finally {
            composeRule.runOnIdle {
                visible.value = false
                shell.release()
            }
            pending.complete(Unit)
            composeRule.waitForIdle()
            fixture.close()
        }
    }

    /** Keeps only this initial projection pending while the existing runtime/receiver remains operational. */
    private fun heldSubscriptions(
        pending: CompletableDeferred<Unit>,
        opens: AtomicInteger,
    ) = ChatListLiveSubscriptions(
        openChatListWindow = { _, _ ->
            opens.incrementAndGet()
            pending.await()
            error("held projection released during teardown")
        },
        openChats = { _, _ -> error("initial projection remains held") },
    )

    /** Samples the real background entry point throughout its receiver work instead of replacing phase by hand. */
    private fun assertBackgroundRuntimePreservesReady(fixture: NotificationBootstrapTestFixture) =
        runBlocking {
            val recovery = async { fixture.ensureNotificationRuntimeStarted() }
            var reclaimedLoading = false
            while (!recovery.isCompleted) {
                reclaimedLoading = reclaimedLoading || fixture.appState.phase == AppPhase.Bootstrapping
                yield()
            }
            recovery.await()
            assertFalse("background recovery must not reclaim bootstrap loading", reclaimedLoading)
        }

    /** Supplies an authenticated local account through the existing bootstrap FFI fixture. */
    private fun account() = AccountSummaryFfi(ACCOUNT, "a".repeat(64), true, false, false, true)

    /** The local projection belongs to one stable native group identity throughout reconciliation. */
    private fun row() =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = TITLE,
            groupName = TITLE,
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

    private companion object {
        const val ACCOUNT = "local-startup-account"
        const val GROUP_ID = "1111111111111111111111111111111111111111111111111111111111111111"
        const val TITLE = "Persisted local startup room"
    }
}
