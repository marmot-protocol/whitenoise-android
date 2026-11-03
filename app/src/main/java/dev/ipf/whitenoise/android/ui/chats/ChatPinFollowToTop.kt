package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.state.ChatListBatchCommand
import dev.ipf.whitenoise.android.state.ChatListBatchOutcome
import dev.ipf.whitenoise.android.state.ChatListBatchResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One confirmed explicit pin's request to show the pinned block from the absolute list top (#3555).
 *
 * The request is presentation-owned: it names the dataset and the user-drag generation it was issued
 * for, so a folder/query/account change or a later drag retires it instead of moving another viewport.
 */
internal data class ChatPinFollowRequest(
    val requestId: Long,
    val chatIds: Set<String>,
    val datasetKey: ChatListDatasetKey,
    val userGestureGeneration: Long,
)

/** The list dataset and drag generation an explicit pin started from. */
internal data class ChatPinFollowScope(
    val datasetKey: ChatListDatasetKey,
    val userGestureGeneration: Long,
)

/**
 * Holds at most one pending follow for one controller/account/runtime lifetime. The screen refreshes
 * [currentScope] after each composition; an action captures it when the user acts, so a pin that
 * commits after the reader changed folder, query or account cannot move the replacement viewport.
 */
@Stable
internal class ChatPinFollowOwner {
    private var nextRequestId = 0L

    /** The committed viewport scope, captured by a pin when the user starts it. */
    var currentScope: ChatPinFollowScope? = null

    /** The one follow still waiting for its projection, or null. */
    var request by mutableStateOf<ChatPinFollowRequest?>(null)
        private set

    /** Records confirmed pins for [scope]; a later confirmation replaces an earlier unconsumed one. */
    fun issue(
        scope: ChatPinFollowScope?,
        chatIds: Set<String>,
    ) {
        if (scope == null || chatIds.isEmpty()) return
        nextRequestId += 1L
        request =
            ChatPinFollowRequest(
                requestId = nextRequestId,
                chatIds = chatIds.mapTo(mutableSetOf(), ::chatPinFollowId),
                datasetKey = scope.datasetKey,
                userGestureGeneration = scope.userGestureGeneration,
            )
    }

    /** Clears [consumed] unless a newer confirmation already replaced it. */
    fun consume(consumed: ChatPinFollowRequest) {
        if (request == consumed) request = null
    }
}

/** Batch outcomes are keyed by lowercase group id; rows may carry either case. */
internal fun chatPinFollowId(id: String): String = id.lowercase(java.util.Locale.ROOT)

/**
 * Reads, from snapshot state, whether the head window one return installed has reached the list. A
 * backward page published during the return can change the rows first, so row changes alone prove nothing.
 */
internal fun interface ChatPinFollowHeadPresented {
    /** True once the installed head frame, or a newer one, is what the list shows. */
    fun presented(): Boolean
}

/** What a pending follow request should do with the current presentation. */
internal enum class ChatPinFollowStep { WAIT, FOLLOW, RETIRE }

/**
 * Pins this batch attempt confirmed that [previous] had not, so a Check of an earlier attempt or an
 * identical redelivered result never replays the follow. Only a PIN batch's CHANGED outcomes count:
 * failed, unconfirmed, unstarted and already-pinned targets never invent a successful pin.
 */
internal fun newlyConfirmedPins(
    command: ChatListBatchCommand,
    previous: ChatListBatchResult?,
    attempt: ChatListBatchResult?,
): Set<String> {
    if (command != ChatListBatchCommand.PIN || attempt == null) return emptySet()
    val alreadyConfirmed =
        previous
            ?.outcomes
            ?.filterValues { it == ChatListBatchOutcome.CHANGED }
            ?.keys
            .orEmpty()
    return attempt.outcomes
        .filterValues { it == ChatListBatchOutcome.CHANGED }
        .keys
        .minus(alreadyConfirmed)
}

/**
 * Retires a request once its dataset or drag generation is gone, waits while a followed chat still
 * shows as unpinned or other list motion owns the viewport, and follows otherwise. A chat the current
 * filter excludes does not hold the request: the list still returns to its own top.
 */
internal fun chatPinFollowStep(
    request: ChatPinFollowRequest,
    datasetKey: ChatListDatasetKey,
    userGestureGeneration: Long,
    unpinnedVisibleIds: Set<String>,
    motionSettled: Boolean,
): ChatPinFollowStep =
    when {
        request.datasetKey != datasetKey || request.userGestureGeneration != userGestureGeneration ->
            ChatPinFollowStep.RETIRE
        request.chatIds.any { chatPinFollowId(it) in unpinnedVisibleIds } || !motionSettled -> ChatPinFollowStep.WAIT
        else -> ChatPinFollowStep.FOLLOW
    }

