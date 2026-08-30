package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Conversation-owned arbitration between the row gesture and transcript scroll.
 *
 * A single finger remains entirely owned by [LazyListState]. When the row sees
 * the second contact, [claim] snapshots the list's position at that recognition
 * point, cancels the in-progress drag, and holds the scroll mutex so neither an
 * inherited delta nor a fling can move the touched message. [release] reapplies
 * the exact anchor before returning scrolling to the transcript.
 */
@Stable
internal class TtsQuickTransportViewportLock(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    private var anchor: ViewportAnchor? = null
    private var holdJob: Job? = null

    /** Freezes the current viewport once for the active two-finger pointer sequence. */
    fun claim() {
        if (anchor != null) return
        val captured =
            ViewportAnchor(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
            )
        anchor = captured
        listState.requestScrollToItem(captured.index, captured.offset)
        holdJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                listState.scroll(MutatePriority.PreventUserInput) {
                    awaitCancellation()
                }
            }
    }

    /** Restores the recognition anchor and releases the transcript without residual velocity. */
    fun release() {
        val captured = anchor ?: return
        listState.requestScrollToItem(captured.index, captured.offset)
        anchor = null
        holdJob?.cancel()
        holdJob = null
    }

    private data class ViewportAnchor(
        val index: Int,
        val offset: Int,
    )
}

/** Remembers one viewport lock for the lifetime of the conversation's [LazyListState]. */
@Composable
internal fun rememberTtsQuickTransportViewportLock(listState: LazyListState): TtsQuickTransportViewportLock {
    val scope = rememberCoroutineScope()
    val lock = remember(listState, scope) { TtsQuickTransportViewportLock(listState, scope) }
    DisposableEffect(lock) {
        onDispose { lock.release() }
    }
    return lock
}
