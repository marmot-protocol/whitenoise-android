package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which avatars a notification warms before posting, and which warmed bitmap becomes the card's identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationAvatarPreWarmTriggerTest {
    /** #3311: a direct chat's identity image is the sender's picture, and a group's comes only from the group. */
    @Test
    fun identityBitmapFollowsTheConversationKind() {
        val sender = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val group = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val warmed = PreWarmedNotificationAvatars(null, null, senderAvatarBitmap = sender, groupAvatarBitmap = group)
        assertSame(sender, warmed.conversationBitmap(isDm = true))
        assertSame(group, warmed.conversationBitmap(isDm = false))
        val senderOnly = PreWarmedNotificationAvatars(null, null, senderAvatarBitmap = sender)
        assertNull("a group never borrows the sender's face", senderOnly.conversationBitmap(isDm = false))
    }

    @Test
    fun dmPreWarmUsesTheNotificationPayloadBeforeProfileMaterialization() {
        val target =
            notificationAvatarPreWarmTarget(
                update = update(isDm = true, pictureUrl = "https://example.com/alice.png"),
                appLockScreenVisible = false,
            )

        assertEquals("alice", target.senderAccountIdHex)
        assertEquals("https://example.com/alice.png", target.senderAvatarUrl)
        assertEquals(false, target.resolveGroupAvatar)
        assertEquals(true, target.preWarmRemoteImages)
    }

    @Test
    fun groupPreWarmSkipsMemberPayloadAndProfilePicturesButRequestsTheGroupAvatar() {
        val target =
            notificationAvatarPreWarmTarget(
                update = update(isDm = false, pictureUrl = "http://127.0.0.1/alice.png"),
                appLockScreenVisible = true,
            )

        assertNull(target.senderAccountIdHex)
        assertNull(target.senderAvatarUrl)
        assertEquals(true, target.resolveGroupAvatar)
        assertEquals(false, target.preWarmRemoteImages)
    }

    /** Group post-write warmup calls only the group owner, never the sender profile callback. */
    @Test
    fun groupCoordinatorNeverResolvesTheHiddenSenderProfile() =
        runBlocking {
            var senderReads = 0
            var groupReads = 0
            val coordinator =
                NotificationAvatarCoordinator(
                    appLocked = { false },
                    shouldPost = { _, _ -> true },
                    canPost = { true },
                    senderAvatarUrl = {
                        senderReads += 1
                        "https://example.com/member.png"
                    },
                    groupAvatarUrl = {
                        groupReads += 1
                        null
                    },
                )
            val groupMessage = update(isDm = false, pictureUrl = "https://example.com/payload.png")
            coordinator.preWarm(groupMessage, engineMuted = false)
            assertEquals(0, senderReads)
            assertEquals(1, groupReads)
        }

    /** Even callers carrying a ready member bitmap cannot feed it into a group's late picture work. */
    @Test
    fun lateSourcesKeepSenderPicturesOnlyForDirectMessages() {
        val picture = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val ready = PreWarmedNotificationAvatars(null, null, senderAvatarBitmap = picture)
        val warmed = PreWarmedNotificationAvatars("https://example.com/member.png", "https://example.com/group.png")
        val group = ready.lateIdentitySources(warmed, isDm = false)
        assertNull(group.senderBitmap)
        assertNull(group.senderUrl)
        assertEquals(warmed.groupAvatarUrl, group.conversationUrl)
        val dm = ready.lateIdentitySources(warmed, isDm = true)
        assertSame(picture, dm.senderBitmap)
        assertSame(picture, dm.conversationBitmap)
        assertEquals(warmed.senderAvatarUrl, dm.senderUrl)
        assertEquals(warmed.senderAvatarUrl, dm.conversationUrl)
    }

    @Test
    fun preWarmEligibilityRequiresAPostableMessagingNotification() {
        val message = update(isDm = true, pictureUrl = null)

        assertTrue(shouldPreWarmNotificationAvatars(message, shouldPost = true, canPost = true))
        assertFalse(shouldPreWarmNotificationAvatars(message, shouldPost = false, canPost = true))
        assertFalse(shouldPreWarmNotificationAvatars(message, shouldPost = true, canPost = false))
        assertFalse(
            shouldPreWarmNotificationAvatars(
                update(isDm = true, pictureUrl = null, isFromSelf = true),
                shouldPost = true,
                canPost = true,
            ),
        )
        assertFalse(
            shouldPreWarmNotificationAvatars(
                update(isDm = true, pictureUrl = null, reactionEmoji = "👍"),
                shouldPost = true,
                canPost = true,
            ),
        )
        assertFalse(
            shouldPreWarmNotificationAvatars(
                update(isDm = false, pictureUrl = null, trigger = NotificationTriggerFfi.GROUP_INVITE),
                shouldPost = true,
                canPost = true,
            ),
        )
    }

    private fun update(
        isDm: Boolean,
        pictureUrl: String?,
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
        reactionEmoji: String? = null,
        isFromSelf: Boolean = false,
    ) = NotificationUpdateFfi(
        notificationKey = "message:account-a:message-a",
        conversationKey = "conversation:account-a:group-a",
        trigger = trigger,
        trafficClass = dev.ipf.marmotkit.NotificationTrafficClassFfi.STANDARD,
        accountRef = "account-a",
        accountIdHex = "account-id-a",
        groupIdHex = "group-a",
        groupName = null,
        isDm = isDm,
        isMention = false,
        messageIdHex = "message-a",
        sender = NotificationUserFfi(accountIdHex = "alice", displayName = "Alice", pictureUrl = pictureUrl),
        receiver = NotificationUserFfi(accountIdHex = "bob", displayName = "Bob", pictureUrl = null),
        previewText = "hello",
        reactionEmoji = reactionEmoji,
        reactedToPreview = null,
        timestampMs = 1L,
        isFromSelf = isFromSelf,
    )
}
