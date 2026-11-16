package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.graphics.Bitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Hands optional picture work to its execution owner while keeping content and write checks here. */
internal suspend fun LocalNotificationPresenter.dispatchMessagingEnrichment(
    update: NotificationUpdateFfi,
    content: LocalNotificationContent,
    messaging: MessagingPostContext,
    showToken: ConversationCardShowToken,
    directShareEligible: Boolean,
    isPostStillAllowed: () -> Boolean,
) {
    enrichmentRunner.runOrLaunch(
        requiresRemoteAvatarResolution = messaging.requiresRemoteAvatarResolution,
        showToken = showToken,
    ) {
        enrichMessagingNotification(
            update = update,
            content = content,
            messaging = messaging,
            showToken = showToken,
            directShareEligible = directShareEligible,
            isPostStillAllowed = isPostStillAllowed,
        )
    }
}

/**
 * Rewrites the card and republishes its shortcut only when a picture resolved after the post. An unchanged
 * picture leaves both alone, because the pre-post publish already published this shortcut and reported its use,
 * so a message reports the shortcut used once. A changed picture republishes it and reports its use once more.
 */
private suspend fun LocalNotificationPresenter.enrichMessagingNotification(
    update: NotificationUpdateFfi,
    content: LocalNotificationContent,
    messaging: MessagingPostContext,
    showToken: ConversationCardShowToken,
    directShareEligible: Boolean,
    isPostStillAllowed: () -> Boolean,
) {
    val (conversationAvatarBitmap, senderAvatarBitmap) =
        resolveMessagingAvatars(messaging, notificationConversationDomain(update), notificationSenderDomain(update))
    val avatarChanged =
        conversationAvatarBitmap !== messaging.conversationAvatarBitmap ||
            senderAvatarBitmap !== messaging.senderAvatarBitmap
    val stillOwned = isPostStillAllowed() && ConversationCardPostSynchronizer.isShowCurrent(showToken)
    if (!avatarChanged || !stillOwned) return

    val enrichedSender =
        notificationSenderPerson(
            content,
            senderAvatarBitmap ?: notificationMonogramBitmap(content.senderName, content.senderKey),
        )
    postEnrichedMessagingNotification(
        update = update,
        content = content,
        messaging = messaging,
        showToken = showToken,
        directShareEligible = directShareEligible,
        isPostStillAllowed = isPostStillAllowed,
        conversationAvatarBitmap = conversationAvatarBitmap,
        senderAvatarBitmap = senderAvatarBitmap,
        enrichedSender = enrichedSender,
    )
}

/**
 * Resolves the post's picture inputs, fetching only a slot that has a URL and no bitmap. The conversation slot
 * decodes in [conversationDomain], the sender slot in [senderDomain].
 */
private suspend fun LocalNotificationPresenter.resolveMessagingAvatars(
    messaging: MessagingPostContext,
    conversationDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
    senderDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
): Pair<Bitmap?, Bitmap?> =
    resolveAvatars(
        NotificationIdentityPictureSources(
            conversationBitmap = messaging.conversationAvatarBitmap,
            conversationUrl = messaging.conversationAvatarUrl,
            senderBitmap = messaging.senderAvatarBitmap,
            senderUrl = messaging.senderAvatarUrl,
            conversationDomain = conversationDomain,
            senderDomain = senderDomain,
        ),
    )

/**
 * Resolves both picture slots concurrently off the main thread, keeping a present bitmap and never fetching it.
 * The conversation slot first reads MDK's stored copy of the picture the chat list draws (#3635), so a picture
 * already on the device never depends on the network, and it replaces a picture the first post drew from
 * elsewhere, which stays when the store has none. A missing picture then comes from the slot's loader, or from
 * its raw URL only when the slot has no loader, as on the first post. Loaders run only in the late refresh, after
 * the enrichment permit is released, so they get a longer bound than a URL picture and a slow network or a newly
 * started decoder still gets the picture. A loader that stops behind the app lock or fails is never followed by
 * a URL fetch (#3635). A loader that fails yields no picture for its slot without cancelling the other.
 */
@Suppress("MaxLineLength")
internal suspend fun LocalNotificationPresenter.resolveAvatars(sources: NotificationIdentityPictureSources): Pair<Bitmap?, Bitmap?> =
    withContext(Dispatchers.Default) {
        coroutineScope {
            val conversationAvatar =
                async {
                    sources.storedConversationLoader?.let { boundedIdentityLoad(it) }
                        ?: sources.conversationBitmap
                        ?: sources.conversationLoader?.let { boundedIdentityLoad(it) }
                        ?: sources.conversationUrl.takeUnless { sources.hasConversationLoader }?.let { url ->
                            withContext(MediaTrustScope.element(sources.conversationDomain)) {
                                avatarBitmapResolver(url)
                            }
                        }
                }
            val senderAvatar =
                async {
                    sources.senderBitmap
                        ?: sources.senderLoader?.let { boundedIdentityLoad(it) }
                        ?: sources.senderUrl.takeIf { sources.senderLoader == null }?.let { url ->
                            withContext(MediaTrustScope.element(sources.senderDomain)) {
                                avatarBitmapResolver(url)
                            }
                        }
                }
            conversationAvatar.await() to senderAvatar.await()
        }
    }

