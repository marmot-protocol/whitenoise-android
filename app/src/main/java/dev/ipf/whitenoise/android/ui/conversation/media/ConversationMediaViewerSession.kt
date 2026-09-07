package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi

/** Identity boundary that prevents a viewer session from crossing account or conversation generations. */
internal data class ConversationMediaViewerOwner(
    val accountRef: String?,
    val conversationId: String,
    val runtimeGeneration: Int,
)

/** Source-epoch-independent identity for one attachment in the conversation gallery. */
internal data class ConversationMediaViewerAttachmentId(
    val messageIdHex: String,
    val attachmentIndex: Int,
)

/** Immutable input emitted when a timeline visual is selected, even while its bytes are pending. */
internal data class ConversationMediaViewerOpenRequest(
    val messageIdHex: String,
    val attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    val tappedAttachmentIndex: Int,
    val sender: String,
    val recordedAt: ULong,
    val mine: Boolean,
) {
    val openingAttachment: ConversationMediaViewerAttachmentId?
        get() =
            attachments
                .firstOrNull { it.index == tappedAttachmentIndex && it.index >= 0 }
                ?.let { ConversationMediaViewerAttachmentId(messageIdHex, it.index) }
}

/** Active request plus the logical page selected within its generation-fenced viewer. */
internal data class ConversationMediaViewerActiveSession(
    val sessionId: Long,
    val request: ConversationMediaViewerOpenRequest,
    val selectedAttachment: ConversationMediaViewerAttachmentId,
)

/**
 * Owns one bounded, non-saveable conversation media session above lazy timeline rows.
 * Session ids reject callbacks from a previously dismissed/replaced viewer; logical
 * attachment ids deliberately exclude `sourceEpoch` so authoritative references can refresh in place.
 */
@Stable
internal class ConversationMediaViewerSessionState(
    val owner: ConversationMediaViewerOwner,
) {
    var active by mutableStateOf<ConversationMediaViewerActiveSession?>(null)
        private set

    private var nextSessionId = 0L

    /** Opens a valid request, or refreshes the same logical opening attachment without replacing its session. */
    fun open(request: ConversationMediaViewerOpenRequest): Boolean {
        if (owner.accountRef.isNullOrBlank() || owner.conversationId.isBlank()) return false
        val openingAttachment = request.openingAttachment
        return if (request.messageIdHex.isBlank() || openingAttachment == null) {
            false
        } else {
            val retainedRequest = request.copy(attachments = request.attachments.toList())
            val current = active
            active =
                if (current?.request?.openingAttachment == openingAttachment) {
                    current.copy(request = retainedRequest)
                } else {
                    ConversationMediaViewerActiveSession(
                        sessionId = ++nextSessionId,
                        request = retainedRequest,
                        selectedAttachment = openingAttachment,
                    )
                }
            true
        }
    }

    /** Records a settled pager page only when it belongs to the currently active viewer generation. */
    fun selectPage(
        sessionId: Long,
        attachment: ConversationMediaViewerAttachmentId,
    ): Boolean {
        val current = active ?: return false
        return if (
            current.sessionId != sessionId || attachment.messageIdHex.isBlank() || attachment.attachmentIndex < 0
        ) {
            false
        } else {
            if (current.selectedAttachment != attachment) {
                active = current.copy(selectedAttachment = attachment)
            }
            true
        }
    }

    /** Dismisses exactly the active generation and rejects duplicate or stale callbacks. */
    fun dismiss(sessionId: Long): Boolean {
        val current = active ?: return false
        return if (current.sessionId != sessionId) {
            false
        } else {
            active = null
            true
        }
    }
}

/** Creates fresh fail-closed session state whenever its account, conversation, or runtime generation changes. */
@Composable
internal fun rememberConversationMediaViewerSessionState(owner: ConversationMediaViewerOwner) =
    remember(owner) {
        ConversationMediaViewerSessionState(owner)
    }

/** Mounts the active viewer outside the lazy row and keys its playback subtree only to a deliberate new open. */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationMediaViewerSessionHost(
    state: ConversationMediaViewerSessionState,
    content: @Composable (ConversationMediaViewerActiveSession) -> Unit,
) {
    val active = state.active ?: return
    key(active.sessionId) { content(active) }
}
