package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.ui.common.AccountActionColors

/**
 * #414/#442: paint stored `@npub1…` chip runs as friendly visible labels (`@alice` when the profile is resolved,
 * short `@npub1…` otherwise) and each `:shortcode:` range in [emoji] as an artwork slot, while keeping the
 * backing TextFieldValue canonical for send/markdown. A filled chip with black-or-white content reads on any
 * accent, including ones that vanish as text.
 */
@Composable
internal fun rememberComposerVisualTransformation(
    highlightMentionChips: Boolean,
    mentionCandidates: List<MentionComposer.Candidate>,
    actionColors: AccountActionColors,
    composition: TextRange?,
    emoji: List<AnnotatedString.Range<String>>,
): VisualTransformation {
    val chipColor = actionColors.container
    val chipContentColor = actionColors.content
    val mentionCandidateLookup =
        remember(highlightMentionChips, mentionCandidates) {
            if (highlightMentionChips) MentionComposer.candidatesByNpub(mentionCandidates) else emptyMap()
        }
    return remember(
        highlightMentionChips,
        chipColor,
        chipContentColor,
        mentionCandidateLookup,
        composition,
        emoji,
    ) {
        if (!highlightMentionChips && emoji.isEmpty()) {
            VisualTransformation.None
        } else {
            ComposerVisualTransformation(
                mentionCandidates = mentionCandidateLookup.takeIf { highlightMentionChips },
                chipStyle = SpanStyle(color = chipContentColor, fontWeight = FontWeight.Medium, background = chipColor),
                composition = composition,
                emoji = emoji,
            )
        }
    }
}

/** Mention labels when [mentionCandidates] is set, plus a slot per [emoji] range; the stored text stays canonical. */
internal class ComposerVisualTransformation(
    private val mentionCandidates: Map<String, MentionComposer.Candidate>?,
    private val chipStyle: SpanStyle,
    private val composition: TextRange?,
    private val emoji: List<AnnotatedString.Range<String>>,
) : VisualTransformation {
    // #607: the chip renderer must never crash the composer. The
    // primary fix (MentionComposer.repairChipDeletion in
    // onValueChange) makes a partial `@npub1…` chip state
    // impossible, but degrade gracefully — fall back to the
    // untransformed text — if any unforeseen malformed input
    // state still drives buildAnnotatedString / the offset
    // mapping out of bounds, rather than letting it throw.
    override fun filter(text: AnnotatedString): TransformedText = runCatching { transform(text) }.getOrElse { TransformedText(text, OffsetMapping.Identity) }

    private fun transform(text: AnnotatedString): TransformedText {
        val source = text.text
        val mentions =
            mentionCandidates?.let { MentionComposer.editingVisualText(source, it) }
                ?: MentionComposer.ChipVisualText(source, emptyList(), source.length)
        // Ranges come from the field value; skip any that no longer match the text being transformed.
        val slots = emoji.filter { source.regionMatches(it.start, it.item, 0, it.item.length) }
        val composer = composerVisualText(source, mentions, slots)
        val visual = composer.mapping
        // Legacy BasicTextField reports the IME's composing-character
        // bounds through CursorAnchorInfo. A shortened mention label can
        // map both ends of a non-empty composing range inside the stored
        // `@npub...` to the same visible offset; Compose then forwards an
        // empty range to fillBoundingBoxes and crashes. Keep the canonical
        // text and identity mapping only for that transient composition.
        // Friendly mention labels return as soon as the IME commits it.
        val compositionCollapses =
            composition?.takeUnless { it.collapsed }?.let { range ->
                visual.originalToTransformed(range.min) >= visual.originalToTransformed(range.max)
            } == true
        if (compositionCollapses) return TransformedText(text, OffsetMapping.Identity)
        val visualLength = visual.text.length
        val offsetMapping =
            object : OffsetMapping {
                /** Maps the source caret into the bounded visible text. */
                override fun originalToTransformed(offset: Int): Int = visual.originalToTransformed(offset).coerceIn(0, visualLength)

                /** Maps chip and emoji selections into the current source draft bounds. */
                override fun transformedToOriginal(offset: Int): Int = visual.transformedToOriginal(offset).coerceIn(0, source.length)
            }
        return TransformedText(styled(composer), offsetMapping)
    }

    private fun styled(composer: ComposerVisualText): AnnotatedString {
        val visualLength = composer.mapping.text.length
        return buildAnnotatedString {
            append(composer.mapping.text)
            composer.mentionRanges.forEach { range ->
                // Clamp span bounds into the transformed text so a stale/oversized
                // range can't trip addStyle's range check.
                val spanStart = range.first.coerceIn(0, visualLength)
                val spanEnd = (range.last + 1).coerceIn(spanStart, visualLength)
                if (spanEnd > spanStart) addStyle(chipStyle, spanStart, spanEnd)
            }
            composer.emoji.forEach { slot -> addStringAnnotation(COMPOSER_EMOJI_TAG, slot.item, slot.start, slot.end) }
        }
    }
}
