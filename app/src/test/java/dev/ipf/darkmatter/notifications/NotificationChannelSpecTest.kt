package dev.ipf.darkmatter.notifications

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
        // Re-keyed from the original low-importance "reactions" channel so
        // reactions heads-up; the old ID is retired on the OS side.
        assertEquals("reactions_v2", NotificationChannelSpec.REACTIONS.id)
        assertEquals("invites", NotificationChannelSpec.INVITES.id)
        assertEquals("darkmatter.messages.v2", NotificationChannelSpec.LEGACY_MESSAGES_CHANNEL_ID)
        assertEquals("reactions", NotificationChannelSpec.LEGACY_REACTIONS_CHANNEL_ID)
    }

    private fun update(
        trigger: NotificationTriggerFfi,
        isDm: Boolean = false,
        reactionEmoji: String? = null,
    ) = NotificationUpdateFfi(
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
