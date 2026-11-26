package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.rememberingNativeDraftBody
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickBudget

/**
 * Copies a native-draft document that no longer fits the in-memory budget into a private snapshot,
 * so the send keeps it without growing the retained heap or reopening a picker grant. Call off Main.
 */
internal fun stageDraftDocumentToFile(
    attachment: PendingAttachment,
    budget: FileBackedPickBudget?,
): DocumentReadOutcome {
    val pick = budget?.stage(attachment.byteCount) { attachment.plaintextBytes.inputStream() }
    val source = pick?.source
    // The staged copy replaces the bytes the draft's original is recognized by, so they are remembered first.
    val staged =
        source?.let { attachment.rememberingNativeDraftBody().copy(plaintextBytes = ByteArray(0), sourceFile = it) }
    return DocumentReadOutcome(
        attachments = listOfNotNull(staged),
        failures = if (staged == null) setOf(pick?.failure.toDocumentReadFailure()) else emptySet(),
        albumOverflowed = false,
        totalBytes = 0L,
    )
}

/** Attachment budget result independent of a conversation or its lifecycle. */
internal data class BudgetedAttachments(
    val attachments: List<PendingAttachment>,
    val totalBytes: Long,
    val overflowed: Boolean,
)

/** Preserves pick order while rejecting attachments that exceed the remaining native album budget. */
internal fun limitAttachmentsToBudget(
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
