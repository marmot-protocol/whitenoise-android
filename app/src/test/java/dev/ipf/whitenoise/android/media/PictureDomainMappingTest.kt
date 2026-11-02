package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.notifications.notificationConversationDomain
import dev.ipf.whitenoise.android.notifications.notificationSenderDomain
import dev.ipf.whitenoise.android.ui.common.groupAvatarDecodeDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Which worker domain each kind of picture names (#3373): only bytes a member pinned enter a conversation's worker. */
class PictureDomainMappingTest {
    /** A group's encrypted picture decodes in its conversation, its URL picture single-use, a person's in public. */
    @Test
    fun groupPicturesUseTheirConversationOnlyWhenAMemberPinnedTheBytes() {
        assertEquals(MediaTrustDomain.Conversation(ACCOUNT, GROUP), groupAvatarDecodeDomain(true, true, ACCOUNT, GROUP))
        assertEquals(MediaTrustDomain.Unique, groupAvatarDecodeDomain(true, false, ACCOUNT, GROUP))
        assertEquals(MediaTrustDomain.PublicProfiles(ACCOUNT), groupAvatarDecodeDomain(false, false, ACCOUNT, GROUP))
        assertEquals(MediaTrustDomain.Unique, groupAvatarDecodeDomain(true, true, null, GROUP))
    }

    /** A notification's group URL picture decodes single-use; a direct chat's and a sender's in the public profiles. */
    @Test
    fun notificationPicturesNeverUseTheConversationsWorker() {
        assertEquals(MediaTrustDomain.Unique, notificationConversationDomain(update(isDm = false)))
        assertEquals(MediaTrustDomain.PublicProfiles(ACCOUNT), notificationConversationDomain(update(isDm = true)))
        assertEquals(MediaTrustDomain.PublicProfiles(ACCOUNT), notificationSenderDomain(update(isDm = false)))
    }

    /**
     * Every pick of one draft shares that draft's outgoing domain, which no later draft, other conversation, account,
     * received media or local file shares.
     */
    @Test
    fun outgoingPicksShareTheirDraftsDomain() {
        val outgoing = MediaTrustDomain.outgoing(ACCOUNT, GROUP, DRAFT)
        assertEquals(MediaTrustDomain.Outgoing(ACCOUNT, GROUP, DRAFT), outgoing)
        assertNotEquals(outgoing, MediaTrustDomain.outgoing(ACCOUNT, GROUP, "later-draft"))
        assertNotEquals(outgoing, MediaTrustDomain.outgoing(ACCOUNT, "other", DRAFT))
        assertNotEquals(outgoing, MediaTrustDomain.outgoing("other", GROUP, DRAFT))
        assertNotEquals(outgoing, MediaTrustDomain.local(ACCOUNT))
        assertNotEquals(outgoing, MediaTrustDomain.conversation(ACCOUNT, GROUP))
        assertEquals(MediaTrustDomain.Unique, MediaTrustDomain.outgoing(ACCOUNT, GROUP, null))
        assertEquals(MediaTrustDomain.Unique, MediaTrustDomain.outgoing(ACCOUNT, null, DRAFT))
        assertEquals(MediaTrustDomain.Unique, MediaTrustDomain.outgoing(null, GROUP, DRAFT))
        assertEquals("Outgoing", outgoing.toString())
    }

    /** A notification update for [isDm] in the fixture account and group. */
    private fun update(isDm: Boolean) =
        NotificationUpdateFfi(
            isMention = false,
            notificationKey = "membership:account:message",
            conversationKey = "conversation:account:group",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = ACCOUNT,
            accountIdHex = ACCOUNT,
            groupIdHex = GROUP,
            groupName = "General",
            isDm = isDm,
            messageIdHex = "message",
            sender = NotificationUserFfi(accountIdHex = "sender", displayName = null, pictureUrl = null),
            receiver = NotificationUserFfi(accountIdHex = ACCOUNT, displayName = "Me", pictureUrl = null),
            previewText = null,
            timestampMs = 1234,
            isFromSelf = false,
            reactionEmoji = null,
            reactedToPreview = null,
            sticker = null,
        )

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val DRAFT = "draft"
    }
}
