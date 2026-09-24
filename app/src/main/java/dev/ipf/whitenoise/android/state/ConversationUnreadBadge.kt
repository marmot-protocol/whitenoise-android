package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.ConversationUnreadBadge.Source

/**
 * The one owner of the number on the jump-to-newest badge.
 *
 * Two kinds of evidence exist for it: the rows loaded on screen, exact while the row the reader
 * last read is among them, and the chat-list projection — MDK's durable unread count, which lags
 * the reader by a mark-read round trip and knows nothing about where they scrolled. Paging the
 * bounded window used to switch between the two, and with no projection at hand to count every
 * loaded row, so scrolling into history showed unrelated or climbing numbers (#2726). This owner
 * counts from the loaded rows whenever the read anchor is among them, holds that count while paging
 * moves the anchor off screen, and while holding lets only a projection that grew — a message that
 * arrived — raise it. The number changes when the reader reads past unread rows or a message
 * arrives, never because a page loaded or left.
 */
internal data class ConversationUnreadBadge(
    val anchorMessageId: String? = null,
    val count: Int = 0,
    /** The projection's unread count at the last look, so its growth can be read as arrivals. */
    val projectionSeen: Int? = null,
    val source: Source = Source.NONE,
    /**
     * The number is a lower bound: it was taken inside a window that stops short of the tail with no
     * projection to consult, so while that holds it may only grow towards the truth, never below the
     * rows still loaded after the anchor.
     */
    val partial: Boolean = false,
) {
    /** Where the current number came from. */
    enum class Source {
        /** No count taken yet. */
        NONE,

        /** The read anchor is among the loaded rows and the rows after it were counted. */
        LOADED,

        /** The count was taken while the anchor was loaded and is held now that paging moved it off screen. */
        HELD,

        /**
         * The anchor was never seen loaded, or there is none, and the projection's count stands in;
         * it then follows the projection upwards only, until the rows can be counted.
         */
        PROJECTION,

        /** The anchor is off screen, was never counted, and no projection exists: nothing is known, so 0. */
        UNKNOWN,

        /**
         * The anchor is loaded but the window stops short of the newest row and nothing better is
         * known, so the rows after the anchor were counted once — an undercount — and are held.
         */
        PARTIAL,
    }
}

/**
 * Reconciles the badge with the current rows, read anchor and projection. [projectionUnread] is
 * null when the chat has no projection to consult. [windowReachesTail] says whether the loaded rows
 * include the newest message: only then do the rows after the anchor amount to the unread set, since
 * a backward page can trim the newest rows while keeping the anchor, and counting what is left would
 * make a page load change the number.
 */
@Suppress("CyclomaticComplexMethod") // One decision table; splitting it would hide which case wins.
internal fun ConversationUnreadBadge.reconcile(
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
    projectionUnread: Int?,
    windowReachesTail: Boolean,
): ConversationUnreadBadge {
    val anchorLoaded = readAnchorMessageId != null && timeline.any { it.record.messageIdHex == readAnchorMessageId }
    val previousAnchorLoaded = anchorMessageId != null && timeline.any { it.record.messageIdHex == anchorMessageId }
    val sameAnchor = anchorMessageId == readAnchorMessageId && source != Source.NONE
    // Every source but NONE and UNKNOWN stands for a number the reader has been shown.
    val counted = source != Source.NONE && source != Source.UNKNOWN
    val afterAnchor by lazy { countUnreadIncoming(timeline, readAnchorMessageId) }
    val projection = projectionUnread?.coerceAtLeast(0)
    // A lower bound may not fall below the unread rows still loaded after the anchor.
    val floor = if (partial && anchorLoaded) afterAnchor else 0
    return when {
        // The rows after the anchor are the unread set. The projection can lag them (it moves a
        // mark-read round trip behind), so the baseline for later arrivals is whichever is higher,
        // or a projection merely catching up would be added again as arrivals.
        windowReachesTail && (anchorLoaded || readAnchorMessageId == null) ->
            counted(readAnchorMessageId, afterAnchor, projection?.let { maxOf(it, afterAnchor) }, Source.LOADED)
        // The reader read past unread rows inside a window that stops short of the tail: the rows
        // between the two anchors are what they read, and nothing else about the set is known.
        anchorLoaded && counted && !sameAnchor && previousAnchorLoaded ->
            held(
                readAnchorMessageId,
                projectionUnread,
                read = receivedRowsBetween(timeline, anchorMessageId, readAnchorMessageId),
                floor = floor,
            )
        // Opened or landed mid-history: the rows after the anchor stop at the window's edge, so the
        // durable count stands in rather than a page-local one that every forward page would raise.
        anchorLoaded && !sameAnchor && projection != null ->
            counted(readAnchorMessageId, projection, projectionUnread, Source.PROJECTION)
        anchorLoaded && !sameAnchor -> counted(readAnchorMessageId, afterAnchor, null, Source.PARTIAL, partial = true)
        // A number taken from the projection follows it upwards only: a later decrease is a mark-read
        // committing reads that happened before the number was taken, and nothing the reader did since.
        sameAnchor && source == Source.PROJECTION ->
            counted(
                anchorMessageId,
                maxOf(count, projection ?: count),
                projectionUnread ?: projectionSeen,
                Source.PROJECTION,
            )
        // Paging moved the anchor off screen, or trimmed the tail while keeping it: the rows left on
        // screen say nothing new; only a projection that grew since the last look does, as an arrival.
        sameAnchor && counted -> held(anchorMessageId, projectionUnread, floor = floor)
        projection != null -> counted(readAnchorMessageId, projection, projectionUnread, Source.PROJECTION)
        sameAnchor -> this
        // Nothing read yet and no projection: everything received so far is unread, counted once —
        // exactly when the window reaches the tail, as far as the window goes otherwise.
        readAnchorMessageId == null ->
            counted(
                null,
                afterAnchor,
                null,
                if (windowReachesTail) Source.LOADED else Source.PARTIAL,
                partial = !windowReachesTail,
            )
        // An anchor that is off screen and was never counted. Counting the loaded rows would call
        // every retained row unread; the badge stays quiet until the anchor or a projection appears.
        else -> counted(readAnchorMessageId, 0, null, Source.UNKNOWN)
    }
}

