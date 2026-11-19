@file:Suppress("FunctionNaming") // Compose UI functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.ttsStartFailureMessage
import dev.ipf.whitenoise.android.ui.conversation.messages.RenderedTextHit
import dev.ipf.whitenoise.android.ui.conversation.messages.preparedHitFromRenderedHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Projects a local text attachment and reports the precise media-mix start refusal. */
@Suppress("LongParameterList", "LongMethod")
internal suspend fun WhiteNoiseAppState.speakTextAttachment(
    preview: TextAttachmentPreview,
    senderKey: String,
    senderDisplayName: String,
    messageIdHex: String,
    attachmentIndex: Int,
    actions: TextAttachmentNativeActions,
    hit: RenderedTextHit? = null,
) {
    val entry =
        withContext(Dispatchers.Default) {
            textAttachmentTtsEntry(
                preview = preview,
                senderKey = senderKey,
                senderDisplayName = senderDisplayName,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
            )
        }
    if (!actions.isCurrent()) return
    val preparedHit = hit?.let { preparedHitFromRenderedHit(entry, it) }
    if (hit != null && preparedHit == null) return
    val started =
        entry.text.isNotBlank() &&
            speakAloudPrepared(
                listOf(entry),
                Locale.getDefault(),
                startRenderedHit = preparedHit,
                isCurrent = actions::isCurrent,
            )
    if (!started) {
        if (actions.isCurrent()) {
            present(if (entry.text.isBlank()) R.string.tts_bar_error else ttsStartFailureMessage())
        }
        // The prepared-start owner already cleans up its own session; never stop a replacement queue here.
    } else {
        val speech = ttsController.state.value
        val source = actions.playbackSource
        if (source != null && actions.isCurrent() && source.accountRef == activeAccountRef) {
            attachmentSpeechDestination.value =
                dev.ipf.whitenoise.android.audio
                    .AttachmentSpeechDestination(source, speech.sessionId)
                    .current(speech)
        }
    }
}

internal data class TextAttachmentSpeechContext(
    val senderKey: String,
    val senderDisplayName: String,
    val messageIdHex: String,
    val attachmentIndex: Int,
)
