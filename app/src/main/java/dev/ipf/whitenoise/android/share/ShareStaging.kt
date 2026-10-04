package dev.ipf.whitenoise.android.share

import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.ConcurrentHashMap

/** Ephemeral staged stream URIs for one conversation opened from a share intent. */
data class ShareStreamStaging(
    val mediaUris: List<Uri>,
    val documentUris: List<Uri>,
) {
    fun isEmpty(): Boolean = mediaUris.isEmpty() && documentUris.isEmpty()
}

/** Maximum staged media/document URIs per conversation composer shelf. */
const val SHARE_STREAM_MAX_ITEMS = 10

data class CappedShareStreamStaging(
    val accepted: ShareStreamStaging,
    val droppedCount: Int,
)

/**
 * Apply the composer attachment cap before consuming a one-shot staged share.
 * Media and documents share [maxItems] minus all already-queued shelf
 * items so inbound shares never silently discard overflow.
 */
fun capShareStreamStaging(
    staging: ShareStreamStaging,
    existingMediaCount: Int = 0,
    existingDocumentCount: Int = 0,
    maxItems: Int = SHARE_STREAM_MAX_ITEMS,
): CappedShareStreamStaging {
    if (staging.isEmpty() || maxItems <= 0) {
        return CappedShareStreamStaging(
            accepted = ShareStreamStaging(emptyList(), emptyList()),
            droppedCount = if (maxItems <= 0) staging.mediaUris.size + staging.documentUris.size else 0,
        )
    }
    val room = (maxItems - existingMediaCount - existingDocumentCount).coerceAtLeast(0)
    val acceptedMedia = staging.mediaUris.take(room)
    val acceptedDocuments = staging.documentUris.take((room - acceptedMedia.size).coerceAtLeast(0))
    val incomingCount = staging.mediaUris.size + staging.documentUris.size
    val acceptedCount = acceptedMedia.size + acceptedDocuments.size
    return CappedShareStreamStaging(
        accepted = ShareStreamStaging(mediaUris = acceptedMedia, documentUris = acceptedDocuments),
        droppedCount = (incomingCount - acceptedCount).coerceAtLeast(0),
    )
}

/**
 * In-memory share stream staging keyed by `"<accountIdHex> <groupIdHex>"`.
 * Private intake files have separate durable account/chat leases; this is their one-shot UI handoff.
 * Text shares use [DraftStore] instead.
 */
class ShareStagingStore {
    private val pending = ConcurrentHashMap<String, ShareStreamStaging>()
    private val revisionState = mutableIntStateOf(0)

    /** Compose-observable revision used to wake an already-mounted conversation. */
    val revision: Int
        get() = revisionState.intValue

    fun stage(
        accountIdHex: String,
        groupIdHex: String,
        staging: ShareStreamStaging,
    ) {
        if (staging.isEmpty()) return
        val key = draftKey(accountIdHex, groupIdHex)
        pending.merge(key, staging) { existing, incoming ->
            ShareStreamStaging(
                mediaUris = (existing.mediaUris + incoming.mediaUris).distinct(),
                documentUris = (existing.documentUris + incoming.documentUris).distinct(),
            )
        }
        revisionState.intValue += 1
    }

    /** Wake a mounted composer after a text-only share updates its draft. */
    internal fun notifyTextStaged() {
        revisionState.intValue += 1
    }

    fun consume(
        accountIdHex: String,
        groupIdHex: String,
    ): ShareStreamStaging? = pending.remove(draftKey(accountIdHex, groupIdHex))

    fun consumeCapped(
        accountIdHex: String,
        groupIdHex: String,
        existingMediaCount: Int,
        existingDocumentCount: Int,
        maxItems: Int = SHARE_STREAM_MAX_ITEMS,
    ): CappedShareStreamStaging? {
        val key = draftKey(accountIdHex, groupIdHex)
        val staging = pending[key] ?: return null
        val capped =
            capShareStreamStaging(
                staging = staging,
                existingMediaCount = existingMediaCount,
                existingDocumentCount = existingDocumentCount,
                maxItems = maxItems,
            )
        pending.remove(key)
        return capped
    }

    private fun draftKey(
        accountIdHex: String,
        groupIdHex: String,
    ): String = "$accountIdHex $groupIdHex"
}

/**
 * Split inbound share streams into the conversation media shelf vs document shelf,
 * mirroring the composer attachment picker paths.
 */
fun classifyShareStreams(
    uris: List<Uri>,
    resolveMime: (Uri) -> String,
    intentMimeType: String? = null,
): ShareStreamStaging {
    if (uris.isEmpty()) return ShareStreamStaging(emptyList(), emptyList())
    val media = mutableListOf<Uri>()
    val documents = mutableListOf<Uri>()
    uris.forEach { uri ->
        val mime = resolveMime(uri).ifBlank { intentMimeType.orEmpty() }
        when {
            mime.startsWith("image/", ignoreCase = true) ||
                mime.startsWith("video/", ignoreCase = true) -> media.add(uri)
            else -> documents.add(uri)
        }
    }
    return ShareStreamStaging(mediaUris = media.distinct(), documentUris = documents.distinct())
}

/** Actual private-file sizes share the 10-item/256 MiB raw-source shelf budget. */
internal fun capPrivateShareShelf(
    uris: List<Uri>,
    metadata: (Uri) -> org.json.JSONObject?,
): List<Uri> {
    var bytes = 0L
    val accepted = mutableListOf<Uri>()
    uris.distinct().forEach { uri ->
        val size = metadata(uri)?.optLong("size", -1) ?: -1
        if (size > 0 && bytes + size <= PRIVATE_SHARE_BATCH_MAX_BYTES && accepted.size < SHARE_STREAM_MAX_ITEMS) {
            accepted += uri
            bytes += size
        }
    }
    return accepted
}
