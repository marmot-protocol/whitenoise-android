@file:Suppress("ReturnCount") // Length-prefixed parsing rejects malformed input at the exact failing token.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import java.nio.charset.StandardCharsets
import java.util.UUID

/** One occurrence in the composer shelf; duplicate URIs intentionally get distinct IDs. */
internal data class PendingMediaSlot(
    val id: String,
    val uri: Uri,
) {
    init {
        require(id.isNotBlank())
    }
}

internal fun newPendingMediaSlot(uri: Uri): PendingMediaSlot =
    PendingMediaSlot(
        id = UUID.randomUUID().toString(),
        uri = uri,
    )

internal fun appendPendingMediaSlots(
    current: List<PendingMediaSlot>,
    uris: List<Uri>,
    maxItems: Int,
    createSlot: (Uri) -> PendingMediaSlot = ::newPendingMediaSlot,
): List<PendingMediaSlot> {
    val available = (maxItems - current.size).coerceAtLeast(0)
    if (available == 0 || uris.isEmpty()) return current
    return current + uris.take(available).map(createSlot)
}

/**
 * Saves both occurrence identity and URI through rotation/process recreation.
 * The length-prefixed payload has no delimiter assumptions about provider URIs.
 */
internal val PendingMediaSlotListSaver: Saver<List<PendingMediaSlot>, String> =
    Saver(
        save = { slots ->
            encodePendingMediaSlotTokens(slots.map { slot -> slot.id to slot.uri.toString() })
        },
        restore = { encoded ->
            val tokens =
                if (encoded.startsWith(SLOT_ENCODING_PREFIX) || encoded.isEmpty()) {
                    decodePendingMediaSlotTokens(encoded).orEmpty()
                } else {
                    decodeUriListTokens(encoded).mapIndexed { index, uri ->
                        legacySlotId(index, uri) to uri
                    }
                }
            tokens.map { (id, uri) -> PendingMediaSlot(id, Uri.parse(uri)) }
        },
    )

internal fun encodePendingMediaSlotTokens(tokens: List<Pair<String, String>>): String {
    if (tokens.isEmpty()) return ""
    return buildString {
        append(SLOT_ENCODING_PREFIX)
        tokens.forEach { (id, uri) ->
            append(id.length)
            append(':')
            append(id)
            append(uri.length)
            append(':')
            append(uri)
        }
    }
}

internal fun decodePendingMediaSlotTokens(encoded: String): List<Pair<String, String>>? {
    if (encoded.isEmpty()) return emptyList()
    if (!encoded.startsWith(SLOT_ENCODING_PREFIX)) return null
    val result = mutableListOf<Pair<String, String>>()
    var cursor = SLOT_ENCODING_PREFIX.length
    while (cursor < encoded.length) {
        val idLength = encoded.readLengthAt(cursor) ?: return null
        cursor = idLength.nextIndex
        if (idLength.value == 0 || cursor + idLength.value > encoded.length) return null
        val id = encoded.substring(cursor, cursor + idLength.value)
        cursor += idLength.value

        val uriLength = encoded.readLengthAt(cursor) ?: return null
        cursor = uriLength.nextIndex
        if (uriLength.value == 0 || cursor + uriLength.value > encoded.length) return null
        val uri = encoded.substring(cursor, cursor + uriLength.value)
        cursor += uriLength.value
        result += id to uri
    }
    return result
}

private data class EncodedLength(
    val value: Int,
    val nextIndex: Int,
)

private fun String.readLengthAt(start: Int): EncodedLength? {
    val colon = indexOf(':', start)
    if (colon <= start) return null
    val value = substring(start, colon).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return EncodedLength(value, colon + 1)
}

private fun legacySlotId(
    index: Int,
    uri: String,
): String = UUID.nameUUIDFromBytes("$index\u0000$uri".toByteArray(StandardCharsets.UTF_8)).toString()

private const val SLOT_ENCODING_PREFIX = "media-slots-v1:"
