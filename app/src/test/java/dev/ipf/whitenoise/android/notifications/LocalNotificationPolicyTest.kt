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
    fun adminRoleNotificationsPostWhenTheConversationAllowsNotifications() {
        listOf(
            NotificationTriggerFfi.MADE_ADMIN,
            NotificationTriggerFfi.REMOVED_AS_ADMIN,
        ).forEach { trigger ->
            assertTrue(
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

    @Test
    fun adminRoleNotificationsRespectConversationMutes() {
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
                    conversationNotifyMode = { _, _ -> ChatNotifyMode.NONE },
                    engineMuted = true,
                ),
            )
        }
    }

    @Test
    fun adminRoleNotificationsNeverPostForDirectMessages() {
        listOf(
            NotificationTriggerFfi.MADE_ADMIN,
            NotificationTriggerFfi.REMOVED_AS_ADMIN,
        ).forEach { trigger ->
            assertFalse(
                LocalNotificationPolicy.shouldPost(
                    update(
                        groupIdHex = "direct-message",
                        trigger = trigger,
                        isDm = true,
                    ),
                    appInForeground = false,
                    activeConversationGroupIdHex = null,
                    activeConversationAccountRef = null,
                    appLockScreenVisible = false,
                ),
            )
        }
    }

    /** A muted member's ordinary message, mention and reaction all go quiet in that one group. */
    @Test
    fun mutedMemberMessagesMentionsAndReactionsAreSuppressedInThatGroup() {
        assertFalse(postsWithMutedMember(update(groupIdHex = "muted-member-group", accountRef = "account-a")))
        assertFalse(
            postsWithMutedMember(
                update(groupIdHex = "muted-member-group", accountRef = "account-a", isMention = true),
            ),
        )
        assertFalse(
            postsWithMutedMember(
                update(groupIdHex = "muted-member-group", accountRef = "account-a", reactionEmoji = "👍"),
            ),
        )
    }

    /** Muting a member is scoped to one group, so the same person still notifies elsewhere. */
    @Test
    fun mutedMemberStillNotifiesInAnotherGroup() {
        assertTrue(postsWithMutedMember(update(groupIdHex = "other-group", accountRef = "account-a")))
    }

    /** Muting a member belongs to one local account, so a second account in the same group is unaffected. */
    @Test
    fun mutedMemberStillNotifiesAnotherLocalAccountInTheSameGroup() {
        assertTrue(postsWithMutedMember(update(groupIdHex = "muted-member-group", accountRef = "account-b")))
    }

    /** Only the muted member goes quiet; every other member of that group still notifies. */
    @Test
    fun otherMembersOfTheMutedGroupStillNotify() {
        assertTrue(
            postsWithMutedMember(
                update(
                    groupIdHex = "muted-member-group",
                    accountRef = "account-a",
                    senderIdHex = OTHER_SENDER_ID,
                ),
            ),
        )
    }

    /** Membership and admin events are safety-critical, so a per-member mute never touches them. */
    @Test
    fun mutedMemberDoesNotSuppressMembershipOrAdminEvents() {
        listOf(
            NotificationTriggerFfi.REMOVED_FROM_GROUP,
            NotificationTriggerFfi.MADE_ADMIN,
            NotificationTriggerFfi.REMOVED_AS_ADMIN,
            NotificationTriggerFfi.GROUP_INVITE,
        ).forEach { trigger ->
            assertTrue(
                "per-member mute must not suppress $trigger",
                postsWithMutedMember(
                    update(groupIdHex = "muted-member-group", accountRef = "account-a", trigger = trigger),
                ),
            )
        }
    }

    /** An update with no sender identity fails open rather than being silenced on another member's behalf. */
    @Test
    fun updateWithoutSenderIdentityFailsOpen() {
        val mutesEverything: GroupSenderMutePredicate = { _, _, _ -> true }
        listOf("", "   ").forEach { blankSender ->
            assertTrue(
                "a blank sender identity must fail open",
                postsWithMutedMember(
                    update(groupIdHex = "muted-member-group", accountRef = "account-a", senderIdHex = blankSender),
                    senderMutedInGroup = mutesEverything,
                ),
            )
        }
    }

    /** Direct messages have no per-member dimension, so a stray entry can never silence one. */
    @Test
    fun directMessagesAreNeverSuppressedByAPerMemberMute() {
        val mutesEverything: GroupSenderMutePredicate = { _, _, _ -> true }
        assertTrue(
            postsWithMutedMember(
                update(groupIdHex = "direct-message", accountRef = "account-a", isDm = true),
                senderMutedInGroup = mutesEverything,
            ),
        )
    }

    /** With no per-member mute configured, the default lookup leaves every message posting. */
    @Test
    fun defaultSenderMuteLookupSuppressesNothing() {
        assertTrue(
            LocalNotificationPolicy.shouldPost(
                update(groupIdHex = "muted-member-group", accountRef = "account-a"),
                appInForeground = false,
                activeConversationGroupIdHex = null,
                activeConversationAccountRef = null,
                appLockScreenVisible = false,
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
            appLockScreenVisible = false,
        )

    private fun update(
        groupIdHex: String,
        accountRef: String = "account",
        isMention: Boolean = false,
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
        isDm: Boolean = false,
        senderIdHex: String = DEFAULT_SENDER_ID,
        reactionEmoji: String? = null,
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
        isDm = isDm,
        messageIdHex = "message",
        sender = user(accountIdHex = senderIdHex),
        receiver = user(accountIdHex = accountRef, displayName = "Me"),
        previewText = "Hello",
        reactionEmoji = reactionEmoji,
        reactedToPreview = null,
        timestampMs = 1234,
        isFromSelf = false,
    )

    private fun user(
        accountIdHex: String = DEFAULT_SENDER_ID,
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )

    /** Silences exactly one account/group/member triple, matching the stored preference's scope. */
    private fun mutedMember(
        accountRef: String = "account-a",
        groupIdHex: String = "muted-member-group",
        senderIdHex: String = DEFAULT_SENDER_ID,
    ): GroupSenderMutePredicate =
        { candidateAccount, candidateGroup, candidateSender ->
            candidateAccount == accountRef && candidateGroup == groupIdHex && candidateSender == senderIdHex
        }

    /** Evaluates [update] with a background app and no active conversation, so only mutes can decide. */
    private fun postsWithMutedMember(
        update: NotificationUpdateFfi,
        senderMutedInGroup: GroupSenderMutePredicate = mutedMember(),
    ): Boolean =
        LocalNotificationPolicy.shouldPost(
            update = update,
            appInForeground = false,
            activeConversationGroupIdHex = null,
            activeConversationAccountRef = null,
            appLockScreenVisible = false,
            senderMutedInGroup = senderMutedInGroup,
        )

    private companion object {
        const val DEFAULT_SENDER_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val OTHER_SENDER_ID = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
}
