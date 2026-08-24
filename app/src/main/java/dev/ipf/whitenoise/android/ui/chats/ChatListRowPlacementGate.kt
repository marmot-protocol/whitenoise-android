package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        delay(CHAT_LIST_ROW_PLACEMENT_MILLIS.toLong())
        placementInProgress = false
    }
    return placementInProgress
}
