package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationEmojiSource
import dev.ipf.whitenoise.android.state.conversationEmojiSources
import dev.ipf.whitenoise.android.state.readConversationEmojiArtwork
import dev.ipf.whitenoise.android.ui.conversation.messages.emojiArt
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Original sources for the current loaded conversation window, with artwork decoded only for visible cells. */
internal class ConversationEmojiPicker(
    val sources: Map<String, ConversationEmojiSource>,
    private val read: suspend (String, ConversationEmojiSource) -> EmojiArt?,
) {
    private val decodes = Semaphore(2)

    /** Uses the normal policy-aware attachment reader and verifies plaintext before drawing received artwork. */
    suspend fun artwork(shortcode: String): EmojiArt? =
        decodes.withPermit {
            sources[shortcode]?.let { source ->
                read(shortcode, source)
            }
        }
}

/** Drops received picker metadata and pixels whenever its account, controller or visible window changes. */
@Composable
internal fun rememberConversationEmojiPicker(controller: ConversationController): ConversationEmojiPicker? {
    val appState = controller.appState
    val owner = Triple(appState.activeAccountRef, appState.runtimeGeneration, appState.mediaUploadSessionEpoch())
    if (controller.boundAccountRef != owner.first) return null
    val rows = controller.timeline.toList()
    val deleted = controller.deletedMessageIds.toSet()
    return key(controller, owner, rows, deleted) {
        val picker by produceState<ConversationEmojiPicker?>(null) {
            value =
                ConversationEmojiPicker(controller.conversationEmojiSources()) { code, source ->
                    emojiArt(controller.mediaTrustDomain) {
                        controller.readConversationEmojiArtwork(code, source, CustomEmojiStore.MAX_BYTES.toLong()).bytes
                    }
                }
        }
        picker
    }
}
