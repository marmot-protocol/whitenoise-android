package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi

/** Keeps typed timestamps readable as canonical markup until localized timestamp rendering is adopted. */
internal fun markdownTimestampLiteral(timestamp: MarkdownInlineFfi.Timestamp): String {
    val style =
        when (timestamp.style) {
            MarkdownTimestampStyleFfi.SHORT_TIME -> 't'
            MarkdownTimestampStyleFfi.LONG_TIME -> 'T'
            MarkdownTimestampStyleFfi.SHORT_DATE -> 'd'
            MarkdownTimestampStyleFfi.LONG_DATE -> 'D'
            MarkdownTimestampStyleFfi.SHORT_DATE_TIME -> 'f'
            MarkdownTimestampStyleFfi.LONG_DATE_TIME -> 'F'
            MarkdownTimestampStyleFfi.COMPACT_DATE_TIME -> 's'
            MarkdownTimestampStyleFfi.COMPACT_DATE_TIME_SECONDS -> 'S'
            MarkdownTimestampStyleFfi.RELATIVE -> 'R'
        }
    return "<t:${timestamp.unixSeconds}:$style>"
}
