package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** Replace the current selection with a validated recipient and clear stale IME composition. */
internal fun TextFieldState.replaceSelectionForRecipientPaste(replacement: String) {
    edit {
        val start = selection.min.coerceIn(0, length)
        val end = selection.max.coerceIn(start, length)
        replace(start, end, replacement)
        selection = TextRange(start + replacement.length)
    }
}

/** Replaces programmatic QR/clear content without recreating the field's IME owner. */
internal fun TextFieldState.replaceRecipientText(replacement: String) {
    edit {
        replace(0, length, replacement)
        selection = TextRange(replacement.length)
    }
}
