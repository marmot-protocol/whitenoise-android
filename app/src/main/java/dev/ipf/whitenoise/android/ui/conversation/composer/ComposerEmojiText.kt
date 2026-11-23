package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.composerEmojiArtwork
import dev.ipf.whitenoise.android.ui.rememberEmojiArtPainter

/** Marks the visible slot that draws one shortcode's artwork; the item is the shortcode. */
internal const val COMPOSER_EMOJI_TAG = "composer-emoji"

/**
 * The visible stand-in for a rendered `:shortcode:`: an em-wide space the artwork covers, followed by a word
 * joiner so a line break never separates the artwork from the word after it.
 */
internal const val COMPOSER_EMOJI_SLOT = "\u2003\u2060"

/** The composer's visible text: mention labels and emoji slots, with the stored-to-visible offset mapping. */
internal class ComposerVisualText(
    val mapping: MentionComposer.ChipVisualText,
    val mentionRanges: List<IntRange>,
    val emoji: List<AnnotatedString.Range<String>>,
)

/** One stored run and the visible text that replaces it; [shortcode] is set for emoji slots. */
private data class VisibleRun(
    val original: IntRange,
    val replacement: String,
    val shortcode: String?,
)

/** Shortcodes the composer sends as emoji: bundled marmots plus the user's sendable, installed and chat sets. */
internal fun composerEmojiShortcodes(library: CustomEmojiSet): Set<String> =
    (EmojiShortcodes.builtins + library.composerShortcodes).toSet()

/** Nonliteral occurrences of [shortcodes] in the stored draft, in text order. */
internal fun composerEmojiRanges(
    text: String,
    shortcodes: Set<String>,
): List<AnnotatedString.Range<String>> =
    if (shortcodes.isEmpty() || ':' !in text) {
        emptyList()
    } else {
        EmojiShortcodes.presentationRanges(text).filter { it.item in shortcodes }
    }

/**
 * The shortcodes in [value] that draw as artwork: those in [shortcodes] outside any range the keyboard is composing
 * and with no selection end strictly inside. A caret placed inside a code, or a code being typed, stays editable
 * text, and editing repairs only what is drawn. An edit also leaves alone any code its [incoming] composition
 * touches, so a keyboard that starts composing inside one edits it as text.
 */
internal fun activeComposerEmojiRanges(
    value: TextFieldValue,
    shortcodes: Set<String>,
    incoming: TextRange? = null,
): List<AnnotatedString.Range<String>> {
    val composing = listOfNotNull(value.composition, incoming).filterNot { it.collapsed }
    return composerEmojiRanges(value.text, shortcodes).filter { code ->
        val interior = code.start + 1 until code.end
        val composed = composing.any { code.start < it.max && code.end > it.min }
        !composed && value.selection.start !in interior && value.selection.end !in interior
    }
}

/**
 * Combines [mentions] with one [COMPOSER_EMOJI_SLOT] per range in [emoji]. The stored draft keeps the canonical
 * `:shortcode:` for sending; only the visible text changes, the same way mention chips show labels.
 */
internal fun composerVisualText(
    text: String,
    mentions: MentionComposer.ChipVisualText,
    emoji: List<AnnotatedString.Range<String>>,
): ComposerVisualText {
    val labels =
        mentions.ranges.map {
            VisibleRun(it.original, mentions.text.substring(it.transformed.first, it.transformed.last + 1), null)
        }
    val slots =
        emoji
            .filter { code -> mentions.ranges.none { code.start <= it.original.last && code.end > it.original.first } }
            .map { VisibleRun(it.start until it.end, COMPOSER_EMOJI_SLOT, it.item) }
    if (slots.isEmpty()) return ComposerVisualText(mentions, mentions.ranges.map { it.transformed }, emptyList())
    val visible = StringBuilder()
    val ranges = mutableListOf<MentionComposer.ChipVisualRange>()
    val mentionRanges = mutableListOf<IntRange>()
    val slotRanges = mutableListOf<AnnotatedString.Range<String>>()
    var source = 0
    for ((original, replacement, shortcode) in (labels + slots).sortedBy { it.original.first }) {
        visible.append(text, source, original.first)
        val start = visible.length
        visible.append(replacement)
        val transformed = start until visible.length
        ranges += MentionComposer.ChipVisualRange(original, transformed)
        if (shortcode != null) {
            slotRanges += AnnotatedString.Range(shortcode, start, start + 1)
        } else {
            mentionRanges += transformed
        }
        source = original.last + 1
    }
    visible.append(text, source, text.length)
    return ComposerVisualText(
        MentionComposer.ChipVisualText(visible.toString(), ranges, text.length),
        mentionRanges,
        slotRanges,
    )
}

/** Rendered shortcodes in [text] whose artwork is ready, with their painters. Codes still loading stay as text. */
@Composable
internal fun rememberComposerEmojiPainters(text: String): Map<String, Painter> {
    val library = LocalCustomEmoji.current
    val shortcodes = remember(library) { composerEmojiShortcodes(library) }
    val codes = remember(text, shortcodes) { composerEmojiRanges(text, shortcodes).map { it.item }.distinct() }
    val painters = LinkedHashMap<String, Painter>()
    for (code in codes) {
        key(code) {
            composerEmojiArtwork(code)?.let { painters[code] = rememberEmojiArtPainter(it) }
        }
    }
    return painters
}

/** Draws each emoji slot's artwork over the field's text, using the slots annotated in [layout]'s own text. */
internal fun Modifier.composerEmojiArtwork(
    painters: Map<String, Painter>,
    layout: () -> TextLayoutResult?,
): Modifier =
    drawWithContent {
        drawContent()
        if (painters.isNotEmpty()) layout()?.let { drawComposerEmoji(it, painters) }
    }

private fun DrawScope.drawComposerEmoji(
    layout: TextLayoutResult,
    painters: Map<String, Painter>,
) {
    val text = layout.layoutInput.text
    for (slot in text.getStringAnnotations(COMPOSER_EMOJI_TAG, 0, text.length)) {
        val painter = painters[slot.item]
        val box = layout.getBoundingBox(slot.start)
        // The em space is exactly one em wide, the same box message bubbles give inline artwork.
        val side = minOf(box.width, box.height)
        if (painter != null && side > 0f) {
            translate(box.center.x - side / 2, box.center.y - side / 2) {
                with(painter) { draw(Size(side, side)) }
            }
        }
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
    for (code in activeComposerEmojiRanges(old, shortcodes, proposed.composition)) {
        val overlaps = start < code.end && end > code.start
        if (overlaps && (start > code.start || end < code.end)) {
            start = minOf(start, code.start)
            end = maxOf(end, code.end)
        }
    }
    val widened = start != deleted.first || end != deleted.last + 1
    return if (widened) TextFieldValue(old.text.removeRange(start, end), TextRange(start)) else null
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
