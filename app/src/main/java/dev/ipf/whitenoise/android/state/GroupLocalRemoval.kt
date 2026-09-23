package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared local group wipe used by chat-list Delete and sole-member Leave flows.
 * The engine drops its own rows/secrets, but Android owns decrypted media caches
 * and tray notifications, so clear those before the group references disappear.
 */
internal suspend fun WhiteNoiseAppState.deleteGroupLocalWithClientCleanup(
    account: String,
    groupIdHex: String,
) {
    conversationDictation.onTargetRemoved(account, groupIdHex)
    evictGroupMediaCaches(account, groupIdHex)
    deleteDraftBeforeGroupRemoval(account, groupIdHex)
    marmotIo { deleteGroupLocal(account, groupIdHex) }
    removeComposerExpansionForGroup(account, groupIdHex)
    dismissConversationNotifications(account, groupIdHex)
}

/** The chat-list's recoverable local wipe must not change Android-owned state before commit. */
internal suspend fun WhiteNoiseAppState.deleteChatGroupLocalWithRecovery(
    account: String,
    groupIdHex: String,
    isCurrent: () -> Boolean,
    onNativeCommitted: () -> Unit,
) {
    // Capture encrypted media identifiers while the native group is still present. Unlike the
    // legacy leave/reset paths, a failed preflight aborts without touching the group or caches.
    val media = retryIdempotentRuntimeMutation {
        if (!isCurrent()) throw CancellationException("chat binding changed during local deletion preflight")
        marmotIo { listMedia(account, groupIdHex, null) }
    }
    val cacheKeys =
        media.map { rec ->
            mediaCacheKey(account, groupIdHex, rec.messageIdHex, rec.attachmentIndex.toInt())
        }
    val tags = media.mapNotNull { it.reference.ciphertextSha256 }.toSet()

    deleteLocalGroupWithRecovery(
        isCurrent = isCurrent,
        delete = { marmotIo { deleteGroupLocal(account, groupIdHex) } },
        isGroupPresent = {
            // The durable chat-list projection is the same native source that supplied the
            // row being removed. Unlike groupDetails, this does not depend on a live MLS roster.
            marmotIo { chatList(account, true) }
                .any { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        },
    )
    onNativeCommitted()
    // Native success (including an already-committed lost response) is the only point at which
    // clearing the draft, dictation target, cache, expansion and notifications is safe.
    withContext(NonCancellable) {
        conversationDictation.onTargetRemoved(account, groupIdHex)
        if (cacheKeys.isNotEmpty()) {
            removeMediaMemoryCacheKeys(cacheKeys, Dispatchers.Main.immediate, ::removeMediaMemoryCacheEntry)
        }
        if (cacheKeys.isNotEmpty() || tags.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                cacheKeys.forEach { diskMediaCache.remove(it) }
                if (tags.isNotEmpty()) diskMediaCache.removeByCiphertextTags(tags)
            }
        }
        deleteDraftBeforeGroupRemoval(account, groupIdHex)
        draftStore.replaceFromAuthoritative(account, groupIdHex, null, null)
        removeComposerExpansionForGroup(account, groupIdHex)
        dismissConversationNotifications(account, groupIdHex)
    }
}

internal suspend fun WhiteNoiseAppState.evictGroupMediaCaches(
    account: String,
    groupIdHex: String,
) {
    val media =
        runCatchingCancellable { marmotIo { listMedia(account, groupIdHex, null) } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return
    val cacheKeys =
        media.map { rec ->
            mediaCacheKey(account, groupIdHex, rec.messageIdHex, rec.attachmentIndex.toInt())
        }
    // ByteSizeLruCache is backed by a non-thread-safe LinkedHashMap. Keep the
    // in-memory L1 removals main-confined even though the disk L2 eviction below
    // correctly runs on IO.
    removeMediaMemoryCacheKeys(
        cacheKeys = cacheKeys,
        dispatcher = Dispatchers.Main.immediate,
        removeEntry = ::removeMediaMemoryCacheEntry,
    )
    val tags = media.mapNotNull { it.reference.ciphertextSha256 }.toSet()
    withContext(Dispatchers.IO) {
        cacheKeys.forEach { diskMediaCache.remove(it) }
        if (tags.isNotEmpty()) diskMediaCache.removeByCiphertextTags(tags)
    }
}

/**
 * MDK 0.10.0 `forgetGroupLocal`: erases this device's history and protocol state for the group without
 * sending a leave or disband, so the account rejoins only through a Welcome whose authenticated timestamp
 * is strictly newer than the reset. Android owns decrypted media caches, drafts, dictation targets and
 * tray notifications, so those are cleared before the group's rows disappear, mirroring the local delete.
 *
 * Returns false when MDK was already waiting for a fresh Welcome for this group.
 */
internal suspend fun WhiteNoiseAppState.forgetGroupLocalWithClientCleanup(
    account: String,
    groupIdHex: String,
): Boolean {
    conversationDictation.onTargetRemoved(account, groupIdHex)
    evictGroupMediaCaches(account, groupIdHex)
    deleteDraftBeforeGroupRemoval(account, groupIdHex)
    val reset = marmotIo { forgetGroupLocal(account, groupIdHex) }
    removeComposerExpansionForGroup(account, groupIdHex)
    dismissConversationNotifications(account, groupIdHex)
    return reset
}
