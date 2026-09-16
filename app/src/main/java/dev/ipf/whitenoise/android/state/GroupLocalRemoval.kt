package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Dispatchers
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
