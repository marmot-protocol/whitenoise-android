package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentHistoryCursor
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi

/** Newest-first attachment slots read per page while finding one received message's attachments. */
internal const val RECEIPT_ATTACHMENT_PAGE_LIMIT = 50u

/**
 * Pages read at most for one receipt. A burst of newer media can push a message's slots past this bound;
 * its bubbles then fetch it when shown, as before, but a receipt never becomes a history scan.
 */
internal const val RECEIPT_ATTACHMENT_PAGE_BUDGET = 3

/** The automatic-download row MDK's own category puts an attachment in; a rejected slot has none. */
internal fun AttachmentCategoryFfi.autoDownloadType(): MediaAutoDownloadType? =
    when (this) {
        AttachmentCategoryFfi.IMAGE -> MediaAutoDownloadType.Image
        AttachmentCategoryFfi.VIDEO -> MediaAutoDownloadType.Video
        AttachmentCategoryFfi.AUDIO -> MediaAutoDownloadType.Audio
        AttachmentCategoryFfi.FILE -> MediaAutoDownloadType.Document
        AttachmentCategoryFfi.REJECTED -> null
    }

/** One attachment of a received message, with the policy row that decides whether it downloads automatically. */
internal data class IncomingAttachment(
    val request: AttachmentTransferRequest,
    val type: MediaAutoDownloadType,
)

/**
 * Picks [messageIdHex]'s accepted, encrypted slots out of history [entries], each under its own type's
 * policy row. No category is excluded here: images, animated images, video, voice and audio, and files
 * all reach the receipt scheduler, which applies the receiving account's policy per type.
 */
internal fun incomingAttachments(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String,
    entries: List<AttachmentEntryFfi>,
): List<IncomingAttachment> =
    entries.filter { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }.mapNotNull { entry ->
        val accepted = entry.attachment as? MediaAttachmentOutcomeFfi.Accepted
        val type = entry.category.autoDownloadType()
        if (accepted == null || type == null || accepted.reference.sourceEpoch == 0uL) {
            null
        } else {
            IncomingAttachment(
                request =
                    AttachmentTransferRequest(
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                        messageIdHex = entry.messageIdHex,
                        attachmentIndex = accepted.attachmentIndex.toInt(),
                        sourceMessageIdHex = entry.sourceMessageIdHex,
                    ),
                type = type,
            )
        }
    }

/** Whether a newest-first page has moved past [messageIdHex]'s run of slots onto an older message. */
internal fun List<AttachmentEntryFfi>.passedMessage(messageIdHex: String): Boolean {
    val first = indexOfFirst { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }
    return first >= 0 && drop(first).any { !it.messageIdHex.equals(messageIdHex, ignoreCase = true) }
}

/**
 * Finds a received message's attachments in MDK's authoritative attachment history, newest first, reading at
 * most [pageBudget] pages of [pageLimit] slots and stopping once the message's run of slots has ended. A
 * history change between pages ends the read with what was found; the bubbles still cover anything missed.
 */
@Suppress("ReturnCount") // Each page outcome ends the bounded read directly.
internal suspend fun WhiteNoiseAppState.receivedMessageAttachments(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String,
    pageLimit: UInt = RECEIPT_ATTACHMENT_PAGE_LIMIT,
    pageBudget: Int = RECEIPT_ATTACHMENT_PAGE_BUDGET,
): List<IncomingAttachment> {
    val found = mutableListOf<IncomingAttachment>()
    var cursor: AttachmentHistoryCursor? = null
    try {
        repeat(pageBudget) {
            val previous = cursor
            val read =
                try {
                    marmotIo { attachmentHistoryPage(accountRef, groupIdHex, pageLimit, previous) }
                } finally {
                    previous?.close()
                }
            cursor = null
            val page = (read as? AttachmentPageReadFfi.Page)?.page ?: return found
            val next = page.nextCursor
            var kept = false
            try {
                found += incomingAttachments(accountRef, groupIdHex, messageIdHex, page.entries)
                if (page.entries.passedMessage(messageIdHex) || !page.hasMore || next == null) return found
                cursor = next
                kept = true
            } finally {
                if (!kept) next?.close()
                page.version.close()
            }
        }
    } finally {
        cursor?.close()
    }
    return found
}
