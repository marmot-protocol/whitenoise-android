package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A state-backed outlined field with an anchored emoji picker. [containerModifier] takes parent layout data such
 * as Row weight, and [modifier] decorates the editor itself.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
internal fun ProseTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    errorMessage: String? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    maxLength: Int? = null,
    containerModifier: Modifier = Modifier,
) {
    val isError = errorMessage != null
    val emojiAnchor = rememberEmojiSheetFieldAnchor()
    val artwork = rememberTextFieldEmojiArtwork(state, enabled = true)
    val layout = remember { TextLayoutHolder() }
    val scroll = rememberScrollState()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val inputLimit =
        remember(maxLength) {
            maxLength?.let { limit -> InputTransformation { if (length > limit) replace(limit, length, "") } }
        }
    val limits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines)
    val style = LocalTextStyle.current
    val textColor =
        style.color.takeOrElse {
            when {
                !enabled -> colors.disabledTextColor
                isError -> colors.errorTextColor
                focused -> colors.focusedTextColor
                else -> colors.unfocusedTextColor
            }
        }
    val editorLabel: @Composable (TextFieldLabelScope.() -> Unit)? = label?.let { { it() } }
    val cutout =
        if (label == null) {
            Modifier
        } else {
            val halfLabelHeight =
                with(LocalDensity.current) {
                    MaterialTheme.typography.bodySmall.lineHeight
                        .toDp() / 2
                }
            Modifier.semantics(mergeDescendants = true) {}.padding(top = halfLabelHeight)
        }
    val decorator =
        OutlinedTextFieldDefaults.decorator(
            state = state,
            enabled = enabled,
            lineLimits = limits,
            outputTransformation = artwork.output,
            interactionSource = interactions,
            labelPosition = TextFieldLabelPosition.Cutout(),
            label = editorLabel,
            placeholder = placeholder,
            leadingIcon = {
                TextEntryEmojiFieldAction(
                    TextFieldValue(state.text.toString(), state.selection, state.composition),
                    enabled,
                    owner = state,
                    anchor = emojiAnchor,
                ) { (maxLength == null || it.text.length <= maxLength) && insertTextEntryEmoji(state, it, null) }
            },
            isError = isError,
            colors = colors,
        )
    Box(containerModifier.emojiSheetFieldAnchor(emojiAnchor), propagateMinConstraints = true) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides colors.textSelectionColors,
            LocalClipboard provides emojiFieldClipboard(true),
        ) {
            BasicTextField(
                state = state,
                modifier =
                    modifier
                        .then(cutout)
                        .textFieldErrorSemantics(errorMessage)
                        .defaultMinSize(OutlinedTextFieldDefaults.MinWidth, OutlinedTextFieldDefaults.MinHeight)
                        .then(emojiEditableText(state, enabled)),
                enabled = enabled,
                textStyle = style.merge(TextStyle(color = textColor)),
                cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
                lineLimits = limits,
                interactionSource = interactions,
                inputTransformation = artwork.inputWith(inputLimit),
                outputTransformation = artwork.output,
                onTextLayout = { layout.result = it },
                scrollState = scroll,
                decorator = artwork.decorate(decorator, { layout.result?.invoke() }, scroll, horizontal = singleLine),
            )
        }
    }
}
