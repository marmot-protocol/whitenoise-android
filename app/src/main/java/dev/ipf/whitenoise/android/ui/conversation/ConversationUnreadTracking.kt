package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationUnreadBadge
import dev.ipf.whitenoise.android.state.ConversationUnreadJumpState
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.advanceConversationReadAnchor
import dev.ipf.whitenoise.android.state.logUnreadBadgeTransition
import dev.ipf.whitenoise.android.state.reconcileConversationUnreadJump

/**
 * Keeps the read anchor, the jump-to-newest badge and the two-stage unread jump target current from a recompose
 * scope of its own. The conversation screen owns all three states and writes them from callbacks, but never reads
 * them while composing: a send moves the anchor, then the badge, then the jump target, and each of those reads in
 * the screen body recomposed the whole conversation once more.
 *
 * [projectionUnread] is the entry projection's unread count when one was available, and [nearBottom] whether the
 * reader is at the newest edge, which retires the current unread stack.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // The screen's unread owners and the rows they are measured over.
internal fun ConversationUnreadTracking(
    controller: ConversationController,
    renderedTimeline: List<TimelineMessage>,
    timelineViewport: ConversationTimelineViewport,
    trailingRowCount: Int,
    initialTimelineAnchored: Boolean,
    nearBottom: Boolean,
    projectionUnread: Int?,
    readAnchor: MutableState<String?>,
    badge: MutableState<ConversationUnreadBadge>,
    unreadJump: MutableState<ConversationUnreadJumpState>,
) {
    ConversationReadAnchorEffect(
        controller = controller,
        readAnchor = readAnchor,
        renderedTimeline = renderedTimeline,
        timelineViewport = timelineViewport,
        trailingRowCount = trailingRowCount,
        initialTimelineAnchored = initialTimelineAnchored,
    )
    val readAnchorMessageId = readAnchor.value
    ConversationUnreadBadgeReconcileEffect(
        badge = badge,
        anchored = initialTimelineAnchored,
        timeline = controller.timeline,
        readAnchorMessageId = readAnchorMessageId,
        projectionUnread = projectionUnread,
        windowReachesTail = !controller.hasMoreAfterTimeline,
        onTransition = { before, after ->
            logUnreadBadgeTransition("DMConversation", before, after, controller.timeline.size)
        },
    )
    // The first composition after anchoring has not reconciled yet; exposing 0 there would retire the
    // two-stage jump target, so the jump target waits for the first reconciliation.
    val badgeUi = badge.value.uiState(initialTimelineAnchored)
    val currentJump = unreadJump.value
    LaunchedEffect(
        controller,
        initialTimelineAnchored,
        renderedTimeline,
        readAnchorMessageId,
        badgeUi,
        nearBottom,
        currentJump,
    ) {
        if (!badgeUi.reconciled) return@LaunchedEffect
        unreadJump.value =
            reconcileConversationUnreadJump(
                current = unreadJump.value,
                timeline = renderedTimeline,
                readAnchorMessageId = readAnchorMessageId,
                unreadCount = badgeUi.count,
                nearBottom = nearBottom,
            )
    }
}

/**
 * Advances [readAnchor] to the message id of the deepest row the user has settled on. The candidate id is a key so
 * an optimistic UUID being replaced in the same list slot still advances the anchor to the confirmed message. A
 * missing durable watermark rebases only at the fully loaded tail; while a newer page exists it may be off-window
 * and must remain monotonic.
 *
 * A row only counts once it clears the composer outright. The transcript rests with the next message showing a
 * sliver above the composer, and taking that sliver as read deducted a message from the unread badge that nobody
 * had seen. Scrolling recomposes only this effect, never the screen that owns the anchor.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // The anchor and the rows it is measured over.
private fun ConversationReadAnchorEffect(
    controller: ConversationController,
    readAnchor: MutableState<String?>,
    renderedTimeline: List<TimelineMessage>,
    timelineViewport: ConversationTimelineViewport,
    trailingRowCount: Int,
    initialTimelineAnchored: Boolean,
) {
    val renderedSize = renderedTimeline.size
    val currentHighestVisibleTimelineIndex by
        remember(timelineViewport, renderedSize, trailingRowCount) {
            derivedStateOf {
                // The reading viewport rather than the raw layout, and within it only a row that
                // clears the composer outright. Counting a row the composer covers, or one left
                // showing a sliver above it, clears an unread badge for a message nobody has read.
                val newest =
                    timelineViewport.readingLayoutInfo().newestReadRow(
                        timelineListIndices = trailingRowCount until trailingRowCount + renderedSize,
                    )
                if (newest == null || renderedSize == 0) return@derivedStateOf -1
                // Reversed layout, bottom to top: [maybe bottom error]
                // [timeline items, newest first][maybe older-loading]
                // [maybe top error][maybe group recovery][top spacer].
                // The lowest fully clear index is therefore the newest row actually read.
                conversationTimelineIndexForListIndex(
                    listIndex = newest.index,
                    timelineSize = renderedSize,
                    trailingRowCount = trailingRowCount,
                ).coerceIn(0, renderedSize - 1)
            }
        }
    val currentHighestVisibleMessageId =
        renderedTimeline
            .getOrNull(currentHighestVisibleTimelineIndex)
            ?.record
            ?.messageIdHex
    LaunchedEffect(
        controller,
        initialTimelineAnchored,
        currentHighestVisibleTimelineIndex,
        currentHighestVisibleMessageId,
        controller.lastReadMessageId,
    ) {
        val idx =
            conversationReadAnchorCandidateIndex(
                initialTimelineAnchored = initialTimelineAnchored,
                highestVisibleTimelineIndex = currentHighestVisibleTimelineIndex,
            )
        if (idx < 0) return@LaunchedEffect
        readAnchor.value =
            advanceConversationReadAnchor(
                timeline = renderedTimeline,
                currentUiAnchorId = readAnchor.value,
                durableAnchorId = controller.lastReadMessageId,
                candidateIndex = idx,
                canRebaseMissingAnchor =
                    idx == renderedTimeline.lastIndex &&
                        !controller.hasMoreAfterTimeline,
            )
    }
}
