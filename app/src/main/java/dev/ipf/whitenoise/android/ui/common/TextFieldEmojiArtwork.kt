package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.conversation.composer.repairComposerEmojiDeletion
import dev.ipf.whitenoise.android.ui.rememberEmojiArtPainter
import dev.ipf.whitenoise.android.ui.withSpokenEmojiNames

private val shortcodeSlotStyle by lazy {
    // A monospace colon provides the advance for the artwork. Scale it to one em.
    val paint = Paint().apply { typeface = Typeface.MONOSPACE }
    SpanStyle(
        color = Color.Transparent,
        fontFamily = FontFamily.Monospace,
        fontStyle = FontStyle.Normal,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.em,
        textGeometricTransform = TextGeometricTransform(scaleX = paint.textSize / paint.measureText(":")),
    )
}

private val collapsedShortcodeStyle =
    SpanStyle(
        color = Color.Transparent,
        letterSpacing = 0.em,
        textGeometricTransform = TextGeometricTransform(scaleX = 0f),
    )

private const val EMOJI_ISOLATE = "\u2066\u2060" // Left-to-right isolate and a word joiner marking our opening.
private const val END_EMOJI_ISOLATE = "\u2069"
private const val WORD_JOINER = "\u2060"
private val authoredIsolates = Regex("\u2066\u2060+")
private val clipboardIsolates =
    Regex("\u2066(\u2060+)(?:(:[A-Za-z0-9_-]{1,${EmojiShortcodes.MAX_CODE_LENGTH}}:)\u2069)?")

private fun drawableShortcodes(
    text: String,
    custom: CustomEmojiSet,
): List<String> =
    EmojiShortcodes
        .presentationShortcodes(text)
        .filter { EmojiShortcodes.art(it, custom, emptyMap()) != null }

/** Removes the isolates inserted for artwork and restores the original word joiners in authored isolates. */
internal fun decodeEmojiClipboardText(visual: String): String =
    clipboardIsolates.replace(visual) { match ->
        val joiners = match.groupValues[1]
        val code = match.groupValues[2]
        if (joiners.length > 1) {
            "\u2066" + WORD_JOINER.repeat(joiners.length / 2) +
                if (code.isEmpty()) "" else code + END_EMOJI_ISOLATE
        } else {
            code.ifEmpty { match.value }
        }
    }

/**
 * One atomic edit range per emoji. The isolate makes the transparent shortcode act like a neutral image in
 * bidirectional text. One character reserves one em and the rest collapse. The backing state stays canonical.
 */
