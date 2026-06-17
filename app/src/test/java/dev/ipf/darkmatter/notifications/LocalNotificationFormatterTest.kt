package dev.ipf.darkmatter.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
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
        // hex-key fallback. See #206.
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
        // The crux of #206: payload displayName is null even though the app has
        // a name (npub) for the sender. The override must be used instead of the
        // raw hex key.
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
