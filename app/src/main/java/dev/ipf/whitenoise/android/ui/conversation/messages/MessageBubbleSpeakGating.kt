package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageProjector

/** Whether the long-press menu should offer Speak aloud for this bubble. */
internal fun messageBubbleCanSpeak(
    record: AppMessageRecordFfi,
    editedText: String?,
    deleted: Boolean,
    invalidated: Boolean,
    ttsHasUsableEngine: Boolean,
): Boolean =
    !deleted &&
        !invalidated &&
        ttsHasUsableEngine &&
        MessageProjector.canSpeak(
            record,
            editedText = editedText,
        )
