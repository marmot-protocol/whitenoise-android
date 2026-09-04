package dev.ipf.whitenoise.android.state

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessRestorationLocalShellBeforeNotificationReceiverTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun failedSignedOutAccountRestorationDoesNotPublishReady() =
        runBlocking {
            val signedOutAccount =
                AccountSummaryFfi(
                    label = "signed-out-account",
                    accountIdHex = "b".repeat(64),
                    localSigning = true,
                    externalSigning = false,
                    signedOut = true,
                    running = false,
                )
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(signedOutAccount),
                    signInFailure = IllegalStateException("test sign-in failure"),
                )

            try {
                fixture.bootstrap()

                assertTrue(
                    "failed signed-out restoration must keep the authenticated shell unavailable",
                    fixture.appState.phase is AppPhase.Failed,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun localProjectionIsUsefulBeforeReceiverConvergenceCompletes() =
        runBlocking {
            val account =
                AccountSummaryFfi(
                    label = "account-a",
                    accountIdHex = "a".repeat(64),
                    localSigning = false,
                    externalSigning = true,
                    signedOut = false,
                    running = true,
                )
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyBlockSubscriptions = true,
                    receiverTimeoutMillis = 60_000L,
                    accounts = listOf(account),
                    chatListRows = listOf(namedGroupRow()),
                )
            val bootstrap = async { fixture.bootstrap() }
            try {
                withTimeout(5_000L) {
                    while (fixture.appState.phase != AppPhase.Ready) yield()
                }

                assertEquals(account.label, fixture.appState.activeAccountRef)
                assertEquals(
                    "the active account signer must be restored before the shell becomes operational",
                    1,
                    fixture.signerRegistrationCalls.get(),
                )
                assertTrue("receiver convergence should still be pending", bootstrap.isActive)
                assertRestoredShell(observeRestoredShell(fixture, account.label))
                assertStartupCriticalPath(fixture)
            } finally {
                fixture.allowSubscriptions()
                bootstrap.cancelAndJoin()
                fixture.close()
            }
        }

    private fun observeRestoredShell(
        fixture: NotificationBootstrapTestFixture,
        accountRef: String,
    ): RestoredShellObservation {
        var observation: RestoredShellObservation? = null
        Handler(Looper.getMainLooper()).post {
            val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
            try {
                val ready =
                    holder.prepareFirstUsefulFrame(
                        phase = fixture.appState.phase,
                        activeAccountRef = accountRef,
                        runtimeGeneration = fixture.appState.runtimeGeneration,
                        appLockScreenVisible = false,
                    )
                val controller = holder.chatsController(accountRef, fixture.appState.runtimeGeneration)
                observation =
                    RestoredShellObservation(
                        ready = ready,
                        localSnapshotLoaded = controller.hasLoadedLocalSnapshot,
                        title =
                            controller.items
                                .single()
                                .group.name,
                    )
            } finally {
                holder.release()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(observation)
    }

    private fun assertRestoredShell(observation: RestoredShellObservation) {
        assertTrue("the restored shell must be prepared before routing", observation.ready)
        assertTrue("startup must seed an authoritative local snapshot", observation.localSnapshotLoaded)
        assertEquals("Persisted release room", observation.title)
    }

    private fun assertStartupCriticalPath(fixture: NotificationBootstrapTestFixture) {
        assertEquals(1, fixture.directChatListCalls.get())
        assertEquals(0, fixture.localSnapshotSubscriptionCalls.get())
        assertEquals(0, fixture.localSnapshotGroupSubscriptionCalls.get())
        assertEquals(0, fixture.localSnapshotReadCalls.get())
        assertEquals("the first frame must not wait for member enrichment", 0, fixture.memberProjectionCalls.get())
    }

    private data class RestoredShellObservation(
        val ready: Boolean,
        val localSnapshotLoaded: Boolean,
        val title: String,
    )

    private fun namedGroupRow() =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = "1".repeat(64),
            archived = false,
            pendingConfirmation = false,
            title = "Persisted release room",
            groupName = "Persisted release room",
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
}
