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
import kotlinx.coroutines.flow.first
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
 * When the bounded window had earlier rows, the restored head reaches the list on a later debounced
 * projection, so the final snap waits for [firstRowId] to change rather than for a fixed frame count.
 * A user cancellation still consumes the request, so a stale follow never outlives its gesture.
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
    firstRowId: String?,
    hasEarlierRows: () -> Boolean,
    returnWindowToTop: suspend () -> Unit,
    onScrollStarted: () -> Unit,
    onConsumed: (ChatPinFollowRequest) -> Unit,
) {
    val liveDatasetKey by rememberUpdatedState(datasetKey)
    val liveGestureGeneration by rememberUpdatedState(userGestureGeneration)
    val liveUnpinnedIds by rememberUpdatedState(unpinnedVisibleIds)
    val liveMotionSettled by rememberUpdatedState(motionSettled)
    val liveFirstRowId by rememberUpdatedState(firstRowId)
    val liveHasEarlierRows by rememberUpdatedState(hasEarlierRows)
    val liveReturnWindowToTop by rememberUpdatedState(returnWindowToTop)
    val liveScrollStarted by rememberUpdatedState(onScrollStarted)
    val liveConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(listState, request) {
        val pending = request ?: return@LaunchedEffect

        fun step() =
            chatPinFollowStep(
                request = pending,
                datasetKey = liveDatasetKey,
                userGestureGeneration = liveGestureGeneration,
                unpinnedVisibleIds = liveUnpinnedIds,
                motionSettled = liveMotionSettled && !listState.isScrollInProgress,
            )
        try {
            if (snapshotFlow(::step).first { it != ChatPinFollowStep.WAIT } == ChatPinFollowStep.FOLLOW) {
                liveScrollStarted()
                if (listState.firstVisibleItemIndex > CHAT_PIN_FOLLOW_SNAP_INDEX) {
                    listState.scrollToItem(CHAT_PIN_FOLLOW_SNAP_INDEX)
                }
                listState.animateScrollToItem(0)
                val windowWasShifted = liveHasEarlierRows()
                val headBeforeReturn = liveFirstRowId
                liveReturnWindowToTop()
                if (windowWasShifted) {
                    // The restored head is published by the controller's debounced recompute, not on this frame.
                    withTimeoutOrNull(CHAT_PIN_FOLLOW_WINDOW_TIMEOUT_MILLIS) {
                        snapshotFlow { liveFirstRowId != headBeforeReturn || step() == ChatPinFollowStep.RETIRE }
                            .first { it }
                    }
                }
                // Let the list measure what it now holds before checking the final position.
                withFrameNanos { }
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if ((windowWasShifted || !atTop) && step() != ChatPinFollowStep.RETIRE) {
                    liveScrollStarted()
                    listState.scrollToItem(0)
                }
            }
        } finally {
            liveConsumed(pending)
        }
    }
}

/** Same near-top snap as the jump-to-top button, so a deep follow animates only the final rows. */
private const val CHAT_PIN_FOLLOW_SNAP_INDEX = 10

/** Upper bound on waiting for a restored head window to be published before snapping anyway. */
private const val CHAT_PIN_FOLLOW_WINDOW_TIMEOUT_MILLIS = 1_000L
