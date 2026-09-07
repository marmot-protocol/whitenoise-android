package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.input.TextFieldValue
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
@Suppress("LongParameterList") // Every collaborator owns one distinct side of the draft/geometry transaction.
internal class ComposerDraftExpansionBridge(
    private val draftWriter: CoalescingMessageDraftWriter,
    private val draftStore: DraftStore,
    private val draftRepository: MessageDraftRepository,
    private val expansionRetention: ComposerExpansionStateRetention,
    private val scope: CoroutineScope,
    private val onDraftPresentationRestored: () -> Unit,
    private val onCleanupFailure: (groupIdHex: String, cause: Throwable) -> Unit,
) {
    /** Stores one user edit and advances the matching geometry's stale-send fence. */
    fun setDraft(
        accountRef: String,
        groupIdHex: String,
        value: TextFieldValue,
    ) {
        draftStore.set(accountRef, groupIdHex, value)
        val generation = draftWriter.submit(accountRef, groupIdHex, value.text)
        expansionRetention.onDraftGenerationAdvanced(accountRef, groupIdHex, generation.value)
    }

    /** Applies a delayed producer only while both draft and geometry still share its generation. */
    fun setDraftIfCurrent(
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ): Boolean {
        val generation =
            draftWriter.submitIfCurrent(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                expected = MessageDraftGeneration(expectedRevision),
                content = value.text,
            ) ?: return false
        draftStore.set(accountRef, groupIdHex, value)
        expansionRetention.onDraftGenerationAdvanced(accountRef, groupIdHex, generation.value)
        return true
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
            token.recoveryDraft?.let { recovery ->
                draftStore.restoreSnapshot(token.accountRef, token.groupIdHex, recovery)
            }
            expansionRetention.onSendTerminalFailure(
                token.accountRef,
                token.groupIdHex,
                token.generation.value,
                token.composerExpansionRevision,
            )
            onDraftPresentationRestored()
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
                        }
                        is MessageDraftMutationResult.Failure -> onCleanupFailure(groupIdHex, result.cause)
                        else -> Unit
                    }
                }
                MessageDraftConditionalDeleteResult.Superseded -> Unit
            }
        }
    }

    /** Flushes queued edits before the owning group removes its authoritative draft. */
    suspend fun deleteBeforeGroupRemoval(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftMutationResult {
        draftWriter.flush()
        return draftRepository.delete(accountRef, groupIdHex)
    }
}