private class TextFieldEmojiOutput(
    private val custom: State<CustomEmojiSet>,
) : OutputTransformation {
    @OptIn(ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        for (match in authoredIsolates.findAll(source).toList().asReversed()) {
            val end = match.range.last + 1
            replace(end, end, WORD_JOINER.repeat(match.value.length - 1))
        }
        val ranges =
            EmojiShortcodes
                .presentationRanges(asCharSequence().toString())
                .filter { EmojiShortcodes.art(it.item, custom.value, emptyMap()) != null }
        for (range in ranges.asReversed()) {
            replace(range.start, range.end, EMOJI_ISOLATE + range.item + END_EMOJI_ISOLATE)
        }
        val isolateLength = EMOJI_ISOLATE.length + END_EMOJI_ISOLATE.length
        ranges.forEachIndexed { index, range ->
            val start = range.start + index * isolateLength + EMOJI_ISOLATE.length
            addStyle(shortcodeSlotStyle, start, start + 1)
            addStyle(collapsedShortcodeStyle, start + 1, start + range.item.length)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun emojiInput(
    custom: State<CustomEmojiSet>,
    state: TextFieldState,
): InputTransformation =
    InputTransformation {
        if (length >= originalText.length) return@InputTransformation
        val original = originalText.toString()
        val codes = drawableShortcodes(original, custom.value).toSet()
        val repaired =
            repairComposerEmojiDeletion(
                old = TextFieldValue(original, originalSelection, state.composition),
                proposed = TextFieldValue(asCharSequence().toString(), selection),
                shortcodes = codes,
            ) ?: return@InputTransformation
        replace(0, length, repaired.text)
        selection = repaired.selection
    }

/** Paints artwork in the space reserved by the emoji output transformation. */
internal class TextFieldEmojiArtwork(
    private val input: InputTransformation?,
    val output: OutputTransformation?,
    private val painters: Map<String, Painter>,
) {
    @Composable
    fun inputWith(limit: InputTransformation?): InputTransformation? =
        remember(input, limit) {
            input?.let { if (limit == null) it else it.then(limit) } ?: limit
        }

    fun decorate(
        decorator: TextFieldDecorator,
        layout: () -> TextLayoutResult?,
        scroll: ScrollState,
        horizontal: Boolean,
    ): TextFieldDecorator =
        TextFieldDecorator { innerTextField ->
            decorator.Decoration {
                Box(
                    Modifier.drawWithContent {
                        drawContent()
                        layout()?.let { drawArtwork(it, scroll, horizontal) }
                    },
                    propagateMinConstraints = true,
                ) { innerTextField() }
            }
        }

    private fun DrawScope.drawArtwork(
        layout: TextLayoutResult,
        scroll: ScrollState,
        horizontal: Boolean,
    ) {
        if (painters.isEmpty()) return
        val dx =
            when {
                !horizontal -> 0f
                layoutDirection == LayoutDirection.Rtl -> size.width - layout.size.width + scroll.value
                else -> -scroll.value.toFloat()
            }
        val dy = if (horizontal) 0f else -scroll.value.toFloat()
        val ranges = EmojiShortcodes.presentationRanges(layout.layoutInput.text.text)
        clipRect {
            translate(dx, dy) {
                for (range in ranges) {
                    val painter = painters[range.item] ?: continue
                    val box = layout.getBoundingBox(range.start)
                    val side = minOf(box.width, box.height)
                    if (side > 0f) {
                        translate(box.center.x - side / 2, box.center.y - side / 2) {
                            with(painter) { draw(Size(side, side)) }
                        }
                    }
                }
            }
        }
    }
}

/** Accessibility consumes the authored string and its line/character offsets together. */
@Composable
internal fun emojiEditableText(
    state: TextFieldState,
    enabled: Boolean,
    readOnly: Boolean,
    layout: TextLayoutHolder,
): Modifier {
    val custom = LocalCustomEmoji.current
    val context = LocalContext.current
    val resources = context.resources
    val measurer = rememberTextMeasurer(cacheSize = 2)
    return Modifier.semantics {
        val authored = AnnotatedString(state.text.toString())
        editableText = authored.withSpokenEmojiNames(custom, resources)
        textSelectionRange = state.selection
        getTextLayoutResult { results ->
            val current = layout.result?.invoke() ?: return@getTextLayoutResult false
            results.add(measurer.authoredLayout(current, authored, custom))
        }
        setSelection { start, end, _ ->
            val offsets = 0..state.text.length
            if (!enabled || start !in offsets || end !in offsets) {
                false
            } else {
                state.edit { selection = TextRange(start, end) }
                true
            }
        }
        if (enabled) {
            copyText { copyAuthoredSelection(state, context, cut = false) }
            if (!readOnly) cutText { copyAuthoredSelection(state, context, cut = true) }
        }
    }
}

private fun TextMeasurer.authoredLayout(
    current: TextLayoutResult,
    authored: AnnotatedString,
    custom: CustomEmojiSet,
): TextLayoutResult {
    val input = current.layoutInput
    if (input.text.text == authored.text) return current
    val placeholder = Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)
    val placeholders =
        EmojiShortcodes
            .presentationRanges(authored.text)
            .filter { EmojiShortcodes.art(it.item, custom, emptyMap()) != null }
            .map { AnnotatedString.Range(placeholder, it.start, it.end) }
    return measure(
        text = authored,
        style = input.style,
        overflow = input.overflow,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        placeholders = placeholders,
        constraints = input.constraints,
        layoutDirection = input.layoutDirection,
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver,
    )
}

private fun copyAuthoredSelection(
    state: TextFieldState,
    context: Context,
    cut: Boolean,
): Boolean {
    val selected = state.selection
    if (selected.collapsed) return false
    val text = state.text.subSequence(selected.min, selected.max).toString()
    val copied = copyToClipboard(context, "plain text", text)
    if (copied) {
        state.edit {
            if (cut) replace(selected.min, selected.max, "")
            selection = TextRange(if (cut) selected.min else selected.max)
        }
    }
    return copied
}

/** The field's latest text layout getter, read in the draw phase. */
internal class TextLayoutHolder {
    var result: (() -> TextLayoutResult?)? = null
}

@Composable
internal fun rememberTextFieldEmojiArtwork(
    state: TextFieldState,
    enabled: Boolean,
): TextFieldEmojiArtwork {
    val custom = LocalCustomEmoji.current
    val currentCustom = rememberUpdatedState(custom)
    val input = remember(state, enabled) { if (enabled) emojiInput(currentCustom, state) else null }
    val output = remember(enabled) { if (enabled) TextFieldEmojiOutput(currentCustom) else null }
    val codes by remember(state, custom, enabled) {
        derivedStateOf { if (enabled) drawableShortcodes(state.text.toString(), custom) else emptyList() }
    }
    val painters =
        buildMap {
            for (code in codes) {
                key(code) {
                    EmojiShortcodes.art(code, custom, emptyMap())?.let { put(code, rememberEmojiArtPainter(it)) }
                }
            }
        }
    return remember(input, output, painters) { TextFieldEmojiArtwork(input, output, painters) }
}
