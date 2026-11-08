package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.notifications.LocalNotificationContent
import dev.ipf.whitenoise.android.notifications.NotificationIdentityPictureSources

/** MDK's stored avatar a notification shows for its conversation, and the worker domain that decodes it. */
internal data class StoredNotificationAvatar(
    val asset: AvatarAssetFfi,
    val domain: MediaTrustDomain,
)

/**
 * #3635: the stored avatar the chat list draws for this row, through the chat list's own adoption rule, so a named
 * group never borrows a member's face while a direct chat shows its contact. Null when the row selects no stored
 * picture the device holds now.
 */
internal fun PresentedChatRowFfi.storedNotificationAvatar(accountRef: String): StoredNotificationAvatar? =
    chatListItemFromProjection(row, presentation, avatarAsset).storedNotificationAvatar(accountRef)

/** #3635: [storedNotificationAvatar] for a row already projected as the chat list's item. */
internal fun ChatListItem.storedNotificationAvatar(accountRef: String): StoredNotificationAvatar? {
    val asset = selectedAvatarAsset?.takeIf { it.isRenderable() } ?: return null
    return StoredNotificationAvatar(asset, chatListAvatarDomain(this, accountRef))
}

/**
 * #3635: the peer whose private contact picture this chat row shows instead of its stored picture, by the chat row's
 * own rule: the account the projector names for a direct chat or an unnamed pair, unless the group owns its picture.
 */
internal fun ChatListItem.privatePictureContact(): String? =
    GroupProjector
        .avatarAccount(group, presentationOtherMemberAccount, presentationMemberCount)
        ?.takeUnless { GroupProjector.ownsGroupPicture(this) }

/**
 * #3635: the picture a notification's first post shows for this chat row, as the row shows it: the viewer's private
 * picture of its [privatePictureContact] when [contactPicture] reports a choice for them, else the pixels the chat
 * list already decoded for its stored picture. Null when neither is in hand.
 */
internal fun ChatListItem.readyNotificationPicture(
    accountRef: String,
    contactPicture: (String) -> Pair<Boolean, Bitmap?>,
): Bitmap? {
    val (chosen, private) = privatePictureContact()?.let(contactPicture) ?: (false to null)
    return if (chosen) private else readyStoredAvatarBitmap(accountRef)
}

/** #3635: the pixels the chat list already decoded for this row's stored selected avatar, or null without them. */
internal fun ChatListItem.readyStoredAvatarBitmap(accountRef: String): Bitmap? =
    selectedAvatarAsset
        ?.takeIf { it.isRenderable() }
        ?.cacheKey(accountRef)
        ?.let(AvatarImageLoader::cachedImage)
        ?.asAndroidBitmap()

/**
 * Avatar warmup metadata or exact ready-local bitmaps retained across first publication and correction.
 * [storedConversationBitmap] holds the pixels the chat list decoded for the row's stored selected avatar (#3635).
 */
internal data class PreWarmedNotificationAvatars(
    val senderAvatarUrl: String?,
    val groupAvatarUrl: String?,
    val senderAvatarBitmap: Bitmap? = null,
    val groupAvatarBitmap: Bitmap? = null,
    val storedConversationBitmap: Bitmap? = null,
) {
    /**
     * Chooses the card image from the same immutable snapshot as the sender image: the picture the chat list shows
     * for the row first (#3635), then the sender's picture for a direct chat or the group's own picture for a group.
     */
    fun conversationBitmap(isDm: Boolean): Bitmap? {
        val own = if (isDm) senderAvatarBitmap else groupAvatarBitmap
        return storedConversationBitmap ?: own
    }
}

/**
 * #3311: where a card first drawn from this ready snapshot may still get a missing picture, using the URLs the
 * post-write prewarm resolved in [warmed]. A direct chat's identity picture is its sender's, so both slots follow
 * the sender URL, and a group's identity comes only from the group URL. Every sender row draws its sender's picture,
 * in a group too (#3635). A reaction card draws only the person who reacted, so it has no conversation slot. A
 * picture the card already showed is kept.
 */
internal fun PreWarmedNotificationAvatars.lateIdentitySources(
    warmed: PreWarmedNotificationAvatars,
    isDm: Boolean,
    conversationDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    senderDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    isReaction: Boolean = false,
): NotificationIdentityPictureSources =
    NotificationIdentityPictureSources(
        conversationBitmap = conversationBitmap(isDm).takeUnless { isReaction },
        conversationUrl = (if (isDm) warmed.senderAvatarUrl else warmed.groupAvatarUrl).takeUnless { isReaction },
        senderBitmap = senderAvatarBitmap,
        senderUrl = warmed.senderAvatarUrl,
        conversationDomain = conversationDomain,
        senderDomain = senderDomain,
    )

/**
 * #3311: a late picture refresh prepared inside the enrichment permit, from the content the card shows and where its
 * missing pictures may still come from, and run after the permit is released.
 */
internal data class LateNotificationIdentity(
    val content: LocalNotificationContent,
    val sources: NotificationIdentityPictureSources,
)

/** Snapshots only decoded cache hits; no URL reaches a presenter that could start enrichment. */
internal fun readyNotificationAvatarSnapshot(
    senderUrl: String?,
    groupUrl: String?,
): PreWarmedNotificationAvatars =
    PreWarmedNotificationAvatars(
        senderAvatarUrl = null,
        groupAvatarUrl = null,
        senderAvatarBitmap = AvatarImageLoader.peekBitmap(ProfileSanitizer.protocolImageUrl(senderUrl)),
        groupAvatarBitmap = AvatarImageLoader.peekBitmap(ProfileSanitizer.protocolImageUrl(groupUrl)),
    )
