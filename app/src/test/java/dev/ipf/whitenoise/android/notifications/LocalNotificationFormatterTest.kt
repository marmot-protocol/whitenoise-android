package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationFormatterTest {
    @Test
    fun messageNotificationUsesSenderGroupAndPreviewText() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    groupName = "Launch",
                    previewText = "We are go",
                    sender = user(displayName = "Alice"),
                ),
            )

        assertEquals("Alice in Launch", content?.title)
        assertEquals("We are go", content?.body)
        // Per-conversation tag (account|group) is what makes a chat's messages
        // accumulate into a single card.
        assertEquals("account|group", content?.notificationTag)
        assertEquals("Alice", content?.senderName)
        assertTrue(content?.isGroupConversation == true)
        assertEquals("Launch", content?.conversationTitle)
    }

    @Test
    fun directMessageIsNotMarkedAsGroupConversation() {
        val content =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = true, groupName = null),
            )

        assertTrue(content?.isGroupConversation == false)
        assertNull(content?.conversationTitle)
    }

    @Test
    fun inviteNotificationUsesInviteCopy() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.GROUP_INVITE,
                    groupName = null,
                    previewText = null,
                    sender = user(displayName = "Bob"),
                ),
            )

        assertEquals("Group invite", content?.title)
        assertEquals("Invite from Bob", content?.body)
    }

    @Test
    fun autoAcceptedInviteNotificationUsesJoinedCopy() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.GROUP_INVITE,
                    groupName = null,
                    previewText = null,
                    sender = user(displayName = "Bob"),
                ),
                conversationTitleOverride = "Launch",
                groupInviteAutoAccepted = true,
            )

        assertEquals("Joined Launch", content?.title)
        assertEquals("You were invited by Bob", content?.body)
        assertEquals("Launch", content?.conversationTitle)
    }

    @Test
    fun autoAcceptedUnnamedInviteNotificationFallsBackToNewGroupCopy() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.GROUP_INVITE,
                    groupName = null,
                    previewText = null,
                    sender = user(displayName = "Bob"),
                ),
                groupInviteAutoAccepted = true,
            )

        assertEquals("Joined a new group", content?.title)
    }

    @Test
    fun reactionWithPreviewReadsAsAReactionLine() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    reactionEmoji = "👍",
                    reactedToPreview = "Lunch at 1?",
                ),
            )

        assertEquals("reacted 👍 to: \"Lunch at 1?\"", content?.body)
    }

    @Test
    fun reactionWithoutPreviewOmitsTheReactedToClause() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    reactionEmoji = "👍",
                    reactedToPreview = null,
                ),
            )

        assertEquals("reacted 👍", content?.body)
    }

    @Test
    fun reactionGetsItsOwnNotificationIdentityDistinctFromMessages() {
        // A reaction and a normal message in the SAME conversation must not
        // share the (tag, id) Android keys on, or one would mutate the other's
        // card and "mute reactions, keep messages" would break.
        val message =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a"),
            )
        val reaction =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a", reactionEmoji = "👍"),
            )

        assertEquals(LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID, message?.notificationId)
        assertEquals(LocalNotificationFormatter.REACTION_NOTIFICATION_ID, reaction?.notificationId)
        assertNotEquals(message?.notificationId, reaction?.notificationId)
        assertNotEquals(message?.notificationTag, reaction?.notificationTag)
    }

    @Test
    fun reactionsInTheSameConversationShareAReactionCard() {
        // Multiple reactions in one chat collapse onto the same reaction card
        // (distinct from the message card), so they stay independently mute-able
        // without fragmenting into N alerts.
        val first =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a", reactionEmoji = "👍"),
            )
        val second =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a", reactionEmoji = "❤️"),
            )

        assertEquals(first?.notificationTag, second?.notificationTag)
        assertEquals(first?.notificationId, second?.notificationId)
    }

    @Test
    fun reactionTagDoesNotCollideWithTheConversationDismissalKey() {
        // dismissConversationMessages cancels (account|group, id 0). The reaction
        // card must live outside that key so dismissing a conversation's messages
        // does not silently clear its reactions and vice versa.
        val reaction =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a", reactionEmoji = "👍"),
            )
        val dismissal = LocalNotificationFormatter.conversationDismissalKey("account", "group-a")

        assertNotEquals(dismissal.tag, reaction?.notificationTag)
        assertNotEquals(dismissal.id, reaction?.notificationId)
    }

    @Test
    fun reactionDismissalKeyMatchesThePostedReactionIdentity() {
        // Opening a conversation must cancel the exact (tag, id) the reaction
        // card was posted under, or reaction cards linger after read.
        val reaction =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a", reactionEmoji = "👍"),
            )
        val dismissal = LocalNotificationFormatter.reactionDismissalKey("account", "group-a")

        assertEquals(reaction?.notificationTag, dismissal.tag)
        assertEquals(reaction?.notificationId, dismissal.id)
    }

    @Test
    fun blankReactionEmojiKeepsTheNormalMessageIdentity() {
        // A blank emoji is not a reaction — it stays on the message card.
        val content =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a", reactionEmoji = "   "),
            )

        assertEquals(LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID, content?.notificationId)
        assertEquals("account|group-a", content?.notificationTag)
    }

    @Test
    fun nonReactionMessageStillUsesPreviewText() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    previewText = "We are go",
                ),
            )

        assertEquals("We are go", content?.body)
    }

    @Test
    fun messageBodyUsesCallerResolvedPreviewText() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    previewText = "hello @npub1rawmention",
                ),
                previewTextOverride = "hello @Alice",
            )

        assertEquals("hello @Alice", content?.body)
    }

    @Test
    fun renameSystemRowBodyUsesResolvedDiffPreviewText() {
        // A kind:1210 rename surfaces through the same preview-text path as a
        // normal message: the caller resolves the "old → new" diff string and
        // it must render verbatim as the body without special casing.
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    groupName = "Marmot Protocol",
                    previewText = "The group was renamed from “Marmot Lab” to “Marmot Protocol”",
                    sender = user(displayName = "Alice"),
                ),
                previewTextOverride = "Alice renamed “Marmot Lab” → “Marmot Protocol”",
            )

        assertEquals("Alice renamed “Marmot Lab” → “Marmot Protocol”", content?.body)
    }

    @Test
    fun reactionBodyUsesCallerResolvedReactedToPreview() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    reactionEmoji = "👍",
                    reactedToPreview = "thanks @npub1rawmention",
                ),
                reactedToPreviewOverride = "thanks @Alice",
            )

        assertEquals("reacted 👍 to: \"thanks @Alice\"", content?.body)
    }

    @Test
    fun blankPreviewOverrideFallsBackToPayloadPreviewText() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    previewText = "We are go",
                ),
                previewTextOverride = "   ",
            )

        assertEquals("We are go", content?.body)
    }

    @Test
    fun captionlessAttachmentsNameTheirMediaTypeInsteadOfNewMessage() {
        fun body(kind: ReplyMediaKind) =
            LocalNotificationFormatter
                .content(
                    update(trigger = NotificationTriggerFfi.NEW_MESSAGE, previewText = null),
                    mediaKind = kind,
                )?.body

        assertEquals("sent a picture", body(ReplyMediaKind.Photo))
        assertEquals("sent a video", body(ReplyMediaKind.Video))
        assertEquals("sent a voice message", body(ReplyMediaKind.Voice))
        assertEquals("sent a file", body(ReplyMediaKind.Document))
    }

    @Test
    fun captionedAttachmentShowsTheCaptionNotTheMediaTypeLabel() {
        // A media message with text resolves to its caption; the type-aware
        // label is only for the captionless case.
        val content =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, previewText = "on the beach 🏖️"),
                mediaKind = ReplyMediaKind.Photo,
            )

        assertEquals("on the beach 🏖️", content?.body)
    }

    @Test
    fun unclassifiedEmptyMessageStillFallsBackToNewMessage() {
        val content =
            LocalNotificationFormatter.content(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, previewText = null),
                mediaKind = ReplyMediaKind.None,
            )

        assertEquals("New message", content?.body)
    }

    @Test
    fun messagePreviewResolutionIsNeededOnlyForNormalMessages() {
        assertTrue(
            LocalNotificationFormatter.needsPreviewTextResolution(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, reactionEmoji = null),
            ),
        )
    }

    @Test
    fun reactionPreviewResolutionSkipsUnusedMessagePreview() {
        assertFalse(
            LocalNotificationFormatter.needsPreviewTextResolution(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    previewText = "message body that would otherwise be parsed",
                    reactionEmoji = "👍",
                    reactedToPreview = "reacted-to body still needs resolution",
                ),
            ),
        )
    }

    @Test
    fun invitePreviewResolutionIsNotNeeded() {
        assertFalse(
            LocalNotificationFormatter.needsPreviewTextResolution(
                update(trigger = NotificationTriggerFfi.GROUP_INVITE),
            ),
        )
    }

    @Test
    fun reactedToPreviewResolutionIsNeededOnlyForReactions() {
        assertTrue(
            LocalNotificationFormatter.needsReactedToPreviewResolution(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, reactionEmoji = "👍", reactedToPreview = "hello"),
            ),
        )
        assertFalse(
            LocalNotificationFormatter.needsReactedToPreviewResolution(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, reactionEmoji = null, reactedToPreview = "hello"),
            ),
        )
        assertFalse(
            LocalNotificationFormatter.needsReactedToPreviewResolution(
                update(trigger = NotificationTriggerFfi.GROUP_INVITE, reactedToPreview = "hello"),
            ),
        )
    }

    @Test
    fun selfAuthoredNotificationsAreSuppressed() {
        assertNull(
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    isFromSelf = true,
                ),
            ),
        )
    }

    @Test
    fun messagesInTheSameConversationShareATag() {
        // Different per-message keys, same account + group: they must collapse
        // onto one tag so the card accumulates instead of stacking.
        val first = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, notificationKey = "msg-1"))
        val second = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, notificationKey = "msg-2"))

        assertEquals(first?.notificationTag, second?.notificationTag)
        assertEquals(first?.notificationId, second?.notificationId)
    }

    @Test
    fun conversationDismissalKeyMatchesPostedMessageNotificationKey() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    groupIdHex = "group-a",
                ),
            )

        assertEquals(
            NotificationDismissalKey(
                tag = content?.notificationTag ?: error("missing content"),
                id = content.notificationId,
            ),
            LocalNotificationFormatter.conversationDismissalKey(
                accountRef = "account",
                groupIdHex = "group-a",
            ),
        )
    }

    @Test
    fun differentConversationsGetDistinctTags() {
        val launch = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-a"))
        val ops = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.NEW_MESSAGE, groupIdHex = "group-b"))

        assertNotEquals(launch?.notificationTag, ops?.notificationTag)
    }

    @Test
    fun invitesStayIndividualPerNotificationKey() {
        val first = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.GROUP_INVITE, notificationKey = "invite-1"))
        val second = LocalNotificationFormatter.content(update(trigger = NotificationTriggerFfi.GROUP_INVITE, notificationKey = "invite-2"))

        assertNotEquals(first?.notificationTag, second?.notificationTag)
    }

    @Test
    fun senderNameOverrideTakesPrecedenceOverPayloadDisplayName() {
        // The caller resolves the sender name (cached profile / contact name,
        // else npub). When present it must win over the FFI payload name and the
        // hex-key fallback.
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    groupName = "Launch",
                    sender = user(displayName = "stale-payload-name"),
                ),
                senderNameOverride = "Alice",
            )

        assertEquals("Alice", content?.senderName)
        assertEquals("Alice in Launch", content?.title)
    }

    @Test
    fun senderNameOverrideIsUsedWhenPayloadDisplayNameIsNull() {
        // Payload displayName is null even though the app has a name (npub) for
        // the sender. The override must be used instead of the raw hex key.
        val npub = "npub1qy88wumn8ghj7"
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    isDm = true,
                    groupName = null,
                    sender = user(displayName = null),
                ),
                senderNameOverride = npub,
            )

        assertEquals(npub, content?.senderName)
        assertEquals(npub, content?.title)
    }

    @Test
    fun blankSenderNameOverrideFallsBackToPayloadName() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    isDm = true,
                    groupName = null,
                    sender = user(displayName = "Bob"),
                ),
                senderNameOverride = "   ",
            )

        assertEquals("Bob", content?.senderName)
    }

    @Test
    fun missingNameEverywhereStillShortensTheKeyAsALastResort() {
        // With no override and no payload name, fall back to a shortened key.
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.NEW_MESSAGE,
                    isDm = true,
                    groupName = null,
                    sender = user(displayName = null),
                ),
                senderNameOverride = null,
            )

        // Never the full raw hex key.
        assertNotEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            content?.senderName,
        )
        assertTrue(content?.senderName?.contains("...") == true)
    }

    @Test
    fun inviteBodyUsesSenderNameOverride() {
        val content =
            LocalNotificationFormatter.content(
                update(
                    trigger = NotificationTriggerFfi.GROUP_INVITE,
                    groupName = null,
                    previewText = null,
                    sender = user(displayName = null),
                ),
                senderNameOverride = "Carol",
            )

        assertEquals("Invite from Carol", content?.body)
    }

    private fun update(
        trigger: NotificationTriggerFfi,
        notificationKey: String = "message:account:message",
        groupName: String? = "General",
        groupIdHex: String = "group",
        previewText: String? = "Hello",
        sender: NotificationUserFfi = user(),
        isDm: Boolean = false,
        isFromSelf: Boolean = false,
        reactionEmoji: String? = null,
        reactedToPreview: String? = null,
    ) = NotificationUpdateFfi(
        isMention = false,
        notificationKey = notificationKey,
        conversationKey = "conversation:account:$groupIdHex",
        trigger = trigger,
        accountRef = "account",
        accountIdHex = "account",
        groupIdHex = groupIdHex,
        groupName = groupName,
        isDm = isDm,
        messageIdHex = "message",
        sender = sender,
        receiver = user(accountIdHex = "account", displayName = "Me"),
        previewText = previewText,
        timestampMs = 1234,
        isFromSelf = isFromSelf,
        reactionEmoji = reactionEmoji,
        reactedToPreview = reactedToPreview,
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
