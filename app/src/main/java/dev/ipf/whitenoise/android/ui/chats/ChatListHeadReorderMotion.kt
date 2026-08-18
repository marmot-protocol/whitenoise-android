package dev.ipf.whitenoise.android.ui.chats

import android.os.SystemClock
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
    val accountRef: String? = null,
    val runtimeGeneration: Int = 0,
)

/** Stable domain-row coordinate captured from the current lazy-list viewport. */
internal data class ChatListViewportAnchor(
    val chatId: String,
    val scrollOffset: Int,
)

/** One user-requested departure of a pinned visible head. */
internal data class ChatListHeadDemotion(
    val chatId: String,
    val transactionId: Long,
    val viewportAnchor: ChatListViewportAnchor? = null,
    val viewportGeneration: Long = 0L,
)

private data class ChatListHeadMotionState(
    val activeHeadId: String?,
    val pinnedOrder: List<String>,
)

private data class ChatListHeadScrollSnapshot(
    val headId: String?,
    val pinnedOrder: List<String>,
    val firstVisibleItemIndex: Int,
    val isScrollInProgress: Boolean,
)

internal fun LazyListState.chatListViewportAnchor(visibleChatIds: Set<String>): ChatListViewportAnchor? =
    if (isScrollInProgress) {
        null
    } else {
        val layout = layoutInfo
        layout.visibleItemsInfo
            .firstOrNull { visible -> (visible.key as? String) in visibleChatIds }
            ?.let { item ->
                ChatListViewportAnchor(
                    chatId = item.key as String,
                    // Positive scroll offsets place the item above the viewport start;
                    // negative offsets preserve a row below a visible synthetic item.
                    scrollOffset = layout.viewportStartOffset - item.offset,
                )
            }
    }

@Composable
internal fun rememberChatListUserGestureGeneration(listState: LazyListState): Long {
    var generation by remember(listState) { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) generation += 1L
        }
    }
    return generation
}

@Composable
private fun chatListViewportAnchorEffect(
    listState: LazyListState,
    datasetKey: ChatListDatasetKey,
    userHeadDemotion: ChatListHeadDemotion?,
    userHeadDemotionSettled: Boolean,
    userHeadDemotionTargetIndex: Int?,
    viewportGeneration: Long,
    onConsumed: (ChatListHeadDemotion) -> Unit,
) {
    var consumedTransactionId by
        remember(listState, datasetKey) { mutableStateOf<Long?>(null) }
    val liveConsumedCallback by rememberUpdatedState(onConsumed)
    SideEffect {
        val demotion = userHeadDemotion?.takeIf { userHeadDemotionSettled }
        if (demotion != null && consumedTransactionId != demotion.transactionId) {
            val anchor = demotion.viewportAnchor
            val viewportAuthorityIsCurrent =
                anchor != null &&
                    demotion.viewportGeneration == viewportGeneration &&
                    !listState.isScrollInProgress
            if (viewportAuthorityIsCurrent) {
                userHeadDemotionTargetIndex?.let { targetIndex ->
                    listState.requestScrollToItem(
                        index = targetIndex,
                        scrollOffset = anchor.scrollOffset,
                    )
                }
            }
            consumedTransactionId = demotion.transactionId
            liveConsumedCallback(demotion)
        }
    }
}

private fun shouldCorrectHeadScroll(
    previous: ChatListHeadScrollSnapshot?,
    current: ChatListHeadScrollSnapshot,
    isActiveList: Boolean,
): Boolean =
    previous != null &&
        previous.pinnedOrder == current.pinnedOrder &&
        shouldSnapChatListForHeadReorder(
            previousHeadId = previous.headId,
            currentHeadId = current.headId,
            preReorderFirstVisibleItemIndex = previous.firstVisibleItemIndex,
            isScrollInProgress = previous.isScrollInProgress || current.isScrollInProgress,
            isActiveList = isActiveList,
        )

private suspend fun LazyListState.animateHeadScrollCorrection() {
    val gateStartedAtMs = SystemClock.uptimeMillis()
    try {
        animateScrollToItem(0)
    } finally {
        // Placement uses Compose's spring animation and can outlive the scroll
        // correction. Preserve the minimum input gate even if a newer scroll
        // mutation cancels this animation.
        withContext(NonCancellable) {
            val elapsedMs = SystemClock.uptimeMillis() - gateStartedAtMs
            val remainingMs = CHAT_LIST_HEAD_INPUT_GATE_MILLIS - elapsedMs
            if (remainingMs > 0L) delay(remainingMs)
        }
    }
}

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
    pinnedOrder: List<String> = emptyList(),
    datasetKey: ChatListDatasetKey,
    isActiveList: Boolean,
    userHeadDemotion: ChatListHeadDemotion? = null,
    userHeadDemotionSettled: Boolean = false,
    userHeadDemotionTargetIndex: Int? = null,
    viewportGeneration: Long = 0L,
    onUserHeadDemotionConsumed: (ChatListHeadDemotion) -> Unit = {},
    onHeadReorderInProgressChange: (Boolean) -> Unit = {},
) {
    chatListViewportAnchorEffect(
        listState = listState,
        datasetKey = datasetKey,
        userHeadDemotion = userHeadDemotion,
        userHeadDemotionSettled = userHeadDemotionSettled,
        userHeadDemotionTargetIndex = userHeadDemotionTargetIndex,
        viewportGeneration = viewportGeneration,
        onConsumed = onUserHeadDemotionConsumed,
    )
    val liveHeadMotionState by
        rememberUpdatedState(
            ChatListHeadMotionState(
                activeHeadId = activeHeadId,
                pinnedOrder = pinnedOrder,
            ),
        )
    val liveProgressCallback by rememberUpdatedState(onHeadReorderInProgressChange)
    // A filter replacement restarts this collector and clears its previous-head
    // snapshot. LazyColumn keeps any still-valid keyed scroll anchor; unlike an
    // incoming-message promotion, the replacement never launches scroll motion.
    LaunchedEffect(listState, datasetKey, isActiveList) {
        var activeCorrections = 0
        try {
            var previous: ChatListHeadScrollSnapshot? = null
            snapshotFlow {
                val headMotionState = liveHeadMotionState
                ChatListHeadScrollSnapshot(
                    headId = headMotionState.activeHeadId,
                    pinnedOrder = headMotionState.pinnedOrder,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    isScrollInProgress = listState.isScrollInProgress,
                )
            }.collect { current ->
                if (shouldCorrectHeadScroll(previous, current, isActiveList)) {
                    launch {
                        activeCorrections += 1
                        liveProgressCallback(true)
                        try {
                            listState.animateHeadScrollCorrection()
                        } finally {
                            // Cleanup itself must never suspend: otherwise a
                            // cancellation can strand all row actions off.
                            activeCorrections -= 1
                            if (activeCorrections == 0) liveProgressCallback(false)
                        }
                    }
                }
                previous = current
            }
        } finally {
            liveProgressCallback(false)
        }
    }
}

private const val CHAT_LIST_MEMBERSHIP_FADE_MILLIS = 120
internal const val CHAT_LIST_HEAD_INPUT_GATE_MILLIS = 500L
