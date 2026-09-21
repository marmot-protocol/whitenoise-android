package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.editor.stagedPhotoAttachmentId
import dev.ipf.whitenoise.android.state.PendingAttachment
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val DOCUMENT_ATTACHMENT_ID_PREFIX = "document-"

/** Converts send-ready bytes into the native draft representation that owns unsent attachment data. */
internal fun PendingAttachment.toMessageDraftAttachment(id: String): MessageDraftAttachmentFfi =
    MessageDraftAttachmentFfi(
        id = id,
        fileName = fileName,
        mediaType = mediaType,
        plaintext = plaintextBytes,
        dim = dim,
        thumbhash = thumbhash,
        durationSeconds = null,
        waveformSamples = emptyList(),
    )

/** Restores native draft bytes without reopening the original picker grant. */
internal fun MessageDraftAttachmentFfi.toPendingAttachment(): PendingAttachment =
    PendingAttachment(
        plaintextBytes = plaintext,
        mediaType = mediaType,
        fileName = fileName,
        dim = dim,
        thumbhash = thumbhash,
    )

/** Images and videos use the visual shelf; every other MIME remains a document. */
internal fun MessageDraftAttachmentFfi.isComposerVisual(): Boolean =
    mediaType.startsWith("image/", ignoreCase = true) ||
        mediaType.startsWith("video/", ignoreCase = true)

/** Stable document identity wins over MIME so document-picker images stay documents. */
internal fun MessageDraftAttachmentFfi.isComposerDocument(): Boolean = id.startsWith(DOCUMENT_ATTACHMENT_ID_PREFIX)

/** Stable identity lets URI restoration and duplicate mutation retries resolve the same native attachment. */
internal fun stagedDocumentAttachmentId(
    accountRef: String,
    groupIdHex: String,
    uri: String,
): String =
    DOCUMENT_ATTACHMENT_ID_PREFIX +
        UUID.nameUUIDFromBytes(
            "$accountRef\u0000$groupIdHex\u0000$uri".toByteArray(StandardCharsets.UTF_8),
        )

/** Native draft matches for saved picker selections plus attachments absent from saved state. */
internal data class PersistedDraftAttachmentReconciliation(
    val mediaBySlotId: Map<String, MessageDraftAttachmentFfi>,
    val documentsByUriString: Map<String, MessageDraftAttachmentFfi>,
    val unmatched: List<MessageDraftAttachmentFfi>,
)

/**
 * Reconnects saveable shelf identity to native-owned bytes after process death,
 * before any caller attempts to reopen a picker URI whose grant may be gone.
 */
internal fun reconcilePersistedDraftAttachments(
    accountRef: String,
    groupIdHex: String,
    mediaSlotIds: List<String>,
    documentUriStrings: List<String>,
    attachments: List<MessageDraftAttachmentFfi>,
    removedAttachmentIds: Set<String> = emptySet(),
): PersistedDraftAttachmentReconciliation {
    val mediaSlotByAttachmentId =
        mediaSlotIds.associateBy { slotId -> stagedPhotoAttachmentId(accountRef, groupIdHex, slotId) }
    val documentUriByAttachmentId =
        documentUriStrings.associateBy { uri -> stagedDocumentAttachmentId(accountRef, groupIdHex, uri) }
    val mediaBySlotId = linkedMapOf<String, MessageDraftAttachmentFfi>()
    val documentsByUriString = linkedMapOf<String, MessageDraftAttachmentFfi>()
    val unmatched = mutableListOf<MessageDraftAttachmentFfi>()

    attachments.forEach { attachment ->
        if (attachment.id in removedAttachmentIds) return@forEach
        val isDocument = attachment.isComposerDocument()
        val mediaSlotId =
            if (!isDocument && attachment.isComposerVisual()) {
                mediaSlotIds.firstOrNull { it == attachment.id } ?: mediaSlotByAttachmentId[attachment.id]
            } else {
                null
            }
        val documentUri =
            if (isDocument || !attachment.isComposerVisual()) documentUriByAttachmentId[attachment.id] else null
        when {
            mediaSlotId != null -> mediaBySlotId[mediaSlotId] = attachment
            documentUri != null -> documentsByUriString[documentUri] = attachment
            else -> unmatched += attachment
        }
    }
    return PersistedDraftAttachmentReconciliation(mediaBySlotId, documentsByUriString, unmatched)
}

/** Identifies one removal from a specific selection lifetime of a document URI. */
internal data class DraftDocumentRemoval(
    val uri: String,
    val generation: Long,
    val sequence: Long,
)

/** Fences preparation results and reselections until older native cleanup completes. */
internal class DraftDocumentRemovalFence {
    private val generationByUri = mutableMapOf<String, Long>()
    private val removedGenerationsByUri = mutableMapOf<String, MutableSet<Long>>()
    private val pendingCleanups = mutableSetOf<DraftDocumentRemoval>()
    private var nextRemovalSequence = 0L

    /** A newly selected URI starts a fresh lifetime without releasing older cleanup fences. */
    @Synchronized
    fun updateInputs(
        previousUris: List<String>,
        currentUris: List<String>,
    ) {
        (currentUris.toSet() - previousUris.toSet()).forEach { uri ->
            generationByUri[uri] = generationByUri.getOrDefault(uri, 0L) + 1L
        }
    }

    /** Records intent before lookup and returns the exact cleanup lifetime to acknowledge. */
    @Synchronized
    fun recordRemoval(uri: String): DraftDocumentRemoval {
        val generation = generationByUri.getOrPut(uri) { 1L }
        val removal = DraftDocumentRemoval(uri, generation, ++nextRemovalSequence)
        removedGenerationsByUri.getOrPut(uri, ::mutableSetOf) += generation
        pendingCleanups += removal
        return removal
    }

    /** Releases only the completed cleanup while retaining the removed lifetime's tombstone. */
    @Synchronized
    fun completeRemoval(removal: DraftDocumentRemoval) {
        pendingCleanups -= removal
    }

    /** Maps every blocked lifetime to native identity before stale restoration can publish it. */
    @Synchronized
    fun removedAttachmentIds(
        accountRef: String,
        groupIdHex: String,
    ): Set<String> =
        generationByUri
            .filter { (uri, generation) ->
                generation in removedGenerationsByUri[uri].orEmpty() || pendingCleanups.any { it.uri == uri }
            }.keys
            .mapTo(mutableSetOf()) { stagedDocumentAttachmentId(accountRef, groupIdHex, it) }

    /** Allows the selected lifetime only after every older cleanup for its URI has completed. */
    @Synchronized
    fun canPublish(
        uri: String,
        currentUris: List<String>,
    ): Boolean {
        val generation = generationByUri[uri] ?: return false
        return uri in currentUris &&
            generation !in removedGenerationsByUri[uri].orEmpty() &&
            pendingCleanups.none { it.uri == uri }
    }
}
