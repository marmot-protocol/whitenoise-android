package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.rememberRecentEmojiRecentsOwner

/** Field-local picker ownership. Disposed, disabled or externally changed editors cannot accept a late pick. */
@Composable
@Suppress("FunctionNaming")
internal fun TextEntryEmojiFieldAction(
    value: TextFieldValue,
    enabled: Boolean,
    owner: Any? = null,
    onInsert: (TextFieldValue) -> Boolean,
) {
    var openingValue by remember { mutableStateOf<TextFieldValue?>(null) }
    var openingOwner by remember { mutableStateOf<Any?>(null) }
    var active by remember { mutableStateOf(true) }
    val currentValue by rememberUpdatedState(value)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOwner by rememberUpdatedState(owner)
    val insert by rememberUpdatedState(onInsert)
    val recents = rememberRecentEmojiRecentsOwner(LocalContext.current)
    DisposableEffect(Unit) { onDispose { active = false } }
    LaunchedEffect(enabled, owner) {
        if (!enabled || openingOwner != owner) openingValue = null
    }
    TextEntryEmojiAction(openingValue != null, enabled, {
        openingOwner = currentOwner
        openingValue = currentValue
    })
    openingValue?.takeIf { enabled }?.let { captured ->
        EmojiPickerSheet(
            onDismissRequest = { openingValue = null },
            recentEmojis = recents.recents,
            onEmojiPicked = { emoji ->
                openingValue = null
                val ownsEditor = active && currentEnabled && currentOwner == openingOwner
                if (ownsEditor && currentValue.text == captured.text) {
                    if (insert(insertEmojiAtSelection(captured, emoji))) recents.onEmojiUsed(emoji)
                }
            },
        )
    }
}

/** Programmatic emoji edits obey the same opt-in input constraints as keyboard edits. */
internal fun insertTextEntryEmoji(
    state: TextFieldState,
    value: TextFieldValue,
    inputTransformation: InputTransformation?,
): Boolean {
    val original = state.text.toString()
    state.edit {
        replace(0, length, value.text)
        selection = value.selection
        inputTransformation?.let { transformation -> with(transformation) { transformInput() } }
    }
    return state.text.toString() != original
}
