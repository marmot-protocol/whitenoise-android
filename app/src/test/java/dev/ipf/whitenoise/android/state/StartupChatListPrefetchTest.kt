package dev.ipf.whitenoise.android.state

import android.app.Application
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Retrying after native startup succeeds reads the current rows instead of reusing the failed attempt. */
    @Test
    fun retryAfterAccountRefreshFailureReadsRowsAgain() =
        runBlocking {
            val reads = Collections.synchronizedList(mutableListOf<String>())
            val firstRead = CompletableDeferred<Unit>()
            val failRefresh = AtomicBoolean(true)
            val fixture =
                fixture(
                    blockStart = true,
                    onListAccountsAfterStart = {
                        if (failRefresh.getAndSet(false)) error("synthetic account refresh failure")
                    },
                ) { accountRef ->
                    reads += accountRef
                    firstRead.complete(Unit)
                }
            try {
                val bootstrap = async { fixture.bootstrap() }
                try {
                    withTimeout(5_000) { firstRead.await() }
                } finally {
                    fixture.allowRuntimeStart()
                }
                bootstrap.await()
                assertTrue(fixture.appState.phase is AppPhase.Failed)
                assertEquals(listOf("self"), reads.toList())

                fixture.retryBootstrap()

                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(listOf("self", "self"), reads.toList())
                assertEquals(listOf(GROUP), seededGroupIds(fixture))
            } finally {
                fixture.close()
            }
        }

    /** Startup failure discards a pending read even if the native read returns later. */
    @Test
    fun failedRuntimeStartCancelsThePendingPrefetch() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val allowReadToFinish = CountDownLatch(1)
            val fixture =
                fixture(blockStart = true, onRuntimeStart = { error("synthetic start failure") }) {
                    entered.complete(Unit)
                    check(allowReadToFinish.await(10, TimeUnit.SECONDS))
                }
            try {
                val bootstrap = async { fixture.bootstrap() }
                try {
                    withTimeout(5_000) { entered.await() }
                    val slot = fixture.appState.accountSwitchHandoff.startupPrefetch
                    val pending = checkNotNull(slot.take())
                    slot.replace(pending)

                    fixture.allowRuntimeStart()
                    bootstrap.await()
                    assertTrue(fixture.appState.phase is AppPhase.Failed)
                    assertNull(slot.take())

                    allowReadToFinish.countDown()
                    val cancellation = withTimeout(5_000) { runCatching { pending.await() }.exceptionOrNull() }
                    assertTrue(cancellation is CancellationException)
                } finally {
                    fixture.allowRuntimeStart()
                    allowReadToFinish.countDown()
                }
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
        onRuntimeStart: (() -> Unit)? = null,
        onListAccountsAfterStart: (() -> Unit)? = null,
        onRead: (String) -> Unit,
    ) = NotificationBootstrapTestFixture(
        context = context,
        initiallyBlockRuntimeStartSynchronously = blockStart,
        accounts = listOf(AccountSummaryFfi("self", SELF, true, false, false, true, false)),
        initialActiveAccountRef = rememberedAccount,
        emitStartupNotification = false,
        localDisplayName = null,
        onRuntimeStart = onRuntimeStart,
        onListAccountsAfterStart = onListAccountsAfterStart,
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
