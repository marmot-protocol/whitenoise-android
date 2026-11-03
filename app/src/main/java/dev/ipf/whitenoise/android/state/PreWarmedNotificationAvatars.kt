package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.notifications.LocalNotificationContent
import dev.ipf.whitenoise.android.notifications.NotificationIdentityPictureSources

/** Avatar warmup metadata or exact ready-local bitmaps retained across first publication and correction. */
internal data class PreWarmedNotificationAvatars(
    val senderAvatarUrl: String?,
    val groupAvatarUrl: String?,
    val senderAvatarBitmap: Bitmap? = null,
    val groupAvatarBitmap: Bitmap? = null,
) {
    /** Chooses the card image from the same immutable snapshot as the sender image. */
    fun conversationBitmap(isDm: Boolean): Bitmap? = if (isDm) senderAvatarBitmap else groupAvatarBitmap
}

/**
 * #3311: where a card first drawn from this ready snapshot may still get a missing picture, using the URLs the
 * post-write prewarm resolved in [warmed]. A direct chat's identity picture is its sender's, so both slots follow
 * the sender URL, and a group's identity comes only from the group URL. A picture the card already showed is kept.
 */
internal fun PreWarmedNotificationAvatars.lateIdentitySources(
    warmed: PreWarmedNotificationAvatars,
    isDm: Boolean,
    conversationDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    senderDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
): NotificationIdentityPictureSources =
    NotificationIdentityPictureSources(
        conversationBitmap = conversationBitmap(isDm),
        conversationUrl = if (isDm) warmed.senderAvatarUrl else warmed.groupAvatarUrl,
        senderBitmap = senderAvatarBitmap.takeIf { isDm },
        senderUrl = warmed.senderAvatarUrl.takeIf { isDm },
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
