package dev.ipf.whitenoise.android.ui.chats

import android.os.SystemClock
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Closes row input in the first composition that publishes a new active head.
 *
 * [ChatListActiveHeadScrollEffect] cannot provide that first-frame guarantee:
 * effects start only after composition, when keyed rows may already be moving.
 * A dataset replacement establishes a fresh baseline instead of treating its
 * first head as a live promotion. Re-keying by [activeHeadId] creates a fresh
 * gate even when rapid activity returns to an earlier head before settling.
 */
@Composable
internal fun rememberChatListHeadReorderGate(
    activeHeadId: String?,
    datasetKey: ChatListDatasetKey,
    isActiveList: Boolean,
    scrollCorrectionInProgress: Boolean,
): Boolean {
    var datasetCompositionEstablished by
        remember(datasetKey, isActiveList) { mutableStateOf(false) }
    var synchronousGateInProgress by
        remember(datasetKey, isActiveList, activeHeadId) {
            mutableStateOf(
                datasetCompositionEstablished &&
                    isActiveList &&
                    activeHeadId != null,
            )
        }

    SideEffect { datasetCompositionEstablished = true }
    LaunchedEffect(datasetKey, isActiveList, activeHeadId) {
        if (!synchronousGateInProgress) return@LaunchedEffect
        delay(CHAT_LIST_HEAD_INPUT_GATE_MILLIS)
        synchronousGateInProgress = false
    }

    return synchronousGateInProgress || scrollCorrectionInProgress
}

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
                        val gateStartedAtMs = SystemClock.uptimeMillis()
                        try {
                            listState.animateScrollToItem(0)
                        } finally {
                            try {
                                // Placement uses Compose's spring animation and
                                // can outlive the scroll correction by a few
                                // frames. Preserve the minimum gate even when a
                                // newer scroll mutation cancels this animation.
                                withContext(NonCancellable) {
                                    val elapsedMs = SystemClock.uptimeMillis() - gateStartedAtMs
                                    val remainingMs = CHAT_LIST_HEAD_INPUT_GATE_MILLIS - elapsedMs
                                    if (remainingMs > 0L) delay(remainingMs)
                                }
                            } finally {
                                // Cleanup itself must never suspend: otherwise a
                                // cancellation can strand all row actions off.
                                activeCorrections -= 1
                                if (activeCorrections == 0) liveProgressCallback(false)
                            }
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
internal const val CHAT_LIST_HEAD_INPUT_GATE_MILLIS = 500L
