package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue

/** Owns the completed text and conditional draft writes for its captured origin. */
internal class ConversationDictationCompletionOwner(
    private val readDraft: (String, String) -> ConversationDictationDraftSnapshot,
    private val writeDraft: (String, String, Long, TextFieldValue) -> Long?,
    private val completionWrite: ((String, String, Long, TextFieldValue) -> ConversationDictationDraftWrite)? = null,
) {
    internal enum class PasteResult { Accepted, Retained, DeferredByHost, Superseded, Abandoned }

    internal data class DraftOperation(
        val sessionId: Long,
        val target: ConversationDictationTarget,
        val text: String,
        val attempts: Int,
        val currentSessionId: () -> Long?,
        val validate: () -> ConversationDictationTargetValidation,
    )

    private data class DeferredWrite(
        val operation: DraftOperation,
        val revision: Long,
        val value: TextFieldValue,
    )

    private var deferredWrite: DeferredWrite? = null

    /** Retries the exact refused host operation; later editor revisions are never rebased or appended. */
    fun retryDeferred(
        sessionId: Long,
        target: ConversationDictationTarget,
    ): PasteResult =
        try {
            val pending = deferredWrite
            when {
                pending == null -> PasteResult.Retained
                pending.operation.sessionId != sessionId ||
                    pending.operation.target != target ||
                    pending.operation.currentSessionId() != sessionId -> PasteResult.Abandoned
                else -> retryCapturedDraft(pending)
            }
        } catch (_: Throwable) {
            // Keep the captured text without rebasing or making a second recovery write.
            PasteResult.Retained
        }

    private fun retryCapturedDraft(pending: DeferredWrite): PasteResult =
        when (pending.operation.validate()) {
            ConversationDictationTargetValidation.Indeterminate -> PasteResult.DeferredByHost
            ConversationDictationTargetValidation.DefinitelyRemoved -> PasteResult.Retained
            ConversationDictationTargetValidation.Available -> {
                val target = pending.operation.target
                if (readDraft(target.accountRef, target.groupIdHex).revision != pending.revision) {
                    PasteResult.Superseded
                } else {
                    when (writeCompletionDraft(pending.operation, pending.revision, pending.value)) {
                        is ConversationDictationDraftWrite.Written -> PasteResult.Accepted
                        ConversationDictationDraftWrite.DeferredByHost -> PasteResult.DeferredByHost
                        ConversationDictationDraftWrite.Refused -> PasteResult.Retained
                    }
                }
            }
        }

    var draftRecovery = ConversationDictationDraftRecovery(readDraft, writeDraft)
        private set
    val intent = ConversationDictationCompletionIntent()
    var transcript = ""
        private set
    val validation = ConversationDictationCompletionValidationOwner()
    val dispatch = ConversationDictationCompletionDispatchOwner { draftRecovery }

    fun parkDraftRecovery(): ConversationDictationDraftRecovery =
        draftRecovery.also { draftRecovery = ConversationDictationDraftRecovery(readDraft, writeDraft) }

    fun restoreDraftRecovery(parked: ConversationDictationDraftRecovery) {
        draftRecovery = parked
    }

    fun recordTranscript(text: String) {
        transcript = text
    }

    /** Recognition teardown retires validation and an unadmitted send. */
    fun reset() {
        deferredWrite = null
        validation.reset()
        dispatch.reset()
        transcript = ""
        intent.reset()
    }

    /** Applies the captured insertion or appends to a concurrent edit using conditional draft writes. */
    fun paste(operation: DraftOperation): PasteResult {
        val (sessionId, target, text) = operation
        var result: PasteResult? = null
        var remaining = operation.attempts
        while (remaining > 0 && result == null) {
            remaining -= 1
            if (operation.currentSessionId() != sessionId) {
                result = PasteResult.Abandoned
            } else if (operation.validate() != ConversationDictationTargetValidation.Available) {
                result = PasteResult.Retained
            } else {
                val current = readDraft(target.accountRef, target.groupIdHex)
                when (val merge = mergeConversationDictationTranscript(target.capturedDraft, current.value, text)) {
                    is ConversationDictationMerge.Applied -> {
                        val accepted =
                            try {
                                writeCompletionDraft(operation, current.revision, merge.value)
                            } catch (_: Throwable) {
                                result = PasteResult.Retained
                                null
                            }
                        when (accepted) {
                            is ConversationDictationDraftWrite.Written -> result = PasteResult.Accepted
                            ConversationDictationDraftWrite.DeferredByHost -> result = PasteResult.DeferredByHost
                            else -> Unit
                        }
                    }
                    ConversationDictationMerge.NeedsAppendFallback -> result = appendLatest(operation)
                }
            }
        }
        return result ?: appendLatest(operation)
    }

    private fun writeCompletionDraft(
        operation: DraftOperation,
        revision: Long,
        value: TextFieldValue,
    ): ConversationDictationDraftWrite {
        val target = operation.target
        val result =
            completionWrite?.invoke(target.accountRef, target.groupIdHex, revision, value)
                ?: writeDraft(target.accountRef, target.groupIdHex, revision, value)
                    ?.let(ConversationDictationDraftWrite::Written)
                ?: ConversationDictationDraftWrite.Refused
        if (result == ConversationDictationDraftWrite.DeferredByHost) {
            deferredWrite = DeferredWrite(operation, revision, value)
        }
        if (result is ConversationDictationDraftWrite.Written) deferredWrite = null
        return result
    }

    fun appendLatest(operation: DraftOperation): PasteResult {
        val (sessionId, target, text) = operation
        var result: PasteResult? = null
        var remaining = operation.attempts
        while (remaining > 0 && result == null) {
            remaining -= 1
            if (operation.currentSessionId() != sessionId) {
                result = PasteResult.Abandoned
            } else if (operation.validate() != ConversationDictationTargetValidation.Available) {
                result = PasteResult.Retained
            } else {
                val current = readDraft(target.accountRef, target.groupIdHex)
                val merged = appendConversationDictationTranscript(current.value, text)
                val accepted =
                    try {
                        writeCompletionDraft(operation, current.revision, merged)
                    } catch (_: Throwable) {
                        result = PasteResult.Retained
                        null
                    }
                when (accepted) {
                    is ConversationDictationDraftWrite.Written -> result = PasteResult.Accepted
                    ConversationDictationDraftWrite.DeferredByHost -> result = PasteResult.DeferredByHost
                    else -> Unit
                }
            }
        }
        return result ?: PasteResult.Retained
    }
}
