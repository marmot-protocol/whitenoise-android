package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos

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
