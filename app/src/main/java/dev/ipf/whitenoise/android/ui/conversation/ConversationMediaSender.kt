package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.share.PrivateShareSendLease
import dev.ipf.whitenoise.android.state.ATTACHMENT_CHUNK_MAX_ITEMS
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfMetadataChoices
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickBudget
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickFailure
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedVideoCheck
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.dropUnconfirmedPdfMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.isVideoMediaType
import dev.ipf.whitenoise.android.ui.conversation.media.nativeFileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.releaseUnadoptedStagedSources
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedDocumentMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedVideoMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.VCARD_MIME_TYPE
import dev.ipf.whitenoise.android.ui.conversation.share.attachedVCardContact
import dev.ipf.whitenoise.android.ui.conversation.share.buildVCard
import dev.ipf.whitenoise.android.ui.conversation.share.contactVCardFileName
import dev.ipf.whitenoise.android.ui.conversation.share.formatContactShareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Keep picker limits aligned with the retained-upload LRU. A larger value
// could evict an attachment during its own insert and make retries impossible.
internal const val MEDIA_ALBUM_MAX_TOTAL_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES

/**
 * Media read/transform/send operations owned by a conversation.
 *
 * Keeping this holder outside [ConversationScreen] removes blocking I/O and
 * attachment policy from the screen's already busy composition scope while
 * preserving the controller and app-state lifetime of each operation.
 * `fileBackedLimits` and `usableSpace` pass straight to its [ConversationAttachmentReader].
 */
