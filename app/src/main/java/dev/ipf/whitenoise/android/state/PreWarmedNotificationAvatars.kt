package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ProfileSanitizer

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
