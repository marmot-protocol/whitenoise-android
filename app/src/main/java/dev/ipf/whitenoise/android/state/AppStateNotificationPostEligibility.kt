package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter

/**
 * Rechecks synchronous app and content fences at every platform write. A reaction's own event id cannot verify
 * its quoted target, so a known removal in that conversation also rejects a captured, still-quoted update.
 */
internal fun WhiteNoiseAppState.isNotificationGenerationPostAllowed(
    update: NotificationUpdateFfi,
    postEpoch: Long,
    accountCacheEpoch: Long,
    engineMuted: Boolean,
    requireUnlocked: Boolean,
    directConversation: Boolean = update.isDm,
    reserveRepeat: Boolean = false,
): Boolean {
    val lockAllowsPost = !requireUnlocked || !appLock.screenVisible
    val runtimeAllowsPost =
        !networkNotificationRecoverySuppressed &&
            notificationPostEpoch.isCurrent(postEpoch) &&
            profileCacheLifetime.isCurrent(accountCacheEpoch)
    // A deleted or hidden message can never be written back by a queued post or late enrichment (#3323).
    val contentAvailable =
        !notificationContentInvalidations.isRemoved(update.accountRef, update.groupIdHex, update.messageIdHex)
    // Reactions name their own event, not the quoted target. Recheck removals at every platform write too.
    val quoteAvailable =
        !LocalNotificationFormatter.isReaction(update) ||
            update.reactedToPreview.isNullOrBlank() ||
            !notificationContentInvalidations.hasRemovalInConversation(update.accountRef, update.groupIdHex)
    return lockAllowsPost &&
        runtimeAllowsPost &&
        contentAvailable &&
        quoteAvailable &&
        shouldPostNotification(update, engineMuted, directConversation, reserveRepeat)
}

internal fun WhiteNoiseAppState.isNotificationEnrichmentAllowed(
    update: NotificationUpdateFfi,
    postEpoch: Long,
    accountCacheEpoch: Long,
    engineMuted: Boolean,
    directConversation: Boolean = update.isDm,
): Boolean =
    isNotificationGenerationPostAllowed(
        update = update,
        postEpoch = postEpoch,
        accountCacheEpoch = accountCacheEpoch,
        engineMuted = engineMuted,
        requireUnlocked = true,
        directConversation = directConversation,
    ) &&
        localNotificationPresenter.isNotificationUpdateCurrentForEnrichment(update)

/** Re-checks every captured fence for one first-post correction candidate. */
internal fun WhiteNoiseAppState.isNotificationEnrichmentAllowed(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
): Boolean =
    isQueuedReactionQuoteCurrent(update, firstPost) &&
        isNotificationEnrichmentAllowed(
            update = update,
            postEpoch = firstPost.epoch,
            accountCacheEpoch = firstPost.accountCacheEpoch,
            engineMuted = firstPost.engineMuted,
            directConversation = update.isDm || firstPost.profileDirectConversation,
        )

/**
 * Identity eviction cannot make an already-captured opaque reaction quote eligible again. Any removal in the
 * same account conservatively rejects its queued quotes; no quoted-target identity is available to narrow it.
 */
internal fun WhiteNoiseAppState.isQueuedReactionQuoteCurrent(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
): Boolean =
    !LocalNotificationFormatter.isReaction(update) ||
        update.reactedToPreview.isNullOrBlank() ||
        notificationContentInvalidations.removalVersion(update.accountRef) == firstPost.contentRemovalVersion

/** Uses the receiver's stored chat classification even when its chat list is cold. */
internal suspend fun WhiteNoiseAppState.isStoredDirectChat(update: NotificationUpdateFfi): Boolean {
    val cachedKind = notificationChatListItem(update)?.projection?.conversationKind
    val kind =
        cachedKind?.takeUnless { it == ChatConversationKindFfi.UNKNOWN }
            ?: presentedNotificationChatRow(update)?.row?.conversationKind
    return kind == ChatConversationKindFfi.DIRECT
}
