package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.mutableStateMapOf
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.media.editor.MessageDraftGeneration

/** Fences stale native draft selections while an accepted composer generation owns their presentation. */
internal class SentComposerDraftPresentation {
    private val generations = mutableStateMapOf<Pair<String, String>, MessageDraftGeneration>()

    fun hide(
        token: DraftSendClearToken,
        generation: MessageDraftGeneration = token.generation,
    ) {
        val key = token.accountRef to token.groupIdHex
        generations[key] = generation
        // Only bounded process-lifetime UI state is retained; MDK owns the recoverable draft.
        if (generations.size > MAX_RETAINED_DRAFTS) generations.keys.firstOrNull { it != key }?.let(generations::remove)
    }

    fun onDraftChanged(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
        text: String,
    ) {
        val key = accountRef to groupIdHex
        if (text.isBlank() && key in generations) generations[key] = generation
    }

    fun removeAccount(accountRef: String) {
        generations.keys.removeAll { it.first == accountRef }
    }

    fun restore(token: DraftSendClearToken) {
        generations.remove(token.accountRef to token.groupIdHex, token.generation)
    }

    fun selectedPreview(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
        localDraft: String?,
        nativePreview: SelectedChatPreviewFfi?,
    ): SelectedChatPreviewFfi? {
        val sentGeneration = generations[accountRef to groupIdHex] ?: return nativePreview
        return when {
            generation != sentGeneration && localDraft != null -> null
            generation == sentGeneration &&
                (nativePreview is SelectedChatPreviewFfi.Draft || nativePreview == SelectedChatPreviewFfi.Empty) ->
                SelectedChatPreviewFfi.Message
            else -> nativePreview
        }
    }

    private companion object {
        const val MAX_RETAINED_DRAFTS = 128
    }
}
