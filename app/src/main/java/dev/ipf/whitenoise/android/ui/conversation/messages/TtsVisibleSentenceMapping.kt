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
        ordered.indices.mapNotNull { firstLayoutIndex ->
            if (firstLayoutIndex + selectedSegments.lastIndex > ordered.lastIndex) return@mapNotNull null
            val localOffsets =
                selectedSegments.mapIndexed { segmentIndex, segment ->
                    uniqueSubstringOffset(
                        text =
                            ordered[firstLayoutIndex + segmentIndex]
                                .layoutResult.layoutInput.text.text,
                        substring = segment,
                    ) ?: return@mapNotNull null
                }
            firstLayoutIndex to localOffsets.first()
        }
    val (firstLayoutIndex, localOffset) = matches.singleOrNull() ?: return null
    val precedingLength =
        ordered
            .take(firstLayoutIndex)
            .sumOf { visibleLayoutSentence(it.layoutResult.layoutInput.text.text).length } + firstLayoutIndex
    return precedingLength + localOffset
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

private fun uniqueSubstringOffset(
    text: String,
    substring: String,
): Int? {
    val first = text.indexOf(substring)
    if (first < 0) return null
    return first.takeIf { text.indexOf(substring, first + 1) < 0 }
}

private val sentenceWhitespace = Regex("\\s+")
private val spaceBeforeSentencePunctuation = Regex("\\s+([,.;:!?])")

private fun normalizeSentenceForAlignment(sentence: String): String =
    sentence
        .trim()
        .replace(sentenceWhitespace, " ")
        .replace(spaceBeforeSentencePunctuation, "$1")
        .lowercase(Locale.ROOT)
