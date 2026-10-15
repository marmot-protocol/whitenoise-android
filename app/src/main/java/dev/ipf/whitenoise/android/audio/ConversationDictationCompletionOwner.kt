package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue

/** Owns the completed text and conditional draft writes for its captured origin. */
internal class ConversationDictationCompletionOwner(
    private val readDraft: (String, String) -> ConversationDictationDraftSnapshot,
    private val writeDraft: (String, String, Long, TextFieldValue) -> Long?,
) {
    internal enum class PasteResult { Accepted, Retained, Abandoned }
    internal data class DraftOperation(
        val sessionId: Long,
        val target: ConversationDictationTarget,
        val text: String,
        val attempts: Int,
        val currentSessionId: () -> Long?,
        val validate: () -> ConversationDictationTargetValidation,
    )
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
                            runCatching {
                                writeDraft(target.accountRef, target.groupIdHex, current.revision, merge.value)
                            }.getOrNull()
                        if (accepted != null) result = PasteResult.Accepted
                    }
                    ConversationDictationMerge.NeedsAppendFallback -> result = appendLatest(operation)
                }
            }
        }
        return result ?: appendLatest(operation)
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
                        writeDraft(target.accountRef, target.groupIdHex, current.revision, merged)
                    } catch (_: Throwable) {
                        result = PasteResult.Retained
                        null
                    }
                if (accepted != null) {
                    result = PasteResult.Accepted
                }
            }
        }
        return result ?: PasteResult.Retained
    }
}