/** A badge whose number was just taken from [source]; [partial] marks it as a lower bound. */
private fun counted(
    anchorMessageId: String?,
    count: Int,
    projectionSeen: Int?,
    source: Source,
    partial: Boolean = false,
) = ConversationUnreadBadge(anchorMessageId, count, projectionSeen, source, partial)

/**
 * The held number, less the [read] rows the reader passed, plus whatever the projection says arrived,
 * and never below [floor] — the unread rows still loaded after the anchor when the number is a lower bound.
 */
private fun ConversationUnreadBadge.held(
    anchorMessageId: String?,
    projectionUnread: Int?,
    read: Int = 0,
    floor: Int = 0,
): ConversationUnreadBadge {
    val next = maxOf((count - read + arrivalsSince(projectionUnread)).coerceAtLeast(0), floor)
    // A projection below the held number is lagging it, not correcting it, so the baseline for later
    // arrivals never drops below the number: a projection catching up is not a second set of arrivals.
    return copy(
        anchorMessageId = anchorMessageId,
        count = next,
        projectionSeen = projectionUnread?.let { maxOf(it, next) } ?: projectionSeen,
        source = Source.HELD,
    )
}

/** Received, non-derived rows after [fromMessageId] up to and including [toMessageId]; zero when either is missing. */
private fun receivedRowsBetween(
    timeline: List<TimelineMessage>,
    fromMessageId: String?,
    toMessageId: String?,
): Int {
    val from = timeline.indexOfFirst { it.record.messageIdHex == fromMessageId }
    val to = timeline.indexOfFirst { it.record.messageIdHex == toMessageId }
    if (from < 0 || to <= from) return 0
    return timeline.subList(from + 1, to + 1).count {
        it.record.direction == "received" && !isDerivedStateKind(it.record.kind)
    }
}

/** How much the projection grew since the last look; a fall (a mark-read that committed) counts as nothing. */
private fun ConversationUnreadBadge.arrivalsSince(projectionUnread: Int?): Int =
    if (projectionUnread != null && projectionSeen != null) (projectionUnread - projectionSeen).coerceAtLeast(0) else 0

/**
 * Logs a change of number or source without message content, so a field report can name the
 * transition. Debug builds only: a release build reads its failure markers, not a line per row read.
 */
internal fun logUnreadBadgeTransition(
    tag: String,
    before: ConversationUnreadBadge,
    after: ConversationUnreadBadge,
    loadedRows: Int,
) {
    if (!BuildConfig.DEBUG) return
    if (before.count == after.count && before.source == after.source) return
    Log.d(
        tag,
        "unread badge ${before.source}->${after.source} count=${before.count}->${after.count} " +
            "anchor=${if (after.anchorMessageId == null) "none" else "set"} loadedRows=$loadedRows " +
            "projection=${after.projectionSeen ?: "none"}",
    )
}
