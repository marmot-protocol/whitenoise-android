package dev.ipf.whitenoise.android.notifications

import android.os.Build
import androidx.core.content.LocusIdCompat
import dev.ipf.marmotkit.NotificationUpdateFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes the conversation's identity shortcut before the first post (#3311), so even a brand-new
 * conversation's first card gets Android's conversation layout and picture without a second write. It reuses the
 * resolved pictures or the monogram and never waits on the network. An unchanged snapshot costs nothing, and a
 * failed publication is contained so the card still posts through the existing fallback. The published shortcut
 * is held in [InFlightConversationShortcuts] until the caller releases it after that write, so a Direct Share
 * refresh in between cannot delete it. Returns the held shortcut id, or null when nothing was published or held.
 */
internal suspend fun LocalNotificationPresenter.publishShortcutBeforePost(
    update: NotificationUpdateFfi,
    content: LocalNotificationContent,
    messaging: MessagingPostContext,
    directShareEligible: Boolean,
    isPublishAllowed: () -> Boolean,
): String? {
    val shortcutId = messaging.shortcutId ?: return null
    return withContext(Dispatchers.Default) {
        conversationShortcuts.publishConversationShortcut(
            update = update,
            content = content,
            shortcutId = shortcutId,
            locusId = LocusIdCompat(shortcutId),
            conversationAvatarUrl = messaging.conversationAvatarUrl,
            conversationAvatarBitmap = messaging.conversationAvatarBitmap,
            senderAvatarUrl = messaging.senderAvatarUrl,
            senderAvatarBitmap = messaging.senderAvatarBitmap,
            sender = messaging.sender,
            directShareEligible = directShareEligible,
            isPublishAllowed = isPublishAllowed,
            holdForPost = true,
            identityContact = messaging.identityContact,
        )
    }.takeIf { it }?.let { shortcutId }
}

/** Publishes the separate reaction shortcut before Android classifies the card for its template. */
internal suspend fun LocalNotificationPresenter.publishReactionShortcutBeforePost(
    update: NotificationUpdateFfi,
    content: LocalNotificationContent,
    isPublishAllowed: () -> Boolean,
): String? {
    val id =
        reactionConversationShortcutId(update.accountRef, update.groupIdHex)
            ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R } ?: return null
    val published =
        withContext(Dispatchers.Default) {
            conversationShortcuts.publishReactionShortcut(
                update,
                content,
                id,
                isPublishAllowed,
            )
        }
    return id.takeIf { published }
}

/**
 * Resolves the post's target channel while rechecking [isWriteAllowed] inside the channel creation
 * lock so a locally deleted conversation or departed account cannot materialize a named child channel.
 */
@Suppress("LongParameterList") // Post-routing parameters shared with LocalNotificationPresenter.show().
internal suspend fun LocalNotificationPresenter.resolvePostChannelId(
    update: NotificationUpdateFfi,
    existingCardChannel: String?,
    channelShortcutId: String?,
    conversationTitleOverride: String?,
    senderNameOverride: String?,
    redactContent: Boolean,
    vibrationPattern: ConversationVibrationPattern,
    isWriteAllowed: () -> Boolean,
): String? =
    withContext(Dispatchers.Default) {
        if (!isWriteAllowed()) return@withContext null
        val channelId =
            existingCardChannel ?: profileNotificationSoundAuthor(update)?.let { author ->
                val selection = profileNotificationOverrides.get(update.accountRef, author)
                if (selection.mode == ProfileNotificationMode.CUSTOM) {
                    profileNotificationChannels.existing(
                        accountRef = update.accountRef,
                        author = author,
                        pattern = selection.vibration,
                        title = senderNameOverride ?: update.sender.displayName,
                        redact = redactContent,
                        isWriteAllowed = isWriteAllowed,
                    )
                } else {
                    null
                }
            } ?: conversationNotificationRouting
                .resolveForPost(
                    channel = NotificationChannelSpec.forUpdate(update),
                    conversationShortcutId = channelShortcutId,
                    conversationTitle = conversationTitleOverride,
                    primaryVibrationPattern = vibrationPattern,
                    accountRef = update.accountRef,
                    isWriteAllowed = isWriteAllowed,
                ).channelId
        channelId.takeIf { isWriteAllowed() }
    }
