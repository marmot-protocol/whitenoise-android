package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageAvailability
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.hiddenMessageIdsInGroup
import dev.ipf.whitenoise.android.state.isLoadingPage
import dev.ipf.whitenoise.android.state.loadMessageAvailability
import dev.ipf.whitenoise.android.state.loadUntilMessageAvailable
import dev.ipf.whitenoise.android.state.returnToLatestWindow
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTransitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Moves the transcript to one target message (a reply preview's original, a search match, a kept message or a
 * global-search result), centers it with its surrounding context and briefly highlights it.
 *
 * Every navigation runs under [ConversationNavigationState]'s latest-wins owner and jobs, launches on the screen's
 * [scope] and scrolls through [scrollCoordinator]. The function dependencies only read the controller's live
 * timeline, the list state and the viewport.
 */
@Suppress("LongParameterList") // Explicit screen dependencies, so a stale capture cannot hide in a provider.
internal class ConversationMessageNavigator(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    private val scope: CoroutineScope,
    private val presentationState: ConversationSurfaceState,
    private val navigationState: ConversationNavigationState,
    private val scrollCoordinator: ConversationScrollCoordinator,
    private val timelineViewport: ConversationTimelineViewport,
    private val conversationAccountRef: String?,
    private val tailIndex: State<Int>,
    private val currentTimelineListIndex: (String) -> Int?,
    private val currentScrollAnchor: () -> ConversationScrollAnchor,
    private val resolveScrollAnchorIndex: (ConversationScrollAnchor) -> Int?,
) {
    /**
     * Heights of the message bubbles currently on screen, used to estimate the
     * centering offset for a row that has not been measured yet.
     */
    private fun visibleBubbleHeights(layoutInfo: LazyListLayoutInfo): List<Int> {
        val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val trailingRows = controller.conversationTrailingRowCount(rendered.size)
        val messageRows = trailingRows..(trailingRows + rendered.size - 1)
        return layoutInfo.visibleItemsInfo
            .filter { visibleItem ->
                if (visibleItem.index !in messageRows) return@filter false
                val row =
                    rendered.getOrNull(
                        conversationTimelineIndexForListIndex(
                            listIndex = visibleItem.index,
                            timelineSize = rendered.size,
                            trailingRowCount = trailingRows,
                        ),
                    ) ?: return@filter false
                timelineRowKind(row.record, appState.streamingDebugEnabled) == TimelineRowKind.Bubble
            }.map { it.size }
    }

    /**
     * Centers [targetMessageId] with surrounding context through the shared latest-wins owner.
     *
     * When [skipIfFullyVisible] is true, a visible bubble stays in place even if its sender gap is clipped.
     * An unmeasured target uses one animated approach followed by a non-animated exact correction,
     * avoiding a second visible bounce.
     */
    suspend fun centerTimelineItemAt(
        targetMessageId: String,
        fallbackTargetIndex: Int,
        reason: ConversationScrollReason,
        skipIfFullyVisible: Boolean = false,
    ): Boolean {
        val completed =
            scrollCoordinator.programmaticJump(
                targetMessageId = targetMessageId,
                reason = reason,
            ) {
                val targetIndex = currentTimelineListIndex(targetMessageId) ?: fallbackTargetIndex
                val layoutInfo = timelineViewport.readingLayoutInfo()
                val viewportHeight = layoutInfo.viewportSize.height
                if (viewportHeight <= 0) {
                    // Layout not measured yet (rare on a fresh open): fall back to the
                    // plain top-aligned jump rather than guessing an offset.
                    animateScrollToItem(targetIndex) {
                        currentTimelineListIndex(targetMessageId) ?: targetIndex
                    }
                    return@programmaticJump
                }
                if (skipIfFullyVisible &&
                    layoutInfo.isItemFullyVisible(targetIndex, timelineViewport.senderGapMeasurements::get)
                ) {
                    return@programmaticJump
                }
                val visibleTargetHeight = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size
                val visibleTimelineHeights = visibleBubbleHeights(layoutInfo)
                val itemHeight =
                    ReplyNavigation.itemHeightForScrollPx(
                        targetMessageId = targetMessageId,
                        measuredItemHeightsByMessageId = navigationState.timelineItemHeightsPx,
                        visibleTargetHeightPx = visibleTargetHeight,
                        visibleTimelineItemHeightsPx = visibleTimelineHeights,
                    )
                val animatedOffset = ReplyNavigation.centeredScrollOffset(viewportHeight, itemHeight)
                animateScrollToItem(targetIndex, animatedOffset) {
                    currentTimelineListIndex(targetMessageId) ?: targetIndex
                }

                // Keep the measured correction in the same coordinator command. A
                // newer drag/jump cancels this whole block before it can snap back.
                withFrameNanos { }
                val resolvedTargetIndex = currentTimelineListIndex(targetMessageId) ?: targetIndex
                val postScrollLayoutInfo = timelineViewport.readingLayoutInfo()
                val measuredItemHeight =
                    postScrollLayoutInfo.visibleItemsInfo.firstOrNull { it.index == resolvedTargetIndex }?.size
                        ?: navigationState.timelineItemHeightsPx[targetMessageId]
                val measuredViewportHeight = postScrollLayoutInfo.viewportSize.height
                if (measuredViewportHeight > 0 && measuredItemHeight != null) {
                    val measuredOffset =
                        ReplyNavigation.centeredScrollOffset(measuredViewportHeight, measuredItemHeight)
                    if (resolvedTargetIndex != targetIndex || measuredOffset != animatedOffset) {
                        scrollToItem(resolvedTargetIndex, measuredOffset)
                    }
                }
            }
        if (completed) scrollCoordinator.settleReadingAt(currentScrollAnchor())
        return completed
    }

    suspend fun showTransientMessageHighlight(messageId: String) {
        navigationState.targetHighlight.highlightWhile(
            messageId = messageId,
            postSettleDwellMillis = TRANSIENT_MESSAGE_HIGHLIGHT_DWELL_MILLIS,
        ) { true }
    }

    fun navigateToReplyTarget(item: TimelineMessage) {
        navigationState.searchJob?.cancel()
        navigationState.targetHighlight.clear()
        navigationState.navigateReplyJob?.cancel()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.navigateReplyJob =
            scope.launch {
                if (!navigationRequest.isCurrent()) return@launch
                val targetMessageId = controller.replyTargetMessageId(item)
                if (targetMessageId == null) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                navigationState.targetHighlight.highlightWhile(targetMessageId) {
                    if (!navigationRequest.isCurrent()) return@highlightWhile false
                    val availability = controller.loadMessageAvailability(targetMessageId)
                    if (!navigationRequest.isCurrent()) return@highlightWhile false
                    when (availability) {
                        MessageAvailability.AVAILABLE -> Unit
                        MessageAvailability.MISSING -> {
                            appState.present(R.string.toast_original_message_unavailable)
                            return@highlightWhile false
                        }
                        MessageAvailability.RETRYABLE -> {
                            appState.present(R.string.error_loaded_content_kept)
                            return@highlightWhile false
                        }
                    }
                    // Resolve the target in the rendered (edit-filtered) list the
                    // LazyColumn shows — an unfiltered index is off by the edits above it.
                    val timelineIndex =
                        controller.timeline
                            .filterNot { MessageProjector.isEdit(it.record) }
                            .indexOfFirst { it.record.messageIdHex == targetMessageId }
                    if (timelineIndex < 0) {
                        appState.present(R.string.toast_original_message_unavailable)
                        return@highlightWhile false
                    }
                    if (!navigationRequest.isCurrent()) return@highlightWhile false
                    centerTimelineItemAt(
                        targetMessageId,
                        requireNotNull(currentTimelineListIndex(targetMessageId)),
                        ConversationScrollReason.Reply,
                        skipIfFullyVisible = true,
                    )
                }
            }
    }

    /**
     * The conversation's steps for landing the global-search result [target] (#1945). The landing stays current
     * only while [request] owns navigation and no user scroll intent arrived after [intent]. [target] is the
     * canonical lowercase id the open recorded, and the reader's position before the window load is kept so a
     * target that turns out to be gone can put them back.
     */
    fun searchResultLandingSteps(
        target: String,
        request: MessageTargetNavigationOwner.Request,
        intent: ConversationScrollIntentToken,
    ): SearchResultLandingSteps {
        val before = scrollCoordinator.bookmark(currentScrollAnchor())

        /** The loaded row of [target], whose id the open already canonicalized. */
        fun loaded(): TimelineMessage? = controller.timeline.firstOrNull { it.record.messageIdHex == target }

        return SearchResultLandingSteps(
            isCurrent = { request.isCurrent() && scrollCoordinator.intentToken.revision == intent.revision },
            ownsNavigation = { request.isCurrent() },
            hidden = {
                appState
                    .hiddenMessageIdsInGroup(conversationAccountRef, controller.group.groupIdHex)
                    .any { canonicalMessageId(it) == target }
            },
            loadWindow = { controller.loadMessageAvailability(target) },
            loadedRecord = { loaded()?.projected },
            center = {
                val index = currentTimelineListIndex(target)
                index != null && centerTimelineItemAt(target, index, ConversationScrollReason.FocusMessage)
            },
            highlight = { showTransientMessageHighlight(target) },
            unavailable = { appState.present(R.string.search_result_unavailable) },
            failed = { navigationState.focusTargetRetry.failed(request) },
            restoreAnchor = {
                scrollCoordinator.restoreReaderAfterLanding(
                    bookmark = before,
                    ownsNavigation = { request.isCurrent() },
                    resolveAnchorIndex = resolveScrollAnchorIndex,
                    rematerialize = { id ->
                        controller.loadMessageAvailability(id) == MessageAvailability.AVAILABLE
                    },
                    returnToNewest = {
                        loadConversationTimelineToNewest(
                            hasMoreAfter = { controller.hasMoreAfterTimeline },
                            loadNewer = controller::loadNewerTimelinePage,
                            returnToLatest = controller::returnToLatestWindow,
                        )
                        withFrameNanos { }
                        tailIndex.value
                    },
                )
            },
        )
    }

    /** Centers and highlights a kept message when the open transcript still holds it. */
    fun jumpToKeptMessage(messageIdHex: String) {
        scope.launch {
            val visibleRows = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
            val timelineIndex = visibleRows.indexOfFirst { it.record.messageIdHex == messageIdHex }
            if (timelineIndex >= 0) {
                val centered =
                    centerTimelineItemAt(
                        messageIdHex,
                        conversationTimelineListIndex(
                            timelineIndex = timelineIndex,
                            timelineSize = visibleRows.size,
                            trailingRowCount = controller.conversationTrailingRowCount(visibleRows.size),
                        ),
                        ConversationScrollReason.Search,
                    )
                if (centered) showTransientMessageHighlight(messageIdHex)
            }
        }
    }

    /** Centers and highlights a loaded search row only while its navigation request remains current. */
    private suspend fun centerLoadedSearchMessage(
        messageIdHex: String,
        navigationRequest: MessageTargetNavigationOwner.Request,
    ) {
        if (!navigationRequest.isCurrent()) return
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == messageIdHex }
        if (timelineIndex >= 0 && navigationRequest.isCurrent()) {
            val centered =
                centerTimelineItemAt(
                    messageIdHex,
                    requireNotNull(currentTimelineListIndex(messageIdHex)),
                    ConversationScrollReason.Search,
                )
            if (centered && navigationRequest.isCurrent()) {
                showTransientMessageHighlight(messageIdHex)
            }
        }
    }

    /** Loads and centers one indexed search result under a cancellable latest-wins request. */
    fun scrollToSearchMatch(match: ConversationSearchMatch) {
        val previousSearchJob = navigationState.searchJob
        previousSearchJob?.cancel()
        navigationState.navigateReplyJob?.cancel()
        navigationState.targetHighlight.clear()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.searchJob =
            scope.launch {
                previousSearchJob?.join()
                if (!navigationRequest.isCurrent()) return@launch
                val available = controller.loadSearchResultMessageAvailable(match)
                if (!available || !navigationRequest.isCurrent()) return@launch
                centerLoadedSearchMessage(match.messageIdHex, navigationRequest)
            }
    }

    // Group-details search can jump to a known id without exhaustive-search
    // timestamp metadata, so retain the bounded reply-navigation path.
    fun scrollToSearchMatch(messageIdHex: String) {
        val previousSearchJob = navigationState.searchJob
        previousSearchJob?.cancel()
        navigationState.navigateReplyJob?.cancel()
        navigationState.targetHighlight.clear()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.searchJob =
            scope.launch {
                previousSearchJob?.join()
                if (!navigationRequest.isCurrent()) return@launch
                val available = controller.loadUntilMessageAvailable(messageIdHex)
                if (!available || !navigationRequest.isCurrent()) return@launch
                centerLoadedSearchMessage(messageIdHex, navigationRequest)
            }
    }

    /** Opens in-conversation search and remembers the reading position its close returns to. */
    fun openSearch() {
        navigationState.openSearchSession(scrollCoordinator.bookmark(currentScrollAnchor()), controller.timeline)
    }

    /**
     * Closes in-conversation search, clears its query and pinned match, and returns the reader to where search
     * opened. A repeated close of the same session keeps that pending return.
     */
    fun closeSearch() {
        val expectedRestoreIntent = scrollCoordinator.intentToken
        navigationState.closeSearchSession(
            scope = scope,
            clearSearch = {
                presentationState.searchState.value =
                    GlobalSearchTransitions.closeSearch(presentationState.searchState.value)
            },
        ) { anchor, isCurrent ->
            // A deep search jump can evict the original viewport from the capped
            // window. Page back to its durable local message before asking the
            // coordinator to restore the logical anchor and exact offset.
            val match = anchor.match
            if (match != null &&
                pageBackForSearchReturn({ controller.isLoadingPage }, isCurrent) {
                    controller.loadSearchResultMessageAvailable(match)
                }
            ) {
                withFrameNanos { }
            }
            if (!isCurrent()) return@closeSearchSession
            // A saved message that could not be paged back stays the reader's search position, rather than
            // landing on whatever row now sits at its old list index.
            val saved = anchor.bookmark.anchor
            if (saved.messageId != null && resolveScrollAnchorIndex(saved) == null) return@closeSearchSession
            scrollCoordinator.restoreBookmark(
                anchor.bookmark,
                expectedIntent = expectedRestoreIntent,
                resolveAnchorIndex = { saved ->
                    resolveScrollAnchorIndex(saved)
                        ?: saved.listIndex.coerceIn(
                            0,
                            (timelineViewport.readingLayoutInfo().totalItemsCount - 1).coerceAtLeast(0),
                        )
                },
            )
        }
    }
}

