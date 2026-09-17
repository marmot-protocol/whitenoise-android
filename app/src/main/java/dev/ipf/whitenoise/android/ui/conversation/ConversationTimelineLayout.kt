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

/** Bottom-aligns underfilled conversations while retaining chronological row spacing. */
internal val CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT =
    Arrangement.spacedBy(2.dp, Alignment.Bottom)

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

/** Resolves the real final message row after every leading structural row. */
internal fun conversationTimelineTailListIndex(
    timelineSize: Int,
    leadingStructuralRowCount: Int,
): Int? =
    if (timelineSize > 0) {
        timelineSize + leadingStructuralRowCount
    } else {
        null
    }
