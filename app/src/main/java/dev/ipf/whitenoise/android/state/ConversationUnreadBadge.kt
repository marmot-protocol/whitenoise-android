package dev.ipf.whitenoise.android.state

import android.util.Log

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
) {
    /** Where the current number came from. */
    enum class Source {
        /** No count taken yet. */
        NONE,

        /** The read anchor is among the loaded rows and the rows after it were counted. */
        LOADED,

        /** The count was taken while the anchor was loaded and is held now that paging moved it off screen. */
        HELD,

        /** The anchor was never seen loaded, or there is none, and the projection's count stands in. */
        PROJECTION,

        /** The anchor is off screen, was never counted, and no projection exists: nothing is known, so 0. */
        UNKNOWN,
    }
}

/**
 * Reconciles the badge with the current rows, read anchor and projection. [projectionUnread] is
 * null when the chat has no projection to consult.
 */
internal fun ConversationUnreadBadge.reconcile(
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
    projectionUnread: Int?,
): ConversationUnreadBadge {
    val anchorLoaded =
        readAnchorMessageId != null && timeline.any { it.record.messageIdHex == readAnchorMessageId }
    val sameAnchor = anchorMessageId == readAnchorMessageId && source != ConversationUnreadBadge.Source.NONE
    val countedWhileLoaded =
        source == ConversationUnreadBadge.Source.LOADED || source == ConversationUnreadBadge.Source.HELD
    return when {
        anchorLoaded ->
            ConversationUnreadBadge(
                anchorMessageId = readAnchorMessageId,
                count = countUnreadIncoming(timeline, readAnchorMessageId),
                projectionSeen = projectionUnread,
                source = ConversationUnreadBadge.Source.LOADED,
            )
        sameAnchor && countedWhileLoaded ->
            // Paging moved the anchor off screen. The rows left on screen say nothing new about what
            // lies after it; only a projection that grew since the last look does, and that is an arrival.
            copy(
                count = count + arrivalsSince(projectionUnread),
                projectionSeen = projectionUnread ?: projectionSeen,
                source = ConversationUnreadBadge.Source.HELD,
            )
        projectionUnread != null ->
            ConversationUnreadBadge(
                anchorMessageId = readAnchorMessageId,
                count = projectionUnread.coerceAtLeast(0),
                projectionSeen = projectionUnread,
                source = ConversationUnreadBadge.Source.PROJECTION,
            )
        sameAnchor -> this
        readAnchorMessageId == null ->
            // Nothing read yet and no projection: everything received so far is unread, counted once.
            ConversationUnreadBadge(
                anchorMessageId = null,
                count = countUnreadIncoming(timeline, null),
                projectionSeen = null,
                source = ConversationUnreadBadge.Source.LOADED,
            )
        else ->
            // An anchor that is off screen and was never counted. Counting the loaded rows would call
            // every retained row unread; the badge stays quiet until the anchor or a projection appears.
            ConversationUnreadBadge(
                anchorMessageId = readAnchorMessageId,
                count = 0,
                projectionSeen = null,
                source = ConversationUnreadBadge.Source.UNKNOWN,
            )
    }
}

/** How much the projection grew since the last look; a fall (a mark-read that committed) counts as nothing. */
private fun ConversationUnreadBadge.arrivalsSince(projectionUnread: Int?): Int =
    if (projectionUnread != null && projectionSeen != null) (projectionUnread - projectionSeen).coerceAtLeast(0) else 0

/** Logs a change of number or source without message content, so a field report can name the transition. */
internal fun logUnreadBadgeTransition(
    tag: String,
    before: ConversationUnreadBadge,
    after: ConversationUnreadBadge,
    loadedRows: Int,
) {
    if (before.count == after.count && before.source == after.source) return
    Log.d(
        tag,
        "unread badge ${before.source}->${after.source} count=${before.count}->${after.count} " +
            "anchor=${if (after.anchorMessageId == null) "none" else "set"} loadedRows=$loadedRows " +
            "projection=${after.projectionSeen ?: "none"}",
    )
}