/**
 * Consumes [request] once: after the projection shows the pinned rows and head motion settles, runs
 * the jump-to-top button's bounded scroll and window return, then finishes at item zero, offset zero.
 * Motion uses the list's animated scroll, so the system animation scale applies as it does for the
 * button. [onScrollStarted] marks each programmatic move for the head-motion owner.
 *
 * When the bounded window had earlier rows, [returnWindowToTop] reports the frame to wait for — the head
 * frame it installed, or the live window's current frame when a newer one already won or MDK answered stale —
 * and the follow waits until that frame is presented, bounded by a timeout. While the presented window still
 * has earlier rows, the return is retried a bounded number of times, so a stale answer cannot leave the snap
 * on a shifted window's first row. A null report means no live window remains, so the follow settles on what
 * is shown.
 *
 * A dataset or drag change retires the request at any point, including mid-animation or mid-return: the
 * follow job is cancelled at once, so an old pin never scrolls or returns the window of the list that
 * replaced its own. A retired or cancelled follow still consumes the request.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // Effect composable; one request plus the live gates it reads.
internal fun ChatPinFollowToTopEffect(
    listState: LazyListState,
    request: ChatPinFollowRequest?,
    datasetKey: ChatListDatasetKey,
    userGestureGeneration: Long,
    unpinnedVisibleIds: Set<String>,
    motionSettled: Boolean,
    hasEarlierRows: () -> Boolean,
    returnWindowToTop: suspend () -> ChatPinFollowHeadPresented?,
    onScrollStarted: () -> Unit,
    onConsumed: (ChatPinFollowRequest) -> Unit,
) {
    val liveDatasetKey by rememberUpdatedState(datasetKey)
    val liveGestureGeneration by rememberUpdatedState(userGestureGeneration)
    val liveUnpinnedIds by rememberUpdatedState(unpinnedVisibleIds)
    val liveMotionSettled by rememberUpdatedState(motionSettled)
    val liveHasEarlierRows by rememberUpdatedState(hasEarlierRows)
    val liveReturnWindowToTop by rememberUpdatedState(returnWindowToTop)
    val liveScrollStarted by rememberUpdatedState(onScrollStarted)
    val liveConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(listState, request) {
        val pending = request ?: return@LaunchedEffect

        /** This request's step against the live dataset, drag generation, pinned rows and list motion. */
        fun step() =
            chatPinFollowStep(
                request = pending,
                datasetKey = liveDatasetKey,
                userGestureGeneration = liveGestureGeneration,
                unpinnedVisibleIds = liveUnpinnedIds,
                motionSettled = liveMotionSettled && !listState.isScrollInProgress,
            )

        /** True once the request's dataset or drag generation is gone. */
        fun retired() = step() == ChatPinFollowStep.RETIRE
        try {
            if (snapshotFlow(::step).first { it != ChatPinFollowStep.WAIT } == ChatPinFollowStep.FOLLOW) {
                coroutineScope {
                    val follow =
                        launch {
                            followPinnedBlockToTop(
                                listState = listState,
                                retired = ::retired,
                                hasEarlierRows = { liveHasEarlierRows() },
                                returnWindowToTop = { liveReturnWindowToTop() },
                                onScrollStarted = { liveScrollStarted() },
                            )
                        }
                    // The list keeps its state across folder and query changes, so the follow must stop the moment
                    // its dataset or drag generation goes, not only at its next check.
                    val retirement = launch { snapshotFlow(::retired).first { it }.also { follow.cancel() } }
                    follow.join()
                    retirement.cancel()
                }
            }
        } finally {
            liveConsumed(pending)
        }
    }
}

/**
 * Scrolls [listState] to the top, returns a shifted window to its head — retrying while a stale or racing answer
 * leaves earlier rows presented — and snaps to item zero, offset zero unless the request [retired] meanwhile.
 */
private suspend fun followPinnedBlockToTop(
    listState: LazyListState,
    retired: () -> Boolean,
    hasEarlierRows: () -> Boolean,
    returnWindowToTop: suspend () -> ChatPinFollowHeadPresented?,
    onScrollStarted: () -> Unit,
) {
    onScrollStarted()
    if (listState.firstVisibleItemIndex > CHAT_PIN_FOLLOW_SNAP_INDEX) {
        listState.scrollToItem(CHAT_PIN_FOLLOW_SNAP_INDEX)
    }
    listState.animateScrollToItem(0)
    val windowWasShifted = hasEarlierRows()
    var shifted = windowWasShifted
    var attempts = 0
    while (shifted && attempts < CHAT_PIN_FOLLOW_RETURN_ATTEMPTS && !retired()) {
        attempts += 1
        val head = returnWindowToTop() ?: break
        // The restored head is published by the controller's debounced recompute, not on this frame.
        withTimeoutOrNull(CHAT_PIN_FOLLOW_WINDOW_TIMEOUT_MILLIS) {
            snapshotFlow { head.presented() || retired() }.first { it }
        }
        shifted = hasEarlierRows()
    }
    // Let the list measure what it now holds before checking the final position.
    withFrameNanos { }
    val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    if ((windowWasShifted || !atTop) && !retired()) {
        onScrollStarted()
        listState.scrollToItem(0)
    }
}

/** Same near-top snap as the jump-to-top button, so a deep follow animates only the final rows. */
private const val CHAT_PIN_FOLLOW_SNAP_INDEX = 10

/** Returns tried while a stale or racing answer leaves the presented window with earlier rows. */
private const val CHAT_PIN_FOLLOW_RETURN_ATTEMPTS = 3

/** Upper bound on waiting for a restored head window to be published before snapping anyway. */
private const val CHAT_PIN_FOLLOW_WINDOW_TIMEOUT_MILLIS = 1_000L
