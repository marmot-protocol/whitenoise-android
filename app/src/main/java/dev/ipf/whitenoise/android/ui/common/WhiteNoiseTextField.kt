package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Container fill a hosting surface hands to its fields, for example the lowest surface inside settings. */
val LocalWhiteNoiseTextFieldContainerColor = staticCompositionLocalOf { Color.Unspecified }

private object WhiteNoiseTextFieldDefaults {
    /** Material's standard content inset for input, supporting text, and leading icon artwork. */
    val ContentInset = 16.dp

    /** Material already gives an above label 4 dp; this closes the gap to the 16 dp content line. */
    val AboveLabelAdditionalStartInset = 12.dp

    /** Keep the approved 28 dp form geometry local; production extra-large is currently 24 dp. */
    val Shape = RoundedCornerShape(28.dp)

    /** A full-shape state ring replaces a persistent resting outline. */
    val StateRingWidth = 2.dp
}

/**
 * Tonal form field with caller-owned state and a full-shape focus or error ring.
 *
 * [state] stays caller-owned. A non-null, localized [errorMessage] enables the error ring
 * and accessibility announcement; callers also supply visible guidance through [supportingText].
 * [containerColor] overrides the rest fill for a nested surface without changing app theme defaults.
 *
 * Material still owns text editing, cursor/selection, icon slots, label/supporting typography,
 * focus collection, state colors, and state animation. The shared container adds the approved
 * higher-contrast tonal rest surface, transparent rest border, and 2 dp focus/error ring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun WhiteNoiseTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    containerColor: Color = LocalWhiteNoiseTextFieldContainerColor.current,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (TextFieldLabelScope.() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    errorMessage: String? = null,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
) {
    val isError = errorMessage != null
    val interactionSource = remember { MutableInteractionSource() }
    val colors = whiteNoiseTextFieldColors(containerColor)
    val focused = interactionSource.collectIsFocusedAsState().value
    val resolvedTextColor =
        textStyle.color.takeOrElse {
            colors.textColorFor(enabled = enabled, isError = isError, focused = focused)
        }

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            state = state,
            modifier =
                modifier
                    .textFieldErrorSemantics(errorMessage)
                    .defaultMinSize(
                        minWidth = OutlinedTextFieldDefaults.MinWidth,
                        minHeight = OutlinedTextFieldDefaults.MinHeight,
                    ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.merge(TextStyle(color = resolvedTextColor)),
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = lineLimits,
            interactionSource = interactionSource,
            inputTransformation = inputTransformation,
            outputTransformation = outputTransformation,
            decorator =
                OutlinedTextFieldDefaults.decorator(
                    state = state,
                    enabled = enabled,
                    lineLimits = lineLimits,
                    outputTransformation = outputTransformation,
                    interactionSource = interactionSource,
                    labelPosition = TextFieldLabelPosition.Above(),
                    label = label?.let(::insetLabel),
                    placeholder = placeholder,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    supportingText = supportingText,
                    isError = isError,
                    colors = colors,
                    contentPadding = whiteNoiseTextFieldContentPadding(),
                    container = {
                        WhiteNoiseTextFieldContainer(
                            enabled = enabled,
                            isError = isError,
                            interactionSource = interactionSource,
                            colors = colors,
                        )
                    },
                ),
        )
    }
}

/** Resolve state colors from the active palette, including existing AMOLED contrast. */
@Composable
private fun whiteNoiseTextFieldColors(containerColor: Color): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val resolvedContainerColor = containerColor.takeOrElse { scheme.surfaceContainerHigh }
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = resolvedContainerColor,
        unfocusedContainerColor = resolvedContainerColor,
        disabledContainerColor = scheme.surfaceContainerLow,
        errorContainerColor = resolvedContainerColor,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = if (isAmoledSurfaceTheme()) scheme.outline else Color.Transparent,
        disabledBorderColor = if (isAmoledSurfaceTheme()) scheme.outline.copy(alpha = 0.38f) else Color.Transparent,
        errorBorderColor = scheme.error,
    )
}

/** Keep the full-shape focus/error ring and AMOLED resting outline under Material animation. */
@Suppress("FunctionNaming")
@Composable
private fun WhiteNoiseTextFieldContainer(
    enabled: Boolean,
    isError: Boolean,
    interactionSource: MutableInteractionSource,
    colors: TextFieldColors,
) {
    OutlinedTextFieldDefaults.Container(
        enabled = enabled,
        isError = isError,
        interactionSource = interactionSource,
        colors = colors,
        shape = WhiteNoiseTextFieldDefaults.Shape,
        focusedBorderThickness = WhiteNoiseTextFieldDefaults.StateRingWidth,
        unfocusedBorderThickness =
            if (isAmoledSurfaceTheme() && !isError) 1.dp else WhiteNoiseTextFieldDefaults.StateRingWidth,
    )
}

/** Material 1.5.0-alpha20 exposes the no-attached-label padding API as `contentPadding`. */
private fun whiteNoiseTextFieldContentPadding(): PaddingValues =
    OutlinedTextFieldDefaults.contentPadding(
        start = WhiteNoiseTextFieldDefaults.ContentInset,
        end = WhiteNoiseTextFieldDefaults.ContentInset,
    )

/** Align the above label with the input's directional 16 dp inset. */
@OptIn(ExperimentalMaterial3Api::class)
private fun insetLabel(label: @Composable TextFieldLabelScope.() -> Unit): @Composable TextFieldLabelScope.() -> Unit =
    {
        val labelScope = this
        Box(Modifier.padding(start = WhiteNoiseTextFieldDefaults.AboveLabelAdditionalStartInset)) {
            label(labelScope)
        }
    }

/** Keep the spoken error tied to the same caller-owned message that enables error chrome. */
private fun Modifier.textFieldErrorSemantics(errorMessage: String?): Modifier {
    if (errorMessage == null) return this
    return semantics { error(errorMessage) }
}

/** Preserve an explicit text style color while otherwise using Material state colors. */
private fun TextFieldColors.textColorFor(
    enabled: Boolean,
    isError: Boolean,
    focused: Boolean,
): Color =
    when {
        !enabled -> disabledTextColor
        isError -> errorTextColor
        focused -> focusedTextColor
        else -> unfocusedTextColor
    }
