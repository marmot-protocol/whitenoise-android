package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.state.tracedPagingSection

private const val MAX_READING_START_LAYOUT_CORRECTIONS = 3

/**
 * Measured reverse-list coordinates, not a second timeline or protocol-state cache. Shared by the mention
 * button and notification landing through the reading-start settle.
 */
internal data class ConversationMentionJumpLayout(
    val viewportEndOffsetPx: Int,
    val itemHeightPx: Int?,
    val estimatedItemHeightPx: Int? = null,
) {
    val isMeasured: Boolean
        get() = viewportEndOffsetPx > 0 && itemHeightPx != null && itemHeightPx > 0

    val readingStartOffset: Int
        get() = ReplyNavigation.readingStartScrollOffset(viewportEndOffsetPx, itemHeightPx ?: estimatedItemHeightPx)
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
 * correcting against fresh signed reverse-list geometry until its reading start sits at the top.
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
    val index = probe.resolveTargetIndex() ?: return null
    val offset = probe.readLayout(index).readingStartOffset
    val reached =
        if (approach is ConversationReadingStartApproach.Animated) {
            probe.traced(ConversationMentionJumpTrace.APPROACH) {
                animateScrollToItem(
                    index,
                    offset,
                    traceMentionJump = probe.traceSections,
                    resolveIndex = probe.resolveTargetIndex,
                )
            }
        } else {
            scrollToItem(index, offset)
            true
        }
    return ConversationReadingStartPlacement(index, offset).takeIf { reached }
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
        val index = probe.resolveTargetIndex()
        val layout = index?.let(probe.readLayout)
        if (index == null || layout == null || !layout.isMeasured) break
        if (index == placement.index && layout.readingStartOffset == placement.offsetPx) {
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
 * the scroll intent this record was captured against.
 */
internal class ConversationReadingStartIntent(
    private val anchor: ConversationScrollAnchor,
    private val probe: ConversationReadingStartProbe,
    private var placement: ConversationReadingStartPlacement,
    private var intentRevision: Long,
) {
    val targetMessageId: String?
        get() = anchor.messageId

    /** True while no gesture, command or restore has replaced the intent this landing settled. */
    fun isCurrent(coordinator: ConversationScrollCoordinator) = coordinator.intentToken.revision == intentRevision

    /** Fresh viewport end and row height for the target, the inputs that decide its reading start. */
    fun geometry(): Pair<Int, Int?>? {
        val layout = probe.resolveTargetIndex()?.let(probe.readLayout) ?: return null
        return layout.viewportEndOffsetPx to layout.itemHeightPx
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
        if (placed != null && placed != placement && coordinator.intentToken.revision == before) {
            placement = placed
            coordinator.settleReadingAt(anchor.copy(listIndex = placed.index, pixelOffset = placed.offsetPx))
            intentRevision = coordinator.intentToken.revision
        }
        return result.commandCompleted
    }
}
