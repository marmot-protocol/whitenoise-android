package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isLoadingPage
import dev.ipf.whitenoise.android.state.loadUntilMessageAvailable
import dev.ipf.whitenoise.android.state.tracedPagingSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Visits the oldest unread mention from the jump chip: finds it (searching past the loaded window when the entry
 * count says one exists), positions the transcript at its reading start, marks it read and highlights it.
 *
 * An instance holds the owners it is remembered on, plus functions that read only the controller, the list state,
 * the viewport and the navigation state, which are keys as well. Everything the screen recomputes per composition
 * comes from [latest], read once per jump, so a jump keeps one consistent set of values for as long as it runs.
 */
@Suppress("LongParameterList") // Explicit screen dependencies, so a stale capture cannot hide in a provider.
internal class ConversationUnreadMentionJump(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    private val scope: CoroutineScope,
    private val navigationState: ConversationNavigationState,
    private val scrollCoordinator: ConversationScrollCoordinator,
    private val timelineViewport: ConversationTimelineViewport,
    private val mentionSeed: ConversationMentionJumpSeed,
    private val currentTimelineListIndex: (String) -> Int?,
    private val currentScrollAnchor: () -> ConversationScrollAnchor,
    private val mentionJumpLayout: (String, Int) -> ConversationMentionJumpLayout,
    private val latest: State<Inputs>,
) {
    /**
     * The values one composition of the screen computed for the jump. A plain class, compared by identity like the
     * closures it replaced. State is read through the members when the jump runs, never while composing.
     */
    internal class Inputs(
        val entryFirstUnreadMessageId: String?,
        val mentionsSelf: (TimelineMessage) -> Boolean,
        val messageNavigator: ConversationMessageNavigator,
        val unreadMentionMessageIds: () -> List<String>,
        val initialTimelineAnchored: () -> Boolean,
        val readAnchor: MutableState<String?>,
    )

    /**
     * The oldest unread mention, waiting for entry positioning and searching past the window if needed. Null when the
     * request was superseded or nothing was found; a search that ran out of history retires the seed.
     */
    private suspend fun nextUnreadMentionTarget(
        inputs: Inputs,
        isCurrent: () -> Boolean,
    ): String? {
        var readAnchorMessageId by inputs.readAnchor
        val search =
            mentionSeed.jumpTarget(
                awaitAnchored = { snapshotFlow { inputs.initialTimelineAnchored() }.first { it } },
                loadedTarget = { inputs.unreadMentionMessageIds().firstOrNull() },
            ) {
                resolveFirstUnreadMention(
                    readAnchorMessageId = readAnchorMessageId,
                    firstUnreadMessageId = inputs.entryFirstUnreadMessageId,
                    timeline = { controller.timeline },
                    mentionsActiveAccount = inputs.mentionsSelf,
                    loadMessage = { controller.loadUntilMessageAvailable(it) },
                    hasMoreAfter = { controller.hasMoreAfterTimeline },
                    loadNewer = {
                        snapshotFlow { controller.isLoadingPage }.first { !it }
                        isCurrent() && controller.loadNewerTimelinePage()
                    },
                    isCurrent = isCurrent,
                )
            }
        if (!isCurrent()) return null
        if (search !is ConversationMentionSearch.Found) {
            if (search == ConversationMentionSearch.Exhausted) mentionSeed.retire()
            appState.present(R.string.toast_original_message_unavailable)
        }
        return (search as? ConversationMentionSearch.Found)?.messageId
    }

    /** Visits the oldest unread mention, marks it read and highlights it; a later navigation supersedes it. */
    fun jumpToNextUnreadMention() {
        val inputs = latest.value
        var readAnchorMessageId by inputs.readAnchor
        if (inputs.unreadMentionMessageIds().isEmpty() && !mentionSeed.active) return
        navigationState.searchJob?.cancel()
        navigationState.targetHighlight.clear()
        navigationState.navigateReplyJob?.cancel()
        val navigationRequest = navigationState.targetNavigation.begin()
        navigationState.navigateReplyJob =
            scope.launch {
                tracedPagingSection(ConversationMentionJumpTrace.TOTAL) {
                    val targetMessageId =
                        nextUnreadMentionTarget(inputs) { navigationRequest.isCurrent() } ?: return@launch
                    val available =
                        tracedPagingSection(ConversationMentionJumpTrace.AVAILABILITY) {
                            controller.loadUntilMessageAvailable(targetMessageId)
                        }
                    if (!navigationRequest.isCurrent()) return@launch
                    if (!available) {
                        appState.present(R.string.toast_original_message_unavailable)
                        return@launch
                    }
                    val timelineIndex =
                        controller.timeline
                            .filterNot { MessageProjector.isEdit(it.record) }
                            .indexOfFirst { it.record.messageIdHex == targetMessageId }
                    if (timelineIndex < 0) {
                        appState.present(R.string.toast_original_message_unavailable)
                        return@launch
                    }
                    if (!navigationRequest.isCurrent()) return@launch
                    val reached =
                        scrollCoordinator.jumpToMentionReadingStart(
                            targetMessageId = targetMessageId,
                            resolveTargetIndex = { currentTimelineListIndex(targetMessageId) },
                            readLayout = { index -> mentionJumpLayout(targetMessageId, index) },
                            onCompleted = {
                                if (navigationRequest.isCurrent()) {
                                    scrollCoordinator.settleLandingAt(
                                        currentScrollAnchor(),
                                        conversationLandingAtNewestEdge(
                                            atNaturalTail = timelineViewport.isAtNaturalTail,
                                            hasMoreAfter = controller.hasMoreAfterTimeline,
                                        ),
                                    )
                                }
                            },
                        )
                    if (!reached || !navigationRequest.isCurrent()) return@launch
                    // Keep the landing point on the same process-track family as the async approach.
                    tracedPagingSection(ConversationMentionJumpTrace.LANDED) { Unit }
                    // Mark read up to the visited mention so the count — and the
                    // chat-list @-badge — decrement in step; advance the local read
                    // anchor so the chip's derived count updates immediately.
                    readAnchorMessageId = targetMessageId
                    mentionSeed.visited(targetMessageId)
                    controller.markReadUpTo(targetMessageId)
                    tracedPagingSection(ConversationMentionJumpTrace.HIGHLIGHT) {
                        inputs.messageNavigator.showTransientMessageHighlight(targetMessageId)
                    }
                }
            }
    }
}

