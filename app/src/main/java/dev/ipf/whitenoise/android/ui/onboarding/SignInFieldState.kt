package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.BunkerClipboardPaste

/** Holds the secure field and its transient paste record while pairing temporarily replaces Sign In. */
internal class SignInFieldState(
    initialText: String = "",
) {
    val key = TextFieldState(initialText)

    /** Captured on explicit paste and retained only for the current field value. */
    var clipboardPaste: BunkerClipboardPaste? = null

    /** Drops the clipboard record when a keyboard edit changes the link. */
    fun discardPasteForDifferentValue(value: String) {
        clipboardPaste = clipboardPaste?.takeIf { it.matchesLink(value) }
    }

    /** Replaces the draft from its owner and drops any record of the previous input. */
    fun replaceText(value: String) {
        clipboardPaste = null
        key.setTextAndPlaceCursorAtEnd(value)
    }
}
