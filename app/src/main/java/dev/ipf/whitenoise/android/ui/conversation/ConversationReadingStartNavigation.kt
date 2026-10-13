package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.state.tracedPagingSection

private const val MAX_READING_START_LAYOUT_CORRECTIONS = 3

/** Offsets within this many pixels of the settled one are measurement rounding, not a reader's scroll. */
private const val MOVED_OFFSET_TOLERANCE_PX = 1

/**
 * Measured reverse-list coordinates, not a second timeline or protocol-state cache. Shared by the mention
 * button and notification landing through the reading-start settle.
 */
internal data class ConversationMentionJumpLayout(
    val viewportEndOffsetPx: Int,
    val itemHeightPx: Int?,
    val estimatedItemHeightPx: Int? = null,
    val isNewest: Boolean = false,
    val itemOffsetPx: Int? = null,
    val tailContentHeightPx: Int? = null,
    val viewportStartOffsetPx: Int = 0,
    val isAtNaturalTail: Boolean = false,
) {
    val isMeasured: Boolean
        get() = viewportEndOffsetPx > 0 && itemHeightPx != null && itemHeightPx > 0

    /** A requested offset alone cannot prove success when the native list clamps at its tail. */
    val isAtReadingStart: Boolean
        get() =
            itemOffsetPx == null ||
                (
                    isMeasured &&
                        kotlin.math.abs(itemOffsetPx + requireNotNull(itemHeightPx) - viewportEndOffsetPx) <= 1
                )

    val readingStartOffset: Int
        get() = ReplyNavigation.readingStartScrollOffset(viewportEndOffsetPx, itemHeightPx ?: estimatedItemHeightPx)

    /** A clamped notification target is readable without inventing blank space below the newest row. */
    val isFullyVisibleAtNaturalTail: Boolean
        get() =
            isAtNaturalTail &&
                isMeasured &&
                itemOffsetPx != null &&
                itemOffsetPx >= viewportStartOffsetPx &&
                itemOffsetPx + requireNotNull(itemHeightPx) <= viewportEndOffsetPx
}

/**
 * The complete measured geometry a reading-start settle needs for [index]: the clear viewport end, the row's size
 * and offset, and the tail extent that decides how much scroll runway a mention needs. Notifications use the
 * same physical geometry but accept a fully visible native tail clamp instead of reserving empty runway.
 * [cachedItemHeightPx] is a height seen earlier for an unmeasured row.
 */
internal fun ConversationTimelineViewport.readingStartLayout(
    index: Int,
    isNewest: Boolean,
    cachedItemHeightPx: Int? = null,
): ConversationMentionJumpLayout {
    val layout = readingLayoutInfo()
    val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
    return ConversationMentionJumpLayout(
        viewportEndOffsetPx = layout.viewportEndOffset,
        itemHeightPx = row?.size,
        estimatedItemHeightPx =
            cachedItemHeightPx ?: ReplyNavigation.estimateItemHeightPx(layout.visibleItemsInfo.map { it.size }),
        isNewest = isNewest,
        itemOffsetPx = row?.offset,
        tailContentHeightPx = tailContentHeightPx(index),
        viewportStartOffsetPx = layout.viewportStartOffset,
        isAtNaturalTail = isAtNaturalTail,
    )
}

/** The reversed-list write that put one message's reading start at the top of the clear viewport. */
internal data class ConversationReadingStartPlacement(
    val index: Int,
    val offsetPx: Int,
)

/**
 * Live list access for one reading-start target. The index is resolved by message identity on every
 * read, so header or page changes while a command is suspended cannot leave a stale row behind.
 * [traceSections] stays on for the mention button, whose benchmark budgets read those sections.
 */
internal data class ConversationReadingStartProbe(
    val resolveTargetIndex: () -> Int?,
    val readLayout: (Int) -> ConversationMentionJumpLayout,
    val awaitLayout: suspend () -> Unit = { withFrameNanos { } },
    val traceSections: Boolean = true,
    /** Mention jumps retain their runway; notification opens stay within the real transcript bounds. */
    val reserveTailSpace: Boolean = true,
)

