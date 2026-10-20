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

/**
 * How far before a receipt a page's slots may have been received while the received message could still be
 * further down. A page received entirely earlier than this ends the read, which keeps a text message's
 * receipt to one page read in a group whose recent attachments are older.
 */
internal const val RECEIPT_RECENT_WINDOW_SECONDS = 30L * 60L

/** Restarts allowed when history changes under the read's cursor, before the read gives up. */
private const val RECEIPT_LOOKUP_RESTARTS = 1

/** How one receipt's bounded history read ended, as a privacy-safe log key. */
internal enum class ReceiptLookupEnd(
    val key: String,
) {
    /** The received message's run of slots ended. */
    Passed("passed"),

    /** A page held nothing received recently, so the message carries no attachments that far down. */
    Stale("stale"),

    /** The group's attachment history ended. */
    HistoryEnd("history_end"),

    /** The page budget ran out first. */
    Exhausted("exhausted"),

    /** History kept changing under the cursor, or MDK refused the read. */
    Unavailable("unavailable"),
}

/** One receipt's planned attachments, with how many pages the read took and why it stopped. */
internal data class ReceiptAttachmentLookup(
    val attachments: List<IncomingAttachment>,
    val pages: Int,
    val end: ReceiptLookupEnd,
)

/**
 * Decides, for one newest-first page, whether the read can stop. [seenBefore] carries across pages that the
 * message's run already started, so a run ending exactly on a page boundary still stops at the next page.
 * Returns whether the message has been seen so far, and the reason to stop, or null to read on.
 */
internal fun List<AttachmentEntryFfi>.receiptPageEnd(
    messageIdHex: String,
    seenBefore: Boolean,
    recentSinceSeconds: Long,
): Pair<Boolean, ReceiptLookupEnd?> {
    val first = indexOfFirst { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }
    val seen = seenBefore || first >= 0
    val passed =
        when {
            first >= 0 -> drop(first).any { !it.messageIdHex.equals(messageIdHex, ignoreCase = true) }
            else -> seenBefore && isNotEmpty()
        }
    val stale = !seen && none { it.receivedAt.toLong() >= recentSinceSeconds }
    val end =
        when {
            passed -> ReceiptLookupEnd.Passed
            stale -> ReceiptLookupEnd.Stale
            else -> null
        }
    return seen to end
}

/**
 * Finds a received message's attachments in MDK's authoritative attachment history, newest first, reading at
 * most [pageBudget] pages of [pageLimit] slots. The read stops once the message's run of slots has ended, or
 * on a page with nothing received within [RECEIPT_RECENT_WINDOW_SECONDS] of [receivedAtMillis]. A cursor made
 * stale by a history change restarts the read from the newest page once, without spending the page budget,
 * and the bubbles still cover anything missed.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // Each page outcome ends the bounded read directly.
internal suspend fun WhiteNoiseAppState.receivedMessageAttachments(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String,
    receivedAtMillis: Long,
    pageLimit: UInt = RECEIPT_ATTACHMENT_PAGE_LIMIT,
    pageBudget: Int = RECEIPT_ATTACHMENT_PAGE_BUDGET,
): ReceiptAttachmentLookup {
    val recentSince = receivedAtMillis / MILLIS_PER_SECOND - RECEIPT_RECENT_WINDOW_SECONDS
    val found = mutableListOf<IncomingAttachment>()
    var seen = false
    var pages = 0
    var restarts = 0
    var cursor: AttachmentHistoryCursor? = null

    fun lookup(end: ReceiptLookupEnd) = ReceiptAttachmentLookup(found.toList(), pages, end)
    try {
        while (pages < pageBudget) {
            // The cursor is consumed by this read, so the outer cleanup must never close it a second time.
            val previous = cursor
            cursor = null
            val read =
                try {
                    marmotIo(MarmotTraceSection.MEDIA_LIST) {
                        attachmentHistoryPage(accountRef, groupIdHex, pageLimit, previous)
                    }
                } finally {
                    previous?.close()
                }
            val page =
                when (read) {
                    is AttachmentPageReadFfi.Page -> read.page.also { pages += 1 }
                    AttachmentPageReadFfi.RestartRequired,
                    AttachmentPageReadFfi.CursorMismatch,
                    -> {
                        if (restarts++ >= RECEIPT_LOOKUP_RESTARTS) return lookup(ReceiptLookupEnd.Unavailable)
                        // The restarted read gets the whole page budget again, as documented above.
                        found.clear()
                        seen = false
                        pages = 0
                        continue
                    }
                    AttachmentPageReadFfi.InvalidLimit -> return lookup(ReceiptLookupEnd.Unavailable)
                }
            val next = page.nextCursor
            var kept = false
            try {
                found += incomingAttachments(accountRef, groupIdHex, messageIdHex, page.entries)
                val (seenNow, end) = page.entries.receiptPageEnd(messageIdHex, seen, recentSince)
                seen = seenNow
                if (end != null) return lookup(end)
                if (!page.hasMore || next == null) return lookup(ReceiptLookupEnd.HistoryEnd)
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
    return lookup(ReceiptLookupEnd.Exhausted)
}

private const val MILLIS_PER_SECOND = 1_000L
