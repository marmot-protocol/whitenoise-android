package dev.ipf.whitenoise.android.ui.common

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
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.conversation.composer.repairComposerEmojiDeletion
import dev.ipf.whitenoise.android.ui.rememberEmojiArtPainter

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
    SpanStyle(color = Color.Transparent, letterSpacing = 0.em, textGeometricTransform = TextGeometricTransform(scaleX = 0f))

private fun drawableShortcodes(
    text: String,
    custom: CustomEmojiSet,
): List<String> = EmojiShortcodes.presentationShortcodes(text).filter { EmojiShortcodes.art(it, custom, emptyMap()) != null }

/**
 * Records each drawable shortcode as an atomic edit range, keeping the actual text in that range for Cut, Copy
 * and accessibility. Only the layout changes: the first character reserves one em, and the remaining ones collapse.
 * The transformation stays the same instance as codes are entered and artwork becomes available.
 */
private class TextFieldEmojiOutput(
    private val custom: State<CustomEmojiSet>,
) : OutputTransformation {
    @OptIn(ExperimentalFoundationApi::class)
    override fun TextFieldBuffer.transformOutput() {
        val ranges =
            EmojiShortcodes
                .presentationRanges(asCharSequence().toString())
                .filter { EmojiShortcodes.art(it.item, custom.value, emptyMap()) != null }
        for (range in ranges) replace(range.start, range.end, range.item)
        for (range in ranges) {
            addStyle(shortcodeSlotStyle, range.start, range.start + 1)
            addStyle(collapsedShortcodeStyle, range.start + 1, range.end)
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
                    if (side <= 0f) continue
                    translate(box.center.x - side / 2, box.center.y - side / 2) {
                        with(painter) { draw(Size(side, side)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun emojiEditableText(state: TextFieldState): Modifier {
    val custom = LocalCustomEmoji.current
    val resources = LocalContext.current.resources
    return Modifier.semantics {
        editableText = EmojiShortcodes.speak(AnnotatedString(state.text.toString()), custom, resources)
    }
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
