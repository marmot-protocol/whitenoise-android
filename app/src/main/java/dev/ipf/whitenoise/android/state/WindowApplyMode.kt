package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

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

/** Immutable controller state needed to prepare one window without touching Compose state. */
internal data class WindowApplySnapshot(
    val heldRecords: List<TimelineMessageRecordFfi>,
    val pendingProjectionIds: Set<String>,
)

/** Copies mutable record and bridge indexes before suspending on the preparation dispatcher. */
internal fun currentWindowApplySnapshot(
    heldRecords: Collection<TimelineMessageRecordFfi>,
    pendingProjectionIds: Collection<String>,
) = WindowApplySnapshot(heldRecords.toList(), pendingProjectionIds.toSet())

/** Resolves the display index key off-main so commit validation never projects a record. */
private fun preparedProjectedItemId(
    messageIdHex: String,
    actionRecord: AppMessageRecordFfi,
): String {
    val streamId =
        MessageProjector.streamId(actionRecord).takeIf { MessageProjector.isStreamStart(actionRecord) }
    return streamId?.let { "stream:$it" } ?: "msg:$messageIdHex"
}

/** Pure result of interpreting a native window before the main-thread commit. */
internal data class PreparedWindowRow(
    val record: TimelineMessageRecordFfi,
    val actionRecord: AppMessageRecordFfi,
    val projectedItemId: String,
    val needsProjection: Boolean,
    val reconcilesOptimistic: Boolean,
)

/** Cheap main-thread validation of a diff prepared while other projection writers could run. */
internal data class WindowApplyCommitPlan(
    val departedIds: Set<String>,
    val projectIds: Set<String>,
) {
    val touchedIds: Set<String> get() = departedIds + projectIds
}

/** Rechecks mutable indexes before committing an EXTEND prepared from an earlier snapshot. */
internal fun PreparedWindowApply.planCommit(
    snapshot: WindowApplySnapshot,
    liveRecords: Map<String, TimelineMessageRecordFfi>,
    projectedItemIds: Set<String>,
    pendingProjectionIds: Set<String>,
): WindowApplyCommitPlan {
    val snapshotById = snapshot.heldRecords.associateBy(TimelineMessageRecordFfi::messageIdHex)
    val pageIds = rows.mapTo(HashSet(rows.size)) { it.record.messageIdHex }
    val departedIds =
        if (mode == WindowApplyMode.EXTEND) {
            liveRecords.keys.filterTo(HashSet()) { it !in pageIds && it !in pendingProjectionIds }
        } else {
            emptySet()
        }
    val projectIds =
        buildSet {
            rows.forEach { row ->
                val id = row.record.messageIdHex
                // The exact send bridge must place deferred media at its final position.
                if (mode == WindowApplyMode.EXTEND && id in pendingProjectionIds) return@forEach
                if (
                    row.needsProjection ||
                    liveRecords[id] !== snapshotById[id] ||
                    row.projectedItemId !in projectedItemIds
                ) {
                    add(id)
                }
            }
        }
    return WindowApplyCommitPlan(departedIds, projectIds)
}

/** Pure result of interpreting a native window before the main-thread commit. */
internal data class PreparedWindowApply(
    val rows: List<PreparedWindowRow>,
    val mode: WindowApplyMode,
    val departedIds: Set<String>,
    val authoritativeOrder: Map<String, ULong>,
    val touchedIds: Set<String>,
    val profileIds: Set<String>,
)

/** Deterministic work counters plus separately measured preparation and main-commit durations. */
data class WindowApplyPerformanceSample(
    val preparedRowCount: Int,
    val committedProjectionCount: Int,
    val preparationNanos: Long,
    val mainCommitNanos: Long,
)

internal data class TimedPreparedWindowApply(
    val value: PreparedWindowApply,
    val durationNanos: Long,
)

/** Couples the off-main preparation duration with the measured main-thread commit cost. */
internal fun TimedPreparedWindowApply.performanceSample(
    prepared: PreparedWindowApply,
    committedProjectionCount: Int,
    commitStartedAtNanos: Long,
    commitFinishedAtNanos: Long,
) = WindowApplyPerformanceSample(
    preparedRowCount = prepared.rows.size,
    committedProjectionCount = committedProjectionCount,
    preparationNanos = durationNanos,
    mainCommitNanos = (commitFinishedAtNanos - commitStartedAtNanos).coerceAtLeast(0L),
)

