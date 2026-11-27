package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/**
 * Shared group-name input chrome; picker ownership stays with each screen. The screen passes the same
 * [emojiAnchor] to its picker sheet so the sheet opens underneath this field.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupNameEmojiField(
    state: TextFieldState,
    label: String,
    emojiPickerOpen: Boolean,
    onEmojiPickerClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    emojiAnchor: EmojiSheetFieldAnchor? = null,
) {
    val openBelowField = rememberEmojiSheetOpener(emojiAnchor)
    WhiteNoiseTextField(
        state = state,
        label = { Text(label) },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        enabled = enabled,
        emojiArtwork = true,
        leadingIcon = {
            TextEntryEmojiAction(
                pickerOpen = emojiPickerOpen,
                enabled = enabled,
                onClick = { openBelowField(onEmojiPickerClick) },
            )
        },
        modifier = emojiAnchor?.let { modifier.emojiSheetFieldAnchor(it) } ?: modifier,
    )
}
