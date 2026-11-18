package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrBefore

internal sealed interface ConversationDictationMerge {
    data class Applied(
        val value: TextFieldValue,
    ) : ConversationDictationMerge

    data object NeedsAppendFallback : ConversationDictationMerge
}

/**
 * Merges a recognized segment at the captured selection when concurrent draft edits can be
 * remapped unambiguously; otherwise asks the caller to append against the latest draft snapshot.
 */
@Suppress("ReturnCount")
internal fun mergeConversationDictationTranscript(
    captured: TextFieldValue,
    current: TextFieldValue,
    transcript: String,
): ConversationDictationMerge {
    val recognized = transcript.trim()
    if (recognized.isEmpty()) return ConversationDictationMerge.Applied(current)

    if (current.text == captured.text) {
        return ConversationDictationMerge.Applied(
            insertConversationDictationTranscript(current, captured.selection, recognized),
        )
    }

    val remappedSelection =
        remapConversationDictationSelection(captured, current)
            ?: return ConversationDictationMerge.NeedsAppendFallback
    return ConversationDictationMerge.Applied(
        insertConversationDictationTranscript(current, remappedSelection, recognized),
    )
}

/**
 * Re-anchors a dictation on the draft as it stands now, with the transcript's insertion point remapped
 * exactly as [mergeConversationDictationTranscript] would place it, or at the end when that is ambiguous.
 */
internal fun ConversationDictationTarget.rebasedOnto(current: ConversationDictationDraftSnapshot) =
    when {
        current.revision == capturedDraftRevision && current.value.text == capturedDraft.text -> this
        current.value.text == capturedDraft.text -> copy(capturedDraftRevision = current.revision)
        else -> {
            val selection =
                remapConversationDictationSelection(capturedDraft, current.value)
                    ?: TextRange(current.value.text.length)
            copy(
                capturedDraft = TextFieldValue(current.value.text, selection),
                capturedDraftRevision = current.revision,
            )
        }
    }

/** Appends a recognized segment to the draft and places the cursor after the inserted text. */
internal fun appendConversationDictationTranscript(
    current: TextFieldValue,
    transcript: String,
): TextFieldValue {
    val recognized = transcript.trim()
    if (recognized.isEmpty()) return current
    val separator =
        when {
            current.text.isBlank() -> ""
            current.text.last().isWhitespace() -> ""
            else -> " "
        }
    val appended = current.text + separator + recognized
    return TextFieldValue(appended, TextRange(appended.length))
}

/** Replaces a grapheme-safe selection while preserving readable word boundaries around it. */
private fun insertConversationDictationTranscript(
    current: TextFieldValue,
    selection: TextRange,
    transcript: String,
): TextFieldValue {
    val rawStart = minOf(selection.start, selection.end).coerceIn(0, current.text.length)
    val rawEnd = maxOf(selection.start, selection.end).coerceIn(rawStart, current.text.length)
    val start = current.text.graphemeBoundaryAtOrBefore(rawStart)
    val end = current.text.graphemeBoundaryAtOrAfter(rawEnd)
    val needsLeadingSpace =
        start > 0 &&
            !current.text[start - 1].isWhitespace() &&
            !transcript.first().isWhitespace()
    val needsTrailingSpace =
        end < current.text.length &&
            Character.isLetterOrDigit(current.text.codePointAt(end)) &&
            !transcript.last().isWhitespace()
    val insertion =
        buildString {
            if (needsLeadingSpace) append(' ')
            append(transcript)
            if (needsTrailingSpace) append(' ')
        }
    val inserted = current.text.replaceRange(start, end, insertion)
    return TextFieldValue(inserted, TextRange(start + insertion.length))
}

/**
 * Maps the captured insertion range into a concurrently edited draft using
 * unchanged context on both sides. A unique best anchor is required; edits
 * that remove the selection or make its location ambiguous fail closed so the
 * transcript can be offered for explicit insertion/copying.
 */
