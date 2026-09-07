package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNotificationRemovalFormatterTest {
    /** Removal is one sentence while its account/group-scoped notification identity stays unchanged. */
    @Test
    fun removalNotificationUsesMembershipIdentityAndOneSentence() {
        val content =
            content(
                update(
                    groupName = "Launch",
                    previewText = null,
                ),
            )

        assertEquals("You were removed from Launch", content?.title)
        assertEquals("", content?.body)
        assertEquals("group-membership|account|group", content?.notificationTag)
        assertEquals(LocalNotificationFormatter.GROUP_MEMBERSHIP_NOTIFICATION_ID, content?.notificationId)
        assertNotEquals("account|group", content?.notificationTag)
    }

    /** Missing group names retain a localized, meaningful title without a second sentence. */
    @Test
    fun removalNotificationFallsBackWhenTheGroupHasNoName() {
        val content = content(update(groupName = null, previewText = null))

        assertEquals("You were removed from a group", content?.title)
        assertEquals("", content?.body)
    }

    /** Actor summaries must not repeat the removal already stated by the title. */
    @Test
    fun removalNotificationOmitsThePayloadSummary() {
        val content = content(update(previewText = "Alice removed you"))

        assertEquals("You were removed from General", content?.title)
        assertEquals("", content?.body)
    }

    /** Resolving the actor does not reintroduce a duplicate removal sentence. */
    @Test
    fun removalNotificationOmitsTheResolvedSummary() {
        val content =
            content(
                update(previewText = "Someone removed you"),
                previewTextOverride = "Alice removed you",
            )

        assertEquals("", content?.body)
    }

    /** A blank override cannot fall back to the redundant native summary. */
    @Test
    fun blankRemovalSummaryOverrideStillOmitsThePayloadSummary() {
        val content =
            content(
                update(previewText = "Alice removed you"),
                previewTextOverride = "   ",
            )

        assertEquals("", content?.body)
    }

    @Test
    fun selfAuthoredRemovalNotificationIsSuppressed() {
        assertNull(content(update(isFromSelf = true)))
    }

    private fun content(
        update: NotificationUpdateFfi,
        previewTextOverride: String? = null,
    ): LocalNotificationContent? =
        LocalNotificationFormatter.content(
            update = update,
            previewTextOverride = previewTextOverride,
            shortNpub = { SAMPLE_SHORT_NPUB },
        )

    private fun update(
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.REMOVED_FROM_GROUP,
        groupName: String? = "General",
        previewText: String? = "Someone removed you",
        isFromSelf: Boolean = false,
    ) = NotificationUpdateFfi(
        isMention = false,
        notificationKey = "membership:account:message",
        conversationKey = "conversation:account:group",
        trigger = trigger,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = "account",
        accountIdHex = "account",
        groupIdHex = "group",
        groupName = groupName,
        isDm = false,
        messageIdHex = "message",
        sender = user(),
        receiver = user(accountIdHex = "account", displayName = "Me"),
        previewText = previewText,
        timestampMs = 1234,
        isFromSelf = isFromSelf,
        reactionEmoji = null,
        reactedToPreview = null,
    )

    private fun user(
        accountIdHex: String = SAMPLE_ACCOUNT_ID_HEX,
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )

    private companion object {
        const val SAMPLE_ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SAMPLE_SHORT_NPUB = "npub1qy352...hstefp92"
    }
}
