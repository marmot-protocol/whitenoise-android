// Fixed ids are regression inputs shared by unit and device tests, not tunable constants.
@file:Suppress("MagicNumber")

package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi

/**
 * One notification route's cast: two local accounts that both belong to a shared conversation, and a
 * backlog whose oldest unread, notified and newest messages are three different ids. Every id differs so
 * a test that lands on the oldest unread row, or on the newest one, cannot pass for the notified one.
 * Plain data over the FFI types, so it compiles into both the unit and the instrumented test sources.
 */
internal object NotificationScenario {
    const val SOURCE_ACCOUNT_REF = "account-a"
    const val TARGET_ACCOUNT_REF = "account-b"
    val SOURCE_ACCOUNT_ID = "a1".repeat(32)
    val TARGET_ACCOUNT_ID = "b2".repeat(32)
    val GROUP_ID = "c3".repeat(32)

    /** The first message the reader had not seen when the conversation opened. */
    val OLDEST_UNREAD_ID = "d4".repeat(32)

    /** The message the tapped card represents, deliberately neither the oldest unread nor the newest. */
    val NOTIFIED_ID = "e5".repeat(32)

    /** The latest message in the conversation, newer than the card's. */
    val NEWEST_ID = "f6".repeat(32)

    /** A signed-in local account summary, running and able to sign. */
    fun account(
        ref: String,
        accountIdHex: String,
    ) = AccountSummaryFfi(
        label = ref,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** The source account that starts active and the target account a notification tap must switch to. */
    fun accounts(): List<AccountSummaryFfi> =
        listOf(
            account(SOURCE_ACCOUNT_REF, SOURCE_ACCOUNT_ID),
            account(TARGET_ACCOUNT_REF, TARGET_ACCOUNT_ID),
        )

    /** A message-card target with named defaults, for taps routed to [accountRef] and [groupIdHex]. */
    fun target(
        accountRef: String = TARGET_ACCOUNT_REF,
        groupIdHex: String = GROUP_ID,
        messageIdHex: String? = NOTIFIED_ID,
        kind: NotificationTargetKind = NotificationTargetKind.MESSAGE,
    ) = NotificationTarget(
        accountRef = accountRef,
        groupIdHex = groupIdHex,
        messageIdHex = messageIdHex,
        kind = kind,
    )

    /**
     * A chat-list row with [unreadCount] unread messages whose first unread id is [firstUnreadMessageIdHex].
     * The default first unread differs from [NOTIFIED_ID], which is the whole point of the scenario.
     */
    fun chatListRow(
        groupIdHex: String = GROUP_ID,
        unreadCount: Int = 3,
        firstUnreadMessageIdHex: String? = OLDEST_UNREAD_ID,
        lastReadMessageIdHex: String? = null,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupIdHex,
        archived = false,
        pendingConfirmation = false,
        title = "Shared group",
        groupName = "Shared group",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = unreadCount.toULong(),
        hasUnread = unreadCount > 0,
        firstUnreadMessageIdHex = firstUnreadMessageIdHex,
        lastReadMessageIdHex = lastReadMessageIdHex,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
        updatedAt = 0uL,
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