/**
 * Remembers the navigator against every value its operations read, so a new instance appears exactly when the
 * former local functions of [ConversationScreen] would have captured a different value. The function arguments
 * read only [controller], [listState] and [timelineViewport], which are keys as well, and [tailIndex] is the
 * screen's remembered updated state, read when a landing returns to the newest edge.
 */
@Composable
@Suppress("LongParameterList")
internal fun rememberConversationMessageNavigator(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    scope: CoroutineScope,
    presentationState: ConversationSurfaceState,
    navigationState: ConversationNavigationState,
    listState: LazyListState,
    timelineViewport: ConversationTimelineViewport,
    scrollCoordinator: ConversationScrollCoordinator,
    conversationAccountRef: String?,
    tailIndex: State<Int>,
    currentTimelineListIndex: (String) -> Int?,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    resolveScrollAnchorIndex: (ConversationScrollAnchor) -> Int?,
): ConversationMessageNavigator =
    remember(
        appState,
        controller,
        scope,
        presentationState,
        navigationState,
        listState,
        timelineViewport,
        scrollCoordinator,
        conversationAccountRef,
        tailIndex,
    ) {
        ConversationMessageNavigator(
            appState = appState,
            controller = controller,
            scope = scope,
            presentationState = presentationState,
            navigationState = navigationState,
            scrollCoordinator = scrollCoordinator,
            timelineViewport = timelineViewport,
            conversationAccountRef = conversationAccountRef,
            tailIndex = tailIndex,
            currentTimelineListIndex = currentTimelineListIndex,
            currentScrollAnchor = currentScrollAnchor,
            resolveScrollAnchorIndex = resolveScrollAnchorIndex,
        )
    }