/** How a reading-start settle reaches the row before it validates fresh geometry. */
internal sealed interface ConversationReadingStartApproach {
    /** Snaps near a far row, then animates the last few rows, as the visible mention button does. */
    data object Animated : ConversationReadingStartApproach

    /** One non-animated write for a transcript that is still hidden, so the first reveal is on target. */
    data object Instant : ConversationReadingStartApproach

    /** Starts from a row already reached, so a geometry change only pays for the correction it needs. */
    data class InPlace(
        val placement: ConversationReadingStartPlacement,
    ) : ConversationReadingStartApproach
}

/** Whether the command ran to completion and where it left the row, when the geometry was measurable. */
internal data class ConversationReadingStartResult(
    val commandCompleted: Boolean,
    val placement: ConversationReadingStartPlacement?,
) {
    val reached: Boolean
        get() = commandCompleted && placement != null
}

/**
 * The settle core shared by the mention button and notification landing: reach the row, then keep
 * correcting against fresh signed reverse-list geometry until its reading start sits at the top,
 * or a notification target is fully visible at the natural newest boundary without extra padding.
 * One latest-wins command owns the whole sequence under [reason], so a drag or newer navigation
 * cancels it before a stale correction can be written.
 */
internal suspend fun ConversationScrollCoordinator.settleReadingStart(
    targetMessageId: String,
    reason: ConversationScrollReason,
    probe: ConversationReadingStartProbe,
    approach: ConversationReadingStartApproach,
    resultingMode: ConversationScrollMode? = null,
): ConversationReadingStartResult {
    var placement: ConversationReadingStartPlacement? = null
    val completed =
        programmaticJump(targetMessageId, reason, resultingMode) {
            placement = placeReadingStart(probe, approach)
        }
    return ConversationReadingStartResult(completed, placement)
}

/** Reaches the row, repairs an estimate that overshot a collapsed row, then validates measured geometry. */
@Suppress("ReturnCount") // Guard clauses keep the ordered approach, repair and validation steps explicit.
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.placeReadingStart(
    probe: ConversationReadingStartProbe,
    approach: ConversationReadingStartApproach,
): ConversationReadingStartPlacement? {
    var placement = approachReadingStart(probe, approach) ?: return null
    if (approach !is ConversationReadingStartApproach.InPlace) {
        probe.traced(ConversationMentionJumpTrace.LAYOUT) { probe.awaitLayout() }
    }
    var measuredIndex = probe.resolveTargetIndex() ?: return null
    var measuredLayout = probe.readLayout(measuredIndex)
    // An expanded-row estimate can overshoot a now-collapsed row entirely.
    // Reach its newest edge once without the estimate, then measure afresh.
    if (!measuredLayout.isMeasured && measuredLayout.viewportEndOffsetPx > 0 && placement.offsetPx != 0) {
        probe.traced(ConversationMentionJumpTrace.CORRECTION) { scrollToItem(measuredIndex, 0) }
        placement = ConversationReadingStartPlacement(measuredIndex, 0)
        probe.traced(ConversationMentionJumpTrace.LAYOUT) { probe.awaitLayout() }
        measuredIndex = probe.resolveTargetIndex() ?: return null
        measuredLayout = probe.readLayout(measuredIndex)
    }
    if (!measuredLayout.isMeasured) return null
    return settleReadingStartLayout(probe, placement)
}

