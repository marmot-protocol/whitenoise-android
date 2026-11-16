package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.EXTRA_GENERATION
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.dismissalTime

/**
 * The card's final pictures once at least one it was drawn without has arrived, or null when none has. Nothing is
 * fetched while previews are hidden, and a picture the card already showed is never fetched again. A stored
 * conversation picture with the same pixels as the one the card shows changes nothing.
 */
@Suppress("MaxLineLength")
internal suspend fun LocalNotificationPresenter.arrivedPictures(sources: NotificationIdentityPictureSources): Pair<Bitmap?, Bitmap?>? =
    if (!sources.mayUpdatePictures || !NotificationPreviewPreferences.capture(context).allowed) {
        null
    } else {
        resolveAvatars(sources).takeIf { (conversation, sender) ->
            val shown = sources.conversationBitmap
            (conversation !== shown && conversation?.sameAs(shown) != true) || sender !== sources.senderBitmap
        }
    }

/**
 * #3635: the card's own pictures when the late refresh found the peer whose private picture the card follows, and
 * the live card does not record them yet, as on an account whose chat list was not loaded at the post. The
 * rewrite records the peer, so the final write and every later Save or Clear apply their current choice.
 */
internal fun LocalNotificationPresenter.unrecordedContactPictures(
    update: NotificationUpdateFfi,
    content: LocalNotificationContent,
    sources: NotificationIdentityPictureSources,
): Pair<Bitmap?, Bitmap?>? {
    val contact = sources.identityContact ?: return null
    val recorded = refreshableIdentityCard(update, content)?.notification?.recordedIdentityContact()
    return (sources.conversationBitmap to sources.senderBitmap).takeIf { recorded != contact }
}

/**
 * The live card a late picture may rewrite: this message's card, the app's latest write for its key and not
 * dismissed, carrying an identity snapshot, and still allowed to show its preview. A redacted or hidden-preview
 * card fails [NotificationPreviewPreferences.canExpose], and a replaced or dismissed one fails the rest.
 */
internal fun LocalNotificationPresenter.refreshableIdentityCard(
    update: NotificationUpdateFfi,
    content: LocalNotificationContent,
): StatusBarNotification? {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return null
    val expectedMessageId = update.messageIdHex?.takeIf(String::isNotBlank)
    return publisher.activeNotification(manager, content.notificationTag, content.notificationId)?.takeIf { live ->
        val card = live.notification
        val generation = card.extras.getString(EXTRA_GENERATION)
        val latestWrite =
            generation != null &&
                !NotificationCardGenerations.isDismissed(generation) &&
                ConversationCardPostedRegistry.latestGeneration(live.tag.orEmpty(), live.id) == generation
        val sameMessage = expectedMessageId != null && conversationCardMessageIdHex(card) == expectedMessageId
        sameMessage &&
            latestWrite &&
            ConversationCardPostedRegistry.isCurrentPayload(
                live.tag.orEmpty(),
                live.id,
                card.extras.getString(EXTRA_PAYLOAD_REVISION),
            ) &&
            card.recordedIdentityInputs() != null &&
            NotificationPreviewPreferences.canExpose(context, card, card)
    }
}

/**
 * Republishes the conversation shortcut with the late pictures and rewrites [live] through the enrichment rewrite.
 * The card keeps its own sender Person, so a sender renamed since the post keeps the new name, and its recorded
 * identity title and monogram seed, so only the pictures change.
 */
internal fun LocalNotificationPresenter.rewriteWithLatePictures(
    refresh: LateIdentityRefresh,
    live: StatusBarNotification,
): Boolean {
    val card = live.notification
    val inputs = card.recordedIdentityInputs()
    val livePerson = cardContent.liveSenderPerson(card, refresh.content.senderKey)
    if (inputs == null || livePerson == null) return false
    val (conversation, senderPicture) = refresh.pictures
    val sender =
        senderPicture?.let { livePerson.toBuilder().setIcon(IconCompat.createWithBitmap(it)).build() } ?: livePerson
    val (title, seed) = inputs
    val contact = card.recordedIdentityContact() ?: refresh.identityContact
    val identity =
        refresh.content.identityImage(
            seed = seed,
            titleOverride = title,
            conversationAvatar = conversation,
            senderAvatar = senderPicture,
            contact = contact,
        )
    card.shortcutId?.let { shortcutId ->
        conversationShortcuts.publishConversationShortcut(
            update = refresh.update,
            content = refresh.content,
            shortcutId = shortcutId,
            locusId = LocusIdCompat(shortcutId),
            conversationAvatarUrl = null,
            conversationAvatarBitmap = conversation,
            senderAvatarUrl = null,
            senderAvatarBitmap = senderPicture,
            sender = sender,
            directShareEligible = refresh.directShareEligible,
            isPublishAllowed = {
                refresh.isCurrent() && publisher.canPublishPreviewShortcut(refresh.content, card)
            },
            identityContact = contact,
        )
    }
    return cardContent.enrichedIdentityNotification(card, sender, identity)?.let { rewritten ->
        postLateIdentityRewrite(refresh, live, rewritten)
    } == true
}

/** Refreshes only the reactor's picture, retaining the live card's layout, artwork and grouping. */
internal fun LocalNotificationPresenter.rewriteWithLateReactorPicture(
    refresh: LateIdentityRefresh,
    live: StatusBarNotification,
    sources: NotificationIdentityPictureSources,
): Boolean {
    val reactor =
        refresh.pictures.second
            ?.takeUnless { it === sources.senderBitmap }
            ?.let(refresh.content::reactorImage) ?: return false
    val rewritten = cardContent.reactorPictureNotification(live.notification, reactor)
    return postLateIdentityRewrite(refresh, live, rewritten)
}

/** Writes one late picture rewrite over the same live card, under the refresh's fences up to the platform write. */
internal fun LocalNotificationPresenter.postLateIdentityRewrite(
    refresh: LateIdentityRefresh,
    live: StatusBarNotification,
    rewritten: Notification,
): Boolean =
    publisher.postNotificationSafely(
        NotificationManagerCompat.from(context),
        live.tag.orEmpty(),
        live.id,
        rewritten,
        recordedAtMs = dismissalTime(live),
        mustBeLive = true,
        finalPostAllowed = refresh.isCurrent,
    ) == NotificationCardWriteResult.WRITTEN

/** Platform metadata carries only the existing opaque ownership scope, never a local account label. */
internal fun LocalNotificationPresenter.contactPictureAccountExtras(account: String): Bundle =
    Bundle().apply {
        putString(CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA, conversationShortcutAccountScope(account))
    }

/** The same scoped callback serves initial publication and later platform-card reconciliation. */
internal fun LocalNotificationPresenter.currentContactAvatar(
    account: String,
    contact: String,
): Pair<Boolean, Bitmap?> {
    val scope = conversationShortcutAccountScope(account) ?: return false to null
    return contactAvatarOverride(scope, contact)
}
