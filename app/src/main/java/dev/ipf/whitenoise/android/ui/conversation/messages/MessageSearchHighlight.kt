package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * What the conversation's open search is marking, or null when search is closed.
 *
 * [matchedMessageIds] is the same set the match counter and the navigation arrows use. A bubble
 * outside it must not be marked even when the query appears in its displayed text: a reaction row,
 * a deleted tombstone or an agent stream can show text that search itself never counts, and marking
 * one would promise a result the arrows can never reach.
 *
 * Carried as a local so every rendered bubble can mark its own matches without this being threaded
 * through each layer between the screen and a text leaf.
 */
internal data class ConversationSearchMarking(
    val needle: String,
    val matchedMessageIds: Set<String>,
) {
    /** Whether this message is one search counted, and so one whose text may be marked. */
    fun marks(messageIdHex: String): Boolean = messageIdHex in matchedMessageIds
}

internal val LocalConversationSearchMarking = compositionLocalOf<ConversationSearchMarking?> { null }

/** Marker paint for search matches, resolved from the bubble the text sits on. */
internal data class MessageSearchHighlight(
    val needle: String,
    val fill: Color,
)

/**
 * Every occurrence of [needle] in [text], case-insensitively, oldest first and non-overlapping.
 *
 * All occurrences are marked rather than only the first: a long message that mentions the term
 * repeatedly should show the reader where each one is, not just where the match was detected.
 */
internal fun messageSearchMatchRanges(
    text: String,
    needle: String,
): List<IntRange> {
    val trimmed = needle.trim()
    val haystack = text.lowercase(Locale.ROOT)
    val target = trimmed.lowercase(Locale.ROOT)
    // Lowercasing can change length for some scripts, which would misplace every span, so those
    // bodies are left unmarked rather than marked in the wrong place.
    val markable = trimmed.isNotEmpty() && text.isNotEmpty() && haystack.length == text.length
    if (!markable) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from <= haystack.length - target.length) {
        val at = haystack.indexOf(target, from)
        if (at < 0) break
        ranges += at until (at + target.length)
        from = at + target.length
    }
    return ranges
}

/**
 * Paints a marker behind each matched range.
 *
 * Drawn behind the text rather than as a text span so it reads the same on a white incoming bubble,
 * a tinted outgoing one and a custom colour, and so it never alters the glyphs themselves: a match
 * must not change how the message is worded or weighted.
 */
internal fun Modifier.messageSearchHighlight(
    layoutResult: TextLayoutResult?,
    ranges: List<IntRange>,
    highlight: MessageSearchHighlight?,
): Modifier {
    if (layoutResult == null || highlight == null || ranges.isEmpty()) return this
    return drawBehind {
        val radius = CornerRadius(MESSAGE_SEARCH_HIGHLIGHT_RADIUS_DP.dp.toPx())
        val padding = MESSAGE_SEARCH_HIGHLIGHT_PADDING_DP.dp.toPx()
        ranges.forEach { range ->
            val boxes = searchHighlightBoxes(layoutResult, range)
            boxes.forEach { box ->
                drawRoundRect(
                    color = highlight.fill,
                    topLeft = Offset(box.left - padding, box.top),
                    size = Size(box.width + padding * 2, box.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/** One rectangle per line the range covers, so a match that wraps is marked on both lines. */
private fun searchHighlightBoxes(
    layoutResult: TextLayoutResult,
    range: IntRange,
): List<SearchHighlightBox> {
    val end = minOf(range.last, layoutResult.layoutInput.text.length - 1)
    if (range.first > end) return emptyList()
    val firstLine = layoutResult.getLineForOffset(range.first)
    val lastLine = layoutResult.getLineForOffset(end)
    return (firstLine..lastLine).mapNotNull { line ->
        val lineStart = layoutResult.getLineStart(line)
        val lineEnd = layoutResult.getLineEnd(line, visibleEnd = true)
        val from = maxOf(range.first, lineStart)
        val to = minOf(end + 1, lineEnd)
        if (from >= to) {
            null
        } else {
            val left = layoutResult.getHorizontalPosition(from, usePrimaryDirection = true)
            val right = layoutResult.getHorizontalPosition(to, usePrimaryDirection = true)
            SearchHighlightBox(
                left = minOf(left, right),
                top = layoutResult.getLineTop(line),
                width = kotlin.math.abs(right - left),
                height = layoutResult.getLineBottom(line) - layoutResult.getLineTop(line),
            )
        }
    }
}

private data class SearchHighlightBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private const val MESSAGE_SEARCH_HIGHLIGHT_RADIUS_DP = 4
private const val MESSAGE_SEARCH_HIGHLIGHT_PADDING_DP = 2

/** Opacity of the marker over the bubble's own content colour, readable without obscuring glyphs. */
internal const val MESSAGE_SEARCH_HIGHLIGHT_ALPHA = 0.28f
