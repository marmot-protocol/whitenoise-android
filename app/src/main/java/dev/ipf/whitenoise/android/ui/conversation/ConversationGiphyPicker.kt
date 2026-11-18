package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.conversation.media.GiphySearchSheet

/** Owns the transient picker and returns its launcher only while this conversation can send GIFs. */
@Composable
internal fun rememberConversationGiphyPicker(
    controller: ConversationController,
    attachmentOrigin: String,
    presentationAvailable: Boolean,
    onAccepted: () -> Unit,
): (() -> Unit)? {
    var open by remember(attachmentOrigin) { mutableStateOf(false) }
    val available = controller.canSendMessages && controller.editingMessageId == null && presentationAvailable
    LaunchedEffect(available) {
        if (!available) open = false
    }
    if (open && available) {
        GiphySearchSheet(
            apiKey = BuildConfig.WHITENOISE_GIPHY_API_KEY,
            onDismiss = { open = false },
            onSend = { wireText ->
                if (open) {
                    open = false
                    controller.appState.launchMutation {
                        if (controller.ownsRetainedForegroundPresentation && controller.canSendMessages && controller.editingMessageId == null) {
                            controller.send(wireText, onAccepted = onAccepted, consumeReply = false)
                        }
                    }
                }
            },
        )
    }
    return if (BuildConfig.WHITENOISE_GIPHY_API_KEY.isNotBlank() && available) ({ open = true }) else null
}
