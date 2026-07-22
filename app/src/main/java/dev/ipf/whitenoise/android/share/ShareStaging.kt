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

/**
 * In-memory share stream staging keyed by `"<accountIdHex> <groupIdHex>"`.
 * Not persisted — URI grants are session-scoped. Text shares use [DraftStore] instead.
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
