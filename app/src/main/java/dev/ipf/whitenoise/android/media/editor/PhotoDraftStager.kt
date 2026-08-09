@file:Suppress("ReturnCount") // Guard returns keep staging and recovery failure states explicit.

package dev.ipf.whitenoise.android.media.editor

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.PendingAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal data class DraftBackedPhoto(
    val attachment: MessageDraftAttachmentFfi,
    val attachmentDigest: String,
    val sourceLeaseId: String,
    val sourceInfo: PhotoEditorSourceInfo,
    val recipe: PhotoEditRecipe,
    val quality: MediaQuality,
) {
    fun pendingAttachment(): PendingAttachment =
        PendingAttachment(
            plaintextBytes = attachment.plaintext,
            mediaType = attachment.mediaType,
            fileName = attachment.fileName,
            dim = attachment.dim,
            thumbhash = attachment.thumbhash,
        )
}

internal data class DraftPreparedPhoto(
    val attachment: MessageDraftAttachmentFfi,
    val attachmentDigest: String,
) {
    fun pendingAttachment(): PendingAttachment =
        PendingAttachment(
            plaintextBytes = attachment.plaintext,
            mediaType = attachment.mediaType,
            fileName = attachment.fileName,
            dim = attachment.dim,
            thumbhash = attachment.thumbhash,
        )
}

internal sealed interface PhotoDraftStageResult {
    data class Success(
        val photo: DraftBackedPhoto,
    ) : PhotoDraftStageResult

    data class NotEditable(
        val reason: PhotoEditorSourceFailure,
    ) : PhotoDraftStageResult

    /** MDK bytes remain sendable, but the retained source/session cannot reopen. */
    data class PreparedOnly(
        val photo: DraftPreparedPhoto,
    ) : PhotoDraftStageResult

    data object SourceUnavailable : PhotoDraftStageResult

    data object DraftUnavailable : PhotoDraftStageResult
}