/** Performs the first write for [approach] and reports it, or null when the row cannot be reached. */
@Suppress("ReturnCount") // Guard clauses keep the in-place, resolution and write outcomes explicit.
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.approachReadingStart(
    probe: ConversationReadingStartProbe,
    approach: ConversationReadingStartApproach,
): ConversationReadingStartPlacement? {
    if (approach is ConversationReadingStartApproach.InPlace) return approach.placement
    var index = probe.resolveTargetIndex() ?: return null
    reserveReadingStartSpace(index, probe)
    index = probe.resolveTargetIndex() ?: return null
    val offset = probe.readLayout(index).readingStartOffset
    var placedIndex = index
    var placedOffset = offset
    val reached =
        if (approach is ConversationReadingStartApproach.Animated) {
            probe.traced(ConversationMentionJumpTrace.APPROACH) {
                animateScrollToItem(
                    index,
                    offset,
                    traceMentionJump = probe.traceSections,
                    resolveIndex = probe.resolveTargetIndex,
                    resolveScrollOffset = { resolved ->
                        placedIndex = resolved
                        placedOffset = probe.readLayout(resolved).readingStartOffset
                        placedOffset
                    },
                )
            }
        } else {
            scrollToItem(index, offset)
            true
        }
    return ConversationReadingStartPlacement(placedIndex, placedOffset).takeIf { reached }
}

/** Reserves only the missing native runway; unknown near-tail geometry waits for the measured approach. */
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.reserveReadingStartSpace(
    index: Int,
    probe: ConversationReadingStartProbe,
) {
    if (!probe.reserveTailSpace) return
    val layout = probe.readLayout(index)
    val height =
        layout.tailContentHeightPx
            ?: if (layout.isNewest) layout.itemHeightPx ?: layout.estimatedItemHeightPx ?: 1 else null
    if (height != null) reserveMentionReadingStart(height, probe.awaitLayout)
}

/** Re-resolves the logical target after a reserved-padding layout pass before validating its coordinate. */
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.readLayoutAfterReserve(
    probe: ConversationReadingStartProbe,
): Pair<Int, ConversationMentionJumpLayout>? {
    val measured = resolveMeasuredLayout(probe) ?: return null
    val layout = measured.second
    val height = layout.tailContentHeightPx ?: layout.itemHeightPx.takeIf { layout.isNewest }
    return if (probe.reserveTailSpace && height != null) {
        reserveMentionReadingStart(height, probe.awaitLayout)
        resolveMeasuredLayout(probe)
    } else {
        measured
    }
}

/** Pairs the current logical index with measured geometry, rejecting targets absent from the viewport. */
private fun resolveMeasuredLayout(probe: ConversationReadingStartProbe): Pair<Int, ConversationMentionJumpLayout>? =
    probe.resolveTargetIndex()?.let { index ->
        probe.readLayout(index).takeIf { it.isMeasured }?.let { index to it }
    }

/** A corrective write can remeasure an animating row; validate its fresh geometry before success. */
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.settleReadingStartLayout(
    probe: ConversationReadingStartProbe,
    initial: ConversationReadingStartPlacement,
): ConversationReadingStartPlacement? {
    var placement = initial
    var attempt = 0
    var reached = false
    while (attempt <= MAX_READING_START_LAYOUT_CORRECTIONS && !reached) {
        val measured = readLayoutAfterReserve(probe) ?: break
        val (index, layout) = measured
        if (!probe.reserveTailSpace && layout.isFullyVisibleAtNaturalTail) {
            // The requested negative offset may have clamped. Keep the actual
            // coordinate so a resize or saved restore cannot replay the overshoot.
            placement = ConversationReadingStartPlacement(index, -requireNotNull(layout.itemOffsetPx))
            reached = true
        } else if (
            index == placement.index &&
            layout.readingStartOffset == placement.offsetPx &&
            layout.isAtReadingStart
        ) {
            reached = true
        } else if (attempt < MAX_READING_START_LAYOUT_CORRECTIONS) {
            probe.traced(ConversationMentionJumpTrace.CORRECTION) {
                scrollToItem(index, layout.readingStartOffset)
            }
            placement = ConversationReadingStartPlacement(index, layout.readingStartOffset)
            probe.traced(ConversationMentionJumpTrace.LAYOUT) { probe.awaitLayout() }
        }
        attempt++
    }
    return placement.takeIf { reached }
}

/** Wraps [block] in a paging trace section only for callers whose benchmarks read the mention sections. */
private inline fun <T> ConversationReadingStartProbe.traced(
    name: String,
    block: () -> T,
): T = if (traceSections) tracedPagingSection(name, block) else block()

