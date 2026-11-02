package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Next optimistic timelineOrder: one past the max across both the published
 * timeline and the in-flight optimistic items. Including `pending` is what
 * stops back-to-back optimistic sends from colliding while a publish is still
 * coalescing (the published list is stale in that window). See #225.
 */
internal fun nextTimelineOrder(
    published: Sequence<ULong>,
    pending: Sequence<ULong>,
): ULong = (published + pending).maxOrNull()?.plus(1uL) ?: 1uL

/**
 * An optimistic-send position override (#1256) is a transient bridge: it pins a
 * confirmed row to where its optimistic bubble sat so the row doesn't jump on the
 * optimistic→confirmed handoff. Once the optimistic bubble is gone and the real
 * projection has landed the override has done its job — leaving it keyed to a
 * confirmed id pins that row at a stale position while its neighbours re-sort,
 * which renders a newer message above an older one (#1578). Returns the override
 * ids that are now orphaned and safe to release back to their true projected spot.
 */
internal fun orphanedOptimisticSendPreserveIds(
    optimisticSendPreserveIds: Set<String>,
    optimisticKeys: Set<String>,
    projectedMessageIds: Set<String>,
): Set<String> =
    optimisticSendPreserveIds.filterTo(mutableSetOf()) { id ->
        "msg:$id" !in optimisticKeys && id in projectedMessageIds
    }

internal fun confirmedOptimisticMessageKeys(
    optimisticKeys: Set<String>,
    projectedMessageIds: Set<String>,
): Set<String> {
    val projectedKeys = projectedMessageIds.mapTo(mutableSetOf()) { "msg:$it" }
    return optimisticKeys.filterTo(mutableSetOf()) { it in projectedKeys }
}

/**
 * Conversation-retained ownership for optimistic-send position bridges.
 *
 * The optimistic rows and their ordering overrides survive controller
 * replacement, so the marker identifying those overrides must survive too.
 */
internal class OptimisticSendPositionPreserves {
    private val ids = mutableSetOf<String>()

    fun add(id: String) {
        ids.add(id)
    }

    fun remove(id: String) {
        ids.remove(id)
    }

    fun retainAll(retainedIds: Set<String>) {
        ids.retainAll(retainedIds)
    }

    fun clear() {
        ids.clear()
    }

    operator fun contains(id: String): Boolean = id in ids

    fun snapshot(): Set<String> = ids.toSet()

    fun releaseOrphaned(
        optimisticKeys: Set<String>,
        projectedMessageIds: Set<String>,
    ): Set<String> =
        orphanedOptimisticSendPreserveIds(
            optimisticSendPreserveIds = ids,
            optimisticKeys = optimisticKeys,
            projectedMessageIds = projectedMessageIds,
        ).also { orphaned -> ids.removeAll(orphaned) }
}

internal fun deferTimelinePositionSettlement(
    scope: CoroutineScope,
    currentJob: Job?,
    awaitBoundary: suspend () -> Unit = { yield() },
    settle: () -> Unit,
): Job {
    if (currentJob?.isActive == true) return currentJob
    return scope.launch {
        awaitBoundary()
        settle()
    }
}

/** An adjacent pair whose rendered order reverses the engine timeline or arrival order. */
internal data class TimelineAdjacentInversion(
    val above: TimelineMessage,
    val below: TimelineMessage,
    val sourceTimelineInverted: Boolean,
    val arrivalInverted: Boolean,
)

/**
 * Finds rendered neighbours whose display order reverses either the engine's
 * authenticated timeline time or this device's observation (receive) order — the
 * #1578 symptom of a newer message above an older one. The sort is a total order,
 * so an inversion means the comparator read an overridden position rather than a
 * row's true send time. Optimistic rows are skipped: they have no authoritative
 * projection yet, so their position is legitimately provisional. Splitting source
 * vs. arrival inversion tells a stale local override (source-inverted, usually
 * carrying an override) apart from faithful engine timestamp skew on receive
 * (arrival-inverted only) — the issue's source #1 vs #3.
 */
internal fun adjacentTimelineInversions(ordered: List<TimelineMessage>): List<TimelineAdjacentInversion> =
    ordered
        .windowed(size = 2)
        .mapNotNull { (above, below) ->
            val aboveSource = above.projected ?: return@mapNotNull null
            val belowSource = below.projected ?: return@mapNotNull null
            val sourceTimelineInverted = aboveSource.timelineAt > belowSource.timelineAt
            val arrivalInverted = aboveSource.receivedAt > belowSource.receivedAt
            if (!sourceTimelineInverted && !arrivalInverted) return@mapNotNull null
            TimelineAdjacentInversion(
                above = above,
                below = below,
                sourceTimelineInverted = sourceTimelineInverted,
                arrivalInverted = arrivalInverted,
            )
        }

private fun TimelineMessage.projectedMessageIdHex(): String? = projected?.messageIdHex

/**
 * Records of the rows the engine actually projected, in window order.
 * Optimistic sends and stream-debug rows carry synthetic ids that vanish on
 * reconciliation or cleanup, so anything tracking timeline identity across
 * time — paging anchors, tail watermarks — must never see them.
 */
internal fun canonicalTimelineRecords(items: List<TimelineMessage>): List<AppMessageRecordFfi> =
    items.filter { it.projected != null }.map(TimelineMessage::record)

/**
 * Oldest live timeline message ids to drop so at most [maxLiveItems] non-[protectedIds]
 * rows remain. Deliberately-loaded history (captured when the user scrolls up via
 * [loadOlderPage]) is never trimmed; only rows added by live Upserts after that are
 * capped (#1163).
 */
internal fun timelineMessageIdsExceedingLiveCap(
    items: Collection<TimelineMessage>,
    protectedIds: Set<String>,
    maxLiveItems: Int,
): List<String> {
    if (maxLiveItems < 0) return emptyList()
    val live =
        items.filter {
            val id = it.projectedMessageIdHex()
            id != null && id !in protectedIds
        }
    val overflow = live.size - maxLiveItems
    if (overflow <= 0) return emptyList()
    return live
        .sortedWith(::compareTimelineMessages)
        .take(overflow)
        .mapNotNull { it.projectedMessageIdHex() }
}

internal fun AppMessageRecordFfi.withRecordedAtOverride(recordedAt: ULong?): AppMessageRecordFfi = recordedAt?.let { copy(recordedAt = it) } ?: this

/**
 * The timeline position to reuse when retrying a failed optimistic send.
 * [stored] is non-nullable (`TimelineMessage.timelineOrder` defaults to `0uL`),
 * so an elvis against it is dead code; `0uL` is the "unset" sentinel and is the
 * only case that should mint a fresh order via [freshOrder]. See #101.
 */
internal fun retriedTimelineOrder(
    stored: ULong,
    freshOrder: () -> ULong,
): ULong = stored.takeIf { it != 0uL } ?: freshOrder()
