package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLoadFailureEdge

/** The one resting interval between the final timeline row and the composer. */
internal val CONVERSATION_TIMELINE_TAIL_GAP = 8.dp

/**
 * Bottom-aligns underfilled conversations while retaining chronological row
 * spacing. This matches the arrangement a reversed lazy column defaults to, so
 * a short transcript still rests against the composer.
 */
internal val CONVERSATION_TIMELINE_ROW_SPACING = 2.dp

internal val CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT =
    Arrangement.spacedBy(CONVERSATION_TIMELINE_ROW_SPACING, Alignment.Bottom)

/**
 * Applies the resting tail gap and any temporary snackbar clearance at the
 * content edge, where Compose can keep the real final row as the scroll anchor.
 */
internal fun conversationTimelineContentPadding(
    snackbarContentInset: Dp,
    foregroundOverlap: Dp = 0.dp,
): PaddingValues = PaddingValues(bottom = CONVERSATION_TIMELINE_TAIL_GAP + snackbarContentInset + foregroundOverlap)

/**
 * Whether the transcript emits its group-recovery card as a list row.
 *
 * The row emission and the structural-row count share this one predicate, so
 * the two cannot disagree the way they did while the card was rendered but
 * left uncounted, which shifted every timeline index by one row.
 */
internal fun ConversationController.conversationGroupRecoveryRowVisible(): Boolean =
    groupRecoveryReadFailed || groupRecoveryStatus?.hasVisibleRecoveryState() == true

/** Counts optional rows rendered between the permanent top spacer and the timeline. */
internal fun conversationTimelineLeadingStructuralRowCount(
    hasOlderHeader: Boolean,
    hasInlineTopError: Boolean,
    hasGroupRecovery: Boolean = false,
): Int =
    (if (hasGroupRecovery) 1 else 0) +
        (if (hasOlderHeader) 1 else 0) +
        (if (hasInlineTopError) 1 else 0)

/** Counts the controller's current structural rows before its message timeline. */
internal fun ConversationController.conversationLeadingStructuralRowCount(renderedTimelineSize: Int): Int =
    conversationTimelineLeadingStructuralRowCount(
        hasOlderHeader = hasMoreBefore || isLoadingOlder,
        hasInlineTopError =
            renderedTimelineSize > 0 &&
                error != null &&
                errorEdge == ConversationLoadFailureEdge.TOP,
        hasGroupRecovery = conversationGroupRecoveryRowVisible(),
    )

/**
 * Counts the rows the reversed transcript emits below its newest message.
 *
 * A reversed lazy column lays its first item out against the physical bottom,
 * so the bottom-edge load failure is emitted before the timeline and every
 * message row sits that many indices further from the bottom.
 */
internal fun conversationTimelineTrailingRowCount(hasBottomError: Boolean): Int = if (hasBottomError) 1 else 0

/** Counts the controller's rows currently rendered below its newest message. */
internal fun ConversationController.conversationTrailingRowCount(renderedTimelineSize: Int): Int =
    conversationTimelineTrailingRowCount(
        hasBottomError =
            renderedTimelineSize > 0 &&
                error != null &&
                errorEdge == ConversationLoadFailureEdge.BOTTOM,
    )

/**
 * Resolves the newest message row, which the reversed transcript anchors on.
 *
 * The newest message is the list's own origin, so following the tail costs one
 * scroll to a fixed index instead of arithmetic over the timeline's length.
 */
internal fun conversationTimelineTailListIndex(
    timelineSize: Int,
    trailingRowCount: Int,
): Int? =
    if (timelineSize > 0) {
        trailingRowCount
    } else {
        null
    }

/**
 * Maps a chronological timeline position to its row in the reversed list.
 *
 * The timeline itself stays oldest-first everywhere else, so day separators,
 * sender runs and paging keep reading in chronological order; only the row
 * order handed to the lazy list is inverted.
 */
internal fun conversationTimelineListIndex(
    timelineIndex: Int,
    timelineSize: Int,
    trailingRowCount: Int,
): Int = trailingRowCount + (timelineSize - 1 - timelineIndex)

/** Maps a reversed list row back to its chronological timeline position. */
internal fun conversationTimelineIndexForListIndex(
    listIndex: Int,
    timelineSize: Int,
    trailingRowCount: Int,
): Int = timelineSize - 1 - (listIndex - trailingRowCount)
