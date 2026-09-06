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
    Arrangement.spacedBy(CONVERSATION_TIMELINE_TAIL_GAP, Alignment.Bottom)

/**
 * Applies the resting tail gap and any temporary snackbar clearance at the
 * content edge, where Compose can keep the real final row as the scroll anchor.
 */
internal fun conversationTimelineContentPadding(snackbarContentInset: Dp): PaddingValues =
    PaddingValues(bottom = CONVERSATION_TIMELINE_TAIL_GAP + snackbarContentInset)

/** Counts optional rows rendered between the permanent top spacer and the timeline. */
internal fun conversationTimelineLeadingStructuralRowCount(
    hasOlderHeader: Boolean,
    hasInlineTopError: Boolean,
): Int = (if (hasOlderHeader) 1 else 0) + (if (hasInlineTopError) 1 else 0)

/** Counts the controller's current structural rows before its message timeline. */
internal fun ConversationController.conversationLeadingStructuralRowCount(renderedTimelineSize: Int): Int =
    conversationTimelineLeadingStructuralRowCount(
        hasOlderHeader = hasMoreBefore || isLoadingOlder,
        hasInlineTopError =
            renderedTimelineSize > 0 &&
                error != null &&
                errorEdge == ConversationLoadFailureEdge.TOP,
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
