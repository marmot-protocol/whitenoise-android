package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.editor.stagedPhotoAttachmentId
import dev.ipf.whitenoise.android.state.PendingAttachment
import java.nio.charset.StandardCharsets
import java.util.UUID

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

/** Stable identity lets URI restoration and duplicate mutation retries resolve the same native attachment. */
internal fun stagedDocumentAttachmentId(
    accountRef: String,
    groupIdHex: String,
    uri: String,
): String =
    "document-" +
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
): PersistedDraftAttachmentReconciliation {
    val mediaSlotByAttachmentId =
        mediaSlotIds.associateBy { slotId -> stagedPhotoAttachmentId(accountRef, groupIdHex, slotId) }
    val documentUriByAttachmentId =
        documentUriStrings.associateBy { uri -> stagedDocumentAttachmentId(accountRef, groupIdHex, uri) }
    val mediaBySlotId = linkedMapOf<String, MessageDraftAttachmentFfi>()
    val documentsByUriString = linkedMapOf<String, MessageDraftAttachmentFfi>()
    val unmatched = mutableListOf<MessageDraftAttachmentFfi>()

    attachments.forEach { attachment ->
        val mediaSlotId =
            if (attachment.isComposerVisual()) {
                mediaSlotIds.firstOrNull { it == attachment.id } ?: mediaSlotByAttachmentId[attachment.id]
            } else {
                null
            }
        val documentUri =
            if (attachment.isComposerVisual()) null else documentUriByAttachmentId[attachment.id]
        when {
            mediaSlotId != null -> mediaBySlotId[mediaSlotId] = attachment
            documentUri != null -> documentsByUriString[documentUri] = attachment
            else -> unmatched += attachment
        }
    }
    return PersistedDraftAttachmentReconciliation(mediaBySlotId, documentsByUriString, unmatched)
}
