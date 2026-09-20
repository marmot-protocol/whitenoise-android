package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
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
    uri: android.net.Uri,
): String =
    "document-" +
        UUID.nameUUIDFromBytes(
            "$accountRef\u0000$groupIdHex\u0000$uri".toByteArray(StandardCharsets.UTF_8),
        )