/**
 * A notification landing's reading-start ownership. It lives beside the viewport owner so a viewport
 * or row-height change reruns the same measured settle, instead of reapplying the pixel offset that
 * was correct only for the geometry it was written under, until a gesture or newer command changes
 * the scroll intent this record was captured against. Scrolling the message out of view by an input that
 * is not a drag, such as an accessibility scroll action, a wheel or a key, leaves the intent in place but
 * reports no geometry, so it cannot trigger a settle.
 */
internal class ConversationReadingStartIntent(
    private val anchor: ConversationScrollAnchor,
    private val probe: ConversationReadingStartProbe,
    private var placement: ConversationReadingStartPlacement,
    private var intentRevision: Long,
) {
    val targetMessageId: String?
        get() = anchor.messageId

    /** Where the target row sat in the list when the landing last settled, the baseline for [readerMoved]. */
    private var settledItemOffsetPx: Int? = readItemOffsetPx()

    /** The target row's current list offset, or null while it is not laid out. */
    private fun readItemOffsetPx(): Int? = probe.resolveTargetIndex()?.let(probe.readLayout)?.itemOffsetPx

    /** True while no gesture, command or restore has replaced the intent this landing settled. */
    fun isCurrent(coordinator: ConversationScrollCoordinator) = coordinator.intentToken.revision == intentRevision

    /**
     * Fresh viewport end and measured row height for the target, the inputs that decide its reading start.
     * It is null while the row is not laid out, which includes a user scrolling it out of view by any input,
     * not only a touch drag. Only a change in measured geometry may rerun the settle, so leaving the landed
     * message is never answered by pulling the list back to it. A row that scrolls back into view at a different
     * height is settled once, to its own reading start, and any gesture or newer command retires the intent. The
     * intent is deliberately not retired when the row merely leaves the viewport, because the keyboard and layout
     * churn also move it, and the late-media correction a landing needs would be lost with it.
     */
    fun geometry(): Pair<Int, Int>? {
        val layout = probe.resolveTargetIndex()?.let(probe.readLayout)
        val itemHeight = layout?.itemHeightPx ?: return null
        return layout.viewportEndOffsetPx to itemHeight
    }

    /** True when the reader scrolled the landed row out of view or only partway, by any input. */
    fun readerLeft(): Boolean = geometry() == null || readerMoved()

    /**
     * Whether the reader moved the list since the landing last settled, by an input that raises no drag, such as a
     * wheel, a key or an accessibility scroll action. A row that merely grows or a viewport that resizes keeps its
     * offset from the list's start edge, so only a scroll changes it while the intent is still current. Without a
     * baseline there is nothing to compare, so the first reading becomes the baseline.
     */
    fun readerMoved(): Boolean {
        val now = readItemOffsetPx() ?: return false
        val before = settledItemOffsetPx
        if (before == null) settledItemOffsetPx = now
        return before != null && kotlin.math.abs(now - before) > MOVED_OFFSET_TOLERANCE_PX
    }

    /**
     * Reruns the shared settle against current geometry and records the new placement as the durable
     * reading anchor. A gesture or newer command that wins the race leaves its own intent untouched.
     */
    suspend fun resettle(coordinator: ConversationScrollCoordinator): Boolean {
        val before = coordinator.intentToken.revision
        val result =
            coordinator.settleReadingStart(
                targetMessageId = requireNotNull(anchor.messageId),
                reason = ConversationScrollReason.ViewportChange,
                probe = probe,
                approach = ConversationReadingStartApproach.InPlace(placement),
            )
        val placed = result.placement
        if (placed != null && coordinator.intentToken.revision == before) {
            if (placed != placement) {
                placement = placed
                coordinator.settleReadingAt(anchor.copy(listIndex = placed.index, pixelOffset = placed.offsetPx))
                intentRevision = coordinator.intentToken.revision
            }
            settledItemOffsetPx = readItemOffsetPx()
        }
        return result.commandCompleted
    }
}
