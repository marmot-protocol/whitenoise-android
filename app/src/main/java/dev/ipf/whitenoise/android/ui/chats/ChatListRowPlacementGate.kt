package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * Closes row actions for every bounded placement transition, not only a head
 * promotion. Folder/search membership, pin order, archive changes, and
 * visible-boundary inserts can all move a keyed row while keeping the same
 * head. Using the exact tween duration makes the input boundary coincide with
 * settled visual geometry instead of guessing how long a spring may run
 * (#1828).
 */
@Composable
internal fun rememberChatListRowPlacementGate(
    orderedRowIds: List<String>,
    pinnedBoundaryIndex: Int?,
    leadingItemCount: Int,
    placementDurationMillis: Int = CHAT_LIST_ROW_PLACEMENT_MILLIS,
): Boolean {
    var firstLayoutPublished by remember { mutableStateOf(false) }
    val layoutKey =
        remember(orderedRowIds, pinnedBoundaryIndex, leadingItemCount) {
            Triple(orderedRowIds, pinnedBoundaryIndex, leadingItemCount)
        }
    var placementInProgress by
        remember(layoutKey) {
            mutableStateOf(firstLayoutPublished && orderedRowIds.isNotEmpty())
        }

    SideEffect { firstLayoutPublished = true }
    LaunchedEffect(layoutKey) {
        if (!placementInProgress) return@LaunchedEffect
        delay(placementDurationMillis.toLong())
        placementInProgress = false
    }
    return placementInProgress
}

/**
 * Cancels a pointer sequence if the chat-list input gate closes at any point
 * during that sequence. The generation survives a close/reopen between two
 * pointer events, so releasing an old press after placement settles cannot
 * activate a row at its new location.
 *
 * This is one detector on the list container (not one per row). In the steady
 * state it performs only a generation comparison and does not consume events.
 */
@Composable
internal fun Modifier.cancelPointersAcrossChatListMotion(interactionsEnabled: Boolean): Modifier {
    val gateState = remember { ChatListPointerGateState(interactionsEnabled) }
    SideEffect { gateState.update(interactionsEnabled) }
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startedGeneration = gateState.closureGeneration
            var cancelled = !gateState.interactionsEnabled
            if (cancelled) down.consume()
            var pointerPressed = true
            while (pointerPressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                cancelled =
                    cancelled ||
                    !gateState.interactionsEnabled ||
                    gateState.closureGeneration != startedGeneration
                if (cancelled) event.changes.forEach { it.consume() }
                pointerPressed = event.changes.any { it.pressed }
            }
        }
    }
}

private class ChatListPointerGateState(
    var interactionsEnabled: Boolean,
) {
    var closureGeneration: Long = 0L
        private set

    fun update(enabled: Boolean) {
        if (interactionsEnabled && !enabled) closureGeneration += 1L
        interactionsEnabled = enabled
    }
}
