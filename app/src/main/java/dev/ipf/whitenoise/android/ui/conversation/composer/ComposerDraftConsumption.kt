package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.input.TextFieldValue

/** Clear a sent draft without erasing text entered while acceptance was pending. */
internal fun ComposerTextState.clearDraftIfUnchanged(
    sentText: String,
    onDraftChange: (TextFieldValue) -> Unit,
) {
    if (valueState.value.text != sentText) return
    val cleared = TextFieldValue("")
    valueState.value = cleared
    onDraftChange(cleared)
}
