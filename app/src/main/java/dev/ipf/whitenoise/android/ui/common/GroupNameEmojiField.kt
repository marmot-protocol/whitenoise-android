package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Shared group-name input chrome; picker ownership stays with each screen. The screen passes the same
 * [emojiAnchor] to its picker sheet so the sheet opens underneath this field.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupNameEmojiField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    emojiPickerOpen: Boolean,
    onEmojiPickerClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    emojiAnchor: EmojiSheetFieldAnchor? = null,
) {
    val openBelowField = rememberEmojiSheetOpener(emojiAnchor)
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        leadingIcon = {
            TextEntryEmojiAction(
                pickerOpen = emojiPickerOpen,
                enabled = enabled,
                onClick = { openBelowField(onEmojiPickerClick) },
            )
        },
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
            ),
        modifier = emojiAnchor?.let { modifier.emojiSheetFieldAnchor(it) } ?: modifier,
    )
}
