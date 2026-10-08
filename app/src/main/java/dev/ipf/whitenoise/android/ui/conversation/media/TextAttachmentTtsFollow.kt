package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowPolicy
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowRequest
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsSentenceLayoutRegistry
import dev.ipf.whitenoise.android.ui.conversation.TtsFollowViewport
import dev.ipf.whitenoise.android.ui.conversation.TtsFollowViewportDecision
import dev.ipf.whitenoise.android.ui.conversation.ttsSentenceWasRevealed
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val READER_SENTENCE_LAYOUT_TIMEOUT_MS = 750L

/** Bounded follow attempt using the shared policy and complete-leaf registry, not an estimated text fraction. */
internal class TextAttachmentTtsFollow(
    private val policy: ConversationTtsFollowPolicy,
    private val layouts: ConversationTtsSentenceLayoutRegistry,
    private val scroll: ScrollState,
    private val playback: State<TextAttachmentPlayback?>,
) {
    /** True requests the policy's single retry after an incomplete sentence layout. */
    suspend fun reveal(viewport: Rect): Boolean {
        val request = policy.claimPendingRequest() ?: return false
        val bounds =
            withTimeoutOrNull(READER_SENTENCE_LAYOUT_TIMEOUT_MS) {
                snapshotFlow { layouts.completeSentenceBounds(request.target) }.filterNotNull().first()
            }
        return if (bounds == null) {
            policy.retryFailedFollowAttempt(request.target)
        } else {
            revealMeasuredSentence(request, viewport, bounds)
        }
    }

    private suspend fun revealMeasuredSentence(
        request: ConversationTtsFollowRequest,
        viewport: Rect,
        bounds: Rect,
    ): Boolean {
        if (!isCurrent(request)) return false
        val offset = textAttachmentFollowOffset(scroll.value, scroll.maxValue, viewport, bounds, request)
        if (offset != null) scroll.scrollTo(offset)
        withFrameNanos { }
        return if (!isCurrent(request)) {
            false
        } else if (
            ttsSentenceWasRevealed(layouts.completeSentenceBounds(request.target), layouts.viewportBoundsInWindow)
        ) {
            policy.onFollowSucceeded(request.target)
            false
        } else {
            policy.retryFailedFollowAttempt(request.target)
        }
    }

    private fun isCurrent(request: ConversationTtsFollowRequest): Boolean =
        policy.isCurrentTarget(request.target) && playback.value?.isCurrent?.invoke() == true
}

/** Converts shared window-space sentence anchoring into a bounded eager-scroll offset. */
internal fun textAttachmentFollowOffset(
    current: Int,
    maximum: Int,
    viewport: Rect,
    sentence: Rect,
    request: ConversationTtsFollowRequest,
): Int? =
    when (
        val decision =
            TtsFollowViewport.decide(
                viewport.top.roundToInt(),
                viewport.bottom.roundToInt(),
                0,
                sentence.top.roundToInt(),
                sentence.bottom.roundToInt(),
                request.direction,
                request.anchorAtTop,
            )
    ) {
        TtsFollowViewportDecision.Stay -> null
        is TtsFollowViewportDecision.ScrollToItemOffset ->
            (current.toLong() + decision.offset).coerceIn(0, maximum.coerceAtLeast(0).toLong()).toInt()
    }
