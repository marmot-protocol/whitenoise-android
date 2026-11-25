package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/**
 * Shared compact search. Empty trailing actions, IME options and accessibility labels remain caller-owned;
 * existing entity pickers retain their defaults, while Chats supplies native voice entry and its clear label.
 * The leading search glyph can be swapped, for example for a back action that leaves the search.
 * [emptyTrailingIcon] is the slot for paste or QR actions that show only while the field is empty.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
fun WhiteNoiseCompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    emptyTrailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    clearDescription: String = stringResource(R.string.clear_search),
    outlined: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onClear: (() -> Unit)? = null,
    leadingIcon: @Composable () -> Unit = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
) {
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = SearchFieldMinHeight),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            CompactSearchDecoration(
                value = value,
                innerTextField = innerTextField,
                placeholder = placeholder,
                interactionSource = interactionSource,
                containerColor = containerColor,
                outlined = outlined,
                enabled = true,
                leadingIcon = leadingIcon,
                clearDescription = clearDescription,
                emptyTrailingIcon = emptyTrailingIcon,
                onClear = { onClear?.invoke() ?: onValueChange("") },
            )
        },
    )
}

/**
 * [WhiteNoiseCompactSearchField] over a [TextFieldState], for callers that keep native selection and IME state or
 * intercept paste. [onClear] runs instead of emptying the state, when a caller must route the clear through its policy.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
fun WhiteNoiseCompactSearchField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    emptyTrailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    clearDescription: String = stringResource(R.string.clear_search),
    outlined: Boolean = false,
    enabled: Boolean = true,
    onClear: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        state = state,
        enabled = enabled,
        modifier = modifier.heightIn(min = SearchFieldMinHeight),
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        interactionSource = interactionSource,
        decorator = { innerTextField ->
            CompactSearchDecoration(
                value = state.text.toString(),
                innerTextField = innerTextField,
                placeholder = placeholder,
                interactionSource = interactionSource,
                containerColor = containerColor,
                outlined = outlined,
                enabled = enabled,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                clearDescription = clearDescription,
                emptyTrailingIcon = emptyTrailingIcon,
                onClear = { onClear?.invoke() ?: state.edit { replace(0, length, "") } },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun CompactSearchDecoration(
    value: String,
    innerTextField: @Composable () -> Unit,
    placeholder: String,
    interactionSource: MutableInteractionSource,
    containerColor: Color,
    outlined: Boolean,
    enabled: Boolean,
    leadingIcon: @Composable () -> Unit,
    clearDescription: String,
    emptyTrailingIcon: (@Composable () -> Unit)?,
    onClear: () -> Unit,
) {
    val outline = if (outlined) amoledOutlineBorder() else null
    val fieldColors =
        TextFieldDefaults.colors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            disabledContainerColor = containerColor,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        )
    TextFieldDefaults.DecorationBox(
        value = value,
        innerTextField = innerTextField,
        enabled = enabled,
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactionSource,
        placeholder = { Text(text = placeholder, maxLines = 1) },
        leadingIcon = leadingIcon,
        trailingIcon =
            if (value.isNotEmpty()) {
                {
                    IconButton(onClick = onClear, enabled = enabled) {
                        Icon(painterResource(R.drawable.ic_close), clearDescription)
                    }
                }
            } else {
                emptyTrailingIcon
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = fieldColors,
        contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(top = 0.dp, bottom = 0.dp),
        container = {
            TextFieldDefaults.Container(
                modifier =
                    if (outline != null) {
                        Modifier.border(outline, MaterialTheme.shapes.extraLarge)
                    } else {
                        Modifier
                    },
                enabled = enabled,
                isError = false,
                interactionSource = interactionSource,
                colors = fieldColors,
                shape = MaterialTheme.shapes.extraLarge,
                focusedIndicatorLineThickness = 0.dp,
                unfocusedIndicatorLineThickness = 0.dp,
            )
        },
    )
}

private val SearchFieldMinHeight = 48.dp
