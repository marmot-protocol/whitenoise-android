package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/**
 * Keeps read-aloud following the spoken message while this conversation owns the session, and routes the screen's
 * read-aloud events (a direct drag, a sentence seek, the resume button) into the follow [handle].
 *
 * An instance holds only values it is remembered on: the app state, the controller and the follow handle, which
 * itself is remembered on the group id. A callback that captured an instance therefore sees exactly the app state,
 * controller and handle it would have captured itself. The current TTS state and session ownership are read when an
 * event arrives, as the screen's callbacks read them.
 */
internal class ConversationReadAloudFollow(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    /** The follow handle; the screen's reading-bounds effect is keyed on it. */
    val handle: ConversationTtsFollowHandle,
) {
    /** Sentence layouts of the spoken message, for the rows to report into. */
    val sentenceLayouts: ConversationTtsSentenceLayoutRegistry
        get() = handle.sentenceLayouts

    /** Whether the reader has moved away from the spoken passage and can be offered a way back. */
    val showResumeAction: Boolean
        get() = handle.showResumeAction

    /** Stops following while the reader moves the transcript directly. */
    fun suspendForDirectDrag() {
        handle.suspendForDirectDrag(
            state = appState.ttsController.state.value,
            ownsSession = appState.ownsTtsAutoReadSession(controller.group.groupIdHex),
        )
    }

    /** Keeps a seek into a sentence from scrolling the transcript to the same sentence again. */
    fun onSentenceSeek(state: TtsState) {
        handle.onSentenceSeek(
            state = state,
            ownsSession = appState.ownsTtsAutoReadSession(controller.group.groupIdHex),
        )
    }

    /** Scrolls back to the passage being read. */
    fun revealCurrentPassage() {
        handle.revealCurrentPassage(
            appState.ttsController.state.value,
            appState.ownsTtsAutoReadSession(controller.group.groupIdHex),
        )
    }

    /**
     * Feeds the sentence registry the speech interval of the transcript. Speech rests sentences below the painted top
     * fade, unlike the drag and reaction consumers of the reading interval.
     *
     * Non-restartable like the screen-level effect it replaced, so it stays in the screen's scope.
     */
    @Composable
    @NonRestartableComposable
    fun speechBoundsEffect(timelineViewport: ConversationTimelineViewport) {
        LaunchedEffect(timelineViewport, handle) {
            snapshotFlow { timelineViewport.speechBoundsInWindow }.collect { bounds ->
                if (bounds != null) handle.sentenceLayouts.updateViewportBounds(bounds)
            }
        }
    }

    /**
     * Follows the spoken message in the transcript. A transport-body return ([ttsFocusSessionId] set) asks for an
     * explicit reveal on each focus request.
     *
     * Non-restartable like the screen-local function it replaced, so its reads stay in the screen's scope.
     */
    @Composable
    @NonRestartableComposable
    @Suppress("LongParameterList") // Explicit per-composition values, so a stale capture cannot hide in a provider.
    fun followEffects(
        listState: LazyListState,
        timelineViewport: ConversationTimelineViewport,
        scrollCoordinator: ConversationScrollCoordinator,
        initialTimelineAnchored: Boolean,
        renderedTimeline: List<TimelineMessage>,
        timelineItemHeightsPx: Map<String, Int>,
        currentTimelineListIndex: (String) -> Int?,
        currentScrollAnchor: () -> ConversationScrollAnchor,
        ttsFocusSessionId: Long?,
        focusMessageRequestId: Long,
    ) {
        ConversationTtsFollowEffects(
            appState = appState,
            controller = controller,
            listState = listState,
            timelineViewport = timelineViewport,
            scrollCoordinator = scrollCoordinator,
            handle = handle,
            initialTimelineAnchored = initialTimelineAnchored,
            renderedTimeline = renderedTimeline,
            timelineItemHeightsPx = timelineItemHeightsPx,
            currentTimelineListIndex = currentTimelineListIndex,
            currentScrollAnchor = currentScrollAnchor,
            explicitRevealRequestId =
                if (ttsFocusSessionId == null) {
                    0L
                } else {
                    focusMessageRequestId
                },
        )
    }
}

/**
 * Remembers [ConversationReadAloudFollow] on everything it holds. The follow handle keeps its own key, the group id,
 * so replacing the controller for the same group keeps the handle and its follow state, as before.
 */
@Composable
internal fun rememberConversationReadAloudFollow(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): ConversationReadAloudFollow {
    val handle = rememberConversationTtsFollowHandle(controller.group.groupIdHex)
    return remember(appState, controller, handle) {
        ConversationReadAloudFollow(appState = appState, controller = controller, handle = handle)
    }
}
