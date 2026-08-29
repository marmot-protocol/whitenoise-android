package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

private class ChatListRowMotionHistory {
    var orderedRowIds: List<String>? = null
    var placementDurationMillis: Int = CHAT_LIST_ROW_PLACEMENT_MILLIS
}

/**
 * Keeps adjacent reorders quick while bounding the velocity of a visible row
 * promoted across several slots. Every row in one reorder receives the same
 * duration, so the promoted row and the displaced block settle together.
 */
@Composable
internal fun rememberChatListRowPlacementDurationMillis(
    orderedRowIds: List<String>,
    pinnedBoundaryIndex: Int?,
    datasetKey: ChatListDatasetKey,
    headPromotionEligible: Boolean,
): Int {
    val history =
        remember(datasetKey, pinnedBoundaryIndex) {
            ChatListRowMotionHistory()
        }
    val currentOrder = remember(orderedRowIds) { orderedRowIds.toList() }
    val previousOrder = history.orderedRowIds
    val durationMillis =
        if (previousOrder == currentOrder) {
            history.placementDurationMillis
        } else {
            chatListRowPlacementDurationMillis(
                previousOrder = previousOrder,
                currentOrder = currentOrder,
                pinnedBoundaryIndex = pinnedBoundaryIndex,
                headPromotionEligible = headPromotionEligible,
            )
        }

    SideEffect {
        history.orderedRowIds = currentOrder
        history.placementDurationMillis = durationMillis
    }
    return durationMillis
}

internal fun chatListRowPlacementDurationMillis(
    previousOrder: List<String>?,
    currentOrder: List<String>,
    pinnedBoundaryIndex: Int?,
    headPromotionEligible: Boolean,
): Int {
    if (!headPromotionEligible || pinnedBoundaryIndex != null || previousOrder == null) {
        return CHAT_LIST_ROW_PLACEMENT_MILLIS
    }
    val travelSlots = chatListHeadPromotionTravelSlots(previousOrder, currentOrder) ?: 1
    val extraSlots = (travelSlots - 1).coerceAtLeast(0)
    return (
        CHAT_LIST_ROW_PLACEMENT_MILLIS +
            extraSlots * CHAT_LIST_ROW_PLACEMENT_EXTRA_MILLIS_PER_SLOT
    ).coerceAtMost(CHAT_LIST_ROW_PLACEMENT_MAX_MILLIS)
}

private fun chatListHeadPromotionTravelSlots(
    previousOrder: List<String>,
    currentOrder: List<String>,
): Int? {
    if (previousOrder.size != currentOrder.size || currentOrder.isEmpty()) return null
    val promotedId = currentOrder.first()
    val previousIndex = previousOrder.indexOf(promotedId)
    val isSinglePromotion =
        previousIndex > 0 &&
            currentOrder.indices.all { targetIndex ->
                val expectedPreviousIndex =
                    when {
                        targetIndex == 0 -> previousIndex
                        targetIndex <= previousIndex -> targetIndex - 1
                        else -> targetIndex
                    }
                currentOrder[targetIndex] == previousOrder[expectedPreviousIndex]
            }
    return previousIndex.takeIf { isSinglePromotion }
}

private const val CHAT_LIST_ROW_PLACEMENT_EXTRA_MILLIS_PER_SLOT = 30
internal const val CHAT_LIST_ROW_PLACEMENT_MAX_MILLIS = 360
