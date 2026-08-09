package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface PhotoEditorCommitResult {
    data class Success(
        val draft: MessageDraftFfi,
        val attachment: MessageDraftAttachmentFfi,
        val outputPlan: PhotoEditorOutputPlan,
        val sessionRecoveryPending: Boolean,
    ) : PhotoEditorCommitResult

    data object SourceUnavailable : PhotoEditorCommitResult

    data class RenderFailed(
        val result: PhotoEditorRenderResult,
    ) : PhotoEditorCommitResult

    data object StaleAttachment : PhotoEditorCommitResult

    data object MissingAttachment : PhotoEditorCommitResult

    data object SessionPersistenceFailed : PhotoEditorCommitResult

    data class DraftFailure(
        val cause: Throwable,
    ) : PhotoEditorCommitResult
}

internal class PhotoEditorCommitter(
    private val sources: EditorSourceStore,
    private val renderer: PhotoEditorRenderer,
    private val drafts: MessageDraftRepository,
    private val sourceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    // The commit transaction keeps cancellation checks, MDK mutation, and lease handoff together.
    suspend fun commit(
        accountRef: String,
        groupIdHex: String,
        currentAttachment: MessageDraftAttachmentFfi,
        expectedDigest: String,
        sourceLeaseId: String,
        recipe: PhotoEditRecipe,
        quality: MediaQuality,
    ): PhotoEditorCommitResult {
        val sourceBytes =
            try {
                withContext(sourceDispatcher) { sources.bytes(sourceLeaseId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return PhotoEditorCommitResult.SourceUnavailable
        currentCoroutineContext().ensureActive()
        val rendered = renderer.render(sourceBytes, recipe, quality)
        if (rendered !is PhotoEditorRenderResult.Success) {
            return PhotoEditorCommitResult.RenderFailed(rendered)
        }
        currentCoroutineContext().ensureActive()
        val replacement =
            MessageDraftAttachmentFfi(
                id = currentAttachment.id,
                fileName = editedFileName(currentAttachment.fileName, rendered.image.fileExtension),
                mediaType = rendered.image.mediaType,
                plaintext = rendered.image.bytes,
                dim = "${rendered.image.width}x${rendered.image.height}",
                thumbhash = rendered.image.thumbhash,
                durationSeconds = null,
                waveformSamples = emptyList(),
            )
        val pendingSession =
            EditorAttachmentSession(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                attachmentId = replacement.id,
                attachmentDigest = replacement.editorDigest(),
                sourceLeaseId = sourceLeaseId,
                qualityPreference = quality.preferenceValue,
                recipe = recipe,
                phase = EditorSessionPhase.Pending,
                updatedAtMs = 0L,
            )
        currentCoroutineContext().ensureActive()
        return when (
            val result =
                drafts.replaceAttachment(
                    accountRef = accountRef,
                    groupIdHex = groupIdHex,
                    attachmentId = currentAttachment.id,
                    expectedDigest = expectedDigest,
                    replacement = replacement,
                    pendingSession = pendingSession,
                )
        ) {
            is MessageDraftMutationResult.Success -> {
                val draft = result.draft ?: return PhotoEditorCommitResult.MissingAttachment
                val previousLease = result.previousEditorSession?.sourceLeaseId
                if (!result.editorSessionRecoveryPending && previousLease != null && previousLease != sourceLeaseId) {
                    withContext(sourceDispatcher) { sources.release(previousLease) }
                }
                PhotoEditorCommitResult.Success(
                    draft = draft,
                    attachment = replacement,
                    outputPlan = rendered.plan,
                    sessionRecoveryPending = result.editorSessionRecoveryPending,
                )
            }
            MessageDraftMutationResult.StaleAttachment -> PhotoEditorCommitResult.StaleAttachment
            MessageDraftMutationResult.MissingAttachment -> PhotoEditorCommitResult.MissingAttachment
            MessageDraftMutationResult.SessionPersistenceFailed -> PhotoEditorCommitResult.SessionPersistenceFailed
            is MessageDraftMutationResult.Failure -> PhotoEditorCommitResult.DraftFailure(result.cause)
            MessageDraftMutationResult.DuplicateAttachment,
            MessageDraftMutationResult.InvalidEditorSession,
            ->
                PhotoEditorCommitResult.DraftFailure(
                    IllegalStateException("Editor produced an invalid attachment mutation"),
                )
        }
    }
}

private fun editedFileName(
    original: String,
    extension: String,
): String {
    val safe = MediaPipeline.safeDisplayName(original)
    val dot = safe.lastIndexOf('.')
    val stem = (if (dot > 0) safe.substring(0, dot) else safe).take(96).ifBlank { "image" }
    return "$stem-edited.$extension"
}
