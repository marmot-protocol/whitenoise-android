package dev.ipf.whitenoise.android.core

import java.text.BreakIterator

private val WHITESPACE = Regex("\\s+")

// BreakIterator is not thread-safe, so keep one per thread rather than
// allocating a fresh instance on every initials() call.
private val graphemeBreaker = ThreadLocal.withInitial { BreakIterator.getCharacterInstance() }

/**
 * Single-glyph monogram for a display name: the first letter, else the first emoji grapheme, else the DM
 * fallback.
 */
internal fun formatIdentityMonogram(name: String): String {
    val words =
        name
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
    // The prototype's monogram is one glyph. Candidates are whole grapheme
    // clusters so emoji, surrogate pairs and ZWJ sequences (👨‍👩‍👧, 🏃‍♂️) are
    // never split into a lone surrogate half (#112): the lead grapheme of
    // the first two words, or the first two graphemes of a single word.
    val candidates =
        when {
            words.size >= 2 -> listOfNotNull(firstGrapheme(words[0]), firstGrapheme(words[1]))
            words.size == 1 -> firstGraphemes(words[0], limit = 2)
            else -> emptyList()
        }
    if (candidates.isEmpty()) return "DM"
    // Prefer a letter: an emoji or symbol rendered alone in the avatar circle
    // clips or shows as tofu, so the first letter wins when the name has one
    // ("Alice 😀" → "A", "😀 Alice" → "A"). Only a name with no letters at
    // all falls back to its first emoji grapheme ("😀🔥" → "😀"). See #427.
    val letters = candidates.filter { isLetter(it) }
    return if (letters.isNotEmpty()) {
        letters.first().uppercase()
    } else {
        candidates.first()
    }
}

/** First grapheme cluster of [value], or null when empty. */
private fun firstGrapheme(value: String): String? = firstGraphemes(value, limit = 1).firstOrNull()

/** Up to [limit] leading grapheme clusters of [value]. */
private fun firstGraphemes(
    value: String,
    limit: Int,
): List<String> {
    if (value.isEmpty()) return emptyList()
    // withInitial never yields null, but ThreadLocal.get() is typed nullable.
    val boundaries = graphemeBreaker.get()!!
    boundaries.setText(value)
    val out = mutableListOf<String>()
    var start = boundaries.first()
    var end = boundaries.next()
    while (end != BreakIterator.DONE && out.size < limit) {
        out.add(value.substring(start, end))
        start = end
        end = boundaries.next()
    }
    return out
}

private fun isLetter(grapheme: String): Boolean = grapheme.isNotEmpty() && Character.isLetter(grapheme.codePointAt(0))
