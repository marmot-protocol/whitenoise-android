package dev.ipf.whitenoise.android.state

import android.app.Application
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountSwitchPerformanceIntegrationTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun ordinarySwitchPublishesTwoHundredNamedRowsWithoutLoadingTheirRosters() =
        runBlocking {
            val rows = (1..200).map(::namedRow)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A, ACCOUNT_A_ID), account(ACCOUNT_B, ACCOUNT_B_ID)),
                    chatListRows = rows,
                )

            try {
                fixture.bootstrap()
                val directReadsBeforeSwitch = fixture.directChatListCalls.get()
                var snapshotAtActivation: AccountSwitchLocalSnapshot? = null
                var profileTitleAtActivation: String? = null
                val activated =
                    withTimeout(5_000L) {
                        fixture.appState.setActiveAccount(
                            ACCOUNT_B,
                            onActivated = {
                                snapshotAtActivation =
                                    fixture.appState.consumeAccountSwitchLocalSnapshot(ACCOUNT_B)
                                profileTitleAtActivation = fixture.appState.chatMemberTitleCached(ACCOUNT_A_ID)
                            },
                        )
                    }

                assertTrue(activated)
                assertEquals(ACCOUNT_B, fixture.appState.activeAccountRef)
                assertEquals(
                    "named rows already carry first-frame identity and must not trigger roster pages",
                    0,
                    fixture.memberProjectionCalls.get(),
                )
                assertEquals(
                    rows,
                    snapshotAtActivation?.rows,
                )
                assertEquals("target profile seeds must be visible at activation", "Alice", profileTitleAtActivation)
                assertEquals(
                    "account activation should need one authoritative row read",
                    directReadsBeforeSwitch + 1,
                    fixture.directChatListCalls.get(),
                )
                assertEquals(
                    "the target controller owns live chat-list admission after activation",
                    0,
                    fixture.localSnapshotSubscriptionCalls.get(),
                )
                assertEquals(
                    "full group projection must not delay active-account publication",
                    0,
                    fixture.localSnapshotGroupSubscriptionCalls.get(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ordinarySwitchPreservesDirectAndUnnamedIdentityWithoutFullGroups() =
        runBlocking {
            val rows =
                (1..100).map(::directRow) +
                    (101..150).map(::unnamedRow) +
                    (151..200).map(::namedRow)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A, ACCOUNT_A_ID), account(ACCOUNT_B, ACCOUNT_B_ID)),
                    chatListRows = rows,
                    onGroupMemberIdsPage = { groupIds ->
                        groupIds.map { groupId -> memberProjection(groupId) }
                    },
                )

            try {
                fixture.bootstrap()
                var snapshotAtActivation: AccountSwitchLocalSnapshot? = null
                val activated =
                    withTimeout(5_000L) {
                        fixture.appState.setActiveAccount(
                            ACCOUNT_B,
                            onActivated = {
                                snapshotAtActivation =
                                    fixture.appState.consumeAccountSwitchLocalSnapshot(ACCOUNT_B)
                            },
                        )
                    }

                assertTrue(activated)
                assertEquals(ACCOUNT_B, fixture.appState.activeAccountRef)
                assertEquals(rows, snapshotAtActivation?.rows)
                assertEquals(150, snapshotAtActivation?.memberIds?.size)
                assertTrue(snapshotAtActivation?.groups?.isEmpty() == true)
                assertEquals("Alice", fixture.appState.chatMemberTitleCached(peerId(1)))
                assertTrue(fixture.memberProjectionCalls.get() > 0)
                assertEquals(0, fixture.localSnapshotGroupSubscriptionCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun supersededSlowRowReadCannotPublishAnOlderTarget() =
        runBlocking {
            val blockedReadStarted = CountDownLatch(1)
            val releaseBlockedRead = CountDownLatch(1)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts =
                        listOf(
                            account(ACCOUNT_A, ACCOUNT_A_ID),
                            account(ACCOUNT_B, ACCOUNT_B_ID),
                            account(ACCOUNT_C, ACCOUNT_C_ID),
                        ),
                    onChatList = { accountRef ->
                        if (accountRef == ACCOUNT_B) {
                            blockedReadStarted.countDown()
                            releaseBlockedRead.await()
                        }
                        listOf(namedRow(1))
                    },
                )

            try {
                fixture.bootstrap()
                val switchToB = async { fixture.appState.setActiveAccount(ACCOUNT_B) }
                withTimeout(1_000L) {
                    while (blockedReadStarted.count > 0L) yield()
                }
                val switchToC = async { fixture.appState.setActiveAccount(ACCOUNT_C) }

                assertTrue(switchToC.await())
                releaseBlockedRead.countDown()
                assertFalse(switchToB.await())
                assertEquals(ACCOUNT_C, fixture.appState.activeAccountRef)
            } finally {
                releaseBlockedRead.countDown()
                fixture.close()
            }
        }

    private fun account(
        label: String,
        id: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = id,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    private fun namedRow(index: Int) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = index.toString(16).padStart(64, '0'),
            archived = false,
            pendingConfirmation = false,
            title = "Group $index",
            groupName = "Group $index",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = index.toULong(),
            activitySortAt = index.toULong(),
            updatedAt = index.toULong(),
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

    private fun directRow(index: Int): ChatListRowFfi =
        namedRow(index).copy(
            title = index.toString(16).padStart(64, '0'),
            groupName = "",
            conversationKind = ChatConversationKindFfi.DIRECT,
        )

    private fun unnamedRow(index: Int): ChatListRowFfi =
        namedRow(index).copy(
            title = index.toString(16).padStart(64, '0'),
            groupName = "",
        )

    private fun memberProjection(groupId: String): AppGroupMemberIdsFfi {
        val index = groupId.toInt(16)
        val memberIds =
            if (index <= 100) {
                listOf(ACCOUNT_B_ID, peerId(index))
            } else {
                listOf(ACCOUNT_B_ID, peerId(index), peerId(index + 1_000))
            }
        return AppGroupMemberIdsFfi(
            groupIdHex = groupId,
            memberIdsHex = memberIds,
            adminIdsHex = emptyList(),
        )
    }

    private fun peerId(index: Int): String = (index + 10_000).toString(16).padStart(64, '0')

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val ACCOUNT_C = "account-c"
        val ACCOUNT_A_ID = "aa".repeat(32)
        val ACCOUNT_B_ID = "bb".repeat(32)
        val ACCOUNT_C_ID = "cc".repeat(32)
    }
}
