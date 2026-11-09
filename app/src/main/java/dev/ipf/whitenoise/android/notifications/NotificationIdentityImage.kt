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
 * [contact] names the peer whose private picture the chat row shows for a direct chat MDK reports as a group
 * (#3635), so the final write applies that peer's current choice, as it does for any direct chat.
 */
internal fun LocalNotificationContent.identityImage(
    seed: String,
    titleOverride: String?,
    conversationAvatar: Bitmap?,
    senderAvatar: Bitmap?,
    contact: String? = null,
): NotificationIdentityImage {
    val directChat = !isGroupConversation
    val title = titleOverride?.takeIf(String::isNotBlank) ?: conversationTitle ?: this.title
    val picture = conversationAvatar ?: senderAvatar.takeIf { directChat }
    return NotificationIdentityImage(
        bitmap = picture ?: notificationMonogramBitmap(title, seed),
        title = title,
        seed = seed,
        contactKey = contact?.takeIf(String::isNotBlank) ?: senderKey.takeIf { directChat && it.isNotBlank() },
    )
}

/**
 * A reaction card's picture is the person who reacted (#3635): their picture when one is in hand, else their
 * monogram. It names the reactor as its contact, so their private contact picture wins at the final write, as on
 * their group rows, and the refresh keeps the card's reaction artwork views.
 */
internal fun LocalNotificationContent.reactorImage(senderAvatar: Bitmap?): NotificationIdentityImage {
    val seed = senderKey.ifBlank { notificationTag }
    return NotificationIdentityImage(
        bitmap = senderAvatar ?: notificationMonogramBitmap(senderName, seed),
        title = senderName,
        seed = seed,
        contactKey = senderKey.takeIf(String::isNotBlank),
    )
}

/**
 * Where a card's two picture slots may still come from after its first post (#3311): the bitmap the first post drew,
 * authoritative when present, and the URL a missing one may resolve from. A present bitmap's URL is never fetched.
 * [storedConversationLoader] reads MDK's stored copy of the picture the chat list draws (#3635) and is tried first,
 * also over a [conversationBitmap] the first post drew from elsewhere, such as a direct chat's published sender
 * picture on an account whose chat list was not loaded, so the card still ends on the picture the chat list shows.
 * [conversationLoader] fetches a missing conversation picture itself: the URL, or a group picture that has no URL,
 * such as a Marmot encrypted group image. [senderLoader] fetches the sender's picture with the late refresh's longer
 * bound, so a cold download still arrives. A slot with a loader never falls back to its raw URL, so a loader that
 * stops because the app lock appeared is not followed by a fetch behind the lock, and a failed fetch is not retried.
 * [identityContact] is the peer whose private picture the chat row shows, read with the stored picture, so a rewrite
 * records them and the final write applies their current choice. No private pixels travel in these sources.
 */
internal data class NotificationIdentityPictureSources(
    val conversationBitmap: Bitmap?,
    val conversationUrl: String?,
    val senderBitmap: Bitmap?,
    val senderUrl: String?,
    val conversationLoader: (suspend () -> Bitmap?)? = null,
    val storedConversationLoader: (suspend () -> Bitmap?)? = null,
    /** The worker domain for [conversationUrl]: the public profiles for a direct chat, single-use for a group. */
    val conversationDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    /** The worker domain for [senderUrl]: the receiving account's public profiles. */
    val senderDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    val senderLoader: (suspend () -> Bitmap?)? = null,
    val identityContact: String? = null,
) {
    /**
     * True when the card was drawn without a picture that a URL or a loader may still supply, or without the stored
     * picture the chat list shows.
     */
    val hasMissingPicture: Boolean
        get() =
            storedConversationLoader != null ||
                (conversationBitmap == null && (!conversationUrl.isNullOrBlank() || conversationLoader != null)) ||
                (senderBitmap == null && (!senderUrl.isNullOrBlank() || senderLoader != null))

    /** True when a loader, not the raw URL, supplies the conversation picture. */
    val hasConversationLoader: Boolean
        get() = storedConversationLoader != null || conversationLoader != null

    /** True when a late refresh may still change the card: a missing picture, or a contact it may not record yet. */
    val mayRefresh: Boolean
        get() = hasMissingPicture || identityContact != null
}

/** The contact whose private picture a live card's identity follows at the final write, or null for none. */
internal fun Notification.recordedIdentityContact(): String? =
    extras
        ?.getBundle(EXTRA_IDENTITY_IMAGE)
        ?.getString(IDENTITY_CONTACT)

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
internal fun NotificationCompat.Builder.setConversationIdentityImage(image: NotificationIdentityImage) = setLargeIcon(image.bitmap).recordIdentityImage(image)

/** Retains refresh inputs for a native reaction, whose avatar pixels live in the sender Person. */
internal fun NotificationCompat.Builder.recordIdentityImage(image: NotificationIdentityImage) =
    addExtras(Bundle().apply { putBundle(EXTRA_IDENTITY_IMAGE, image.refreshInputs()) })

/** The monogram inputs and direct-chat contact a final write needs to redraw this picture without its pixels. */
private fun NotificationIdentityImage.refreshInputs(): Bundle =
    Bundle().apply {
        putString(IDENTITY_TITLE, title)
        putString(IDENTITY_SEED, seed)
        contactKey?.let { putString(IDENTITY_CONTACT, it) }
    }

/**
 * Applies the current account-scoped contact choice to a direct chat's or a reaction's identity picture at the final
 * write, the same way the sender Person is refreshed, so a concurrent Save or Clear never posts stale private pixels.
 * A cleared choice draws the public or stored picture [contactAvatar] hands back. A choice without pixels, such as
 * the app lock, falls back to the monogram, never to captured private pixels. Groups are left unchanged. The card
 * keeps its custom views, such as a reaction's artwork, and its group alert behavior.
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
            .setGroupAlertBehavior(notification.groupAlertBehavior)
            .keepingCustomViews(notification)
            .setLargeIcon(pixels ?: notificationMonogramBitmap(title, seed))
            .build()
    }
}

/** Copies [source]'s custom views, which the recovery builder drops, such as a reaction card's artwork (#3635). */
@Suppress("DEPRECATION") // Built custom views live only in these Notification fields.
internal fun NotificationCompat.Builder.keepingCustomViews(source: Notification): NotificationCompat.Builder =
    apply {
        source.contentView?.let(::setCustomContentView)
        source.bigContentView?.let(::setCustomBigContentView)
        source.headsUpContentView?.let(::setCustomHeadsUpContentView)
    }
