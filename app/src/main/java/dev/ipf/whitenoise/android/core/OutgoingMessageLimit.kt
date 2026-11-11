package dev.ipf.whitenoise.android.core

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * Longest message text, in user-perceived characters, that other White Noise clients show in full.
 * Longer text is cut off on arrival without any sign, so Android refuses to send it rather than let
 * the recipient read a silently shortened message.
 */
internal const val MAX_OUTGOING_MESSAGE_CHARACTERS = 8_000

/**
 * Whether [text], exactly as it would be sent (mentions already expanded), is longer than
 * [MAX_OUTGOING_MESSAGE_CHARACTERS]. Characters are counted as grapheme clusters, so an emoji
 * sequence counts once, as it does where the limit is applied on receipt.
 */
internal fun exceedsOutgoingMessageLimit(text: String): Boolean {
    // A grapheme spans at least one UTF-16 unit, so short text never needs the iterator.
    if (text.length <= MAX_OUTGOING_MESSAGE_CHARACTERS) return false
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(text) }
    var count = 0
    while (count <= MAX_OUTGOING_MESSAGE_CHARACTERS && iterator.next() != BreakIterator.DONE) count += 1
    return count > MAX_OUTGOING_MESSAGE_CHARACTERS
}
