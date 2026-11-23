package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVoiceBubble
import dev.ipf.whitenoise.android.ui.conversation.media.VoicePresentationAttachmentKey
import dev.ipf.whitenoise.android.ui.conversation.media.rememberVoicePresentationOwner

/**
 * Own voice notes that are still sending, before any confirmed media exists. Each plays from its retained bytes
 * through a synthetic reference, so the sender can listen while the upload runs; a ring around Play marks the send
 * until it is accepted or fails.
 */
@Composable
@Suppress("FunctionNaming")
internal fun PendingVoiceAttachments(
    item: TimelineMessage,
    pendingAudio: List<IndexedValue<PendingAttachment>>,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onLongPress: () -> Unit,
) {
    pendingAudio.forEach { (index, pending) ->
        val presentationOwner = rememberVoicePresentationOwner(controller, appState)
        val attachmentKey = VoicePresentationAttachmentKey(item.record.messageIdHex, index, 0uL)
        key(presentationOwner, attachmentKey) {
            MediaVoiceBubble(
                messageIdHex = item.record.messageIdHex,
                attachmentIndex = index,
                reference =
                    remember(item.record.messageIdHex, index, pending) {
                        MediaAttachmentReferenceFfi(
                            locators = emptyList(),
                            ciphertextSha256 = "",
                            plaintextSha256 = "",
                            nonceHex = "",
                            fileName = pending.fileName,
                            mediaType = pending.mediaType,
                            version = EncryptedMediaVersionFfi.V1,
                            sourceEpoch = 0uL,
                            dim = null,
                            thumbhash = null,
                        )
                    },
                mine = true,
                focusPlaybackMessage = false,
                controller = controller,
                appState = appState,
                presentationOwner = presentationOwner,
                onLongPress = onLongPress,
                sending = item.status == MessageStatus.Pending,
            )
        }
    }
}
