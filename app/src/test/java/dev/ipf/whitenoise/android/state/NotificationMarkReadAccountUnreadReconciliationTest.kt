package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executing regression coverage for the secondary-account unread dot after a
 * notification-action mark-read: the acting account's per-account aggregate
 * must reconcile through the same coalesced refresh path inbound notification
 * updates use, without an account switch, an inbound notification, or a
 * process restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationMarkReadAccountUnreadReconciliationTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Volatile
    private var backgroundRows: List<ChatListRowFfi> = listOf(unreadRow())

    @Volatile
    private var activeRows: List<ChatListRowFfi> = emptyList()

    @Volatile
    private var backgroundChatListFailure: Throwable? = null

    @Volatile
    private var markReadReturnsRow = true

    private val backgroundChatListCalls = AtomicInteger(0)
    private val activeChatListCalls = AtomicInteger(0)

    private fun fixture() =
        NotificationBootstrapTestFixture(
            context = appContext,
            accounts =
                listOf(
                    account(ACTIVE_ACCOUNT, ACTIVE_ACCOUNT_ID),
                    account(BACKGROUND_ACCOUNT, BACKGROUND_ACCOUNT_ID),
                ),
            onChatList = { accountRef ->
                when (accountRef) {
                    BACKGROUND_ACCOUNT -> {
                        backgroundChatListCalls.incrementAndGet()
                        backgroundChatListFailure?.let { throw it }
                        backgroundRows
                    }
                    ACTIVE_ACCOUNT -> {
                        activeChatListCalls.incrementAndGet()
                        activeRows
                    }
                    else -> emptyList()
                }
            },
            onMarkTimelineMessageRead = { readRow().takeIf { markReadReturnsRow } },
        )

    /** A background mark-read converges its unread dot without switching accounts or receiving another event. */
    @Test
    fun backgroundAccountMarkReadClearsItsDotWithoutSwitchOrInboundNotification() =
        runTest {
            withFixture { fixture ->
                val appState = fixture.appState
                fixture.bootstrapAndRefresh()
                awaitCondition("background dot lights from its own unread") {
                    appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }

                backgroundRows = listOf(readRow())
                val markedRead =
                    pumpingMainLooper {
                        appState.markNotificationMessageRead(BACKGROUND_ACCOUNT, GROUP_ID, MESSAGE_ID)
                    }

                assertTrue(markedRead)
                assertEquals("no account switch may be required", ACTIVE_ACCOUNT, appState.activeAccountRef)
                awaitCondition("background dot clears from the authoritative refresh") {
                    !appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }
                assertEquals(0uL, appState.confirmedUnreadCountForAccount(BACKGROUND_ACCOUNT))
            }
        }

    /** A background mark-read publishes the authoritative unread remainder instead of assuming zero. */
    @Test
    fun backgroundAccountMarkReadReflectsRemainingAuthoritativeUnread() =
        runTest {
            withFixture { fixture ->
                val appState = fixture.appState
                fixture.bootstrapAndRefresh()
                awaitCondition("background dot lights from its own unread") {
                    appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }

                backgroundRows = listOf(unreadRow(unreadCount = 1uL))
                val markedRead =
                    pumpingMainLooper {
                        appState.markNotificationMessageRead(BACKGROUND_ACCOUNT, GROUP_ID, MESSAGE_ID)
                    }

                assertTrue(markedRead)
                awaitCondition("the post-action count replaces the pre-action value") {
                    appState.confirmedUnreadCountForAccount(BACKGROUND_ACCOUNT) == 1uL
                }
                assertTrue(appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT))
            }
        }

    /** A background-account action cannot clear independently retained manual attention on another account. */
    @Test
    fun markReadOnOneAccountNeverTouchesAnotherAccountsDot() =
        runTest {
            withFixture { fixture ->
                val appState = fixture.appState
                fixture.bootstrapAndRefresh()
                awaitCondition("background dot lights from its own unread") {
                    appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }
                // Manually flag the active account so it holds observable dot state
                // that a background-account action must not clear.
                appState.updateAccountManualUnread(ACTIVE_ACCOUNT, hasManualUnread = true)
                assertTrue(appState.accountShowsUnreadDot(ACTIVE_ACCOUNT))

                backgroundRows = listOf(readRow())
                pumpingMainLooper {
                    appState.markNotificationMessageRead(BACKGROUND_ACCOUNT, GROUP_ID, MESSAGE_ID)
                }

                awaitCondition("background dot clears") {
                    !appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }
                assertTrue(
                    "an action on one account must not clear another account's dot",
                    appState.accountShowsUnreadDot(ACTIVE_ACCOUNT),
                )
            }
        }

    /** Failed reconciliation hides a known-stale badge while retaining its evidence for a later refresh. */
    @Test
    fun refreshFailureAfterMarkReadStopsPresentingTheStaleConfirmedCount() =
        runTest {
            withFixture { fixture ->
                val appState = fixture.appState
                fixture.bootstrapAndRefresh()
                awaitCondition("background dot lights from its own unread") {
                    appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }
                assertEquals(2uL, appState.confirmedUnreadCountForAccount(BACKGROUND_ACCOUNT))

                backgroundChatListFailure = RuntimeException("refresh unavailable")
                val markedRead =
                    pumpingMainLooper {
                        appState.markNotificationMessageRead(BACKGROUND_ACCOUNT, GROUP_ID, MESSAGE_ID)
                    }

                assertTrue(markedRead)
                // The known-stale value degrades to retained-but-not-presented
                // instead of staying confirmed, so a failed refresh cannot keep a
                // stale dot lit.
                assertFalse(appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT))
                assertEquals(0uL, appState.confirmedUnreadCountForAccount(BACKGROUND_ACCOUNT))
                assertEquals(
                    "retained evidence must survive for reconciliation",
                    2uL,
                    appState.unreadCountForAccount(BACKGROUND_ACCOUNT),
                )
            }
        }

    /** A missing folded row still schedules authoritative reconciliation without a premature stale transition. */
    @Test
    fun nullMarkReadRowStillReconcilesWithoutDegradingPresentationFirst() =
        runTest {
            withFixture { fixture ->
                val appState = fixture.appState
                fixture.bootstrapAndRefresh()
                awaitCondition("background dot lights from its own unread") {
                    appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }

                markReadReturnsRow = false
                backgroundRows = listOf(readRow())
                val refreshesBefore = backgroundChatListCalls.get()
                val markedRead =
                    pumpingMainLooper {
                        appState.markNotificationMessageRead(BACKGROUND_ACCOUNT, GROUP_ID, MESSAGE_ID)
                    }

                assertTrue(markedRead)
                awaitCondition("an authoritative refresh still runs") {
                    backgroundChatListCalls.get() > refreshesBefore
                }
                awaitCondition("the refresh converges the dot") {
                    !appState.accountShowsUnreadDot(BACKGROUND_ACCOUNT)
                }
            }
        }

    /** An active account with a bound controller folds once and does not enqueue a duplicate refresh. */
    @Test
    fun activeAccountMarkReadFoldsTheRowOnceWithoutASecondScheduledRefresh() =
        runTest {
            activeRows = listOf(unreadRow())
            withFixture { fixture ->
                val appState = fixture.appState
                fixture.bootstrapAndRefresh()
                val controller =
                    ChatsController(
                        appState = appState,
                        initialAccountRef = ACTIVE_ACCOUNT,
                        memberSnapshotLoader = { _, _ -> emptyList() },
                    )
                appState.attachChatsController(controller)
                try {
                    controller.applyChatListRow(unreadRow())
                    awaitCondition("the active controller projects the seeded unread row") {
                        controller.items.singleOrNull()?.unreadCount == 2uL
                    }
                    // This controller has no bind/subscription writer. Drain the seeded
                    // projection before measuring the returned mark-read row's fold.
                    pumpingMainLooper { delay(200L) }
                    val refreshesBefore = activeChatListCalls.get()
                    val projectionBefore = controller.forwardTargetsRevision

                    val markedRead =
                        pumpingMainLooper {
                            appState.markNotificationMessageRead(ACTIVE_ACCOUNT, GROUP_ID, MESSAGE_ID)
                        }

                    assertTrue(markedRead)
                    awaitCondition("the returned read row replaces the active unread row") {
                        controller.items.singleOrNull()?.unreadCount == 0uL
                    }
                    // Give the coalesced refresh scheduler several drain windows: a
                    // second refresh for the folded account would surface here.
                    pumpingMainLooper { delay(200L) }
                    assertEquals(
                        "the returned row produces one controller projection",
                        projectionBefore + 1L,
                        controller.forwardTargetsRevision,
                    )
                    assertEquals(0uL, appState.confirmedUnreadCountForAccount(ACTIVE_ACCOUNT))
                    assertEquals(
                        "a bound-controller fold must not also schedule a per-account refresh",
                        refreshesBefore,
                        activeChatListCalls.get(),
                    )
                } finally {
                    appState.attachChatsController(null)
                    controller.onCleared()
                }
            }
        }

    /** Closes each fixture even when an assertion or awaited production callback fails. */
    private suspend fun withFixture(block: suspend (NotificationBootstrapTestFixture) -> Unit) {
        val fixture = fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    /** Bootstraps the fixture and runs one explicit account refresh so the seeded chat-list truth is confirmed. */
    private suspend fun NotificationBootstrapTestFixture.bootstrapAndRefresh() {
        bootstrap()
        pumpingMainLooper { appState.refreshAccounts() }
    }

    /**
     * Runs [block] off the Robolectric main thread while pumping the paused
     * main looper, mirroring [NotificationBootstrapTestFixture]'s bootstrap
     * driver so production Main-dispatched work can progress.
     */
    private suspend fun <T> pumpingMainLooper(block: suspend () -> T): T =
        coroutineScope {
            val call = async(Dispatchers.Default) { block() }
            try {
                while (!call.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
                shadowOf(Looper.getMainLooper()).idle()
                call.await()
            } finally {
                call.cancel()
            }
        }

    private suspend fun awaitCondition(
        description: String,
        condition: () -> Boolean,
    ) {
        withTimeout(10_000L) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5L))
                delay(5L)
            }
        }
        assertTrue(description, condition())
    }

    private fun account(
        label: String,
        accountIdHex: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    private fun unreadRow(unreadCount: ULong = 2uL) = chatRow(unreadCount = unreadCount)

    private fun readRow() = chatRow(unreadCount = 0uL)

    private fun chatRow(unreadCount: ULong) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = "Chat",
            groupName = "",
            avatarUrl = null,
            avatar = null,
            lastMessage =
                ChatListMessagePreviewFfi(
                    messageIdHex = MESSAGE_ID,
                    sender = "sender",
                    senderDisplayName = "Sender",
                    plaintext = "hello",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    timelineAt = 100uL,
                    deleted = false,
                    attachmentKind = null,
                    attachmentCount = 0u,
                    deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                ),
            unreadCount = unreadCount,
            hasUnread = unreadCount > 0uL,
            firstUnreadMessageIdHex = MESSAGE_ID.takeIf { unreadCount > 0uL },
            lastReadMessageIdHex = MESSAGE_ID.takeIf { unreadCount == 0uL },
            lastReadTimelineAt = 100uL.takeIf { unreadCount == 0uL },
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 100uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private companion object {
        const val ACTIVE_ACCOUNT = "account-a"
        const val BACKGROUND_ACCOUNT = "account-b"
        val ACTIVE_ACCOUNT_ID = "aa".repeat(32)
        val BACKGROUND_ACCOUNT_ID = "bb".repeat(32)
        val GROUP_ID = "cd".repeat(32)
        val MESSAGE_ID = "ef".repeat(32)
    }
}