/** Materializes a transient URI into an encrypted source and an MDK draft attachment. */
internal class PhotoDraftStager(
    private val contentResolver: ContentResolver,
    private val sources: EditorSourceStore,
    private val sessions: EditorSessionStore,
    private val renderer: PhotoEditorRenderer,
    private val drafts: MessageDraftRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun stage(
        uri: Uri,
        attachmentSlotId: String,
        accountRef: String,
        groupIdHex: String,
        quality: MediaQuality,
        legacyOccurrenceIndex: Int? = null,
    ): PhotoDraftStageResult {
        val attachmentId = stagedPhotoAttachmentId(accountRef, groupIdHex, attachmentSlotId)
        findExisting(attachmentId, accountRef, groupIdHex)?.let { return it }

        val staged =
            try {
                withContext(ioDispatcher) { sources.stageUri(contentResolver, uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return PhotoDraftStageResult.SourceUnavailable
            } as? EditorSourceStageResult.Success ?: return PhotoDraftStageResult.SourceUnavailable
        if (legacyOccurrenceIndex != null) {
            val restored =
                findLegacyExisting(
                    sourceLeaseId = staged.lease.id,
                    occurrenceIndex = legacyOccurrenceIndex,
                    accountRef = accountRef,
                    groupIdHex = groupIdHex,
                )
            if (restored != null) {
                withContext(NonCancellable + ioDispatcher) {
                    runCatching { sources.release(staged.lease.id) }
                }
                return PhotoDraftStageResult.Success(restored)
            }
        }
        return stageLease(
            leaseId = staged.lease.id,
            displayName = queryDisplayName(uri),
            attachmentId = attachmentId,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            quality = quality,
        )
    }

    /** Byte entry point used by already-materialized sources and deterministic tests. */
    suspend fun stageBytes(
        sourceBytes: ByteArray,
        displayName: String,
        attachmentId: String,
        accountRef: String,
        groupIdHex: String,
        quality: MediaQuality,
    ): PhotoDraftStageResult {
        findExisting(attachmentId, accountRef, groupIdHex)?.let { return it }
        val staged =
            try {
                withContext(ioDispatcher) { sources.stageBytes(sourceBytes) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return PhotoDraftStageResult.SourceUnavailable
            } as? EditorSourceStageResult.Success ?: return PhotoDraftStageResult.SourceUnavailable
        return stageLease(
            leaseId = staged.lease.id,
            displayName = displayName,
            attachmentId = attachmentId,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            quality = quality,
        )
    }

    // One transaction owns inspection, Original fallback, MDK commit, and lease cleanup.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun stageLease(
        leaseId: String,
        displayName: String,
        attachmentId: String,
        accountRef: String,
        groupIdHex: String,
        quality: MediaQuality,
    ): PhotoDraftStageResult {
        var committed = false
        try {
            val sourceBytes =
                try {
                    withContext(ioDispatcher) { sources.bytes(leaseId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return PhotoDraftStageResult.SourceUnavailable
            val inspected = renderer.inspect(sourceBytes)
            if (inspected is PhotoEditorInspectResult.Failure) {
                return PhotoDraftStageResult.NotEditable(inspected.reason)
            }
            val sourceInfo = (inspected as PhotoEditorInspectResult.Success).source
            val attachment =
                originalAttachment(sourceBytes, displayName, attachmentId, quality)
                    ?: run {
                        val rendered = renderer.render(sourceBytes, PhotoEditRecipe.Original, quality)
                        if (rendered !is PhotoEditorRenderResult.Success) {
                            return PhotoDraftStageResult.SourceUnavailable
                        }
                        MessageDraftAttachmentFfi(
                            id = attachmentId,
                            fileName = editedStageFileName(displayName, rendered.image.fileExtension),
                            mediaType = rendered.image.mediaType,
                            plaintext = rendered.image.bytes,
                            dim = "${rendered.image.width}x${rendered.image.height}",
                            thumbhash = rendered.image.thumbhash,
                            durationSeconds = null,
                            waveformSamples = emptyList(),
                        )
                    }
            val digest = attachment.editorDigest()
            val pending =
                EditorAttachmentSession(
                    accountRef = accountRef,
                    groupIdHex = groupIdHex,
                    attachmentId = attachment.id,
                    attachmentDigest = digest,
                    sourceLeaseId = leaseId,
                    qualityPreference = quality.preferenceValue,
                    recipe = PhotoEditRecipe.Original,
                    phase = EditorSessionPhase.Pending,
                    updatedAtMs = 0L,
                )
            return when (drafts.addAttachment(accountRef, groupIdHex, attachment, pending)) {
                is MessageDraftMutationResult.Success -> {
                    committed = true
                    PhotoDraftStageResult.Success(
                        DraftBackedPhoto(
                            attachment = attachment,
                            attachmentDigest = digest,
                            sourceLeaseId = leaseId,
                            sourceInfo = sourceInfo,
                            recipe = PhotoEditRecipe.Original,
                            quality = quality,
                        ),
                    )
                }
                MessageDraftMutationResult.DuplicateAttachment -> {
                    val reread =
                        drafts
                            .draft(accountRef, groupIdHex)
                            .getOrNull()
                            ?.mediaAttachments
                            ?.firstOrNull { it.id == attachmentId }
                    val recovered = reread?.let { recover(it, accountRef, groupIdHex) }
                    if (recovered != null) {
                        PhotoDraftStageResult.Success(recovered)
                    } else if (reread != null) {
                        PhotoDraftStageResult.PreparedOnly(
                            DraftPreparedPhoto(
                                attachment = reread,
                                attachmentDigest = reread.editorDigest(),
                            ),
                        )
                    } else {
                        PhotoDraftStageResult.DraftUnavailable
                    }
                }
                else -> PhotoDraftStageResult.DraftUnavailable
            }
        } finally {
            // Cancellation and every pre-commit failure must relinquish the
            // new reference. Once MDK + the session record commit, the draft
            // owns that reference until removal or successful supersession.
            if (!committed) {
                withContext(NonCancellable + ioDispatcher) { runCatching { sources.release(leaseId) } }
            }
        }
    }

    private fun originalAttachment(
        sourceBytes: ByteArray,
        displayName: String,
        attachmentId: String,
        quality: MediaQuality,
    ): MessageDraftAttachmentFfi? {
        if (!quality.preservesOriginalImageBytes) return null
        val original =
            MediaPipeline.prepareOriginalImageForUpload(sourceBytes, displayName)
                as? MediaPipeline.OriginalImageReadResult.Success
                ?: return null
        return MessageDraftAttachmentFfi(
            id = attachmentId,
            fileName = original.image.fileName,
            mediaType = original.image.mediaType,
            plaintext = original.image.bytes,
            dim = original.image.dim,
            thumbhash = original.image.thumbhash,
            durationSeconds = null,
            waveformSamples = emptyList(),
        )
    }

    suspend fun remove(
        accountRef: String,
        groupIdHex: String,
        photo: DraftBackedPhoto,
    ) {
        val result =
            drafts.removeAttachment(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                attachmentId = photo.attachment.id,
                expectedDigest = photo.attachmentDigest,
            )
        if (result is MessageDraftMutationResult.Success) {
            val lease = result.previousEditorSession?.sourceLeaseId ?: photo.sourceLeaseId
            withContext(NonCancellable + ioDispatcher) { runCatching { sources.release(lease) } }
        }
    }

    suspend fun removePrepared(
        accountRef: String,
        groupIdHex: String,
        photo: DraftPreparedPhoto,
    ) {
        val result =
            drafts.removeAttachment(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                attachmentId = photo.attachment.id,
                expectedDigest = photo.attachmentDigest,
            )
        if (result is MessageDraftMutationResult.Success) {
            result.previousEditorSession?.sourceLeaseId?.let { lease ->
                withContext(NonCancellable + ioDispatcher) { runCatching { sources.release(lease) } }
            }
        }
    }

    private suspend fun recover(
        attachment: MessageDraftAttachmentFfi,
        accountRef: String,
        groupIdHex: String,
    ): DraftBackedPhoto? {
        val digest = attachment.editorDigest()
        val session =
            runCatching { sessions.committed(accountRef, groupIdHex, attachment.id, digest) }
                .getOrNull()
                ?: return null
        val sourceBytes =
            try {
                withContext(ioDispatcher) { sources.bytes(session.sourceLeaseId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
        val inspected = renderer.inspect(sourceBytes) as? PhotoEditorInspectResult.Success ?: return null
        return DraftBackedPhoto(
            attachment = attachment,
            attachmentDigest = digest,
            sourceLeaseId = session.sourceLeaseId,
            sourceInfo = inspected.source,
            recipe = session.recipe,
            quality = MediaQuality.fromPreference(session.qualityPreference),
        )
    }

    private suspend fun findExisting(
        attachmentId: String,
        accountRef: String,
        groupIdHex: String,
    ): PhotoDraftStageResult? {
        val currentResult = drafts.draft(accountRef, groupIdHex)
        if (currentResult.isFailure) return PhotoDraftStageResult.DraftUnavailable
        val existing =
            currentResult
                .getOrNull()
                ?.mediaAttachments
                ?.firstOrNull { it.id == attachmentId }
                ?: return null
        val recovered = recover(existing, accountRef, groupIdHex)
        return recovered?.let { PhotoDraftStageResult.Success(it) }
            ?: PhotoDraftStageResult.PreparedOnly(
                DraftPreparedPhoto(
                    attachment = existing,
                    attachmentDigest = existing.editorDigest(),
                ),
            )
    }

    /**
     * Old saved-state payloads retained only URI order, so their random slot ID
     * cannot be reconstructed. Match the materialized source lease back to the
     * persisted attachment/session at the same duplicate occurrence instead of
     * appending a second draft attachment.
     */
    private suspend fun findLegacyExisting(
        sourceLeaseId: String,
        occurrenceIndex: Int,
        accountRef: String,
        groupIdHex: String,
    ): DraftBackedPhoto? {
        if (occurrenceIndex < 0) return null
        val attachments =
            drafts
                .draft(accountRef, groupIdHex)
                .getOrNull()
                ?.mediaAttachments
                .orEmpty()
        var matchingOccurrence = 0
        attachments.forEach { attachment ->
            val digest = attachment.editorDigest()
            val session = sessions.committed(accountRef, groupIdHex, attachment.id, digest)
            if (session?.sourceLeaseId == sourceLeaseId) {
                if (matchingOccurrence == occurrenceIndex) {
                    return recover(attachment, accountRef, groupIdHex)
                }
                matchingOccurrence++
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String =
        runCatching {
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.let(MediaPipeline::safeDisplayName) ?: "image"
}

internal fun stagedPhotoAttachmentId(
    accountRef: String,
    groupIdHex: String,
    attachmentSlotId: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(accountRef, groupIdHex, attachmentSlotId).forEach { value ->
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return "photo-" +
        digest
            .digest()
            .take(STAGED_ATTACHMENT_DIGEST_BYTES)
            .joinToString("") { "%02x".format(it) }
}

private const val STAGED_ATTACHMENT_DIGEST_BYTES = 16

private fun editedStageFileName(
    original: String,
    extension: String,
): String {
    val safe = MediaPipeline.safeDisplayName(original)
    val dot = safe.lastIndexOf('.')
    val stem = (if (dot > 0) safe.substring(0, dot) else safe).take(96).ifBlank { "image" }
    return "$stem.$extension"
}
