package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import dev.ipf.whitenoise.android.audio.tts.TtsChunker
import java.util.Locale

/**
 * Maps a rendered visible offset to the speakable sentence index for Speak
 * aloud from here. Returns 0 when mapping is unavailable or ambiguous.
 */
@Suppress("ReturnCount") // Every uncertain projection must fail closed to the deterministic first sentence.
internal fun speakableSentenceIndexAtVisibleOffset(
    visibleText: String,
    speakableText: String,
    visibleOffset: Int?,
    locale: Locale,
): Int {
    if (speakableText.isBlank()) return 0
    if (visibleOffset == null || visibleText.isBlank() || visibleOffset !in 0..visibleText.length) return 0

    val visibleSentence =
        TtsChunker.sentenceIndexAtOffset(visibleText, visibleOffset, locale) ?: return 0
    val visibleSentences = TtsChunker.sentences(visibleText, locale)
    val speakableSentences = TtsChunker.sentences(speakableText, locale)
    if (visibleSentence !in visibleSentences.indices || speakableSentences.isEmpty()) return 0

    val positionallyAligned =
        visibleSentence < speakableSentences.size &&
            (0..visibleSentence).all { index ->
                normalizeSentenceForAlignment(visibleSentences[index]) ==
                    normalizeSentenceForAlignment(speakableSentences[index])
            }
    if (positionallyAligned) return visibleSentence

    val target = normalizeSentenceForAlignment(visibleSentences[visibleSentence])
    val matches =
        speakableSentences.indices.filter { index ->
            normalizeSentenceForAlignment(speakableSentences[index]) == target
        }
    return matches.singleOrNull() ?: 0
}

@Suppress("ReturnCount") // Guard returns reject partial or ambiguous selection-to-layout matches.
internal fun visibleOffsetFromSelection(
    layouts: Collection<SelectableTextLayout>,
    selectedTexts: List<androidx.compose.ui.text.AnnotatedString>,
    preferredVisibleOffset: Int? = null,
): Int? {
    if (selectedTexts.isEmpty()) return null
    val ordered =
        layouts
            .filter { it.coordinates.isAttached }
            .sortedWith { first, second -> compareSelectableTextLayouts(first, second) }
    val selectedSegments = selectedTexts.map { it.text }
    if (ordered.isEmpty() || selectedSegments.any(String::isBlank)) return null

    // SelectionState returns one clipped string per selected Compose Text leaf.
    // Match that contiguous sequence back onto the rendered leaves instead of
    // concatenating it: Markdown block separators are not part of those clipped
    // strings, so concatenation loses every selection spanning two blocks.
    val matches =
        ordered.indices.flatMap { firstLayoutIndex ->
            if (firstLayoutIndex + selectedSegments.lastIndex > ordered.lastIndex) return@flatMap emptyList()
            val candidateLayouts = ordered.drop(firstLayoutIndex).take(selectedSegments.size)
            if (
                selectedSegments.indices.any { segmentIndex ->
                    !candidateLayouts[segmentIndex]
                        .layoutResult.layoutInput.text.text
                        .contains(selectedSegments[segmentIndex])
                }
            ) {
                return@flatMap emptyList()
            }
            substringOffsets(
                text =
                    candidateLayouts
                        .first()
                        .layoutResult.layoutInput.text.text,
                substring = selectedSegments.first(),
            ).map { localOffset ->
                val precedingLength =
                    ordered
                        .take(firstLayoutIndex)
                        .sumOf { visibleLayoutSentence(it.layoutResult.layoutInput.text.text).length } +
                        firstLayoutIndex
                val visibleOffset = precedingLength + localOffset
                SelectionOffsetMatch(
                    visibleOffset = visibleOffset,
                    selectedVisibleLength = selectedSegments.sumOf(String::length) + selectedSegments.lastIndex,
                )
            }
        }
    matches.singleOrNull()?.let { return it.visibleOffset }
    val preferred = preferredVisibleOffset ?: return null
    return matches
        .singleOrNull { match -> preferred in match.visibleOffset until match.selectionEndOffset }
        ?.visibleOffset
}

internal fun concatenatedVisibleText(layouts: Collection<SelectableTextLayout>): String =
    layouts
        .filter { it.coordinates.isAttached }
        .sortedWith { first, second -> compareSelectableTextLayouts(first, second) }
        .joinToString(separator = " ") { layout ->
            visibleLayoutSentence(layout.layoutResult.layoutInput.text.text)
        }

/**
 * Maps a window-space press to a UTF-16 offset in the rendered text snapshot.
 * Separate Compose text nodes are normalized as speakable segments so Markdown
 * list/table/code block boundaries remain sentence boundaries. Missing,
 * out-of-bounds, and ambiguous coordinates return null so callers choose the
 * deterministic fallback.
 */
@Suppress("ReturnCount") // Detached, out-of-bounds, or overlapping layouts must not produce a guessed offset.
internal fun textOffsetAtWindowPosition(
    layouts: Collection<SelectableTextLayout>,
    pressInWindow: Offset?,
): Int? {
    if (pressInWindow == null) return null
    val ordered =
        layouts
            .filter { it.coordinates.isAttached }
            .sortedWith { first, second -> compareSelectableTextLayouts(first, second) }
    val targets =
        ordered.filter { layout ->
            layout.layoutResult.layoutInput.text
                .isNotBlank() &&
                layout.coordinates.boundsInWindow().contains(pressInWindow)
        }
    val target = targets.singleOrNull() ?: return null

    val local = target.coordinates.windowToLocal(pressInWindow)
    val rawOffset =
        target.layoutResult.getOffsetForPosition(
            Offset(
                x =
                    local.x.coerceIn(
                        0f,
                        target.coordinates.size.width
                            .toFloat(),
                    ),
                y =
                    local.y.coerceIn(
                        0f,
                        target.coordinates.size.height
                            .toFloat(),
                    ),
            ),
        )
    val preceding = ordered.takeWhile { it.key !== target.key }
    val precedingLength =
        preceding.sumOf { visibleLayoutSentence(it.layoutResult.layoutInput.text.text).length } + preceding.size
    return precedingLength + rawOffset
}

private fun visibleLayoutSentence(text: String): String {
    val lastVisibleCharacter = text.trimEnd().lastOrNull() ?: return text
    return if (lastVisibleCharacter in ".!?;:,") text else "$text."
}

private data class SelectionOffsetMatch(
    val visibleOffset: Int,
    val selectedVisibleLength: Int,
) {
    val selectionEndOffset: Int
        get() = visibleOffset + selectedVisibleLength
}

private fun substringOffsets(
    text: String,
    substring: String,
): List<Int> =
    buildList {
        var offset = text.indexOf(substring)
        while (offset >= 0) {
            add(offset)
            offset = text.indexOf(substring, offset + 1)
        }
    }

private val sentenceWhitespace = Regex("\\s+")
private val spaceBeforeSentencePunctuation = Regex("\\s+([,.;:!?])")

private fun normalizeSentenceForAlignment(sentence: String): String =
    sentence
        .trim()
        .replace(sentenceWhitespace, " ")
        .replace(spaceBeforeSentencePunctuation, "$1")
        .lowercase(Locale.ROOT)
