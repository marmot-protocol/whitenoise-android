package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keyed chat-row motion for head reorders and folder membership changes.
 *
 * The target list order is also the paint order: rows moving toward an earlier
 * slot stay above later rows while paths cross. Short membership fades keep
 * inserted/removed rows from flashing through shared rows; disappearing lazy
 * items are drawn below retained items by Compose.
 */
internal fun LazyItemScope.chatListRowMotion(targetIndex: Int): Modifier =
    Modifier
        .animateItem(
            fadeInSpec = tween(CHAT_LIST_MEMBERSHIP_FADE_MILLIS),
            fadeOutSpec = tween(CHAT_LIST_MEMBERSHIP_FADE_MILLIS),
        ).zIndex(chatListTargetZIndex(targetIndex))

internal fun chatListTargetZIndex(targetIndex: Int): Float = -targetIndex.toFloat()

internal data class ChatListDatasetKey(
    val showArchived: Boolean,
    val folderId: String?,
    val query: String,
)

/**
 * Active on-list head promotion: pairs [chatListRowMotion] with
 * animated scroll correction when [shouldSnapChatListForHeadReorder] fires.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListActiveHeadScrollEffect(
    listState: LazyListState,
    activeHeadId: String?,
    datasetKey: ChatListDatasetKey,
    isActiveList: Boolean,
    onHeadReorderInProgressChange: (Boolean) -> Unit = {},
) {
    val liveActiveHeadId by rememberUpdatedState(activeHeadId)
    val liveProgressCallback by rememberUpdatedState(onHeadReorderInProgressChange)
    // A filter replacement restarts this collector and clears its previous-head
    // snapshot. LazyColumn keeps any still-valid keyed scroll anchor; unlike an
    // incoming-message promotion, the replacement never launches scroll motion.
    LaunchedEffect(listState, datasetKey, isActiveList) {
        data class HeadScrollSnapshot(
            val headId: String?,
            val firstVisibleItemIndex: Int,
            val isScrollInProgress: Boolean,
        )

        var activeCorrections = 0
        try {
            var previous: HeadScrollSnapshot? = null
            snapshotFlow {
                HeadScrollSnapshot(
                    headId = liveActiveHeadId,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    isScrollInProgress = listState.isScrollInProgress,
                )
            }.collect { current ->
                val before = previous
                previous = current
                if (
                    before != null &&
                    shouldSnapChatListForHeadReorder(
                        previousHeadId = before.headId,
                        currentHeadId = current.headId,
                        preReorderFirstVisibleItemIndex = before.firstVisibleItemIndex,
                        isScrollInProgress = before.isScrollInProgress || current.isScrollInProgress,
                        isActiveList = isActiveList,
                    )
                ) {
                    launch {
                        activeCorrections += 1
                        liveProgressCallback(true)
                        val minimumInputGate = launch { delay(CHAT_LIST_HEAD_INPUT_GATE_MILLIS) }
                        try {
                            listState.animateScrollToItem(0)
                        } finally {
                            // Placement uses Compose's spring animation and can
                            // outlive the scroll correction by a few frames.
                            // Keep row actions off until that crossing window is
                            // over; the LazyColumn itself remains scrollable.
                            minimumInputGate.join()
                            activeCorrections -= 1
                            if (activeCorrections == 0) liveProgressCallback(false)
                        }
                    }
                }
            }
        } finally {
            liveProgressCallback(false)
        }
    }
}

private const val CHAT_LIST_MEMBERSHIP_FADE_MILLIS = 120
private const val CHAT_LIST_HEAD_INPUT_GATE_MILLIS = 500L