/** Runs pure row preparation on the injected dispatcher and times only that work. */
internal suspend fun prepareWindowApplyOn(
    dispatcher: CoroutineDispatcher,
    nanoTime: () -> Long,
    page: TimelinePageFfi,
    snapshot: WindowApplySnapshot,
    replaceWindow: Boolean,
    reconcileNewExtendedRecords: Boolean,
): TimedPreparedWindowApply =
    withContext(dispatcher) {
        val startedAt = nanoTime()
        TimedPreparedWindowApply(
            value = prepareWindowApply(page, snapshot, replaceWindow, reconcileNewExtendedRecords),
            durationNanos = (nanoTime() - startedAt).coerceAtLeast(0L),
        )
    }

/** Excludes stream starts whose terminal row landed in the same window. */
internal fun windowStreamIdsToLaunch(
    streamIds: List<String>,
    isRemoved: (String) -> Boolean,
): List<String> = streamIds.filterNot(isRemoved)

/**
 * Derives the immutable part of a window application.
 *
 * Callers may run this on a background dispatcher: it only reads [snapshot] and [page], and returns
 * fresh collections for the main-thread commit to consume.
 */
@Suppress("LongMethod") // Keep the pure snapshot-to-diff derivation in one background-safe operation.
internal fun prepareWindowApply(
    page: TimelinePageFfi,
    snapshot: WindowApplySnapshot,
    replaceWindow: Boolean,
    reconcileNewExtendedRecords: Boolean = false,
): PreparedWindowApply {
    val mode = if (replaceWindow) WindowApplyMode.REPLACE else WindowApplyMode.EXTEND
    val heldBefore = snapshot.heldRecords.associateBy(TimelineMessageRecordFfi::messageIdHex)
    val retainedIds = page.messages.mapTo(linkedSetOf()) { it.messageIdHex }
    val departedIds =
        if (mode == WindowApplyMode.EXTEND) {
            snapshot.heldRecords
                .asSequence()
                .map(TimelineMessageRecordFfi::messageIdHex)
                .filter { it !in retainedIds && it !in snapshot.pendingProjectionIds }
                .toCollection(linkedSetOf())
        } else {
            emptySet()
        }
    val carriedTokens =
        if (mode == WindowApplyMode.EXTEND) {
            heldBefore.markdownTokensFor(retainedIds)
        } else {
            emptyMap()
        }
    val rows =
        page.messages.map { record ->
            val carried = record.withCarriedMarkdownTokens(carriedTokens, heldBefore)
            val current = heldBefore[record.messageIdHex]
            val actionRecord = TimelineProjector.toAppMessageRecord(carried)
            PreparedWindowRow(
                record = carried,
                actionRecord = actionRecord,
                projectedItemId = preparedProjectedItemId(record.messageIdHex, actionRecord),
                needsProjection =
                    mode == WindowApplyMode.REPLACE ||
                        current == null ||
                        !timelineRecordsRenderEqual(current, carried),
                reconcilesOptimistic =
                    mode.reconcilesOptimistic ||
                        (reconcileNewExtendedRecords && record.messageIdHex !in heldBefore),
            )
        }
    val touchedIds =
        buildSet {
            addAll(departedIds)
            rows
                .asSequence()
                .filter(PreparedWindowRow::needsProjection)
                .mapTo(this) { it.record.messageIdHex }
        }
    val authoritativeOrder =
        buildMap {
            page.messages.forEachIndexed { index, record ->
                if (record.usesAuthoritativePageOrder()) {
                    put(record.messageIdHex, index.toULong())
                }
            }
        }
    val profileIds =
        buildSet {
            rows.forEach { row ->
                add(row.record.sender)
                row.record.replyPreview?.let { add(it.sender) }
                row.record.reactions.userReactions
                    .forEach { add(it.sender) }
            }
        }

    return PreparedWindowApply(
        rows = rows,
        mode = mode,
        departedIds = departedIds,
        authoritativeOrder = authoritativeOrder,
        touchedIds = touchedIds,
        profileIds = profileIds,
    )
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
        // Ordinary rows are keyed by message id, so look that up directly; only a durable stream
        // row, keyed by its stream id, needs the scan.
        val itemId =
            "msg:$id".takeIf(timelineItemsById::containsKey)
                ?: timelineItemsById.keys.firstOrNull { timelineItemHoldsMessage(it, id) }
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
