package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.MessageDraftConditionalDeleteResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftGeneration
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps Android's composer geometry transaction aligned with the MDK-owned
 * draft transaction without putting UI state in the protocol repository.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One owner aligns draft, send, hydration and geometry lifecycles.
internal class ComposerDraftExpansionBridge(
    private val draftWriter: CoalescingMessageDraftWriter,
    private val draftStore: DraftStore,
    private val draftRepository: MessageDraftRepository,
    private val expansionRetention: ComposerExpansionStateRetention,
    private val scope: CoroutineScope,
    private val onDraftPresentationChanged: (
        accountRef: String,
        groupIdHex: String,
        nativeDraftConsumed: Boolean,
    ) -> Unit,
    private val onCleanupFailure: (groupIdHex: String, cause: Throwable) -> Unit,
) {
    private val sentPresentation = SentComposerDraftPresentation()

    // DraftStore intentionally omits whitespace. Remember only the accepted
    // editor transition so deleting that text still reaches the native draft,
    // even if an intervening attachment edit advances the repository generation.
    private val whitespaceEdits = mutableSetOf<Pair<String, String>>()

    /** Stores editor presentation and advances the stale-send fence only for changed text. */
    fun setDraft(
        accountRef: String,
        groupIdHex: String,
        value: TextFieldValue,
    ) {
        val currentDraft = draftStore.getDraft(accountRef, groupIdHex)
        // An absent draft can still own native ordering metadata or a pending-send
        // recovery marker. An unchanged empty callback must not clear either.
        val key = accountRef to groupIdHex
        val clearsWhitespace = value.text.isEmpty() && key in whitespaceEdits
        if (currentDraft == null && value.text.isEmpty() && !clearsWhitespace) return
        val currentText = currentDraft?.textFieldValue?.text.orEmpty()
        draftStore.set(accountRef, groupIdHex, value)
        // Caret and IME composition are Android presentation state. Submitting unchanged
        // content would invalidate a dictation Send captured before this UI-only update.
        if (currentText == value.text && !clearsWhitespace) return
        val generation = draftWriter.submit(accountRef, groupIdHex, value.text)
        rememberWhitespaceEdit(accountRef, groupIdHex, value.text)
        sentPresentation.onDraftChanged(accountRef, groupIdHex, generation, value.text)
        expansionRetention.onDraftGenerationAdvanced(accountRef, groupIdHex, generation.value)
    }

    /** Applies a delayed producer only while both draft and geometry still share its generation. */
    fun setDraftIfCurrent(
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ): Boolean = writeDraftIfCurrent(accountRef, groupIdHex, expectedRevision, value) != null

    /** Returns this write's own generation, never a later sample owned by another producer. */
    fun writeDraftIfCurrent(
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ): Long? {
        val generation =
            draftWriter.submitIfCurrent(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                expected = MessageDraftGeneration(expectedRevision),
                content = value.text,
            ) ?: return null
        draftStore.set(accountRef, groupIdHex, value)
        rememberWhitespaceEdit(accountRef, groupIdHex, value.text)
        sentPresentation.onDraftChanged(accountRef, groupIdHex, generation, value.text)
        expansionRetention.onDraftGenerationAdvanced(accountRef, groupIdHex, generation.value)
        return generation.value
    }

    /** A committed hydration replaces editor presentation, including omitted whitespace. */
    fun replaceHydratedDraft(
        accountRef: String,
        groupIdHex: String,
        content: String?,
        updatedAtMs: Long?,
    ) {
        draftStore.replaceFromAuthoritative(accountRef, groupIdHex, content, updatedAtMs)
        whitespaceEdits.remove(accountRef to groupIdHex)
    }

    /** Returns the generation an explicit resize must capture for this exact draft owner. */
    fun generation(
        accountRef: String,
        groupIdHex: String,
    ): Long = draftWriter.generation(accountRef, groupIdHex).value

    /** Captures draft recovery and UI revision as one account/conversation send token. */
    fun captureForSend(
        accountRef: String?,
        groupIdHex: String,
    ): DraftSendClearToken? =
        accountRef?.let { owner ->
            val generation = draftWriter.generation(owner, groupIdHex)
            expansionRetention.onDraftGenerationAdvanced(owner, groupIdHex, generation.value)
            DraftSendClearToken(
                accountRef = owner,
                groupIdHex = groupIdHex,
                generation = generation,
                recoveryDraft = draftStore.getDraft(owner, groupIdHex),
                composerExpansionRevision = expansionRetention.revisionFor(owner, groupIdHex),
            )
        }

    /** Optimistically hides only the draft and geometry captured by [token]. */
    fun hideForPendingSend(token: DraftSendClearToken): Boolean {
        val claimed =
            draftWriter.beginPendingSendPresentation(token.accountRef, token.groupIdHex, token.generation) {
                if (token.recoveryDraft != null) {
                    draftStore.hideForPendingSend(token.accountRef, token.groupIdHex)
                }
            }
        if (claimed) {
            whitespaceEdits.remove(token.accountRef to token.groupIdHex)
            sentPresentation.hide(token)
            expansionRetention.onSendAccepted(
                token.accountRef,
                token.groupIdHex,
                token.generation.value,
                token.composerExpansionRevision,
            )
        }
        return claimed
    }

    /** Restores a definite failure and publishes a revision so the mounted field rehydrates. */
    fun restoreAfterTerminalFailure(token: DraftSendClearToken) {
        draftWriter.runIfCurrent(token.accountRef, token.groupIdHex, token.generation) {
            sentPresentation.restore(token)
            token.recoveryDraft?.let { recovery ->
                draftStore.restoreSnapshot(token.accountRef, token.groupIdHex, recovery)
            }
            expansionRetention.onSendTerminalFailure(
                token.accountRef,
                token.groupIdHex,
                token.generation.value,
                token.composerExpansionRevision,
            )
            onDraftPresentationChanged(token.accountRef, token.groupIdHex, false)
        }
    }

    /**
     * Evicts only the durably accepted generation, then deletes its MDK draft
     * asynchronously without allowing an older hydration to resurrect it.
     */
    fun clearAfterDurableAcceptance(token: DraftSendClearToken) {
        val accountRef = token.accountRef
        val groupIdHex = token.groupIdHex
        val sentGeneration = token.generation
        val cleanupGeneration =
            draftWriter.beginSuccessfulSendCleanup(accountRef, groupIdHex, sentGeneration) {
                draftStore.set(accountRef, groupIdHex, TextFieldValue(""))
            } ?: return
        whitespaceEdits.remove(accountRef to groupIdHex)
        sentPresentation.hide(token, cleanupGeneration)
        expansionRetention.onSendDurablyAccepted(
            accountRef,
            groupIdHex,
            sentGeneration.value,
            token.composerExpansionRevision,
        )
        scope.launch {
            when (val deletion = draftWriter.deleteIfCurrent(accountRef, groupIdHex, cleanupGeneration)) {
                is MessageDraftConditionalDeleteResult.Applied -> {
                    when (val result = deletion.result) {
                        is MessageDraftMutationResult.Success -> {
                            draftWriter.runIfCurrent(accountRef, groupIdHex, cleanupGeneration) {
                                draftStore.replaceFromAuthoritative(accountRef, groupIdHex, null, null)
                            }
                            // The native draft is now authoritative for any composer that
                            // reopened while this send's cleanup was still running.
                            onDraftPresentationChanged(accountRef, groupIdHex, true)
                        }
                        is MessageDraftMutationResult.Failure -> onCleanupFailure(groupIdHex, result.cause)
                        else -> Unit
                    }
                }
                MessageDraftConditionalDeleteResult.Superseded -> Unit
            }
        }
    }

    /** Keeps native draft selection behind the same account/group/generation fence as the composer. */
    fun selectedPreview(
        accountRef: String,
        groupIdHex: String,
        nativePreview: SelectedChatPreviewFfi?,
        hasOptimisticSendPreview: Boolean = false,
    ): SelectedChatPreviewFfi? =
        sentPresentation.selectedPreview(
            accountRef,
            groupIdHex,
            draftWriter.generation(accountRef, groupIdHex),
            draftStore.get(accountRef, groupIdHex),
            if (hasOptimisticSendPreview && nativePreview == SelectedChatPreviewFfi.Empty) {
                SelectedChatPreviewFfi.Message
            } else {
                nativePreview
            },
        )

    fun removeAccount(accountRef: String) {
        whitespaceEdits.removeAll { it.first == accountRef }
        sentPresentation.removeAccount(accountRef)
    }

    /** Retains omitted whitespace until an accepted text edit or send replaces its editor state. */
    private fun rememberWhitespaceEdit(
        accountRef: String,
        groupIdHex: String,
        text: String,
    ) {
        val key = accountRef to groupIdHex
        if (text.isNotEmpty() && text.isBlank()) {
            whitespaceEdits.add(key)
        } else {
            whitespaceEdits.remove(key)
        }
    }

    /** Flushes queued edits before the owning group removes its authoritative draft. */
    suspend fun deleteBeforeGroupRemoval(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftMutationResult {
        draftWriter.flush()
        return draftRepository.delete(accountRef, groupIdHex).also { result ->
            if (result is MessageDraftMutationResult.Success) whitespaceEdits.remove(accountRef to groupIdHex)
        }
    }
}
