package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos

/**
 * Whether a finished navigation landing rests at the real newest edge. The last loaded row of a window that still
 * has newer pages is not that edge, so tail following and the dropped leave snapshot must wait until they load.
 */
internal fun conversationLandingAtNewestEdge(
    nearBottom: Boolean,
    hasMoreAfter: Boolean,
): Boolean = nearBottom && !hasMoreAfter

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
            probe =
                ConversationReadingStartProbe(
                    resolveTargetIndex,
                    readLayout,
                    awaitLayout,
                ),
            approach = ConversationReadingStartApproach.Animated,
        )
    if (result.commandCompleted) onCompleted()
    return result.reached
}
