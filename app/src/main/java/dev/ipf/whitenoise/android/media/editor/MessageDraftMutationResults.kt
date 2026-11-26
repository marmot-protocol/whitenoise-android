package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftFfi

internal sealed interface MessageDraftMutationResult {
    data class Success(
        val draft: MessageDraftFfi?,
        val previousEditorSession: EditorAttachmentSession? = null,
        val editorSessionRecoveryPending: Boolean = false,
    ) : MessageDraftMutationResult

    data object MissingAttachment : MessageDraftMutationResult

    data object StaleAttachment : MessageDraftMutationResult

    data object DuplicateAttachment : MessageDraftMutationResult

    data object InvalidEditorSession : MessageDraftMutationResult

    data object SessionPersistenceFailed : MessageDraftMutationResult

    data class Failure(
        val cause: Throwable,
    ) : MessageDraftMutationResult
}

internal sealed interface MessageDraftConditionalDeleteResult {
    data class Applied(
        val result: MessageDraftMutationResult,
    ) : MessageDraftConditionalDeleteResult

    data object Superseded : MessageDraftConditionalDeleteResult
}

/** Outcome of a draft save that only writes while its generation is still authoritative. */
internal sealed interface MessageDraftConditionalSaveResult {
    data class Applied(
        val result: MessageDraftMutationResult,
    ) : MessageDraftConditionalSaveResult

    data object Superseded : MessageDraftConditionalSaveResult
}

internal data class MessageDraftGeneration(
    val value: Long,
)
