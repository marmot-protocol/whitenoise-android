package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal const val COMPOSER_DRAFT_VERSION_PREFIX = "\u0001WN\u0001v1\u0001"

private const val FIELD_SEPARATOR = '\u001E'
private const val VERSIONED_DRAFT_FIELD_COUNT = 4

/**
 * Restored composer draft: field value plus whether returning to the chat should
 * auto-focus the composer. Only drafts saved in [COMPOSER_DRAFT_VERSION_PREFIX]
 * format request focus; legacy raw-string drafts keep end-of-text selection
 * without raising the keyboard.
 */
data class ComposerDraftSnapshot(
    val textFieldValue: TextFieldValue,
    val focusOnRestore: Boolean,
)

internal fun encodeComposerDraft(value: TextFieldValue): String {
    val text = value.text
    val selection = value.selection
    return buildString {
        append(COMPOSER_DRAFT_VERSION_PREFIX)
        append(selection.start)
        append(FIELD_SEPARATOR)
        append(selection.end)
        append(FIELD_SEPARATOR)
        append(text.length)
        append(FIELD_SEPARATOR)
        append(text)
    }
}

internal fun decodeComposerDraftStored(stored: String): ComposerDraftSnapshot =
    if (!stored.startsWith(COMPOSER_DRAFT_VERSION_PREFIX)) {
        legacyComposerDraftSnapshot(stored)
    } else {
        decodeVersionedComposerDraft(stored)
    }

private fun decodeVersionedComposerDraft(stored: String): ComposerDraftSnapshot {
    val body = stored.substring(COMPOSER_DRAFT_VERSION_PREFIX.length)
    val fields = body.split(FIELD_SEPARATOR, limit = VERSIONED_DRAFT_FIELD_COUNT)
    val selectionStart = fields.getOrNull(0)?.toIntOrNull()
    val selectionEnd = fields.getOrNull(1)?.toIntOrNull()
    val declaredLength = fields.getOrNull(2)?.toIntOrNull()
    val text = fields.getOrNull(3)
    return when {
        fields.size != VERSIONED_DRAFT_FIELD_COUNT -> malformedComposerDraftSnapshot(stored)
        selectionStart == null ||
            selectionEnd == null ||
            declaredLength == null -> malformedComposerDraftSnapshot(stored)
        text == null || declaredLength != text.length -> malformedComposerDraftSnapshot(stored)
        else ->
            ComposerDraftSnapshot(
                textFieldValue =
                    TextFieldValue(
                        text = text,
                        selection = clampComposerDraftSelection(text, selectionStart, selectionEnd),
                    ),
                focusOnRestore = true,
            )
    }
}

internal fun shouldFocusComposerOnDraftRestore(snapshot: ComposerDraftSnapshot?): Boolean =
    snapshot?.focusOnRestore == true && snapshot.textFieldValue.text.isNotBlank()

private fun legacyComposerDraftSnapshot(stored: String): ComposerDraftSnapshot {
    val selection = TextRange(stored.length)
    return ComposerDraftSnapshot(
        textFieldValue = TextFieldValue(text = stored, selection = selection),
        focusOnRestore = false,
    )
}

private fun malformedComposerDraftSnapshot(stored: String): ComposerDraftSnapshot {
    val selection = TextRange(stored.length)
    return ComposerDraftSnapshot(
        textFieldValue = TextFieldValue(text = stored, selection = selection),
        focusOnRestore = false,
    )
}

private fun clampComposerDraftSelection(
    text: String,
    start: Int,
    end: Int,
): TextRange {
    val length = text.length
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(0, length)
    return TextRange(start = safeStart, end = safeEnd)
}
