package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/**
 * The shared leading action for text entry surfaces that can insert emoji.
 * Containers still own their picker lifecycle (inline for the composer and a
 * sheet for forms), while this pins the visual and accessibility contract.
 */
@Suppress("FunctionNaming")
@Composable
internal fun TextEntryEmojiAction(
    pickerOpen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    togglesKeyboard: Boolean = false,
) {
    val showKeyboard = pickerOpen && togglesKeyboard
    val containerColor =
        if (pickerOpen) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        }
    val contentColor =
        if (pickerOpen) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        modifier =
            modifier
                .size(TextEntryEmojiActionSize)
                .semantics {
                    role = Role.Button
                    selected = pickerOpen
                },
    ) {
        Icon(
            imageVector = if (showKeyboard) Icons.Default.Keyboard else Icons.Outlined.EmojiEmotions,
            contentDescription =
                stringResource(
                    if (showKeyboard) R.string.show_keyboard else R.string.open_emoji_picker,
                ),
            modifier = Modifier.size(TextEntryEmojiIconSize),
        )
    }
}

internal val TextEntryEmojiActionSize = 48.dp
internal val TextEntryEmojiIconSize = 22.dp
