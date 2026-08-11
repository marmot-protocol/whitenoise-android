package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.audio.tts.TtsState
import kotlin.math.roundToInt

/** Stable sentence identity used to de-duplicate word-level TTS callbacks. */
internal data class ConversationTtsFollowTarget(
    val sessionId: Long,
    val messageIdHex: String,
    val sentenceIndex: Int,
    val sentenceCount: Int,
    val projectionId: String,
    val timelineAt: ULong,
)

internal fun TtsState.conversationFollowTargetOrNull(): ConversationTtsFollowTarget? {
    val passage = passage
    if ((this !is TtsState.Speaking && this !is TtsState.Paused) || passage == null) return null
    return ConversationTtsFollowTarget(
        sessionId = sessionId,
        messageIdHex = passage.messageIdHex,
        sentenceIndex = passage.sentenceIndex,
        sentenceCount = sentenceCountWithinMessage.coerceAtLeast(1),
        projectionId = passage.projectionId,
        timelineAt = passage.timelineAt,
    )
}

/**
 * Conversation-local follow policy. Only direct drag input calls [onUserDrag];
 * programmatic list motion therefore cannot suspend itself.
 */
internal class ConversationTtsFollowPolicy {
    var isFollowEnabled: Boolean by mutableStateOf(false)
        private set

    var showResumeAction: Boolean by mutableStateOf(false)
        private set

    private var sessionId: Long? = null
    private var activeTarget: ConversationTtsFollowTarget? = null
    private var evaluatedTarget: ConversationTtsFollowTarget? = null
    private var pendingTarget: ConversationTtsFollowTarget? = null
    private var isSpeaking = false

    fun observe(
        state: TtsState,
        ownsSession: Boolean,
    ) {
        val target = state.conversationFollowTargetOrNull()
        if (!ownsSession || target == null) {
            reset()
            return
        }

        val newSession = sessionId != state.sessionId
        val newSentence = activeTarget != target
        isSpeaking = state is TtsState.Speaking
        sessionId = state.sessionId
        activeTarget = target

        if (newSession) {
            isFollowEnabled = true
            evaluatedTarget = null
        } else if (newSentence && isFollowEnabled) {
            evaluatedTarget = null
        }

        pendingTarget =
            target.takeIf {
                isFollowEnabled &&
                    isSpeaking &&
                    evaluatedTarget != target
            }
        showResumeAction = !isFollowEnabled
    }

    fun claimPendingTarget(): ConversationTtsFollowTarget? {
        val target = pendingTarget?.takeIf { isFollowEnabled } ?: return null
        pendingTarget = null
        evaluatedTarget = target
        return target
    }

    fun onUserDrag() {
        if (activeTarget == null) return
        isFollowEnabled = false
        showResumeAction = true
        pendingTarget = null
    }

    fun resumeFollow() {
        val target = activeTarget ?: return
        isFollowEnabled = true
        showResumeAction = false
        evaluatedTarget = null
        pendingTarget = target.takeIf { isSpeaking }
    }

    fun reset() {
        sessionId = null
        activeTarget = null
        evaluatedTarget = null
        pendingTarget = null
        isSpeaking = false
        isFollowEnabled = false
        showResumeAction = false
    }
}

internal sealed interface TtsFollowViewportDecision {
    data object Stay : TtsFollowViewportDecision

    data class ScrollToItemOffset(
        val offset: Int,
    ) : TtsFollowViewportDecision
}

/** Sentence anchor geometry independent of LazyColumn lifetime and recycling. */
internal object TtsFollowViewport {
    private const val BAND_EDGE_FRACTION = 0.20

    fun decide(
        viewportStart: Int,
        viewportEnd: Int,
        itemOffset: Int,
        itemSize: Int,
        sentenceIndex: Int,
        sentenceCount: Int,
    ): TtsFollowViewportDecision =
        if (viewportEnd - viewportStart <= 0 || itemSize <= 0) {
            TtsFollowViewportDecision.Stay
        } else {
            val viewportSize = viewportEnd - viewportStart
            val count = sentenceCount.coerceAtLeast(1)
            val index = sentenceIndex.coerceIn(0, count - 1)
            val sentenceFraction = (index + 0.5) / count
            val sentenceOffsetInItem = itemSize * sentenceFraction
            val sentencePosition = itemOffset + sentenceOffsetInItem
            val bandStart = viewportStart + viewportSize * BAND_EDGE_FRACTION
            val bandEnd = viewportEnd - viewportSize * BAND_EDGE_FRACTION
            if (sentencePosition in bandStart..bandEnd) {
                TtsFollowViewportDecision.Stay
            } else {
                TtsFollowViewportDecision.ScrollToItemOffset(
                    offset = targetItemScrollOffset(viewportSize, itemSize, index, count),
                )
            }
        }

    fun targetItemScrollOffset(
        viewportSize: Int,
        itemSize: Int,
        sentenceIndex: Int,
        sentenceCount: Int,
    ): Int {
        if (viewportSize <= 0) return 0
        val count = sentenceCount.coerceAtLeast(1)
        val index = sentenceIndex.coerceIn(0, count - 1)
        val sentenceOffsetInItem = itemSize.coerceAtLeast(0) * ((index + 0.5) / count)
        return (sentenceOffsetInItem - viewportSize / 2.0).roundToInt()
    }
}

internal suspend fun followTtsTargetInViewport(
    target: ConversationTtsFollowTarget,
    itemKey: Any,
    targetIndex: Int,
    estimatedItemHeightPx: Int?,
    listState: LazyListState,
    scrollCoordinator: ConversationScrollCoordinator,
    resolveTargetIndex: () -> Int?,
    isCurrentTarget: () -> Boolean,
    currentScrollAnchor: () -> ConversationScrollAnchor,
): Boolean {
    val layoutInfo = listState.layoutInfo
    val viewportSize = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    if (!isCurrentTarget() || viewportSize <= 0) return false
    val visibleTarget = layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
    val decision =
        if (visibleTarget == null) {
            TtsFollowViewportDecision.ScrollToItemOffset(
                offset =
                    TtsFollowViewport.targetItemScrollOffset(
                        viewportSize = viewportSize,
                        itemSize = estimatedItemHeightPx ?: 0,
                        sentenceIndex = target.sentenceIndex,
                        sentenceCount = target.sentenceCount,
                    ),
            )
        } else {
            TtsFollowViewport.decide(
                viewportStart = layoutInfo.viewportStartOffset,
                viewportEnd = layoutInfo.viewportEndOffset,
                itemOffset = visibleTarget.offset,
                itemSize = visibleTarget.size,
                sentenceIndex = target.sentenceIndex,
                sentenceCount = target.sentenceCount,
            )
        }
    return if (decision is TtsFollowViewportDecision.Stay) {
        true
    } else {
        val scrollOffset = (decision as TtsFollowViewportDecision.ScrollToItemOffset).offset
        val completed =
            scrollCoordinator.programmaticJump(
                targetMessageId = target.messageIdHex,
                reason = ConversationScrollReason.ReadAloudFollow,
            ) {
                if (!isCurrentTarget()) return@programmaticJump
                animateScrollToItem(targetIndex, scrollOffset) {
                    resolveTargetIndex() ?: targetIndex
                }
            }
        if (completed) scrollCoordinator.settleReadingAt(currentScrollAnchor())
        completed
    }
}
