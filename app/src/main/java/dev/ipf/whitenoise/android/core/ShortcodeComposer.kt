package dev.ipf.whitenoise.android.core

import java.util.Locale

/** Composer `:token` completion: find the token being typed and replace it with the chosen emoji. */
object ShortcodeComposer {
    private const val MIN_QUERY_LENGTH = 2
    private const val MAX_QUERY_LENGTH = 64

    /** An open `:token` spanning `[start, caret)` in the text, [query] being the part after the colon. */
    data class ActiveQuery(
        val start: Int,
        val query: String,
    )

    /** Text and caret after an insertion. */
    data class Insertion(
        val text: String,
        val selection: Int,
    )

    /**
     * The token directly before [caret]: a colon at the start of the text or after whitespace,
     * then 2–64 shortcode characters. A closing colon ends completion, as does `12:30`.
     */
    fun activeQuery(
        text: String,
        caret: Int,
    ): ActiveQuery? {
        if (caret !in 0..text.length) {
            return null
        }
        var start = caret
        while (start > 0 && isCodeChar(text[start - 1])) {
            start--
        }
        val colon = start - 1
        val opensWithColon = colon >= 0 && text[colon] == ':'
        val afterBoundary = colon <= 0 || text[colon - 1].isWhitespace()
        return ActiveQuery(colon, text.substring(start, caret)).takeIf {
            opensWithColon && afterBoundary && caret - start in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH
        }
    }

    /** Replaces the open token with [emoji] followed by one space, reusing a space already there. */
    fun insert(
        text: String,
        active: ActiveQuery,
        caret: Int,
        emoji: String,
    ): Insertion {
        val rest = text.substring(caret)
        val replacement = if (rest.firstOrNull()?.isWhitespace() == true) emoji else "$emoji "
        return Insertion(text.substring(0, active.start) + replacement + rest, active.start + emoji.length + 1)
    }

    /** Shortcodes containing [query], ignoring case: prefix matches first, then the rest, each in given order. */
    fun matchingShortcodes(
        shortcodes: List<String>,
        query: String,
    ): List<String> {
        val needle = query.lowercase(Locale.ROOT)
        val matches = shortcodes.filter { it.trim(':').lowercase(Locale.ROOT).contains(needle) }
        val (prefix, rest) = matches.partition { it.trim(':').lowercase(Locale.ROOT).startsWith(needle) }
        return prefix + rest
    }

    private fun isCodeChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
}
