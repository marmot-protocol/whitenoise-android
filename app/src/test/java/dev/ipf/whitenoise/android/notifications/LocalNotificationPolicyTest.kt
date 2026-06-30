package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.state.NotificationSuppression
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationPolicyTest {
    @Test
    fun foregroundActiveConversationNotificationIsSuppressed() {
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "active-group", accountRef = "account-a"),
                appInForeground = true,
                activeConversationGroupIdHex = "active-group",
                activeConversationAccountRef = "account-a",
            ),
        )
    }

    @Test
    fun foregroundOtherConversationNotificationStillPosts() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "other-group", accountRef = "account-a"),
                appInForeground = true,
                activeConversationGroupIdHex = "active-group",
                activeConversationAccountRef = "account-a",
            ),
        )
    }

    @Test
    fun foregroundSameGroupDifferentAccountStillPosts() {
        // Both local accounts belong to "active-group". Account A is viewing it;
        // a notification for account B in the same group must NOT be suppressed.
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "active-group", accountRef = "account-b"),
                appInForeground = true,
                activeConversationGroupIdHex = "active-group",
                activeConversationAccountRef = "account-a",
            ),
        )
    }

    @Test
    fun backgroundActiveConversationNotificationStillPosts() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "active-group", accountRef = "account-a"),
                appInForeground = false,
                activeConversationGroupIdHex = "active-group",
                activeConversationAccountRef = "account-a",
            ),
        )
    }

    // End-to-end lifecycle checks (issue #821): drive the suppression state
    // through the reported sequences and assert the post decision, so the policy
    // and the lifecycle transitions are pinned together.

    @Test
    fun chatBackgroundedThenSwipedAwayStillNotifiesThatChat() {
        // Open chat A → background → swipe-away from recents.
        val state =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "active-group", accountRef = "account-a")
                .onBackground()
                .onTaskRemoved()
        assertTrue(post(state, groupIdHex = "active-group", accountRef = "account-a"))
    }

    @Test
    fun chatBackgroundedThenSwipedAwayStillNotifiesOtherChat() {
        val state =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "active-group", accountRef = "account-a")
                .onBackground()
                .onTaskRemoved()
        assertTrue(post(state, groupIdHex = "other-group", accountRef = "account-a"))
    }

    @Test
    fun foregroundedChatStillSuppressesItsOwnMessages() {
        val state =
            NotificationSuppression()
                .onForeground()
                .onActiveConversation(groupIdHex = "active-group", accountRef = "account-a")
        assertFalse(post(state, groupIdHex = "active-group", accountRef = "account-a"))
    }

    private fun post(
        state: NotificationSuppression,
        groupIdHex: String,
        accountRef: String,
    ): Boolean =
        LocalNotificationPolicy.shouldPost(
            update(groupIdHex = groupIdHex, accountRef = accountRef),
            appInForeground = state.inForeground,
            activeConversationGroupIdHex = state.activeConversationGroupIdHex,
            activeConversationAccountRef = state.activeConversationAccountRef,
        )

    private fun update(
        groupIdHex: String,
        accountRef: String = "account",
    ) = NotificationUpdateFfi(
        isMention = false,
        notificationKey = "message:$accountRef:message",
        conversationKey = "conversation:$accountRef:$groupIdHex",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        accountRef = accountRef,
        accountIdHex = accountRef,
        groupIdHex = groupIdHex,
        groupName = "General",
        isDm = false,
        messageIdHex = "message",
        sender = user(),
        receiver = user(accountIdHex = accountRef, displayName = "Me"),
        previewText = "Hello",
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = 1234,
        isFromSelf = false,
    )

    private fun user(
        accountIdHex: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )
}
