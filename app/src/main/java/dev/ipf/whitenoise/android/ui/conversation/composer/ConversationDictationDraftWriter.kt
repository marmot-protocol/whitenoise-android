package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationController

/** An editor replaced by a terminal dictation write cannot overwrite the recovered draft. */
internal fun conversationDictationDraftWriter(
    dictation: ConversationDictationController,
    accountRef: String?,
    groupIdHex: String,
    presentationRevision: Int,
    write: (TextFieldValue, Boolean) -> Unit,
): (TextFieldValue, Boolean) -> Unit =
    { value, textChanged ->
        if (accountRef != null && dictation.completionRevision(accountRef, groupIdHex) == presentationRevision) {
            write(value, textChanged)
        }
    }
