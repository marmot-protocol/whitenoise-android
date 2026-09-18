package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi

/**
 * How one authoritative window replaces what the timeline is holding.
 *
 * MDK returns the whole bounded window on every command, not a delta, so the app decides what that
 * means for the rows already on screen.
 */
internal enum class WindowApplyMode {
    /**
     * The window is a new place in history: open, jump, reconnect, or a return to the live tail.
     * Every index is cleared first, so nothing from the old position can survive into the new one.
     */
    REPLACE,

    /**
     * The window slid by one page while the reader stayed where they were. Rows the new window still
     * holds keep their projected items, so paging costs the rows that actually changed rather than a
     * full rebuild of a list the reader is looking at.
     */
    EXTEND,
}

/** Whether this mode reconciles optimistic sends and admits delayed projections. */
internal val WindowApplyMode.reconcilesOptimistic: Boolean get() = this == WindowApplyMode.REPLACE

/**
 * What one window application decided before it began touching the timeline indexes.
 *
 * The Markdown a row already has, and the rows the window dropped, can only be read before the
 * indexes change, so they are settled up front and carried through the application.
 */
internal class WindowApplyPlan(
    val mode: WindowApplyMode,
    private val carriedTokens: Map<String, MarkdownDocumentFfi>,
    private val heldBefore: Map<String, TimelineMessageRecordFfi>,
    /** Rows whose tally must be recomputed: everything added, altered or dropped by this window. */
    val touchedIds: MutableSet<String>,
) {
    /** Whether the whole window is being rebuilt rather than extended. */
    val replaces: Boolean get() = mode == WindowApplyMode.REPLACE

    /**
     * The record as it should be projected, with Markdown carried over when its text is unchanged,
     * recording whether it differs from what the timeline already held.
     */
    fun carry(
        record: TimelineMessageRecordFfi,
        current: TimelineMessageRecordFfi?,
    ): TimelineMessageRecordFfi {
        val carried = record.withCarriedMarkdownTokens(carriedTokens, heldBefore)
        if (current == null || !timelineRecordsRenderEqual(current, carried)) {
            touchedIds.add(carried.messageIdHex)
        }
        return carried
    }
}

/**
 * Settles what this window means before any index changes: which mode applies, the Markdown already
 * parsed for its rows, and, when extending, the rows the window no longer holds.
 */
internal fun ConversationController.planWindowApply(
    page: TimelinePageFfi,
    replaceWindow: Boolean,
): WindowApplyPlan {
    val mode = if (replaceWindow) WindowApplyMode.REPLACE else WindowApplyMode.EXTEND
    val carriedTokens = timelineRecords.markdownTokensFor(page.messages.map { it.messageIdHex })
    val heldBefore = if (mode == WindowApplyMode.EXTEND) timelineRecords.toMap() else emptyMap()
    val departed = if (mode == WindowApplyMode.EXTEND) removeRowsAbsentFromPage(page) else emptySet()
    return WindowApplyPlan(mode, carriedTokens, heldBefore, departed.toMutableSet())
}

/**
 * Drops the rows an extended window no longer holds.
 *
 * Only rows the window itself dropped are removed. A projection still waiting for its media bridge
 * is kept: its send is mid-flight and the bridge insert, not this page, decides where it lands.
 * Returns the ids removed, so reaction tallies can be recomputed for them.
 */
internal fun ConversationController.removeRowsAbsentFromPage(page: TimelinePageFfi): Set<String> {
    val retained = page.messages.mapTo(mutableSetOf()) { it.messageIdHex }
    val departed =
        timelineRecords.keys.filterTo(mutableSetOf()) { id ->
            id !in retained && id !in pendingProjectionsAwaitingBridge
        }
    departed.forEach(::removeProjectedRecord)
    return departed
}

/**
 * Re-stamps the display order of rows this page kept.
 *
 * A row whose bubble would render identically skips re-projection, which is what makes an extended
 * window cheap — but its projected item still carries the ordinal from where the window used to sit.
 * Display sorts on that ordinal, so without this the kept rows would be ordered by a stale window
 * and a page would visibly scramble history. Rows carrying a local position override keep it, since
 * an in-flight send owns its own placement until MDK confirms it.
 */
internal fun ConversationController.refreshAuthoritativeOrder(page: TimelinePageFfi) {
    page.messages.forEach { record ->
        val id = record.messageIdHex
        val itemId =
            timelineItemsById.keys.firstOrNull { it == "msg:$id" || timelineItemHoldsMessage(it, id) }
                ?: return@forEach
        val item = timelineItemsById[itemId] ?: return@forEach
        val ordinal =
            authoritativeTimelineOrderByMessageId[id]
                ?.takeUnless { id in localTimelineOrderOverrides || id in localTimelineTimestampOverrides }
        if (item.authoritativeOrder != ordinal) {
            timelineItemsById[itemId] = item.copy(authoritativeOrder = ordinal)
        }
    }
}

/** Whether a projected item id stands for this message; durable stream rows carry a stream id instead. */
private fun ConversationController.timelineItemHoldsMessage(
    itemId: String,
    messageIdHex: String,
): Boolean = timelineItemsById[itemId]?.record?.messageIdHex == messageIdHex
