package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAvatarFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #3635: a notification's conversation picture is the stored avatar the chat list draws for the same row, chosen
 * through the chat list's own adoption rule and read from the pixels it already decoded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationStoredAvatarTest {
    /** Leaves no decoded pixels behind for the next scenario. */
    @After
    fun tearDown() {
        AvatarImageLoader.clear()
    }

    /** A group's own stored picture is the notification's picture. */
    @Test
    fun groupRowUsesItsOwnStoredPicture() {
        val asset = asset("group-picture")
        val stored = presented(groupRow(), asset, groupPresentation()).storedNotificationAvatar(ACCOUNT)

        assertNotNull(stored)
        assertEquals(asset.reference, stored?.asset?.reference)
    }

    /** A direct chat lends its contact's stored picture, as the chat list does. */
    @Test
    fun directRowLendsItsContactsStoredPicture() {
        val asset = asset("peer-picture")
        val stored = presented(directRow(), asset, peerPresentation()).storedNotificationAvatar(ACCOUNT)

        assertEquals(asset.reference, stored?.asset?.reference)
    }

    /** A named group never borrows a member's stored face, even when MDK selected one. */
    @Test
    fun namedGroupNeverBorrowsAMembersStoredPicture() {
        val stored = presented(groupRow(), asset("peer-picture"), peerPresentation()).storedNotificationAvatar(ACCOUNT)

        assertNull(stored)
    }

    /** A stored avatar MDK does not hold yet is not selected, so the other picture sources still apply. */
    @Test
    fun missingStoredPictureIsNotSelected() {
        val missing = asset("group-picture", AvatarAvailabilityFfi.MISSING)

        assertNull(presented(groupRow(), missing, groupPresentation()).storedNotificationAvatar(ACCOUNT))
        assertNull(presented(groupRow(), null, groupPresentation()).storedNotificationAvatar(ACCOUNT))
    }

    /** The first post reuses the chat list's decoded pixels for the row, and never another account's. */
    @Test
    fun readyStoredBitmapIsTheChatListsDecodedPixelsForThisAccount() {
        val asset = asset("group-picture")
        val item = chatListItemFromProjection(groupRow(), groupPresentation(), asset)
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        AvatarImageLoader.putCached(checkNotNull(asset.cacheKey(ACCOUNT)), image.asImageBitmap())

        assertSame(image, item.readyStoredAvatarBitmap(ACCOUNT))
        assertNull(item.readyStoredAvatarBitmap("another-account"))
    }

    /**
     * A direct chat's first-post snapshot names the contact whose private picture its chat row shows, so the card
     * applies their choice at the write, and holds only the stored picture's pixels, never a private picture.
     */
    @Test
    fun directChatSnapshotNamesItsContactAndHoldsOnlyTheStoredPicture() {
        val asset = asset("peer-picture")
        val item = chatListItemFromProjection(directRow(), peerPresentation(), asset)
        val stored = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        AvatarImageLoader.putCached(checkNotNull(asset.cacheKey(ACCOUNT)), stored.asImageBitmap())

        val snapshot = PreWarmedNotificationAvatars(null, null).withChatRow(item, ACCOUNT)

        assertEquals(PEER, item.privatePictureContact())
        assertEquals(PEER, snapshot.identityContact)
        assertSame(stored, snapshot.storedConversationBitmap)
    }

    /** A named group's picture is its own, so its snapshot names no member whose private picture could replace it. */
    @Test
    fun namedGroupSnapshotNamesNoContact() {
        val item = chatListItemFromProjection(groupRow(), peerPresentation(), asset("peer-picture"))

        assertNull(item.privatePictureContact())
        assertNull(PreWarmedNotificationAvatars(null, null).withChatRow(item, ACCOUNT).identityContact)
    }

    /**
     * A cleared private picture falls back to the stored picture the contact's chat row shows, from this account's
     * decoded pixels, matching the contact in any case. A named group never lends the face MDK selected for it.
     */
    @Test
    fun clearedPictureFallsBackToTheStoredPictureTheContactsRowShows() {
        val asset = asset("peer-picture")
        val named = chatListItemFromProjection(groupRow(), peerPresentation(), asset)
        val direct = chatListItemFromProjection(directRow(), peerPresentation(), asset)
        val stored = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        AvatarImageLoader.putCached(checkNotNull(asset.cacheKey(ACCOUNT)), stored.asImageBitmap())

        assertSame(stored, listOf(named, direct).readyContactRowPicture(ACCOUNT, PEER.uppercase()))
        assertNull("a named group lends nothing", listOf(named).readyContactRowPicture(ACCOUNT, PEER))
        assertNull("another contact", listOf(direct).readyContactRowPicture(ACCOUNT, "c".repeat(64)))
        assertNull("another account's pixels", listOf(direct).readyContactRowPicture("another-account", PEER))
    }

    /** Without a loaded chat row, as on an account whose chat list is not open, the snapshot adds nothing. */
    @Test
    fun missingChatRowAddsNothingToTheSnapshot() {
        val snapshot = PreWarmedNotificationAvatars(null, null).withChatRow(null, ACCOUNT)

        assertNull(snapshot.identityContact)
        assertNull(snapshot.storedConversationBitmap)
    }

    /** A named group row whose own picture is MDK's encrypted image. */
    private fun groupRow() =
        notificationChatListRow().copy(
            groupName = "Team",
            title = "Team",
            avatar = ChatListAvatarFfi(GROUP_HASH, "key", "nonce", "upload-key", "image/png"),
        )

    /** An unnamed two-person row MDK projects as a direct chat. */
    private fun directRow() =
        notificationChatListRow().copy(
            groupName = "",
            title = "",
            conversationKind = ChatConversationKindFfi.DIRECT,
        )

    /** MDK's selection of the group's own encrypted picture. */
    private fun groupPresentation() =
        ConversationPresentationFfi(
            title = PresentationTextFfi.Literal("Team"),
            avatar =
                SelectedAvatarFfi.EncryptedGroupImage(
                    ChatListAvatarFfi(GROUP_HASH, "key", "nonce", "upload-key", "image/png"),
                    "group-picture",
                ),
            titleSource = PresentationSourceFfi.GROUP,
            avatarSource = PresentationSourceFfi.GROUP,
            peerId = null,
            resolution = PresentationResolutionFfi.CACHED,
        )

    /** MDK's selection of the other person's profile picture. */
    private fun peerPresentation() =
        ConversationPresentationFfi(
            title = PresentationTextFfi.Literal("Alice"),
            avatar = SelectedAvatarFfi.RemoteImage("https://example.invalid/alice.png", "peer-picture"),
            titleSource = PresentationSourceFfi.PEER_PROFILE,
            avatarSource = PresentationSourceFfi.PEER_PROFILE,
            peerId = PEER,
            resolution = PresentationResolutionFfi.CACHED,
        )

    /** MDK's stored avatar for [target]. */
    private fun asset(
        target: String,
        availability: AvatarAvailabilityFfi = AvatarAvailabilityFfi.READY,
    ) = AvatarAssetFfi(target, "stored-$target", availability, AvatarAcquisitionStateFfi.IDLE, 1uL, 1_024uL)

    /** Pairs a row with its presentation and stored avatar. */
    private fun presented(
        row: dev.ipf.marmotkit.ChatListRowFfi,
        asset: AvatarAssetFfi?,
        presentation: ConversationPresentationFfi,
    ) = PresentedChatRowFfi(null, emptyChatRowPreview(), noChatRowActions(), row, presentation, asset)

    private companion object {
        const val ACCOUNT = "account-a"
        val PEER = "a".repeat(64)
        val GROUP_HASH = "b".repeat(64)
    }
}
