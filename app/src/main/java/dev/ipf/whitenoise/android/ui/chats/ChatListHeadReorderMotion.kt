package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier

/** Placement-only reorder animation for keyed chat rows (#1651). */
internal fun LazyItemScope.chatListHeadReorderPlacement(): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        fadeOutSpec = null,
    )

/**
 * Active on-list head promotion: pairs [chatListHeadReorderPlacement] with
 * animated scroll correction when [shouldSnapChatListForHeadReorder] fires.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListActiveHeadScrollEffect(
    listState: LazyListState,
    activeHeadId: String?,
    isActiveList: Boolean,
) {
    val liveActiveHeadId by rememberUpdatedState(activeHeadId)
    LaunchedEffect(listState, isActiveList) {
        data class HeadScrollSnapshot(
            val headId: String?,
            val firstVisibleItemIndex: Int,
            val isScrollInProgress: Boolean,
        )

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
                listState.animateScrollToItem(0)
            }
        }
    }
}
