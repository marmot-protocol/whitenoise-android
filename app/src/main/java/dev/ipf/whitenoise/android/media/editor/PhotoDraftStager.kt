@file:Suppress("ReturnCount") // Guard returns keep staging and recovery failure states explicit.

package dev.ipf.whitenoise.android.media.editor

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.PendingAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

internal data class DraftBackedPhoto(
    val attachment: MessageDraftAttachmentFfi,
    val attachmentDigest: String,
    val sourceLeaseId: String,
    val sourceInfo: PhotoEditorSourceInfo,
    val recipe: PhotoEditRecipe,
    val quality: MediaQuality,
    val restoredFromNative: Boolean = false,
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
    // Transient shelf ownership; fresh preparation must survive cleanup of an older native draft.
    val restoredFromNative: Boolean = false,
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

/**
 * Materializes a transient URI into an encrypted source and an MDK draft attachment. [outgoingDomain] names the
 * worker domain of one pick, its draft's shared outgoing worker, and a stager given none decodes single-use.
 */
@Suppress("LongParameterList") // The stores it commits through, its renderer, its pick routing and its dispatcher.
internal class PhotoDraftStager(
    private val contentResolver: ContentResolver,
    private val sources: EditorSourceStore,
    private val sessions: EditorSessionStore,
    private val renderer: PhotoEditorRenderer,
    private val drafts: MessageDraftRepository,
    private val outgoingDomain: (pick: String) -> dev.ipf.whitenoise.android.media.MediaTrustDomain = {
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    nameLookupPermits: Semaphore = ProviderDisplayNames.sharedPermits,
) {
    private val displayNames = ProviderDisplayNames(contentResolver, ioDispatcher, nameLookupPermits)

    suspend fun stage(
        uri: Uri,
        attachmentSlotId: String,
        accountRef: String,
        groupIdHex: String,
        quality: MediaQuality,
        legacyOccurrenceIndex: Int? = null,
    ): PhotoDraftStageResult {
        val attachmentId = stagedPhotoAttachmentId(accountRef, groupIdHex, attachmentSlotId)
        // The slot is the pick. Its draft's worker is fixed before the first suspension, so work that resumes after
        // the composer changed still decodes in the pick's own draft.
        val domain = outgoingDomain(attachmentSlotId)
        findExisting(attachmentId, accountRef, groupIdHex, domain)?.let { return it }

        val owned = AtomicReference<String?>(null)
        var handedOff = false
        try {
            val staged =
                acquireSource(owned) { sources.stageUri(contentResolver, uri) }
                    ?: return PhotoDraftStageResult.SourceUnavailable
            if (legacyOccurrenceIndex != null) {
                val restored =
                    findLegacyExisting(
                        sourceLeaseId = staged.lease.id,
                        occurrenceIndex = legacyOccurrenceIndex,
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                        domain = domain,
                    )
                // The restored draft keeps its own lease; this pick's reference is released below.
                if (restored != null) return PhotoDraftStageResult.Success(restored)
            }
            val displayName = displayNames.query(uri)
            handedOff = true
            return stageLease(
                leaseId = staged.lease.id,
                displayName = displayName,
                attachmentId = attachmentId,
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                quality = quality,
                domain = domain,
            )
        } finally {
            if (!handedOff) releaseOwned(owned)
        }
    }

    /**
     * Byte entry point used by already-materialized sources and deterministic tests. Its decodes run in the outgoing
     * worker of [pick]'s draft, fixed before the first suspension, the pick being the attachment itself unless the
     * caller names its composer slot.
     */
    @Suppress("LongParameterList") // The draft identity, its source, its quality and the pick that keys its worker.
    suspend fun stageBytes(
        sourceBytes: ByteArray,
        displayName: String,
        attachmentId: String,
        accountRef: String,
        groupIdHex: String,
        quality: MediaQuality,
        pick: String = attachmentId,
    ): PhotoDraftStageResult {
        val domain = outgoingDomain(pick)
        findExisting(attachmentId, accountRef, groupIdHex, domain)?.let { return it }
        val owned = AtomicReference<String?>(null)
        var handedOff = false
        try {
            val staged =
                acquireSource(owned) { sources.stageBytes(sourceBytes) }
                    ?: return PhotoDraftStageResult.SourceUnavailable
            handedOff = true
            return stageLease(
                leaseId = staged.lease.id,
                displayName = displayName,
                attachmentId = attachmentId,
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                quality = quality,
                domain = domain,
            )
        } finally {
            if (!handedOff) releaseOwned(owned)
        }
    }

    /**
     * Stages a source on the IO dispatcher and records its lease in [owned] before the dispatcher hands back. A
     * cancellation that lands after the store took its reference still leaves the lease where the caller's cleanup
     * finds it, so an abandoned pick never keeps its source.
     */
    private suspend fun acquireSource(
        owned: AtomicReference<String?>,
        stage: () -> EditorSourceStageResult,
    ): EditorSourceStageResult.Success? =
        try {
            withContext(ioDispatcher) {
                (stage() as? EditorSourceStageResult.Success)?.also { owned.set(it.lease.id) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** Releases a lease [acquireSource] recorded but never handed to [stageLease], even while cancelled. */
    private suspend fun releaseOwned(owned: AtomicReference<String?>) {
        val leaseId = owned.getAndSet(null) ?: return
        withContext(NonCancellable + ioDispatcher) {
            runCatching { sources.release(leaseId) }
        }
    }

    /** One transaction owns inspection, Original fallback, MDK commit and lease cleanup, decoding in [domain]. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    private suspend fun stageLease(
        leaseId: String,
        displayName: String?,
        attachmentId: String,
        accountRef: String,
        groupIdHex: String,
        quality: MediaQuality,
        domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
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
            val inspected = renderer.inspect(sourceBytes, domain)
            if (inspected is PhotoEditorInspectResult.Failure) {
                return PhotoDraftStageResult.NotEditable(inspected.reason)
            }
            val sourceInfo = (inspected as PhotoEditorInspectResult.Success).source
            val attachment =
                originalAttachment(sourceBytes, displayName, attachmentId, quality, domain)
                    ?: run {
                        val rendered = renderer.render(sourceBytes, PhotoEditRecipe.Original, quality, domain)
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
                    val recovered = reread?.let { recover(it, accountRef, groupIdHex, domain) }
                    if (recovered != null) {
                        PhotoDraftStageResult.Success(recovered)
                    } else if (reread != null) {
                        PhotoDraftStageResult.PreparedOnly(
                            DraftPreparedPhoto(
                                attachment = reread,
                                attachmentDigest = reread.editorDigest(),
                                restoredFromNative = true,
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

    /** The Original-quality attachment for the user's own pick, inspected in its draft's outgoing [domain]. */
    private fun originalAttachment(
        sourceBytes: ByteArray,
        displayName: String?,
        attachmentId: String,
        quality: MediaQuality,
        domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
    ): MessageDraftAttachmentFfi? {
        if (!quality.preservesOriginalImageBytes) return null
        val original =
            MediaPipeline.prepareOriginalImageForUpload(sourceBytes, displayName, domain)
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

    /** Reopens [attachment]'s committed editor session, inspecting its source in its pick's draft's [domain]. */
    private suspend fun recover(
        attachment: MessageDraftAttachmentFfi,
        accountRef: String,
        groupIdHex: String,
        domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
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
        val inspected = renderer.inspect(sourceBytes, domain) as? PhotoEditorInspectResult.Success ?: return null
        return DraftBackedPhoto(
            attachment = attachment,
            attachmentDigest = digest,
            sourceLeaseId = session.sourceLeaseId,
            sourceInfo = inspected.source,
            recipe = session.recipe,
            quality = MediaQuality.fromPreference(session.qualityPreference),
            restoredFromNative = true,
        )
    }

    /** The draft's existing attachment [attachmentId], recovered in [domain], or null when there is none. */
    private suspend fun findExisting(
        attachmentId: String,
        accountRef: String,
        groupIdHex: String,
        domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
    ): PhotoDraftStageResult? {
        val currentResult = drafts.draft(accountRef, groupIdHex)
        if (currentResult.isFailure) return PhotoDraftStageResult.DraftUnavailable
        val existing =
            currentResult
                .getOrNull()
                ?.mediaAttachments
                ?.firstOrNull { it.id == attachmentId }
                ?: return null
        val recovered = recover(existing, accountRef, groupIdHex, domain)
        return recovered?.let { PhotoDraftStageResult.Success(it) }
            ?: PhotoDraftStageResult.PreparedOnly(
                DraftPreparedPhoto(
                    attachment = existing,
                    attachmentDigest = existing.editorDigest(),
                    restoredFromNative = true,
                ),
            )
    }

    /**
     * Old saved-state payloads retained only URI order, so their random slot ID
     * cannot be reconstructed. Match the materialized source lease back to the
     * persisted attachment/session at the same duplicate occurrence instead of
     * appending a second draft attachment. The match is inspected in [domain].
     */
    private suspend fun findLegacyExisting(
        sourceLeaseId: String,
        occurrenceIndex: Int,
        accountRef: String,
        groupIdHex: String,
        domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
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
                    return recover(attachment, accountRef, groupIdHex, domain)
                }
                matchingOccurrence++
            }
        }
        return null
    }
}

/**
 * Reads a provider's display name without blocking the pick: the query runs on the IO dispatcher under a deadline,
 * and a provider that misses it is cancelled and abandoned so its late answer is dropped. Every stager shares
 * [sharedPermits], so at most [MAX_NAME_LOOKUPS] stalled queries hold IO threads in the whole process; further picks
 * go unnamed instead of queueing. A missing name stays null so Original photos keep their format's default name.
 */
internal class ProviderDisplayNames(
    private val contentResolver: ContentResolver,
    ioDispatcher: CoroutineDispatcher,
    private val permits: Semaphore,
) {
    // Outlives one pick's cancellation: the abandoned query still owns its lookup permit until the provider returns.
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    suspend fun query(uri: Uri): String? {
        if (!permits.tryAcquire()) return null
        val signal = CancellationSignal()
        val lookup =
            scope.async {
                try {
                    runCatching {
                        contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    }.getOrNull()
                } finally {
                    permits.release()
                }
            }
        val name =
            try {
                withTimeoutOrNull(NAME_LOOKUP_DEADLINE_MS) { lookup.await() }
            } finally {
                // The lookup job is never cancelled, so its permit is always returned; only the provider is told.
                if (!lookup.isCompleted) signal.cancel()
            }
        return name?.let(MediaPipeline::safeDisplayName)
    }

    companion object {
        const val MAX_NAME_LOOKUPS = 2
        private const val NAME_LOOKUP_DEADLINE_MS = 2_000L

        /** Lookup slots for the process: abandoned queries from a conversation the user left still hold theirs. */
        val sharedPermits = Semaphore(MAX_NAME_LOOKUPS)
    }
}

private const val FALLBACK_DISPLAY_NAME = "image"

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
    original: String?,
    extension: String,
): String {
    val safe = MediaPipeline.safeDisplayName(original ?: FALLBACK_DISPLAY_NAME)
    val dot = safe.lastIndexOf('.')
    val stem = (if (dot > 0) safe.substring(0, dot) else safe).take(96).ifBlank { FALLBACK_DISPLAY_NAME }
    return "$stem.$extension"
}
