package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.state.ChatNotifyMode
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
                appLockScreenVisible = false,
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
                appLockScreenVisible = false,
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
                appLockScreenVisible = false,
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
                appLockScreenVisible = false,
            ),
        )
    }

    @Test
    fun appLockScreenVisibleNotificationIsSuppressed() {
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "other-group", accountRef = "account-a"),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = true,
            ),
        )
    }

    @Test
    fun mutedConversationNotificationIsSuppressed() {
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "muted-group", accountRef = "account-a"),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { accountRef, groupIdHex ->
                    if (accountRef == "account-a" && groupIdHex == "muted-group") {
                        ChatNotifyMode.NONE
                    } else {
                        ChatNotifyMode.ALL
                    }
                },
            ),
        )
    }

    @Test
    fun mutedConversationOnOtherAccountStillPosts() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "muted-group", accountRef = "account-b"),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { accountRef, groupIdHex ->
                    if (accountRef == "account-a" && groupIdHex == "muted-group") {
                        ChatNotifyMode.NONE
                    } else {
                        ChatNotifyMode.ALL
                    }
                },
            ),
        )
    }

    @Test
    fun mentionsOnlyConversationPostsMention() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "quiet-group", accountRef = "account-a", isMention = true),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { _, _ -> ChatNotifyMode.MENTIONS_ONLY },
            ),
        )
    }

    @Test
    fun mentionsOnlyConversationSuppressesNonMention() {
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "quiet-group", accountRef = "account-a", isMention = false),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { _, _ -> ChatNotifyMode.MENTIONS_ONLY },
            ),
        )
    }

    @Test
    fun nothingModeSuppressesMentionToo() {
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "muted-group", accountRef = "account-a", isMention = true),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { _, _ -> ChatNotifyMode.NONE },
            ),
        )
    }

    @Test
    fun engineMutedConversationIsSuppressedEvenForMentions() {
        // The engine's durable mute converges from other devices and is a full
        // mute: it must win over a permissive local mode, mentions included.
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "muted-group", accountRef = "account-a", isMention = true),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { _, _ -> ChatNotifyMode.ALL },
                engineMuted = true,
            ),
        )
    }

    @Test
    fun engineUnmutedConversationKeepsLocalModeDecision() {
        // Engine unmuted must not loosen a stricter local mode: NONE still
        // suppresses, and ALL still posts.
        assertFalse(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "muted-group", accountRef = "account-a"),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { _, _ -> ChatNotifyMode.NONE },
                engineMuted = false,
            ),
        )
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "other-group", accountRef = "account-a"),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { _, _ -> ChatNotifyMode.ALL },
                engineMuted = false,
            ),
        )
    }

    @Test
    fun removalNotificationIgnoresConversationMutes() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(
                    groupIdHex = "removed-group",
                    accountRef = "account-a",
                    trigger = NotificationTriggerFfi.REMOVED_FROM_GROUP,
                ),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
                conversationNotifyMode = { _, _ -> ChatNotifyMode.NONE },
                engineMuted = true,
            ),
        )
    }

    @Test
    fun unsupportedAdminRoleNotificationsFailClosed() {
        listOf(
            NotificationTriggerFfi.MADE_ADMIN,
            NotificationTriggerFfi.REMOVED_AS_ADMIN,
        ).forEach { trigger ->
            assertFalse(
                LocalNotificationPolicy.shouldPost(
                    update(
                        groupIdHex = "admin-role-group",
                        accountRef = "account-a",
                        trigger = trigger,
                    ),
                    appInForeground = false,
                    activeConversationGroupIdHex = null,
                    activeConversationAccountRef = null,
                    appLockScreenVisible = false,
                    conversationNotifyMode = { _, _ -> ChatNotifyMode.ALL },
                ),
            )
        }
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
            appLockScreenVisible = false,
        )

    private fun update(
        groupIdHex: String,
        accountRef: String = "account",
        isMention: Boolean = false,
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
    ) = NotificationUpdateFfi(
        isMention = isMention,
        notificationKey = "message:$accountRef:message",
        conversationKey = "conversation:$accountRef:$groupIdHex",
        trigger = trigger,
        trafficClass = dev.ipf.marmotkit.NotificationTrafficClassFfi.STANDARD,
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
