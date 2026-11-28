package dev.ipf.whitenoise.android.ui.devices

import java.text.BreakIterator

/** The most user-perceived characters of a sign-in's self-reported app name that are shown. */
internal const val SIGN_IN_CLIENT_MAX_CHARACTERS = 32

private const val FIRST_STRONG_ISOLATE = '\u2068'
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'
private const val ELLIPSIS = '\u2026'

/**
 * A sign-in's self-reported app name made safe to show as a claim, or null when it names nothing. Whoever holds the
 * key writes this name, so control and formatting characters (bidi overrides, zero-width marks, line breaks) are
 * dropped, runs of spaces become one, the length is capped at [SIGN_IN_CLIENT_MAX_CHARACTERS] without splitting a
 * character, and the result is wrapped in a first-strong isolate so it cannot reorder the sentence around it.
 */
internal fun signInClientClaim(client: String): String? {
    val visible =
        buildString {
            client.codePoints().forEach { codePoint ->
                when {
                    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> append(' ')
                    hidden(codePoint) -> Unit
                    else -> appendCodePoint(codePoint)
                }
            }
        }.replace(Regex(" {2,}"), " ").trim()
    if (visible.isEmpty()) return null
    return "$FIRST_STRONG_ISOLATE${capped(visible)}$POP_DIRECTIONAL_ISOLATE"
}

/** Control, format, separator, private-use and unassigned code points, which draw nothing or something misleading. */
private fun hidden(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.CONTROL,
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.PRIVATE_USE,
        Character.SURROGATE,
        Character.UNASSIGNED,
        -> true
        else -> false
    }

/** [text] cut after [SIGN_IN_CLIENT_MAX_CHARACTERS] user-perceived characters, with an ellipsis when cut. */
private fun capped(text: String): String {
    val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
    var end = characters.first()
    repeat(SIGN_IN_CLIENT_MAX_CHARACTERS) {
        val next = characters.next()
        if (next == BreakIterator.DONE) return text
        end = next
    }
    return if (end >= text.length) text else text.substring(0, end).trimEnd() + ELLIPSIS
}
