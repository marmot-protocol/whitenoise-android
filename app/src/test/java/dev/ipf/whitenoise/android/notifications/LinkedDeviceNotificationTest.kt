package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.state.needsIncomingAttachmentDownload
import dev.ipf.whitenoise.android.state.shouldPreWarmNotificationAvatars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A message this account sent from another of its devices reaches this device as an update MDK marks as from self.
 * It must never alert here, in a direct chat or a group, mentioned or not, while a peer's message still does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class LinkedDeviceNotificationTest {
    /** No card is built for the account's own message from another device. */
    @Test
    fun aSiblingDevicesMessageBuildsNoNotification() {
        listOf(false, true).forEach { isDm ->
            listOf(false, true).forEach { isMention ->
                val update = update(fromSibling = true, isDm = isDm, isMention = isMention)
                assertNull("dm=$isDm mention=$isMention", content(update))
            }
        }
    }

    /** A peer's message in the same chat still builds its card. */
    @Test
    fun aPeersMessageStillBuildsANotification() {
        assertNotNull(content(update(fromSibling = false)))
    }

    /** Nothing is warmed for a card that will not be posted. */
    @Test
    fun aSiblingDevicesMessageWarmsNoAvatars() {
        val sibling = update(fromSibling = true)

        assertFalse(shouldPreWarmNotificationAvatars(sibling, shouldPost = true, canPost = true))
        assertTrue(shouldPreWarmNotificationAvatars(update(fromSibling = false), shouldPost = true, canPost = true))
    }

    /** With linked devices the account's message from another device still has its attachments fetched here. */
    @Test
    fun siblingAndPeerAttachmentsAreBothFetched() {
        val sibling = update(fromSibling = true)
        val peer = update(fromSibling = false)

        assertTrue(needsIncomingAttachmentDownload(sibling))
        assertTrue(needsIncomingAttachmentDownload(peer))
    }

    /** Only new messages are considered for attachment fetching. */
    @Test
    fun onlyNewMessagesFetchAttachments() {
        val invite = update(fromSibling = false).copy(trigger = NotificationTriggerFfi.GROUP_INVITE)

        assertFalse(needsIncomingAttachmentDownload(invite))
    }

    private fun content(update: NotificationUpdateFfi): LocalNotificationContent? =
        LocalNotificationFormatter.content(update = update, shortNpub = { "npub1sample" })

    /** A new-message update to [ACCOUNT], authored by that account on another device or by a peer. */
    private fun update(
        fromSibling: Boolean,
        isDm: Boolean = false,
        isMention: Boolean = false,
    ) = NotificationUpdateFfi(
        notificationKey = "message:$ACCOUNT:message",
        conversationKey = "conversation:$ACCOUNT:group",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = ACCOUNT,
        accountIdHex = ACCOUNT,
        groupIdHex = "group",
        groupName = "General",
        isDm = isDm,
        isMention = isMention,
        messageIdHex = "message",
        sender = NotificationUserFfi(if (fromSibling) ACCOUNT else "peer", "Sender", null),
        receiver = NotificationUserFfi(ACCOUNT, "Me", null),
        previewText = "Hello",
        sticker = null,
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = 1234,
        isFromSelf = fromSibling,
    )

    private companion object {
        const val ACCOUNT = "account"
    }
}
