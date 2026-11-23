package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.codePointBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.codePointBoundaryAtOrBefore
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrBefore

/**
 * Inserts an emoji at the composer's current selection, replacing any selected
 * range and moving the caret just after the inserted glyph. Kept pure so the
 * cursor math is pinned by local unit tests instead of only by Compose wiring.
 */
internal fun insertEmojiAtSelection(
    value: TextFieldValue,
    emoji: String,
): TextFieldValue {
    val text = value.text
    val rawStart = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val rawEnd = maxOf(value.selection.start, value.selection.end).coerceIn(rawStart, text.length)
    val collapsed = rawStart == rawEnd
    val start =
        if (collapsed) {
            text.graphemeBoundaryAtOrAfter(rawStart)
        } else {
            text.graphemeBoundaryAtOrBefore(rawStart)
        }
    val end = if (collapsed) start else text.graphemeBoundaryAtOrAfter(rawEnd)
    val updatedText =
        buildString {
            append(text, 0, start)
            append(emoji)
            append(text, end, text.length)
        }
    val caret = start + emoji.length
    return value.copy(text = updatedText, selection = TextRange(caret), composition = null)
}

/** Deletes the selected range, or the code point before a clamped caret. */
internal fun deleteComposerSelectionOrPreviousCodePoint(value: TextFieldValue): TextFieldValue? {
    val text = value.text
    val rawStart = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val rawEnd = maxOf(value.selection.start, value.selection.end).coerceIn(rawStart, text.length)
    val hasSelection = rawStart != rawEnd
    val rangeStart =
        if (hasSelection) {
            text.codePointBoundaryAtOrBefore(rawStart)
        } else {
            text.codePointBoundaryAtOrAfter(rawStart)
        }
    val rangeEnd = if (hasSelection) text.codePointBoundaryAtOrAfter(rawEnd) else rangeStart
    if (rangeStart == 0 && !hasSelection) return null
    val deleteStart = if (hasSelection) rangeStart else text.offsetByCodePoints(rangeStart, -1)
    val updatedText = text.removeRange(deleteStart, rangeEnd)
    return value.copy(text = updatedText, selection = TextRange(deleteStart), composition = null)
}

/**
 * Keeps mention chips and rendered [emojiShortcodes] whole across deletions and caret moves, then clamps the
 * selection out of chips when [clampMentionSelection] is set.
 */
internal fun repairComposerMentionEdit(
    oldValue: TextFieldValue,
    proposedValue: TextFieldValue,
    clampMentionSelection: Boolean,
    emojiShortcodes: Set<String> = emptySet(),
): TextFieldValue {
    val repaired =
        MentionComposer.wholeChipBackspace(
            oldText = oldValue.text,
            oldCaret = oldValue.selection.start,
            newText = proposedValue.text,
            newCaret = proposedValue.selection.start,
        )
            ?: MentionComposer.repairChipDeletion(
                oldText = oldValue.text,
                newText = proposedValue.text,
                includeAdjacentOwnedSeparator = oldValue.selection.collapsed,
            )
    val edited =
        clampComposerEmojiSelection(
            oldValue,
            repaired?.let { TextFieldValue(text = it.text, selection = TextRange(it.selection)) }
                ?: repairComposerEmojiDeletion(oldValue, proposedValue, emojiShortcodes)
                ?: proposedValue,
            emojiShortcodes,
        )
    if (!clampMentionSelection) return edited

    val clamped =
        MentionComposer.clampSelectionOutOfChips(
            edited.text,
            edited.selection.start,
            edited.selection.end,
        )
    return if (clamped.start != edited.selection.start || clamped.end != edited.selection.end) {
        edited.copy(selection = TextRange(clamped.start, clamped.end))
    } else {
        edited
    }
}

/**
 * Deleting part of a rendered shortcode deletes all of it, so one backspace removes the emoji instead of
 * revealing a broken `:shortcode`. Null when [proposed] is not a deletion that splits one.
 */
internal fun repairComposerEmojiDeletion(
    old: TextFieldValue,
    proposed: TextFieldValue,
    shortcodes: Set<String>,
): TextFieldValue? {
    val deletion = shortcodes.isNotEmpty() && proposed.text.length < old.text.length
    val deleted = (if (deletion) deletedRange(old.text, proposed) else null) ?: return null
    var start = deleted.first
    var end = deleted.last + 1
    val codes =
        activeComposerEmojiRanges(old, shortcodes).filterNot { composedAfter(it, deleted, proposed.composition) }
    for (code in codes) {
        val overlaps = start < code.end && end > code.start
        if (overlaps && (start > code.start || end < code.end)) {
            start = minOf(start, code.start)
            end = maxOf(end, code.end)
        }
    }
    val widened = start != deleted.first || end != deleted.last + 1
    return if (widened) TextFieldValue(old.text.removeRange(start, end), TextRange(start)) else null
}

/** Whether [code], shifted through the [deleted] range into the proposed draft, touches its [composition]. */
private fun composedAfter(
    code: AnnotatedString.Range<String>,
    deleted: IntRange,
    composition: TextRange?,
): Boolean {
    if (composition == null || composition.collapsed) return false
    val removed = deleted.last + 1 - deleted.first

    /** Maps an old-draft offset into the draft with [deleted] removed. */
    fun shift(offset: Int): Int =
        when {
            offset <= deleted.first -> offset
            offset > deleted.last -> offset - removed
            else -> deleted.first
        }
    return shift(code.start) < composition.max && shift(code.end) > composition.min
}

/** The removed range, preferring the caret so a repeated `::` deletes on the side the user acted on. */
private fun deletedRange(
    oldText: String,
    proposed: TextFieldValue,
): IntRange? {
    val newText = proposed.text
    val removed = oldText.length - newText.length
    val caret = proposed.selection.start.takeIf { proposed.selection.collapsed && it in 0..newText.length }
    val start =
        caret?.takeIf { removed > 0 && oldText.removeRange(it, it + removed) == newText }
            ?: oldText.commonPrefixWith(newText).length
    val end = start + removed
    val consistent = removed > 0 && end <= oldText.length && oldText.removeRange(start, end) == newText
    return if (consistent) start until end else null
}

/**
 * Keeps a moved caret out of a rendered shortcode: moving past one from either side lands on its far edge, so
 * keyboard and space-bar cursor movement step over the emoji in one move.
 */
internal fun clampComposerEmojiSelection(
    old: TextFieldValue,
    edited: TextFieldValue,
    shortcodes: Set<String>,
): TextFieldValue {
    val moved = shortcodes.isNotEmpty() && edited.text == old.text && edited.selection != old.selection
    val ranges = if (moved) activeComposerEmojiRanges(old, shortcodes, edited.composition) else emptyList()

    /** Moves [offset] to the shortcode edge beyond it, judged from where the caret was. */
    fun clamp(
        offset: Int,
        previous: Int,
    ): Int {
        val code = ranges.firstOrNull { offset > it.start && offset < it.end } ?: return offset
        return when {
            previous >= code.end -> code.start
            previous <= code.start -> code.end
            offset - code.start < code.end - offset -> code.start
            else -> code.end
        }
    }
    val start = clamp(edited.selection.start, old.selection.start)
    val end = clamp(edited.selection.end, old.selection.end)
    return if (start == edited.selection.start && end == edited.selection.end) {
        edited
    } else {
        edited.copy(selection = TextRange(start, end))
    }
}
