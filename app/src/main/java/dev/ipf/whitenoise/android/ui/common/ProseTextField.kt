package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue

/** String-model forms still retain a real caret/selection; only explicitly opted-in prose fields use this. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun ProseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    isError: Boolean = false,
    filled: Boolean = false,
    colors: TextFieldColors? = null,
    emojiOwner: Any? = null,
    emojiMaxLength: Int? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) {
        field = TextFieldValue(value, field.selection)
    }
    val change: (TextFieldValue) -> Unit = { updated ->
        field = updated
        onValueChange(updated.text)
    }
    val emoji: @Composable () -> Unit = {
        TextEntryEmojiFieldAction(field, enabled, emojiOwner to onValueChange) { updated ->
            if (emojiMaxLength != null && updated.text.length > emojiMaxLength) {
                false
            } else {
                change(updated)
                true
            }
        }
    }
    if (filled) {
        TextField(
            field,
            change,
            modifier,
            enabled = enabled,
            label = label,
            placeholder = placeholder,
            leadingIcon = emoji,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            isError = isError,
            colors = colors ?: TextFieldDefaults.colors(),
        )
    } else {
        OutlinedTextField(
            field,
            change,
            modifier,
            enabled = enabled,
            label = label,
            placeholder = placeholder,
            leadingIcon = emoji,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            isError = isError,
            colors = colors ?: OutlinedTextFieldDefaults.colors(),
        )
    }
}
