package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.ui.conversation.media.isPendingVideo
import dev.ipf.whitenoise.android.ui.conversation.media.pendingVideoPosterFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun mediaCacheKey(
    account: String,
    groupIdHex: String,
    messageIdHex: String,
    attachmentIndex: Int,
): String = "$account|$groupIdHex|$messageIdHex|$attachmentIndex"

/**
 * True when [cacheKey] was minted by [mediaCacheKey] for this account+group.
 * The retention sweep uses this to drop a pruned group's decrypted in-memory
 * entries: the engine prunes atomically, so no pre-prune media rows survive
 * to map ciphertext tags to exact keys, and the whole group slice goes.
 * Case-insensitive: hex ids drift in casing across sources, matching the
 * projector's comparisons and the chat list's lowercased row keys.
 */
internal fun mediaCacheKeyInGroup(
    cacheKey: String,
    account: String,
    groupIdHex: String,
): Boolean = cacheKey.startsWith("$account|$groupIdHex|", ignoreCase = true)

/**
 * The keys in this account+group's L1 slice whose message row is no longer
 * loaded. The bounded timeline window trims old rows, so an expired-tag
 * mapping for them is gone; the open conversation's sweep drops these
 * fail-closed rather than let a pruned attachment's decrypted bytes outlive
 * the prune, while loaded rows keep their entries (no on-screen refetch).
 */
internal fun staleGroupMediaCacheKeys(
    cachedKeys: Collection<String>,
    account: String,
    groupIdHex: String,
    loadedMessageIds: Set<String>,
): List<String> {
    val loadedLowercase = loadedMessageIds.mapTo(HashSet()) { it.lowercase() }
    return cachedKeys.filter { key ->
        mediaCacheKeyInGroup(key, account, groupIdHex) &&
            key.split('|').getOrNull(2)?.lowercase() !in loadedLowercase
    }
}

internal suspend fun removeMediaMemoryCacheKeys(
    cacheKeys: Iterable<String>,
    dispatcher: CoroutineDispatcher,
    removeEntry: (String) -> Unit,
) {
    withContext(dispatcher) {
        cacheKeys.forEach(removeEntry)
    }
}

/**
 * The poster seeded under a just-sent attachment's confirmed key.
 *
 * A photo decodes its own bytes. A video has a frame pulled from the same bytes instead, so the
 * confirmed bubble opens on the poster the optimistic one already showed rather than waiting for
 * the file to be materialized and read again (#2732). A photo decodes in [domain]'s worker.
 */
internal suspend fun decodeMediaThumbnailOffMain(
    attachment: PendingAttachment,
    domain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
): android.graphics.Bitmap? {
    // A file-backed item has no bytes here; its confirmed bubble paints the thumbhash, then MDK's copy.
    val bytes = attachment.inMemoryBytes ?: return null
    return withContext(Dispatchers.Default) {
        if (attachment.isPendingVideo) {
            pendingVideoPosterFrame(bytes, extractPoster = true).bitmap
        } else {
            MediaPipeline.decodeSampledBitmap(
                bytes,
                MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                domain = domain,
            )
        }
    }
}
