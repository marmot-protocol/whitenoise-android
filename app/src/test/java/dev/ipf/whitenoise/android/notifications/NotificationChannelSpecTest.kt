package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationChannelSpecTest {
    @Test
    fun directMessageRoutesToTheDirectMessagesChannel() {
        assertEquals(
            NotificationChannelSpec.DIRECT_MESSAGES,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = true),
            ),
        )
    }

    @Test
    fun groupMessageRoutesToTheGroupMessagesChannel() {
        assertEquals(
            NotificationChannelSpec.GROUP_MESSAGES,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false),
            ),
        )
    }

    @Test
    fun reactionRoutesToTheReactionsChannelEvenInADm() {
        // A reaction is a NEW_MESSAGE with an emoji; the emoji wins over the
        // DM/group split so reactions can be muted as one category.
        assertEquals(
            NotificationChannelSpec.REACTIONS,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = true, reactionEmoji = "👍"),
            ),
        )
    }

    @Test
    fun reactionRoutesToTheReactionsChannelInAGroup() {
        assertEquals(
            NotificationChannelSpec.REACTIONS,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false, reactionEmoji = "❤️"),
            ),
        )
    }

    @Test
    fun blankReactionEmojiIsTreatedAsAPlainMessage() {
        // A blank emoji is not a reaction — fall back to the message channels.
        assertEquals(
            NotificationChannelSpec.GROUP_MESSAGES,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false, reactionEmoji = "   "),
            ),
        )
    }

    @Test
    fun emojiThatSanitizesToEmptyIsNotRoutedToReactions() {
        // A reactionEmoji of only sanitizer-stripped code points (here a lone
        // zero-width space) is non-blank to a raw isNullOrBlank() check but
        // empties under clean(). It must NOT land on the REACTIONS channel,
        // else it collides with the conversation's message card identity that
        // the formatter (which uses the sanitized predicate) assigns it.
        val zeroWidthSpace = String(Character.toChars(0x200B))
        assertEquals(
            NotificationChannelSpec.GROUP_MESSAGES,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false, reactionEmoji = zeroWidthSpace),
            ),
        )
    }

    @Test
    fun mentionRoutesToTheMentionsChannelInAGroup() {
        assertEquals(
            NotificationChannelSpec.MENTIONS,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false, isMention = true),
            ),
        )
    }

    @Test
    fun mentionTakesPrecedenceOverTheDmChannel() {
        assertEquals(
            NotificationChannelSpec.MENTIONS,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = true, isMention = true),
            ),
        )
    }

    @Test
    fun reactionTakesPrecedenceOverMention() {
        assertEquals(
            NotificationChannelSpec.REACTIONS,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.NEW_MESSAGE, isDm = false, reactionEmoji = "👍", isMention = true),
            ),
        )
    }

    @Test
    fun inviteRoutesToTheInvitesChannel() {
        assertEquals(
            NotificationChannelSpec.INVITES,
            NotificationChannelSpec.forUpdate(
                update(trigger = NotificationTriggerFfi.GROUP_INVITE),
            ),
        )
    }

    @Test
    fun channelIdsAreTheFrozenStableContractWithTheOs() {
        // These IDs are a stable contract — changing one discards the user's
        // per-channel overrides, so it's only done deliberately (re-keying to
        // change a live channel's importance) with the old ID retired.
        assertEquals("messages_dm", NotificationChannelSpec.DIRECT_MESSAGES.id)
        assertEquals("messages_group", NotificationChannelSpec.GROUP_MESSAGES.id)
        assertEquals("mentions", NotificationChannelSpec.MENTIONS.id)
        assertEquals("reactions_v2", NotificationChannelSpec.REACTIONS.id)
        assertEquals("invites_v2", NotificationChannelSpec.INVITES.id)
        assertEquals("membership_changes_v1", NotificationChannelSpec.MEMBERSHIP_CHANGES.id)
    }

    private fun update(
        trigger: NotificationTriggerFfi,
        isDm: Boolean = false,
        reactionEmoji: String? = null,
        isMention: Boolean = false,
    ) = NotificationUpdateFfi(
        isMention = isMention,
        notificationKey = "key",
        conversationKey = "conversation",
        trigger = trigger,
        accountRef = "account",
        accountIdHex = "account",
        groupIdHex = "group",
        groupName = "General",
        isDm = isDm,
        messageIdHex = "message",
        sender = user(),
        receiver = user(accountIdHex = "account", displayName = "Me"),
        previewText = "Hello",
        timestampMs = 1234,
        isFromSelf = false,
        reactionEmoji = reactionEmoji,
        reactedToPreview = null,
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
