package dev.ipf.whitenoise.android.state

import android.app.Application
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Collections

/** Exercises the remembered account's chat-list read that runs beside native startup. */
@RunWith(RobolectricTestRunner::class)
class StartupChatListPrefetchTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    /** The remembered account's rows are read while `start()` is still blocked, and activation does not read again. */
    @Test
    fun rememberedRowsAreReadBeforeRuntimeStartReturnsAndSeedActivation() =
        runBlocking {
            val reads = Collections.synchronizedList(mutableListOf<String>())
            val fixture = fixture(blockStart = true) { accountRef -> reads += accountRef }
            try {
                val bootstrap = async { fixture.bootstrap() }
                try {
                    // Both happen while the held start() has not returned.
                    withTimeout(5_000) { while (reads.isEmpty() || fixture.runtimeStartCalls.get() == 0) yield() }
                    assertEquals(listOf("self"), reads.toList())
                    assertEquals(AppPhase.Bootstrapping, fixture.appState.phase)
                } finally {
                    fixture.allowRuntimeStart()
                }
                bootstrap.await()
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals(listOf("self"), reads.toList())
                assertEquals(listOf(GROUP), seededGroupIds(fixture))
            } finally {
                fixture.close()
            }
        }

    /** A prefetch for an account that startup does not activate is dropped, and the activated account reads its own. */
    @Test
    fun prefetchForAnotherAccountIsDiscarded() =
        runBlocking {
            val reads = Collections.synchronizedList(mutableListOf<String>())
            val speculativeRead = CompletableDeferred<Unit>()
            val fixture =
                fixture(blockStart = true, rememberedAccount = "removed") { accountRef ->
                    reads += accountRef
                    if (accountRef == "removed") speculativeRead.complete(Unit)
                }
            try {
                val bootstrap = async { fixture.bootstrap() }
                try {
                    withTimeout(5_000) { speculativeRead.await() }
                } finally {
                    fixture.allowRuntimeStart()
                }
                bootstrap.await()
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("self", fixture.appState.activeAccountRef)
                assertEquals(listOf("removed", "self"), reads.toList())
                assertEquals(listOf(GROUP), seededGroupIds(fixture))
            } finally {
                fixture.close()
            }
        }

    /** A failed prefetch leaves activation's own read in place. */
    @Test
    fun failedPrefetchFallsBackToTheActivationRead() =
        runBlocking {
            val reads = Collections.synchronizedList(mutableListOf<String>())
            val fixture =
                fixture { accountRef ->
                    reads += accountRef
                    if (reads.size == 1) error("synthetic prefetch failure")
                }
            try {
                fixture.bootstrap()
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals(listOf("self", "self"), reads.toList())
                assertEquals(listOf(GROUP), seededGroupIds(fixture))
            } finally {
                fixture.close()
            }
        }

    /** Group ids of the one-shot snapshot that startup activation handed to the chat list. */
    private fun seededGroupIds(fixture: NotificationBootstrapTestFixture) =
        fixture.appState
            .consumeAccountSwitchLocalSnapshot("self")
            ?.rows
            ?.map(ChatListRowFfi::groupIdHex)

    /** A one-account bootstrap whose presented chat-list reads report their account to [onRead]. */
    private fun fixture(
        blockStart: Boolean = false,
        rememberedAccount: String = "self",
        onRead: (String) -> Unit,
    ) = NotificationBootstrapTestFixture(
        context = context,
        initiallyBlockRuntimeStartSynchronously = blockStart,
        accounts = listOf(AccountSummaryFfi("self", SELF, true, false, false, true, false)),
        initialActiveAccountRef = rememberedAccount,
        emitStartupNotification = false,
        localDisplayName = null,
        onUserProfile = { null },
        onPresentedChatList = { accountRef ->
            onRead(accountRef)
            listOf(row())
        },
    )

    /** A minimal stored group row; its id is the only value these tests compare. */
    private fun row() =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP,
            archived = false,
            pendingConfirmation = false,
            title = "Stored",
            groupName = "Stored",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            leaveBlocked = false,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private companion object {
        val SELF = "11".repeat(32)
        val GROUP = "aa".repeat(32)
    }
}
