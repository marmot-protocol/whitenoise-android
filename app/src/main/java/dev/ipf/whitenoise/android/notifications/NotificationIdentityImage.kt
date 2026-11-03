package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.app.NotificationCompat

/** Bundle holding the identity snapshot's monogram inputs and, for a direct chat, the contact that owns it. */
internal const val EXTRA_IDENTITY_IMAGE = "dev.ipf.whitenoise.android.notify.identity_image"
private const val IDENTITY_TITLE = "title"
private const val IDENTITY_SEED = "seed"
private const val IDENTITY_CONTACT = "contact"

/**
 * The one conversation identity picture a rich message card shows in its primary image slot (#3311). A direct chat
 * uses the peer's picture, a group its group picture, and either falls back to the same monogram its conversation
 * shortcut draws, so short and long first messages, stacked history and later enrichment never disagree.
 * [contactKey] names the peer of a direct chat, whose account-scoped private picture wins at the final write.
 */
internal data class NotificationIdentityImage(
    val bitmap: Bitmap,
    val title: String,
    val seed: String,
    val contactKey: String?,
)

/**
 * Resolves this card's identity snapshot. A direct chat prefers the conversation image the caller resolved for it
 * (the peer's picture), then the sender's own picture. A group never borrows a member's face as its identity.
 */
internal fun LocalNotificationContent.identityImage(
    seed: String,
    titleOverride: String?,
    conversationAvatar: Bitmap?,
    senderAvatar: Bitmap?,
): NotificationIdentityImage {
    val directChat = !isGroupConversation
    val title = titleOverride?.takeIf(String::isNotBlank) ?: conversationTitle ?: this.title
    val picture = conversationAvatar ?: senderAvatar.takeIf { directChat }
    return NotificationIdentityImage(
        bitmap = picture ?: notificationMonogramBitmap(title, seed),
        title = title,
        seed = seed,
        contactKey = senderKey.takeIf { directChat && it.isNotBlank() },
    )
}

/**
 * Where a card's two picture slots may still come from after its first post (#3311): the bitmap the first post drew,
 * authoritative when present, and the URL a missing one may resolve from. A present bitmap's URL is never fetched.
 * [conversationLoader] supplies a group picture that has no URL, such as a Marmot encrypted group image, and is
 * consulted only when neither the bitmap nor the URL gives one.
 */
internal data class NotificationIdentityPictureSources(
    val conversationBitmap: Bitmap?,
    val conversationUrl: String?,
    val senderBitmap: Bitmap?,
    val senderUrl: String?,
    val conversationLoader: (suspend () -> Bitmap?)? = null,
    /** The worker domain for [conversationUrl]: the public profiles for a direct chat, single-use for a group. */
    val conversationDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    /** The worker domain for [senderUrl]: the receiving account's public profiles. */
    val senderDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
) {
    /** True when the card was drawn without a picture that a URL or the conversation loader may still supply. */
    val hasMissingPicture: Boolean
        get() =
            (conversationBitmap == null && (!conversationUrl.isNullOrBlank() || conversationLoader != null)) ||
                (senderBitmap == null && !senderUrl.isNullOrBlank())
}

/**
 * The title and monogram seed a live card's identity snapshot recorded, so a late picture redraws the same snapshot
 * with only its pixels changed. Null for a card that carries no identity, such as a redacted or generic one.
 */
internal fun Notification.recordedIdentityInputs(): Pair<String, String>? =
    extras?.getBundle(EXTRA_IDENTITY_IMAGE)?.let { inputs ->
        inputs.getString(IDENTITY_TITLE).orEmpty() to inputs.getString(IDENTITY_SEED).orEmpty()
    }

/**
 * Puts [image] in the card's primary picture slot and records what a final-write refresh needs to redraw it. The
 * native conversation template draws it in the conversation circle. Android owns the remaining small app
 * attribution and exact geometry; the app adds no separate trailing image view.
 */
internal fun NotificationCompat.Builder.setConversationIdentityImage(image: NotificationIdentityImage) =
    setLargeIcon(image.bitmap).addExtras(Bundle().apply { putBundle(EXTRA_IDENTITY_IMAGE, image.refreshInputs()) })

/** The monogram inputs and direct-chat contact a final write needs to redraw this picture without its pixels. */
private fun NotificationIdentityImage.refreshInputs(): Bundle =
    Bundle().apply {
        putString(IDENTITY_TITLE, title)
        putString(IDENTITY_SEED, seed)
        contactKey?.let { putString(IDENTITY_CONTACT, it) }
    }

/**
 * Applies the current account-scoped contact choice to a direct chat's identity picture at the final write, the
 * same way the sender Person is refreshed, so a concurrent Save or Clear never posts stale private pixels. A cleared
 * or app-locked choice falls back to the monogram, never to captured private pixels. Groups are left unchanged.
 */
internal fun withCurrentIdentityContact(
    context: Context,
    notification: Notification,
    contactAvatar: (String) -> Pair<Boolean, Bitmap?>,
): Notification {
    val identity = notification.extras?.getBundle(EXTRA_IDENTITY_IMAGE)
    val contact = identity?.getString(IDENTITY_CONTACT)
    val (chosen, pixels) = contact?.let(contactAvatar) ?: (false to null)
    return if (identity == null || contact == null || !chosen) {
        notification
    } else {
        val title = identity.getString(IDENTITY_TITLE).orEmpty()
        val seed = identity.getString(IDENTITY_SEED) ?: contact
        NotificationCompat
            .Builder(context, notification)
            .setLargeIcon(pixels ?: notificationMonogramBitmap(title, seed))
            .build()
    }
}