internal class ConversationMediaSender(
    internal val appState: WhiteNoiseAppState,
    internal val controller: ConversationController,
    internal val context: Context,
    fileBackedLimits: () -> FileBackedSendLimits? = ::nativeFileBackedSendLimits,
    usableSpace: (java.io.File) -> Long = ::usableSpaceFor,
    internal val onRevealSent: () -> Unit,
) {
    private val attachmentReader =
        ConversationAttachmentReader(
            appState,
            context,
            fileBackedLimits,
            usableSpace,
            controller::outgoingMediaTrustDomain,
        )

    /** The composer's PDFs that keep their metadata and which of them the person chose to send as they are. */
    internal val pdfChoices = ComposerPdfMetadataChoices()

    init {
        bindCollectionSender(this)
    }

    /** Captures the reply before contact serialization can suspend. */
    fun sendSharedContact(contact: SharedContact) {
        val replyTarget = controller.replyingTo?.messageIdHex
        val replyVersion = controller.replySelectionVersion
        val outboundVisibleStartedAtElapsedMs = SystemClock.elapsedRealtime()
        appState.launchMutation {
            val vcardBytes =
                withContext(Dispatchers.IO) {
                    buildVCard(contact).toByteArray(Charsets.UTF_8)
                }
            // The vCard rides the existing media pipeline as a text/vcard
            // attachment (portable — any client can save it), and the caption
            // carries the human-readable name/phone so a peer with no contact
            // renderer still reads it, and our own bubble draws a card from it.
            val attachment =
                PendingAttachment(
                    plaintextBytes = vcardBytes,
                    mediaType = VCARD_MIME_TYPE,
                    fileName = contactVCardFileName(contact),
                )
            val caption = formatContactShareText(contact).ifBlank { null }
            val seeded =
                controller.queueAttachments(
                    attachments = listOf(attachment),
                    caption = caption,
                    canQueue = { controller.replySelectionVersion == replyVersion },
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                    replyTarget = replyTarget,
                    replyVersion = replyVersion,
                ) ?: return@launchMutation
            onRevealSent()
            controller.uploadQueued(seeded)
        }
    }

    /** Keeps the reviewed file until the optimistic row is seeded; that row's retained bytes then own every retry. */
    fun sendVoiceAttachment(
        file: java.io.File,
        durationMs: Long,
        canSend: () -> Boolean,
        onQueued: (Boolean) -> Unit,
    ) {
        val replyTarget = controller.replyingTo?.messageIdHex
        val replyVersion = controller.replySelectionVersion
        val outboundVisibleStartedAtElapsedMs = SystemClock.elapsedRealtime()
        appState.launchMutation {
            var accepted = false
            try {
                if (!canSend()) return@launchMutation
                val bytes =
                    withContext(Dispatchers.IO) {
                        runCatching { file.readBytes() }.getOrNull()
                    }
                if (!canSend() || bytes == null || bytes.isEmpty()) return@launchMutation
                val attachment =
                    PendingAttachment(
                        plaintextBytes = bytes,
                        mediaType = dev.ipf.whitenoise.android.audio.VoiceRecorder.MIME_TYPE,
                        fileName =
                            "voice-${durationMs}ms.${dev.ipf.whitenoise.android.audio.VoiceRecorder.FILE_EXTENSION}",
                    )
                val seeded =
                    controller.queueAttachments(
                        listOf(attachment),
                        null,
                        canQueue = {
                            canSend() && controller.canSendMessages && controller.replySelectionVersion == replyVersion
                        },
                        outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                        replyTarget = replyTarget,
                        replyVersion = replyVersion,
                    ) ?: return@launchMutation
                // The seeded row owns the take from here, reply or not: its retained bytes back Retry and Discard.
                accepted = true
                onQueued(true)
                onRevealSent()
                controller.uploadQueued(seeded)
            } finally {
                if (!accepted) onQueued(false)
            }
        }
    }

    internal data class PreparedStagedAttachments(
        val images: List<PendingAttachment>,
        val documents: DocumentReadOutcome,
        val imageOverflowed: Boolean,
        val visualFailureToast: Int,
        val imageStorageUnavailable: Boolean = false,
        val videoMetadataRefused: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = images.isEmpty() && documents.attachments.isEmpty()
    }

    /**
     * Sends the staged images and documents with the caption. One pick, or any reply of at most
     * [ATTACHMENT_CHUNK_MAX_ITEMS], goes as one message; several picks become a collection operation whose
     * bounded chunks carry the caption on chunk 1 only. The picks keep their draft's outgoing worker for the whole
     * send, its reads, pending previews and just-sent thumbnails, while a pick made after this call starts a new
     * draft. The draft's worker retires once the send has ended and no sent pick remains staged.
     */
    @Suppress("LongMethod") // The collection hand-off precedes the unchanged single-message path.
    fun sendStagedAttachments(
        imageSlots: List<PendingMediaSlot>,
        documentUris: List<android.net.Uri>,
        caption: String,
        preparedImageAttachments: Map<String, PendingAttachment> = emptyMap(),
        preparedDocumentAttachments: Map<android.net.Uri, PendingAttachment> = emptyMap(),
        onAccepted: () -> Unit = {},
        onRejected: () -> Unit = {},
        onSettled: () -> Unit = {},
        onAfterSend: () -> Unit = {},
    ) {
        val completion = StagedMediaSendCompletion(onAccepted, onRejected, onSettled)
        val replyTarget = controller.replyingTo?.messageIdHex
        val replyVersion = controller.replySelectionVersion
        // A reply is one message read wholly into memory, so it keeps the one-message limit; the shelf stays.
        val replyTooLarge = replyTarget != null && imageSlots.size + documentUris.size > ATTACHMENT_CHUNK_MAX_ITEMS
        if (replyTooLarge || (imageSlots.isEmpty() && documentUris.isEmpty())) {
            if (replyTooLarge) appState.present(R.string.media_album_too_large)
            completion.reject()
            return
        }
        val pendingDraftClear =
            appState.captureDraftForSend(controller.boundAccountRef, controller.group.groupIdHex)
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        val sendDrafts = controller.beginOutgoingSend(imageSlots.map { it.id } + documentUris.map { it.toString() })
        if (replyTarget == null && imageSlots.size + documentUris.size > 1) {
            // Several picks become one ordered collection operation, sent as bounded chunks (#2576).
            val request =
                CollectionSendRequest(
                    imageSlots,
                    documentUris,
                    trimmedCaption,
                    preparedImageAttachments,
                    preparedDocumentAttachments,
                    pendingDraftClear,
                )
            return startCollectionSend(request, completion, onAfterSend, sendDrafts)
        }
        val sourceAccount = appState.accounts.firstOrNull { it.label == controller.boundAccountRef }?.accountIdHex
        val outboundVisibleStartedAtElapsedMs = SystemClock.elapsedRealtime()
        appState.launchMutation {
            var accepted = false
            var sourceLease: PrivateShareSendLease? = null
            val stagedSources = java.util.concurrent.ConcurrentLinkedQueue<StagedUploadSource>()
            var adoptedSources = emptySet<StagedUploadSource>()
            try {
                sourceLease =
                    acquireStagedSources(context, imageSlots.map { it.uri } + documentUris, sourceAccount).getOrElse {
                        appState.present(R.string.share_import_storage)
                        return@launchMutation
                    }
                val prepared =
                    prepareStagedAttachments(
                        imageSlots,
                        documentUris,
                        preparedImageAttachments,
                        preparedDocumentAttachments,
                        allowFileBacked = replyTarget == null,
                        staging = FileBackedSendStaging { stagedSources += it },
                    )
                val ready =
                    preparedForReplySend(prepared, imageSlots.size, documentUris.size, replyTarget != null)
                        ?: return@launchMutation
                val seeded =
                    seedPreparedAttachments(
                        ready,
                        trimmedCaption,
                        outboundVisibleStartedAtElapsedMs,
                        replyTarget,
                        replyVersion,
                    )
                adoptedSources = adoptedStagedSources(controller, seeded)
                if (seeded.isEmpty()) return@launchMutation
                val sourceReleases = retainStagedSources(appState, controller, sourceLease, seeded)
                accepted = true
                if (replyTarget == null) completion.accept()
                onAfterSend()
                settleStagedUpload(seeded, sourceReleases, pendingDraftClear, replyTarget != null, completion)
            } finally {
                controller.endOutgoingSend(sendDrafts)
                releaseUnadoptedStagedSources(stagedSources.toList(), adoptedSources)
                if (!accepted) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { sourceLease?.release() }
                    completion.reject()
                }
            }
        }
    }

    /** Replies cannot silently drop failed picks; all prepared items share one typed native draft. */
    private suspend fun preparedForReplySend(
        prepared: PreparedStagedAttachments,
        imageCount: Int,
        documentCount: Int,
        isReply: Boolean,
    ): PreparedStagedAttachments? {
        val incomplete = prepared.images.size != imageCount || prepared.documents.attachments.size != documentCount
        if (isReply && incomplete) {
            // A document that could not be read says why (for example too large) rather than "draft changed",
            // and so does a video refused because its private metadata could not be removed.
            val documentFailure = prepared.documents.failures.firstOrNull()
            val videoRefusal = prepared.visualFailureToast.takeIf { prepared.videoMetadataRefused }
            appState.present(documentFailure?.messageResource ?: videoRefusal ?: R.string.media_reply_draft_conflict)
            return null
        }
        return if (acceptPreparedAttachments(prepared, imageCount)) {
            val documents =
                prepared.documents.copy(
                    attachments =
                        addMissingThumbhashes(prepared.documents.attachments),
                )
            prepared.copy(documents = documents)
        } else {
            null
        }
    }

    /** A rejected attempt releases its UI claim while retry retains separate exact-shelf settlement. */
    private suspend fun settleStagedUpload(
        seeded: List<ConversationController.QueuedAttachmentSend>,
        releases: List<() -> Unit>?,
        pendingClear: dev.ipf.whitenoise.android.state.DraftSendClearToken?,
        isReply: Boolean,
        completion: StagedMediaSendCompletion,
    ) {
        var replyAccepted = false
        uploadStagedAttachments(seeded, releases) {
            if (isReply) {
                replyAccepted = true
                completion.accept()
            }
            pendingClear?.let(appState::clearDraftAfterSuccessfulSend)
        }
        if (isReply && !replyAccepted) completion.reject()
    }

    /** Uploads seeded sends in order; durable acceptance releases each source owner and clears the draft only once. */
    private suspend fun uploadStagedAttachments(
        seeded: List<ConversationController.QueuedAttachmentSend>,
        releases: List<() -> Unit>?,
        clearDraft: (() -> Unit)?,
    ) {
        seeded.forEachIndexed { index, queued ->
            controller.uploadQueued(
                seeded = queued,
                onDurablyAccepted = {
                    if (index == 0) clearDraft?.invoke()
                    releases?.get(index)?.invoke()
                },
            )
        }
    }

    /**
     * Reads the shelf's visual album and documents for one send. [allowFileBacked] lets picks that do
     * not fit the in-memory budget be staged to private files; the album shares one file-backed budget.
     * Replies pass false: native reply drafts carry attachment bytes, so every reply pick stays in memory.
     * [staging] hears about every snapshot as soon as it exists, so a preparation that fails part-way can
     * still be cleaned up by its caller, and reserves disk for the whole send across the album and documents.
     * Prepared draft attachments, including native drafts an older app version saved, are re-checked for private
     * video metadata before anything reuses them, visual drafts by reported type and Files drafts by content. A
     * video that cannot be cleaned is refused, never sent as saved. [pickKey] turns a slot id or document URI into
     * the pick its draft is looked up by, so a resumed collection's items never take a composer pick's draft.
     */
    @Suppress("LongParameterList") // The shelf, its prepared bytes, its file budget, staging and its draft keys.
    internal suspend fun prepareStagedAttachments(
        imageSlots: List<PendingMediaSlot>,
        documentUris: List<android.net.Uri>,
        preparedImageAttachments: Map<String, PendingAttachment>,
        preparedDocumentAttachments: Map<android.net.Uri, PendingAttachment>,
        allowFileBacked: Boolean,
        staging: FileBackedSendStaging,
        pickKey: (String) -> String = { it },
    ): PreparedStagedAttachments {
        // Reading the native limit is a binding call, so it stays off Main.
        val albumFileBudget =
            if (allowFileBacked) {
                withContext(Dispatchers.IO) { attachmentReader.fileBackedBudget(staging) }
            } else {
                null
            }
        val checkedImages = withContext(Dispatchers.IO) { removePreparedVideoMetadata(preparedImageAttachments) }
        val rawImages = readStagedImages(imageSlots, checkedImages, albumFileBudget, pickKey)
        val images = limitAttachmentsToBudget(rawImages.attachments, MEDIA_ALBUM_MAX_TOTAL_BYTES)
        val documentBudget = (MEDIA_ALBUM_MAX_TOTAL_BYTES - images.totalBytes).coerceAtLeast(0L)
        val documents =
            if (documentUris.isEmpty()) {
                DocumentReadOutcome(emptyList(), emptySet(), albumOverflowed = false, totalBytes = 0L)
            } else {
                readStagedDocuments(
                    documentUris,
                    withContext(Dispatchers.IO) { removePreparedDocumentMetadata(preparedDocumentAttachments) },
                    documentBudget,
                    allowFileBacked,
                    staging,
                    pickKey,
                )
            }
        // Only a missing visual pick reads the notice. getType is provider IPC that may stall
        // arbitrarily, and the send calls this from Main.
        val pickHasVideo =
            rawImages.attachments.size < imageSlots.size &&
                withContext(Dispatchers.IO) {
                    imageSlots.any {
                        ensureActive()
                        isVideoMediaType(safeGetType(context.contentResolver, it.uri))
                    }
                }
        return PreparedStagedAttachments(
            images = images.attachments,
            documents = documents,
            imageOverflowed = rawImages.albumOverflowed || images.overflowed,
            visualFailureToast =
                when {
                    rawImages.videoMetadataRefused -> R.string.toast_video_metadata_not_removable
                    pickHasVideo -> R.string.toast_couldnt_process_video
                    else -> R.string.toast_couldnt_decode_image
                },
            imageStorageUnavailable = rawImages.storageUnavailable,
            videoMetadataRefused = rawImages.videoMetadataRefused,
        )
    }

    /**
     * Reuses native-draft bytes and reads only picks that have not finished staging. With
     * [allowFileBacked], a draft document that no longer fits the in-memory budget is copied to a
     * private file, and an unstaged pick may be read straight into one. [staging] hears about each copy.
     * A prepared video refused for its private metadata is reported, never re-read or sent as saved. Each document
     * decodes in the draft of its pick, [pickKey] of its URI.
     */
    @Suppress("LongParameterList") // The documents, their prepared bytes, the budgets, staging and draft keys.
    private suspend fun readStagedDocuments(
        documentUris: List<android.net.Uri>,
        preparedDocuments: PreparedVideoCheck<android.net.Uri>,
        bytesBudget: Long,
        allowFileBacked: Boolean,
        staging: FileBackedSendStaging,
        pickKey: (String) -> String,
    ): DocumentReadOutcome {
        val attachments = mutableListOf<PendingAttachment>()
        var totalBytes = 0L
        val failures = mutableSetOf<DocumentReadFailure>()
        var overflowed = false
        documentUris.forEach { uri ->
            // A sent document keeps its draft's worker for its thumbhash and its pending preview.
            val domain = controller.outgoingMediaTrustDomain(pickKey(uri.toString()))
            val remaining = (bytesBudget - totalBytes).coerceAtLeast(0L)
            if (remaining == 0L && !allowFileBacked) {
                overflowed = true
                return@forEach
            }
            val staged = preparedDocuments.ready[uri]
            val firstOfUri = attachments.size
            if (uri in preparedDocuments.refused) {
                failures += DocumentReadFailure.VIDEO_METADATA
            } else if (staged != null && staged.plaintextBytes.size.toLong() <= remaining) {
                attachments += staged.copy(outgoingDomain = domain)
                totalBytes += staged.plaintextBytes.size
            } else if (staged != null && !allowFileBacked) {
                overflowed = true
            } else if (staged != null) {
                val copied =
                    withContext(Dispatchers.IO) {
                        val budget = attachmentReader.fileBackedBudget(staging)
                        stageDraftDocumentToFile(staged, budget)
                    }
                attachments += copied.attachments.map { it.copy(outgoingDomain = domain) }
                failures += copied.failures
            } else {
                val read =
                    attachmentReader.readPickedDocuments(listOf(uri), remaining, allowFileBacked, staging, pickKey)
                attachments += read.attachments.map { it.copy(outgoingDomain = domain) }
                totalBytes += read.totalBytes
                failures += read.failures
                overflowed = overflowed || read.albumOverflowed
            }
            val keepsOriginal = pdfChoices.keepsOriginal(uri)
            withContext(Dispatchers.IO) { attachments.dropUnconfirmedPdfMetadata(firstOfUri, keepsOriginal, failures) }
        }
        return DocumentReadOutcome(attachments, failures, overflowed, totalBytes)
    }

    /**
     * Reads the visual album in slot order. Without [fileBacked] each slot is read against the whole
     * in-memory budget and trimmed afterwards, as before; with it, each read sees what is left so a
     * video that no longer fits in memory is staged to a file instead of dropped. A prepared video refused
     * for its private metadata is reported, never re-read or sent as saved. Each slot decodes in the draft of
     * its pick, [pickKey] of its id.
     */
    private suspend fun readStagedImages(
        imageSlots: List<PendingMediaSlot>,
        preparedImages: PreparedVideoCheck<String>,
        fileBacked: FileBackedPickBudget?,
        pickKey: (String) -> String,
    ): VisualReadOutcome {
        val attachments = mutableListOf<PendingAttachment>()
        var overflowed = false
        var storageUnavailable = false
        var videoMetadataRefused = false
        var inMemoryBytes = 0L
        imageSlots.forEach { slot ->
            // A sent pick keeps its draft's worker for its pending preview and its just-sent thumbnail.
            val pick = pickKey(slot.id)
            val domain = controller.outgoingMediaTrustDomain(pick)
            val prepared = preparedImages.ready[slot.id]
            // A draft item after large videos must fit the same native bound and the send's disk, or the album fails.
            // Draft visuals are always held in memory, only a pick staged through the budget lives in a file.
            // The disk check asks the filesystem for free space, so it runs on the IO pool rather than Main.
            val refusal =
                if (prepared != null && fileBacked != null) {
                    withContext(Dispatchers.IO) { fileBacked.admit(prepared.byteCount, prepared.sourceFile == null) }
                } else {
                    null
                }
            if (slot.id in preparedImages.refused) {
                videoMetadataRefused = true
            } else if (refusal != null) {
                overflowed = overflowed || refusal == FileBackedPickFailure.TOO_LARGE
                storageUnavailable = storageUnavailable || refusal == FileBackedPickFailure.STORAGE
            } else if (prepared != null) {
                attachments += prepared.copy(outgoingDomain = domain)
                inMemoryBytes += prepared.plaintextBytes.size
            } else {
                val read =
                    if (fileBacked == null) {
                        attachmentReader.readPickedImages(listOf(slot.uri), pick = pick)
                    } else {
                        val remaining = (MEDIA_ALBUM_MAX_TOTAL_BYTES - inMemoryBytes).coerceAtLeast(0L)
                        attachmentReader.readPickedImages(listOf(slot.uri), fileBacked, remaining, pick = pick)
                    }
                attachments += read.attachments.map { it.copy(outgoingDomain = domain) }
                inMemoryBytes += read.attachments.sumOf { it.plaintextBytes.size.toLong() }
                overflowed = overflowed || read.albumOverflowed
                storageUnavailable = storageUnavailable || read.storageUnavailable
                videoMetadataRefused = videoMetadataRefused || read.videoMetadataRefused
            }
        }
        return VisualReadOutcome(attachments, overflowed, storageUnavailable, videoMetadataRefused)
    }

    /** Presents one notice for what preparation dropped or refused, and reports whether anything is left to send. */
    private fun acceptPreparedAttachments(
        prepared: PreparedStagedAttachments,
        imagePickCount: Int,
    ): Boolean {
        if (prepared.isEmpty && imagePickCount > 0) {
            val toast =
                when {
                    prepared.imageOverflowed -> R.string.media_album_too_large
                    prepared.imageStorageUnavailable -> R.string.share_import_storage
                    else -> prepared.visualFailureToast
                }
            appState.present(toast, copyable = toast == prepared.visualFailureToast)
            return false
        }
        if (prepared.images.size < imagePickCount && !prepared.imageOverflowed && !prepared.imageStorageUnavailable) {
            appState.present(prepared.visualFailureToast, copyable = true)
        }
        if (prepared.imageOverflowed || prepared.documents.albumOverflowed) {
            appState.present(R.string.media_album_too_large)
        } else if (prepared.imageStorageUnavailable) {
            appState.present(R.string.share_import_storage)
        } else {
            val failure = prepared.documents.failures.firstOrNull()
            if (failure != null) appState.present(failure.messageResource)
        }
        return !prepared.isEmpty
    }

    /** One reply owns one native draft/album; ordinary sends retain the existing document grouping. */
    internal suspend fun seedPreparedAttachments(
        prepared: PreparedStagedAttachments,
        caption: String?,
        outboundVisibleStartedAtElapsedMs: Long,
        replyTarget: String?,
        replyVersion: Long,
    ): List<ConversationController.QueuedAttachmentSend> {
        if (replyTarget != null) {
            return listOfNotNull(
                controller.queueAttachments(
                    attachments = prepared.images + prepared.documents.attachments,
                    caption = caption,
                    canQueue = { controller.replySelectionVersion == replyVersion },
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                    replyTarget = replyTarget,
                    replyVersion = replyVersion,
                ),
            )
        }
        val seeded = mutableListOf<ConversationController.QueuedAttachmentSend>()
        if (prepared.images.isNotEmpty()) {
            controller
                .queueAttachments(
                    attachments = prepared.images,
                    caption = caption,
                    replyTarget = null,
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                )?.let(seeded::add)
        }
        val captionConsumedByImages = prepared.images.isNotEmpty()
        prepared.documents.attachments.forEachIndexed { index, attachment ->
            val userCaption = if (!captionConsumedByImages && index == 0) caption else null
            // A raw .vcf goes out like the contact picker's share: text/vcard
            // plus a name/phone caption, so every bubble draws the same card.
            // A caption the user typed wins over the generated one.
            val contact = withContext(Dispatchers.Default) { attachedVCardContact(attachment) }
            val itemAttachment = if (contact == null) attachment else attachment.copy(mediaType = VCARD_MIME_TYPE)
            val itemCaption = userCaption ?: contact?.let(::formatContactShareText)
            controller
                .queueAttachments(
                    attachments = listOf(itemAttachment),
                    caption = itemCaption,
                    replyTarget = null,
                    outboundVisibleStartedAtElapsedMs = outboundVisibleStartedAtElapsedMs,
                )?.let(seeded::add)
        }
        return seeded
    }
}

