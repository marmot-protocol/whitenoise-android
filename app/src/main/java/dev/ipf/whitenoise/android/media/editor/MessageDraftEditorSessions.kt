package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftAttachmentFfi

/** The failure reported while encrypted editor sessions cannot be read. */
internal fun editorSessionStoreUnavailable() = IllegalStateException(EDITOR_SESSION_STORE_UNAVAILABLE_MESSAGE)

/** The failure reported while encrypted editor sources cannot be read. */
internal fun editorSourceStoreUnavailable() = IllegalStateException(EDITOR_SOURCE_STORE_UNAVAILABLE_MESSAGE)

private const val EDITOR_SESSION_STORE_UNAVAILABLE_MESSAGE =
    "Encrypted editor sessions are temporarily unavailable"

private const val EDITOR_SOURCE_STORE_UNAVAILABLE_MESSAGE =
    "Encrypted editor sources are temporarily unavailable"

/** A digest of this attachment's identity, metadata and bytes, so an editor opened on stale bytes fails closed. */
internal fun MessageDraftAttachmentFfi.editorDigest(): String =
    editorAttachmentDigest(
        attachmentId = id,
        fileName = fileName,
        mediaType = mediaType,
        plaintext = plaintext,
        dim = dim,
        thumbhash = thumbhash,
        durationSeconds = durationSeconds,
        waveformSamples = waveformSamples,
    )

/** Whether this pending editor session belongs to [attachment] in the given account and chat. */
internal fun EditorAttachmentSession.matches(
    accountRef: String,
    groupIdHex: String,
    attachment: MessageDraftAttachmentFfi,
): Boolean =
    phase == EditorSessionPhase.Pending &&
        this.accountRef == accountRef &&
        this.groupIdHex == groupIdHex &&
        attachmentId == attachment.id &&
        attachmentDigest == attachment.editorDigest()
