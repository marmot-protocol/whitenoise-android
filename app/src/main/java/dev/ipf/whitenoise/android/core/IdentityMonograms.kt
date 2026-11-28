package dev.ipf.whitenoise.android.core

import java.text.BreakIterator

private val WHITESPACE = Regex("\\s+")
private val LEADING_SHORTCODE = Regex("^:[A-Za-z0-9_-]{1,64}:")

// BreakIterator is not thread-safe, so keep one per thread rather than
// allocating a fresh instance on every initials() call.
private val graphemeBreaker = ThreadLocal.withInitial { BreakIterator.getCharacterInstance() }

/**
 * Single-glyph monogram for a display name: the first letter, else the first emoji grapheme, else the DM
 * fallback. A leading `:shortcode:` that [emojiShortcode] accepts counts as one emoji grapheme, so a custom
 * emoji is chosen or skipped exactly like a Unicode one.
 */
internal fun formatIdentityMonogram(
    name: String,
    emojiShortcode: (String) -> Boolean = { false },
): String {
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
            words.size >= 2 ->
                listOfNotNull(
                    leadingUnits(words[0], 1, emojiShortcode).firstOrNull(),
                    leadingUnits(words[1], 1, emojiShortcode).firstOrNull(),
                )
            words.size == 1 -> leadingUnits(words[0], limit = 2, emojiShortcode)
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

/** Up to [limit] leading units of [value]: grapheme clusters, with an accepted `:shortcode:` as one unit. */
private fun leadingUnits(
    value: String,
    limit: Int,
    emojiShortcode: (String) -> Boolean,
): List<String> {
    val code = LEADING_SHORTCODE.find(value)?.value?.takeIf(emojiShortcode)
    if (code == null) return firstGraphemes(value, limit)
    val rest =
        if (limit > 1) leadingUnits(value.substring(code.length), limit - 1, emojiShortcode) else emptyList()
    return listOf(code) + rest
}

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