/** Remembers the jump on every owner it captures; [inputs] is refreshed each composition. */
@Composable
@Suppress("LongParameterList") // One parameter per owner the jump captures, so a stale capture cannot hide.
internal fun rememberConversationUnreadMentionJump(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    scope: CoroutineScope,
    navigationState: ConversationNavigationState,
    scrollCoordinator: ConversationScrollCoordinator,
    timelineViewport: ConversationTimelineViewport,
    mentionSeed: ConversationMentionJumpSeed,
    currentTimelineListIndex: (String) -> Int?,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    mentionJumpLayout: (String, Int) -> ConversationMentionJumpLayout,
    inputs: ConversationUnreadMentionJump.Inputs,
): ConversationUnreadMentionJump {
    val latest =
        remember(appState, controller, scope, navigationState, scrollCoordinator, timelineViewport, mentionSeed) {
            mutableStateOf(inputs)
        }
    latest.value = inputs
    return remember(appState, controller, scope, navigationState, scrollCoordinator, timelineViewport, mentionSeed) {
        ConversationUnreadMentionJump(
            appState = appState,
            controller = controller,
            scope = scope,
            navigationState = navigationState,
            scrollCoordinator = scrollCoordinator,
            timelineViewport = timelineViewport,
            mentionSeed = mentionSeed,
            currentTimelineListIndex = currentTimelineListIndex,
            currentScrollAnchor = currentScrollAnchor,
            mentionJumpLayout = mentionJumpLayout,
            latest = latest,
        )
    }
}
