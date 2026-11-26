package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.mutableStateMapOf
import dev.ipf.marmotkit.ChatListDraftPreviewFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.media.editor.MessageDraftGeneration

/** Text and generation of a draft whose durable acceptance may still need to clear a reopened editor. */
internal data class AcceptedComposerCaption(
    val generation: MessageDraftGeneration,
    val text: String,
)

/** Fences stale native draft selections while an accepted composer generation owns their presentation. */
internal class SentComposerDraftPresentation {
    private data class Fence(
        val generation: MessageDraftGeneration,
        val sentText: String?,
        val accepted: Boolean,
    )

    private val generations = mutableStateMapOf<Pair<String, String>, Fence>()
    private val insertionOrder = linkedSetOf<Pair<String, String>>()

    fun hide(
        token: DraftSendClearToken,
        generation: MessageDraftGeneration = token.generation,
        accepted: Boolean = false,
    ) {
        val key = token.accountRef to token.groupIdHex
        generations[key] = Fence(generation, token.recoveryDraft?.textFieldValue?.text, accepted)
        // Only bounded process-lifetime UI state is retained; MDK owns the recoverable draft.
        insertionOrder.remove(key)
        insertionOrder.add(key)
        if (insertionOrder.size > MAX_RETAINED_DRAFTS) {
            insertionOrder.first().let { oldest ->
                insertionOrder.remove(oldest)
                generations.remove(oldest)
            }
        }
    }

    /** A definite acceptance, distinct from the optimistic fence used while the token is still pending. */
    fun acceptedCaption(
        accountRef: String,
        groupIdHex: String,
    ): AcceptedComposerCaption? =
        generations[accountRef to groupIdHex]?.takeIf { it.accepted }?.let { fence ->
            fence.sentText?.takeIf { it.isNotBlank() }?.let { AcceptedComposerCaption(fence.generation, it) }
        }

    fun onDraftChanged(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
        text: String,
    ) {
        val key = accountRef to groupIdHex
        if (text.isBlank()) generations[key]?.let { generations[key] = it.copy(generation = generation) }
    }

    fun removeAccount(accountRef: String) {
        generations.keys.removeAll { it.first == accountRef }
        insertionOrder.removeAll { it.first == accountRef }
    }

    fun restore(token: DraftSendClearToken) {
        val key = token.accountRef to token.groupIdHex
        if (generations[key]?.generation == token.generation) {
            generations.remove(key)
            insertionOrder.remove(key)
        }
    }

    fun selectedPreview(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
        localDraft: String?,
        nativePreview: SelectedChatPreviewFfi?,
    ): SelectedChatPreviewFfi? {
        val fence = generations[accountRef to groupIdHex] ?: return nativePreview
        val nativeDraft = (nativePreview as? SelectedChatPreviewFfi.Draft)?.draft
        return when {
            generation != fence.generation && localDraft != null ->
                if (nativeDraft != null && nativeDraftMatchesSentText(nativeDraft, localDraft)) {
                    nativePreview
                } else {
                    SelectedChatPreviewFfi.Draft(
                        ChatListDraftPreviewFfi(
                            text = localDraft,
                            textTruncated = false,
                            attachmentCount = 0uL,
                            attachmentKind = null,
                        ),
                    )
                }
            nativeDraft?.text?.isBlank() == true && nativeDraftHasAttachments(nativeDraft) ->
                nativePreview
            generation == fence.generation &&
                nativeDraft != null &&
                nativeDraftMatchesSentText(nativeDraft, fence.sentText) -> SelectedChatPreviewFfi.Message
            else -> nativePreview
        }
    }

    private fun nativeDraftHasAttachments(draft: ChatListDraftPreviewFfi): Boolean {
        val attachmentCount = draft.attachmentCount
        return attachmentCount > 0uL || draft.attachmentKind != null
    }

    private fun nativeDraftMatchesSentText(
        draft: ChatListDraftPreviewFfi,
        sentText: String?,
    ): Boolean = sentText == null || draft.text == sentText || (draft.textTruncated && sentText.startsWith(draft.text))

    private companion object {
        const val MAX_RETAINED_DRAFTS = 128
    }
}
