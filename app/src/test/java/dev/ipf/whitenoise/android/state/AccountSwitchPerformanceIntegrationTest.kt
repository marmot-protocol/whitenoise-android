package dev.ipf.whitenoise.android.state

import android.app.Application
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
            } finally {
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

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        val ACCOUNT_A_ID = "aa".repeat(32)
        val ACCOUNT_B_ID = "bb".repeat(32)
    }
}
