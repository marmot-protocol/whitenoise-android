package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the reader's place when a long message body expands or collapses in the reversed transcript.
 *
 * An instance holds only values it is remembered on, plus three functions that read only the controller, the list
 * state and the viewport, which are keys as well. A row callback that captured an instance therefore sees exactly the
 * values it would have captured itself. Scroll and timeline state is read when the row reports the change.
 */
@Suppress("LongParameterList") // Explicit screen dependencies, so a stale capture cannot hide in a provider.
internal class ConversationBodyExpansionPosition(
    private val controller: ConversationController,
    private val scope: CoroutineScope,
    private val navigationState: ConversationNavigationState,
    private val timelineViewport: ConversationTimelineViewport,
    private val scrollCoordinator: ConversationScrollCoordinator,
    private val currentTimelineListIndex: (String) -> Int?,
    private val currentScrollAnchor: () -> ConversationScrollAnchor,
    private val resolveScrollAnchorIndex: (ConversationScrollAnchor) -> Int?,
) {
    /**
     * Keeps the former preview near the viewport when its row grows in the reversed list.
     * The height increase would otherwise leave the reader at the newest end of a tall message.
     * Expanding is the reader's own choice of position, so a reply or search jump still loading is
     * cancelled and cannot move the viewport away when it completes.
     */
    fun preserveReadingPositionOnExpansion(item: TimelineMessage) {
        val beforeLayout = timelineViewport.readingLayoutInfo()
        val preview = beforeLayout.visibleItemsInfo.firstOrNull { it.key == item.presentationId } ?: return
        val maxLayoutFrames = 3
        val continuationPx = beforeLayout.viewportSize.height / 5
        navigationState.targetNavigation.cancel()
        scrollCoordinator.cancelForegroundRestoreForUserIntent()
        val wasFollowingTail = scrollCoordinator.isFollowingTail
        scrollCoordinator.settleReadingAt(currentScrollAnchor())
        val expansionRevision = scrollCoordinator.intentToken.revision

        scope.launch {
            if (scrollCoordinator.intentToken.revision != expansionRevision) return@launch
            val completed =
                scrollCoordinator.programmaticJump(
                    targetMessageId = item.record.messageIdHex,
                    reason = ConversationScrollReason.MessageExpansion,
                ) {
                    repeat(maxLayoutFrames) {
                        withFrameNanos { }
                        val expanded =
                            timelineViewport
                                .readingLayoutInfo()
                                .visibleItemsInfo
                                .firstOrNull { it.key == item.presentationId }
                                ?: return@programmaticJump
                        val growth = expanded.size - preview.size
                        if (growth > 0) {
                            // Reveal some new text while retaining the last lines of the preview.
                            val reveal = growth.coerceAtMost(continuationPx)
                            scrollToItem(expanded.index, growth - preview.offset - reveal)
                            return@programmaticJump
                        }
                    }
                }
            if (completed) {
                scrollCoordinator.settleLandingAt(
                    currentScrollAnchor(),
                    atNewestEdge =
                        wasFollowingTail &&
                            conversationLandingAtNewestEdge(
                                atNaturalTail = timelineViewport.isAtNaturalTail,
                                hasMoreAfter = controller.hasMoreAfterTimeline,
                            ),
                )
            }
        }
    }

    /** Keeps the collapsed message's top edge, or the tail, in place when a long body collapses. */
    fun preserveReadingPositionOnCollapse(item: TimelineMessage) {
        scrollCoordinator.cancelForegroundRestoreForUserIntent()
        // Collapse cancels deferred movement while preserving
        // whether the viewport is following the tail.
        val keepFollowingTail =
            scrollCoordinator.isFollowingTail &&
                conversationLandingAtNewestEdge(
                    atNaturalTail = timelineViewport.isAtNaturalTail,
                    hasMoreAfter = controller.hasMoreAfterTimeline,
                )
        currentTimelineListIndex(item.record.messageIdHex)?.let { index ->
            // Preserve a visible top edge; a deep offset into a tall body
            // must not survive after that content has been clipped away.
            val visible =
                timelineViewport
                    .readingLayoutInfo()
                    .visibleItemsInfo
                    .firstOrNull {
                        it.index == index
                    }
            val offset = -(visible?.offset ?: 0).coerceAtLeast(0)
            val anchor =
                ConversationScrollAnchor(
                    messageId = item.record.messageIdHex,
                    itemId = item.presentationId,
                    listIndex = index,
                    pixelOffset = offset,
                )
            scrollCoordinator.settleLandingAt(
                anchor,
                atNewestEdge = keepFollowingTail,
            )
            if (!keepFollowingTail) {
                val bookmark = scrollCoordinator.bookmark(anchor)
                scope.launch {
                    scrollCoordinator.restoreBookmark(
                        bookmark,
                        resolveAnchorIndex = resolveScrollAnchorIndex,
                    )
                }
            }
        } ?: scrollCoordinator.settleLandingAt(
            currentScrollAnchor(),
            atNewestEdge = keepFollowingTail,
        )
    }
}

/**
 * Remembers [ConversationBodyExpansionPosition] against every value its operations read, so a new instance appears
 * exactly when the screen's former expansion function and collapse callback would have captured a different value.
 * [listState] is a key because [currentScrollAnchor] reads it.
 */
@Composable
@Suppress("LongParameterList")
internal fun rememberConversationBodyExpansionPosition(
    controller: ConversationController,
    scope: CoroutineScope,
    navigationState: ConversationNavigationState,
    listState: LazyListState,
    timelineViewport: ConversationTimelineViewport,
    scrollCoordinator: ConversationScrollCoordinator,
    currentTimelineListIndex: (String) -> Int?,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    resolveScrollAnchorIndex: (ConversationScrollAnchor) -> Int?,
): ConversationBodyExpansionPosition =
    remember(controller, scope, navigationState, listState, timelineViewport, scrollCoordinator) {
        ConversationBodyExpansionPosition(
            controller = controller,
            scope = scope,
            navigationState = navigationState,
            timelineViewport = timelineViewport,
            scrollCoordinator = scrollCoordinator,
            currentTimelineListIndex = currentTimelineListIndex,
            currentScrollAnchor = currentScrollAnchor,
            resolveScrollAnchorIndex = resolveScrollAnchorIndex,
        )
    }
