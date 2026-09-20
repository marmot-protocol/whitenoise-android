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

/** Fences preparation results that complete after their document was explicitly removed. */
internal class DraftDocumentRemovalFence {
    private val removedUris = mutableSetOf<String>()

    /** A newly selected URI starts a fresh lifetime; unchanged projections keep their tombstone. */
    fun updateInputs(
        previousUris: List<String>,
        currentUris: List<String>,
    ) {
        removedUris.removeAll(currentUris.toSet() - previousUris.toSet())
    }

    /** Records intent before any prepared value lookup can return early. */
    fun recordRemoval(uri: String) {
        removedUris += uri
    }

    /** Maps removal intent to native identity even before prepared bytes have been restored. */
    fun removedAttachmentIds(
        accountRef: String,
        groupIdHex: String,
    ): Set<String> = removedUris.mapTo(mutableSetOf()) { stagedDocumentAttachmentId(accountRef, groupIdHex, it) }

    /** Allows publication only while the URI is selected in its current lifetime. */
    fun canPublish(
        uri: String,
        currentUris: List<String>,
    ): Boolean = uri in currentUris && uri !in removedUris
}
