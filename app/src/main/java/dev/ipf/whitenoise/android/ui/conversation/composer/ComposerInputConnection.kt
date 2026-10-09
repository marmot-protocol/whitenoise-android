@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.getSelectedText
import androidx.compose.ui.text.input.getTextAfterSelection
import androidx.compose.ui.text.input.getTextBeforeSelection

internal fun composerInputMethodRequest(
    request: PlatformTextInputMethodRequest,
    readAcceptedValue: () -> TextFieldValue,
): PlatformTextInputMethodRequest =
    object : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
            ComposerInputConnection(request.createInputConnection(outAttributes), readAcceptedValue)
    }

/**
 * The legacy Compose connection can answer with its pre-frame snapshot after a hardware Backspace.
 * IMEs use that stale text to resume composition across the preceding separator. Read the accepted
 * composer owner directly; editing, batch boundaries and image input remain owned by Compose.
 */
internal class ComposerInputConnection(
    target: InputConnection,
    private val readAcceptedValue: () -> TextFieldValue,
) : InputConnectionWrapper(target, false) {
    private var active = true

    override fun getTextBeforeCursor(
        length: Int,
        flags: Int,
    ): CharSequence? = if (active) readAcceptedValue().getTextBeforeSelection(length).toString() else null

    override fun getTextAfterCursor(
        length: Int,
        flags: Int,
    ): CharSequence? = if (active) readAcceptedValue().getTextAfterSelection(length).toString() else null

    override fun getSelectedText(flags: Int): CharSequence? {
        if (!active) return null
        val value = readAcceptedValue()
        return if (value.selection.collapsed) null else value.getSelectedText().toString()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(
        beforeLength: Int,
        afterLength: Int,
        flags: Int,
    ): SurroundingText? {
        if (!active) return null
        val value = readAcceptedValue()
        val before = value.getTextBeforeSelection(beforeLength).toString()
        val selected = value.getSelectedText().toString()
        val after = value.getTextAfterSelection(afterLength).toString()
        return SurroundingText(
            before + selected + after,
            before.length,
            before.length + selected.length,
            value.selection.min - before.length,
        )
    }

    override fun getExtractedText(
        request: ExtractedTextRequest?,
        flags: Int,
    ): ExtractedText? {
        // Preserve Compose's registration of extracted-text monitoring for active connections.
        if (!active || super.getExtractedText(request, flags) == null) return null
        val value = readAcceptedValue()
        return ExtractedText().apply {
            text = value.text
            startOffset = 0
            partialStartOffset = -1
            partialEndOffset = value.text.length
            selectionStart = value.selection.min
            selectionEnd = value.selection.max
            this.flags = if ('\n' in value.text) 0 else ExtractedText.FLAG_SINGLE_LINE
        }
    }

    override fun closeConnection() {
        active = false
        super.closeConnection()
    }
}
