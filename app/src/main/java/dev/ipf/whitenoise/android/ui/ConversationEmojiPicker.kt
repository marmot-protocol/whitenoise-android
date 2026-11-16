package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationEmojiSource
import dev.ipf.whitenoise.android.state.conversationEmojiSources
import dev.ipf.whitenoise.android.state.readConversationEmojiArtwork
import dev.ipf.whitenoise.android.ui.conversation.messages.emojiArt
import kotlinx.coroutines.flow.collectLatest
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
    return key(controller, owner) {
        val picker by produceState<ConversationEmojiPicker?>(null) {
            snapshotFlow { controller.timeline.toList() to controller.deletedMessageIds.toSet() }
                .collectLatest {
                    val sources = controller.conversationEmojiSources()
                    if (sources != value?.sources.orEmpty()) {
                        value = conversationEmojiPicker(controller, sources)
                    }
                }
        }
        picker
    }
}

/** Creates visible-cell readers only when the current window contains received definitions. */
private fun conversationEmojiPicker(
    controller: ConversationController,
    sources: Map<String, ConversationEmojiSource>,
): ConversationEmojiPicker? {
    if (sources.isEmpty()) return null
    return ConversationEmojiPicker(sources) { code, source ->
        emojiArt(controller.mediaTrustDomain) {
            controller
                .readConversationEmojiArtwork(
                    code,
                    source,
                    AttachmentDownloadPriority.Automatic,
                    CustomEmojiStore.MAX_BYTES.toLong(),
                ).bytes
        }
    }
}
