package dev.ipf.whitenoise.android.ui.conversation.composer

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
