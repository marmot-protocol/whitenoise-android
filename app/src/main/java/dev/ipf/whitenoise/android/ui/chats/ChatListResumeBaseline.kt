package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Chat-list changes the user could not have watched (#3780).
 *
 * A stopped window keeps recomposing but pauses its animation clock, so changes
 * that arrive in the background would otherwise close the #1828 motion gates
 * and leave frozen motion to finish after resume. Every composition while the
 * host is stopped, and the first one after it starts again, is [active]: rows
 * take their new slots without motion and row input stays open on the first
 * resumed frame. [epoch] changes on each return so gates closed before the app
 * was hidden start fresh. One more composition follows the resumed one, so live
 * motion is re-armed from the snapped slots before any later change. Changes
 * composed after that keep the gates.
 */
@Immutable
internal data class ChatListResumeBaseline(
    val epoch: Int,
    val active: Boolean,
) {
    companion object {
        val None = ChatListResumeBaseline(epoch = 0, active = false)
    }
}

@Composable
internal fun rememberChatListResumeBaseline(): ChatListResumeBaseline {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state =
        remember(lifecycleOwner) {
            // An observer added while stopped only sees ON_CREATE replayed, so seed from the current state.
            val started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ChatListResumeBaselineState(stopped = !started)
        }
    DisposableEffect(lifecycleOwner, state) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> state.stopped = true
                    Lifecycle.Event.ON_START ->
                        if (state.stopped) {
                            state.stopped = false
                            state.resumeEpoch += 1
                        }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val resumeEpoch = state.resumeEpoch
    val baseline =
        ChatListResumeBaseline(
            epoch = resumeEpoch,
            active = state.stopped || resumeEpoch != state.composedEpoch,
        )
    // A state write, so the snapped composition is followed by one that restores motion.
    SideEffect { if (state.composedEpoch != resumeEpoch) state.composedEpoch = resumeEpoch }
    return baseline
}

private class ChatListResumeBaselineState(
    stopped: Boolean,
) {
    var stopped by mutableStateOf(stopped)
    var resumeEpoch by mutableIntStateOf(0)
    var composedEpoch by mutableIntStateOf(0)
}

/**
 * Places a head promoted while the user could not watch at the top before the
 * layout that shows it, so the first drawn frame is the one that takes a tap
 * (#3780). Live promotions keep the animated correction in
 * [ChatListActiveHeadScrollEffect], under the same top-of-list rules.
 */
@Composable
internal fun chatListResumeHeadSnap(
    listState: LazyListState,
    activeHeadId: String?,
    pinnedOrder: List<String>,
    datasetKey: ChatListDatasetKey,
    isActiveList: Boolean,
    resumeBaselineActive: Boolean,
) {
    val composed = remember(listState, datasetKey, isActiveList) { ChatListComposedHead() }
    SideEffect {
        val previousHeadId = composed.headId
        val previousPinnedOrder = composed.pinnedOrder
        composed.headId = activeHeadId
        composed.pinnedOrder = pinnedOrder
        // Runs after apply and before this frame's measure, so the list state
        // still describes the viewport the user last saw.
        if (resumeBaselineActive &&
            previousPinnedOrder == pinnedOrder &&
            shouldSnapChatListForHeadReorder(
                previousHeadId = previousHeadId,
                currentHeadId = activeHeadId,
                preReorderFirstVisibleItemIndex = listState.firstVisibleItemIndex,
                isScrollInProgress = listState.isScrollInProgress,
                isActiveList = isActiveList,
            )
        ) {
            listState.requestScrollToItem(0)
        }
    }
}

private class ChatListComposedHead {
    var headId: String? = null
    var pinnedOrder: List<String>? = null
}
