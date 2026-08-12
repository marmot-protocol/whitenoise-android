package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal const val COMPOSER_DRAFT_VERSION_PREFIX = "\u0001WN\u0001v1\u0001"

private const val FIELD_SEPARATOR = '\u001E'
private const val VERSIONED_DRAFT_FIELD_COUNT = 4

// v2 adds a leading drafted-at (unix seconds) field so the chat-list draft-sort
// timestamp survives a process restart alongside the draft text. v1 blobs still
// decode (drafted-at absent); every write now emits v2.
internal const val COMPOSER_DRAFT_V2_PREFIX = "\u0001WN\u0001v2\u0001"
private const val VERSIONED_DRAFT_V2_FIELD_COUNT = 5
private const val DRAFTED_AT_UNSTAMPED = -1L

/**
 * Restored composer draft: field value plus whether returning to the chat should
 * auto-focus the composer. Only drafts saved in [COMPOSER_DRAFT_VERSION_PREFIX]
 * format request focus; legacy raw-string drafts keep end-of-text selection
 * without raising the keyboard.
 */
data class ComposerDraftSnapshot(
    val textFieldValue: TextFieldValue,
    val focusOnRestore: Boolean,
    val draftedAtSeconds: Long? = null,
)

internal fun encodeComposerDraft(
    value: TextFieldValue,
    draftedAtSeconds: Long? = null,
): String {
    val text = value.text
    val selection = value.selection
    return buildString {
        append(COMPOSER_DRAFT_V2_PREFIX)
        append(draftedAtSeconds ?: DRAFTED_AT_UNSTAMPED)
        append(FIELD_SEPARATOR)
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
    when {
        stored.startsWith(COMPOSER_DRAFT_V2_PREFIX) -> decodeVersionedComposerDraftV2(stored)
        stored.startsWith(COMPOSER_DRAFT_VERSION_PREFIX) -> decodeVersionedComposerDraft(stored)
        else -> legacyComposerDraftSnapshot(stored)
    }

/**
 * Decodes a value from the retired Android draft store for one-time MDK migration.
 * Raw legacy text remains valid, while malformed versioned blobs are dropped instead
 * of being copied into MDK as control-character-prefixed message text.
 */
internal fun decodeLegacyDraftForMigration(stored: String): String? {
    val decoded = decodeComposerDraftStored(stored)
    val isVersioned =
        stored.startsWith(COMPOSER_DRAFT_V2_PREFIX) ||
            stored.startsWith(COMPOSER_DRAFT_VERSION_PREFIX)
    return decoded.textFieldValue.text.takeUnless { isVersioned && !decoded.focusOnRestore }
}

private fun decodeVersionedComposerDraftV2(stored: String): ComposerDraftSnapshot {
    val body = stored.substring(COMPOSER_DRAFT_V2_PREFIX.length)
    val fields = body.split(FIELD_SEPARATOR, limit = VERSIONED_DRAFT_V2_FIELD_COUNT)
    val draftedAtRaw = fields.getOrNull(0)?.toLongOrNull()
    val selectionStart = fields.getOrNull(1)?.toIntOrNull()
    val selectionEnd = fields.getOrNull(2)?.toIntOrNull()
    val declaredLength = fields.getOrNull(3)?.toIntOrNull()
    val text = fields.getOrNull(4)
    return when {
        fields.size != VERSIONED_DRAFT_V2_FIELD_COUNT -> malformedComposerDraftSnapshot(stored)
        draftedAtRaw == null || selectionStart == null || selectionEnd == null || declaredLength == null ->
            malformedComposerDraftSnapshot(stored)
        text == null || declaredLength != text.length -> malformedComposerDraftSnapshot(stored)
        else ->
            ComposerDraftSnapshot(
                textFieldValue =
                    TextFieldValue(
                        text = text,
                        selection = clampComposerDraftSelection(text, selectionStart, selectionEnd),
                    ),
                focusOnRestore = true,
                draftedAtSeconds = draftedAtRaw.takeIf { it >= 0 },
            )
    }
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