@Suppress("ReturnCount")
private fun remapConversationDictationSelection(
    captured: TextFieldValue,
    current: TextFieldValue,
): TextRange? {
    if (captured.text.isEmpty()) return null
    val rawStart = minOf(captured.selection.start, captured.selection.end).coerceIn(0, captured.text.length)
    val rawEnd = maxOf(captured.selection.start, captured.selection.end).coerceIn(rawStart, captured.text.length)
    val start = captured.text.graphemeBoundaryAtOrBefore(rawStart)
    val end = captured.text.graphemeBoundaryAtOrAfter(rawEnd)
    val selected = captured.text.substring(start, end)
    val leftContext = captured.text.substring(0, start)
    val rightContext = captured.text.substring(end)

    val candidates =
        if (selected.isEmpty()) {
            EmptySelectionCandidateWindow.create(
                capturedOffset = start,
                capturedTextLength = captured.text.length,
                currentText = current.text,
            )
        } else {
            current.text
                .occurrenceStarts(selected)
                .map { occurrenceStart -> TextRange(occurrenceStart, occurrenceStart + selected.length) }
        }.filter { range ->
            current.text.isGraphemeBoundary(range.start) && current.text.isGraphemeBoundary(range.end)
        }
    if (candidates.isEmpty()) return null

    val scored =
        candidates.map { candidate ->
            candidate to
                (
                    commonSuffixLengthAt(leftContext, current.text, candidate.start) +
                        commonPrefixLengthAt(rightContext, current.text, candidate.end)
                )
        }
    val bestScore = scored.maxOf { it.second }
    val requiredContext = minOf(MIN_ANCHOR_CONTEXT_CHARS, leftContext.length + rightContext.length).coerceAtLeast(1)
    if (selected.isEmpty() && bestScore < requiredContext) return null
    val best = scored.filter { it.second == bestScore }
    return best.singleOrNull()?.first
}

private object EmptySelectionCandidateWindow {
    /**
     * Produces bounded cursor candidates near both the original and length-shifted offsets for
     * large drafts, while exhaustively scanning small drafts.
     */
    fun create(
        capturedOffset: Int,
        capturedTextLength: Int,
        currentText: String,
    ): List<TextRange> {
        val offsets =
            if (currentText.length <= MAX_EMPTY_SELECTION_SCAN_LENGTH) {
                (0..currentText.length).asSequence()
            } else {
                val originalOffset = capturedOffset.coerceIn(0, currentText.length)
                val shiftedOffset =
                    (capturedOffset + currentText.length - capturedTextLength)
                        .coerceIn(0, currentText.length)
                sequenceOf(originalOffset, shiftedOffset)
                    .distinct()
                    .flatMap { center ->
                        val first = (center - EMPTY_SELECTION_SCAN_RADIUS).coerceAtLeast(0)
                        val last = (center + EMPTY_SELECTION_SCAN_RADIUS).coerceAtMost(currentText.length)
                        (first..last).asSequence()
                    }.distinct()
            }
        return offsets.map(::TextRange).toList()
    }
}

/** Returns every possibly overlapping occurrence used to remap a captured non-empty selection. */
private fun String.occurrenceStarts(needle: String): List<Int> {
    if (needle.isEmpty()) return (0..length).toList()
    val starts = mutableListOf<Int>()
    var from = 0
    while (from <= length - needle.length) {
        val found = indexOf(needle, startIndex = from)
        if (found < 0) break
        starts += found
        from = found + 1
    }
    return starts
}

/** Reports whether [index] is a legal cursor boundary rather than the middle of a grapheme. */
@Suppress("MaxLineLength")
private fun String.isGraphemeBoundary(index: Int): Boolean = index in 0..length && graphemeBoundaryAtOrBefore(index) == index

/** Scores matching right-side anchor context starting at [startInclusive]. */
private fun commonPrefixLengthAt(
    context: String,
    text: String,
    startInclusive: Int,
): Int {
    val limit = minOf(context.length, text.length - startInclusive, MAX_ANCHOR_SCORE_CHARS)
    var index = 0
    while (index < limit && context[index] == text[startInclusive + index]) index += 1
    return index
}

/** Scores matching left-side anchor context ending at [endExclusive]. */
private fun commonSuffixLengthAt(
    context: String,
    text: String,
    endExclusive: Int,
): Int {
    val limit = minOf(context.length, endExclusive, MAX_ANCHOR_SCORE_CHARS)
    var count = 0
    while (
        count < limit &&
        context[context.length - 1 - count] == text[endExclusive - 1 - count]
    ) {
        count += 1
    }
    return count
}

private const val MIN_ANCHOR_CONTEXT_CHARS = 3
private const val MAX_ANCHOR_SCORE_CHARS = 64
private const val MAX_EMPTY_SELECTION_SCAN_LENGTH = 4_096
private const val EMPTY_SELECTION_SCAN_RADIUS = 1_024
