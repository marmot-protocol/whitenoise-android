package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging

internal data class DocumentReadOutcome(
    val attachments: List<PendingAttachment>,
    val failures: Set<DocumentReadFailure>,
    val albumOverflowed: Boolean,
    val totalBytes: Long,
)

/** The visual album read for one send. [videoMetadataRefused] reports a video refused for its private metadata. */
internal data class VisualReadOutcome(
    val attachments: List<PendingAttachment>,
    val albumOverflowed: Boolean,
    val storageUnavailable: Boolean = false,
    val videoMetadataRefused: Boolean = false,
)

// Decoded outcome of the document read pass, surfaced so the unified
// sendStagedAttachments path can blend its results with the image decode.
internal data class DocumentReadAccumulator(
    val attachments: MutableList<PendingAttachment> = mutableListOf(),
    val failures: MutableSet<DocumentReadFailure> = mutableSetOf(),
    var albumOverflowed: Boolean = false,
    var totalBytes: Long = 0L,
    val checkCancellation: () -> Unit = {},
    val staging: FileBackedSendStaging = FileBackedSendStaging(),
) {
    /** Freezes this pass into the result callers blend with the image decode. */
    fun outcome(): DocumentReadOutcome = DocumentReadOutcome(attachments, failures, albumOverflowed, totalBytes)
}