/** Snapshots now owned by queued sends; each is released when its retained upload ends. */
internal fun adoptedStagedSources(
    controller: ConversationController,
    seeded: List<ConversationController.QueuedAttachmentSend>,
): Set<StagedUploadSource> =
    seeded.flatMapTo(mutableSetOf()) { queued ->
        controller.pendingAttachmentsList(queued.tempId).mapNotNull(PendingAttachment::sourceFile)
    }

/**
 * Copies a native-draft document that no longer fits the in-memory budget into a private snapshot,
 * so the send keeps it without growing the retained heap or reopening a picker grant. Call off Main.
 */
private fun stageDraftDocumentToFile(
    attachment: PendingAttachment,
    budget: FileBackedPickBudget?,
): DocumentReadOutcome {
    val pick = budget?.stage(attachment.byteCount) { attachment.plaintextBytes.inputStream() }
    val source = pick?.source
    val staged = source?.let { attachment.copy(plaintextBytes = ByteArray(0), sourceFile = it) }
    return DocumentReadOutcome(
        attachments = listOfNotNull(staged),
        failures = if (staged == null) setOf(pick?.failure.toDocumentReadFailure()) else emptySet(),
        albumOverflowed = false,
        totalBytes = 0L,
    )
}

/** Attachment budget result independent of a conversation or its lifecycle. */
private data class BudgetedAttachments(
    val attachments: List<PendingAttachment>,
    val totalBytes: Long,
    val overflowed: Boolean,
)

