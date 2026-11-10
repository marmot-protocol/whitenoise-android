package dev.ipf.whitenoise.android.core

import java.net.URI

private const val EMOJI_SET_KIND = 30030u
private const val MAX_SET_LINK_LENGTH = 2048

/** Extracts an emoji-set address from paste or share without ever opening the HTTP URL. */
internal fun emojiSetAddress(input: String): String? {
    val text = input.trim()
    val reference =
        when {
            text.length > MAX_SET_LINK_LENGTH -> null
            text.startsWith("nostr:", ignoreCase = true) -> text.substringAfter(':')
            text.startsWith("https://", ignoreCase = true) ->
                runCatching { URI(text).path?.substringAfterLast('/') }.getOrNull()
            else -> text
        } ?: return null
    val address = NostrEventReferenceDecoder.eventReference(reference) as? NostrEventReference.Address
    return reference.takeIf { address?.kind == EMOJI_SET_KIND }
}
