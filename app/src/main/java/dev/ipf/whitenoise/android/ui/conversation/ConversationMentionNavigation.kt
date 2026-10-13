package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos

/**
 * Whether a finished navigation landing rests at the real newest edge. It takes the native newest-edge clamp, not
 * the tolerant near-bottom reading used for gestures, because a landing can reach its reading start while part of
 * the tail is still clipped and must keep that position. The last loaded row of a window that still has newer
 * pages is not the edge either, so tail following and the dropped leave snapshot wait until they load.
 */
internal fun conversationLandingAtNewestEdge(
    atNaturalTail: Boolean,
    hasMoreAfter: Boolean,
): Boolean = atNaturalTail && !hasMoreAfter

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
