package dev.ipf.whitenoise.android.notifications

import android.graphics.Bitmap

/** Pixels identify their owning source; only earlier missing sources may retain an existing launcher icon. */
internal data class PinnedConversationPicture(
    val bitmap: Bitmap?,
    val identity: String? = null,
    val missingIdentities: Set<String> = emptySet(),
)

/**
 * Resolve in presentation order and stop at the first cache hit. A trimmed primary can keep its platform
 * pixels even when a fallback is cached; an old fallback cannot override a newly available primary.
 * Only opaque digests reach launcher extras, never an image URL or private file handle.
 */
internal fun resolvePinnedConversationPicture(
    account: String,
    lifetime: Long,
    sources: Sequence<Pair<String, () -> Bitmap?>>,
): PinnedConversationPicture {
    val missing = mutableSetOf<String>()
    for ((key, read) in sources) {
        val identity = sha256Hex("pinned-picture\u0000${account.length}:$account:$lifetime:${key.length}:$key")
        val bitmap = read()
        if (bitmap != null) return PinnedConversationPicture(bitmap, identity, missing)
        missing += identity
    }
    return PinnedConversationPicture(null, missingIdentities = missing)
}