/** Runs one late picture loader within its bound; a failure yields no picture, never a crash. */
private suspend fun LocalNotificationPresenter.boundedIdentityLoad(load: suspend () -> Bitmap?): Bitmap? =
    withTimeoutOrNull(GROUP_IDENTITY_LOAD_TIMEOUT_MS) {
        runCatchingCancellable { load() }.getOrNull()
    }

/** Rewrites one live card with avatars that changed after its post, preserving dismissal ownership. */
private suspend fun LocalNotificationPresenter.postEnrichedMessagingNotification(
    update: NotificationUpdateFfi,
    content: LocalNotificationContent,
    messaging: MessagingPostContext,
    showToken: ConversationCardShowToken,
    directShareEligible: Boolean,
    isPostStillAllowed: () -> Boolean,
    conversationAvatarBitmap: Bitmap?,
    senderAvatarBitmap: Bitmap?,
    enrichedSender: Person,
) {
    // The enriched rewrite is a second platform write for the same card, so it takes a slot like the first,
    // but only while the card still shows this message. The lock section below checks it again.
    if (!isLiveCardForMessage(content, update)) return
    postPacer.awaitSlot()
    ConversationCardPostSynchronizer.withLock(
        content.notificationTag,
        content.notificationId,
        ConversationCardOp.SHOW_ENRICH,
    ) {
        if (!isPostStillAllowed() || !ConversationCardPostSynchronizer.isShowCurrent(showToken)) {
            return@withLock
        }
        val active =
            publisher.activeConversationCard(content.notificationTag, content.notificationId) ?: return@withLock
        if (!ConversationCardPostedRegistry.isCurrentPayload(
                content.notificationTag,
                content.notificationId,
                active.extras.getString(EXTRA_PAYLOAD_REVISION),
            )
        ) {
            return@withLock
        }
        val expectedMessageId = update.messageIdHex?.takeIf(String::isNotBlank)
        if (
            expectedMessageId != null &&
            conversationCardMessageIdHex(active) != expectedMessageId
        ) {
            return@withLock
        }
        messaging.shortcutId?.let { shortcutId ->
            conversationShortcuts.publishConversationShortcut(
                update = update,
                content = content,
                shortcutId = shortcutId,
                locusId = LocusIdCompat(shortcutId),
                conversationAvatarUrl = messaging.conversationAvatarUrl,
                conversationAvatarBitmap = conversationAvatarBitmap,
                senderAvatarUrl = messaging.senderAvatarUrl,
                senderAvatarBitmap = senderAvatarBitmap,
                sender = enrichedSender,
                directShareEligible = directShareEligible,
                isPublishAllowed = {
                    isPostStillAllowed() &&
                        ConversationCardPostSynchronizer.isShowCurrent(showToken) &&
                        publisher.canPublishPreviewShortcut(content, active)
                },
                identityContact = messaging.identityContact,
            )
        }
        // The late picture replaces the identity image and the sender together, for either card template.
        val identity =
            content.identityImage(
                seed = messaging.shortcutId ?: content.notificationTag,
                titleOverride = messaging.conversationTitleOverride,
                conversationAvatar = conversationAvatarBitmap,
                senderAvatar = senderAvatarBitmap,
                contact = messaging.identityContact,
            )
        postEnrichedAvatarCard(content, active, enrichedSender, identity, showToken)
    }
}

/** Whether this message's card is still on the platform, the cheap check before spending a pacer slot. */
private fun LocalNotificationPresenter.isLiveCardForMessage(
    content: LocalNotificationContent,
    update: NotificationUpdateFfi,
): Boolean {
    val active = publisher.activeConversationCard(content.notificationTag, content.notificationId)
    val expectedMessageId = update.messageIdHex?.takeIf(String::isNotBlank)
    val sameMessage = expectedMessageId == null || conversationCardMessageIdHex(active) == expectedMessageId
    return active != null && sameMessage
}

/** Writes the late sender and identity picture to the still-live card, keeping its dismissal ownership. */
private fun LocalNotificationPresenter.postEnrichedAvatarCard(
    content: LocalNotificationContent,
    active: Notification,
    sender: Person,
    identity: NotificationIdentityImage,
    showToken: ConversationCardShowToken,
) {
    val enriched = cardContent.enrichedIdentityNotification(active, sender, identity) ?: return
    val manager = NotificationManagerCompat.from(context)
    if (
        publisher.postNotificationSafely(
            manager,
            content.notificationTag,
            content.notificationId,
            enriched,
            mustBeLive = true,
        ) == NotificationCardWriteResult.WRITTEN
    ) {
        publisher.retainPostedCardUnlessDismissed(
            showToken,
            manager,
            content.notificationTag,
            content.notificationId,
        )
    }
}
