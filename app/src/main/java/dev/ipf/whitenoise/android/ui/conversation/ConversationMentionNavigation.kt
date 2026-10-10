package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.withFrameNanos

/** Includes measured newer rows, structural rows and spacing, even beneath composer occlusion. */
internal fun LazyListLayoutInfo.mentionTailContentHeightPx(index: Int): Int? {
    val origin = visibleItemsInfo.firstOrNull { it.index == 0 }
    val target = visibleItemsInfo.firstOrNull { it.index == index }
    return if (origin != null && target != null) {
        (target.offset + target.size - origin.offset).coerceAtLeast(1)
    } else {
        null
    }
}

/**
 * Keeps the approach and measured correction in one latest-wins, distance-bounded command. This thin
 * wrapper binds the shared [settleReadingStart] core to the mention reason and its animated approach.
 */
internal suspend fun ConversationScrollCoordinator.jumpToMentionReadingStart(
    targetMessageId: String,
    resolveTargetIndex: () -> Int?,
    readLayout: (Int) -> ConversationMentionJumpLayout,
    awaitLayout: suspend () -> Unit = { withFrameNanos { } },
    onCompleted: () -> Unit = {},
): Boolean {
    val result =
        settleReadingStart(
            targetMessageId = targetMessageId,
            reason = ConversationScrollReason.Mention,
            probe = ConversationReadingStartProbe(resolveTargetIndex, readLayout, awaitLayout),
            approach = ConversationReadingStartApproach.Animated,
        )
    if (result.commandCompleted) onCompleted()
    return result.reached
}