/** Preserves pick order while rejecting attachments that exceed the remaining native album budget. */
private fun limitAttachmentsToBudget(
    attachments: List<PendingAttachment>,
    bytesBudget: Long,
): BudgetedAttachments {
    val accepted = mutableListOf<PendingAttachment>()
    var totalBytes = 0L
    var overflowed = false
    attachments.forEach { attachment ->
        val nextTotal = totalBytes + attachment.plaintextBytes.size
        if (nextTotal > bytesBudget) {
            overflowed = true
        } else {
            totalBytes = nextTotal
            accepted += attachment
        }
    }
    return BudgetedAttachments(accepted, totalBytes, overflowed)
}

/**
 * Completes missing image metadata off the UI thread without replacing existing thumbhashes. Each attachment decodes
 * in the outgoing domain of the draft it is sent from.
 */
internal suspend fun addMissingThumbhashes(attachments: List<PendingAttachment>): List<PendingAttachment> =
    if (attachments.isEmpty()) {
        emptyList()
    } else {
        withContext(Dispatchers.Default) {
            attachments.map { attachment ->
                val needsThumbhash =
                    attachment.sourceFile == null &&
                        attachment.mediaType.startsWith("image/", ignoreCase = true) &&
                        attachment.thumbhash == null
                if (!needsThumbhash) {
                    attachment
                } else {
                    val bitmap =
                        MediaPipeline.decodeSampledBitmap(
                            attachment.plaintextBytes,
                            MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                            // Each document's thumbhash decodes in the worker of the draft it is sent from.
                            domain = attachment.outgoingDomain,
                        )
                    val hash = bitmap?.let { Thumbhash.encodeFromBitmap(it) }
                    bitmap?.recycle()
                    attachment.copy(thumbhash = hash)
                }
            }
        }
    }

@Composable
internal fun rememberConversationMediaSender(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    context: Context,
    onRevealSent: () -> Unit,
): ConversationMediaSender {
    val currentOnRevealSent = rememberUpdatedState(onRevealSent)
    return remember(appState, controller, context) {
        ConversationMediaSender(
            appState = appState,
            controller = controller,
            context = context,
            onRevealSent = { currentOnRevealSent.value() },
        )
    }
}
