package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsSentenceProjectionSegment
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
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

internal data class ConversationTtsFollowSignal(
    val target: ConversationTtsFollowTarget?,
    val isSpeaking: Boolean,
)

internal enum class TtsFollowDirection {
    Forward,
    Reverse,
}

internal data class ConversationTtsFollowRequest(
    val target: ConversationTtsFollowTarget,
    val direction: TtsFollowDirection,
    val anchorAtTop: Boolean,
)

internal data class ConversationTtsSentenceLayoutReport(
    val target: ConversationTtsFollowTarget,
    val rowInstance: Any,
    val renderedLeafId: String,
    val boundsInWindow: Rect,
    val coverage: Set<TtsSentenceProjectionSegment>,
    val expectedCoverage: Set<TtsSentenceProjectionSegment>,
)

internal interface ConversationTtsSentenceLayoutSink {
    fun mountRow(
        messageIdHex: String,
        rowInstance: Any,
    )

    fun unmountRow(
        messageIdHex: String,
        rowInstance: Any,
    )

    fun report(report: ConversationTtsSentenceLayoutReport)

    fun clear(
        target: ConversationTtsFollowTarget,
        rowInstance: Any,
        renderedLeafId: String,
    )
}

internal class ConversationTtsSentenceLayoutRegistry : ConversationTtsSentenceLayoutSink {
    private data class ReportKey(
        val target: ConversationTtsFollowTarget,
        val rowInstance: Any,
        val renderedLeafId: String,
    )

    private val activeRows = mutableStateMapOf<String, Any>()
    private val reports = mutableStateMapOf<ReportKey, ConversationTtsSentenceLayoutReport>()

    var viewportBoundsInWindow by mutableStateOf<Rect?>(null)
        private set

    // staleness-exempt: observable registry version that triggers Compose remeasurement.
    var revision by mutableLongStateOf(0L)
        private set

    override fun mountRow(
        messageIdHex: String,
        rowInstance: Any,
    ) {
        if (activeRows[messageIdHex] === rowInstance) return
        activeRows[messageIdHex] = rowInstance
        reports.keys.removeAll { it.target.messageIdHex == messageIdHex }
        revision++
    }

    /** Removes geometry owned by the exact row instance leaving composition. */
    override fun unmountRow(
        messageIdHex: String,
        rowInstance: Any,
    ) {
        if (activeRows[messageIdHex] !== rowInstance) return
        activeRows.remove(messageIdHex)
        reports.keys.removeAll { it.target.messageIdHex == messageIdHex }
        revision++
    }

    /** Window coordinates belong to the rendered row, independently of overlay occlusion. */
    override fun report(report: ConversationTtsSentenceLayoutReport) {
        if (activeRows[report.target.messageIdHex] !== report.rowInstance) return
        reports[ReportKey(report.target, report.rowInstance, report.renderedLeafId)] =
            report
        revision++
    }

    /** Removes one rendered-leaf report without disturbing sibling Markdown leaves. */
    override fun clear(
        target: ConversationTtsFollowTarget,
        rowInstance: Any,
        renderedLeafId: String,
    ) {
        if (reports.remove(ReportKey(target, rowInstance, renderedLeafId)) != null) revision++
    }

    /** An overlay changes the clear viewport, not the text's absolute window coordinates. */
    fun updateViewportBounds(boundsInWindow: Rect) {
        if (viewportBoundsInWindow == boundsInWindow) return
        viewportBoundsInWindow = boundsInWindow
        revision++
    }

    /** Returns complete current-viewport bounds after every rendered leaf reports. */
    @Suppress("ReturnCount")
    fun completeSentenceBounds(target: ConversationTtsFollowTarget): Rect? {
        val activeRow = activeRows[target.messageIdHex] ?: return null
        val matching =
            reports.values
                .filter { report -> report.target == target && report.rowInstance === activeRow }
        val expected = matching.firstOrNull()?.expectedCoverage.orEmpty()
        if (expected.isEmpty() || matching.any { it.expectedCoverage != expected }) return null
        if (matching.flatMapTo(mutableSetOf()) { it.coverage } != expected) return null
        return matching.map(ConversationTtsSentenceLayoutReport::boundsInWindow).reduceOrNull { first, second ->
            Rect(
                left = min(first.left, second.left),
                top = min(first.top, second.top),
                right = max(first.right, second.right),
                bottom = max(first.bottom, second.bottom),
            )
        }
    }

    /** Missing, clipped and recycled geometry always leaves an explicit recovery action. */
    fun needsReveal(target: ConversationTtsFollowTarget?): Boolean {
        revision
        if (target == null) return false
        return ttsSentenceNeedsReveal(completeSentenceBounds(target), viewportBoundsInWindow)
    }
}

internal fun ttsSentenceNeedsReveal(sentence: Rect?, viewport: Rect?): Boolean =
    sentence == null || viewport == null || viewport.height <= 0f ||
        sentence.height <= 0f || sentence.top < viewport.top || sentence.bottom > viewport.bottom

/** Oversized sentences can expose their beginning, but cannot fit entirely in one viewport. */
internal fun ttsSentenceWasRevealed(sentence: Rect?, viewport: Rect?): Boolean =
    sentence != null && viewport != null && viewport.height > 0f && sentence.height > 0f &&
        sentence.top >= viewport.top - 1f &&
        (
            if (sentence.height > viewport.height) {
                sentence.top < viewport.bottom
            } else {
                sentence.bottom <= viewport.bottom + 1f
            }
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

internal fun TtsState.conversationFollowSignal(): ConversationTtsFollowSignal =
    ConversationTtsFollowSignal(
        target = conversationFollowTargetOrNull(),
        isSpeaking = this is TtsState.Speaking,
    )

@Composable
internal fun rememberConversationTtsFollowPolicy(groupIdHex: String): ConversationTtsFollowPolicy =
    rememberSaveable(groupIdHex, saver = ConversationTtsFollowPolicy.Saver) {
        ConversationTtsFollowPolicy()
    }

/**
 * Conversation-local follow policy. Only direct drag input calls [onUserDrag];
 * programmatic list motion therefore cannot suspend itself.
 */
@Suppress("CyclomaticComplexMethod", "TooManyFunctions")
internal class ConversationTtsFollowPolicy private constructor(
    private var sessionId: Long?,
    initialFollowEnabled: Boolean,
    activeTarget: ConversationTtsFollowTarget? = null,
    private var activeMessageIndex: Int? = null,
    private var activeDirection: TtsFollowDirection = TtsFollowDirection.Forward,
) {
    constructor() : this(sessionId = null, initialFollowEnabled = false)

    private var activeTarget by mutableStateOf(activeTarget)
    val currentTarget: ConversationTtsFollowTarget? get() = activeTarget

    var requestRevision by mutableLongStateOf(0L)
        private set

    var isFollowEnabled: Boolean by mutableStateOf(initialFollowEnabled)
        private set

    var showResumeAction: Boolean by mutableStateOf(sessionId != null && !initialFollowEnabled)
        private set

    private var evaluatedTarget: ConversationTtsFollowTarget? = null
    private var pendingTarget: ConversationTtsFollowTarget? = null
    private var pendingDirection = TtsFollowDirection.Forward
    private var pendingAnchorAtTop = false
    private var retriedTarget: ConversationTtsFollowTarget? = null
    private var explicitRevealTarget: ConversationTtsFollowTarget? = null
    private var prepositionedTarget: ConversationTtsFollowTarget? = null
    private var correctedTarget: ConversationTtsFollowTarget? = null
    private var isSpeaking = false
    private var awaitingPreparedPassage = false

    fun observe(
        state: TtsState,
        ownsSession: Boolean,
    ) {
        val target = state.conversationFollowTargetOrNull()
        if (ownsSession && state is TtsState.Preparing && sessionId == state.sessionId) {
            // Preparation between passages belongs to the same manual-scroll owner.
            isSpeaking = false
            awaitingPreparedPassage = true
            if (explicitRevealTarget == null) pendingTarget = null
            return
        }
        if (!ownsSession || target == null) {
            reset()
            return
        }

        val previousTarget = activeTarget
        val wasAwaitingPreparedPassage = awaitingPreparedPassage
        awaitingPreparedPassage = false
        val previousMessageIndex = activeMessageIndex
        val newSession = sessionId != state.sessionId
        val newSentence = previousTarget != target
        if (newSession) {
            activeDirection = TtsFollowDirection.Forward
        } else if (newSentence && previousTarget != null) {
            activeDirection = followDirection(previousTarget, target, previousMessageIndex, state.messageIndex)
        }
        isSpeaking = state is TtsState.Speaking
        sessionId = state.sessionId
        activeTarget = target
        activeMessageIndex = state.messageIndex
        if (newSession || newSentence) {
            prepositionedTarget = null
            correctedTarget = null
        }

        if (newSession) {
            isFollowEnabled = true
            evaluatedTarget = null
            retriedTarget = null
            explicitRevealTarget = null
        } else if (newSentence) {
            // Progress never takes the viewport back from a person browsing elsewhere.
            evaluatedTarget = null
            retriedTarget = null
            explicitRevealTarget = explicitRevealTarget?.takeIf { wasAwaitingPreparedPassage }?.let { target }
        }

        val explicitPending = target.takeIf { explicitRevealTarget == target && evaluatedTarget != target }
        val automaticPending =
            target.takeIf {
                isFollowEnabled &&
                    isSpeaking &&
                    evaluatedTarget != target
            }
        val requestedTarget = explicitPending ?: automaticPending
        if (requestedTarget != null) {
            if (pendingTarget != requestedTarget) requestRevision++
            pendingTarget = requestedTarget
            pendingDirection = activeDirection
            pendingAnchorAtTop = explicitPending != null
        } else if (explicitRevealTarget != target) {
            pendingTarget = null
            pendingAnchorAtTop = false
        }
        showResumeAction = !isFollowEnabled
    }

    fun requestExplicitReveal(): Boolean {
        val target = activeTarget ?: return false
        isFollowEnabled = true
        showResumeAction = false
        evaluatedTarget = null
        retriedTarget = null
        explicitRevealTarget = target
        prepositionedTarget = null
        correctedTarget = null
        pendingTarget = target
        pendingDirection = activeDirection
        pendingAnchorAtTop = true
        requestRevision++
        return true
    }

    /**
     * A direct seek already placed the target under the listener's finger.
     * Skip exactly that target's automatic scroll without overriding a user's
     * explicit follow-disabled state or discarding another pending sentence.
     */
    fun suppressNextFollowFor(target: ConversationTtsFollowTarget) {
        activeTarget = target
        evaluatedTarget = target
        if (pendingTarget == target) pendingTarget = null
        retriedTarget = null
        explicitRevealTarget = null
    }

    fun claimPendingRequest(): ConversationTtsFollowRequest? {
        val target = pendingTarget?.takeIf { isFollowEnabled && !awaitingPreparedPassage } ?: return null
        pendingTarget = null
        evaluatedTarget = target
        return ConversationTtsFollowRequest(target, pendingDirection, pendingAnchorAtTop)
    }

    fun claimPendingTarget(): ConversationTtsFollowTarget? = claimPendingRequest()?.target

    fun claimPreposition(target: ConversationTtsFollowTarget): Boolean {
        if (!isCurrentTarget(target) || prepositionedTarget == target) return false
        prepositionedTarget = target
        return true
    }

    /** A changed clear viewport may clip the same sentence; user scroll ownership still wins. */
    fun recheckViewport(): Boolean {
        val target = activeTarget?.takeIf { isFollowEnabled && isSpeaking && !awaitingPreparedPassage } ?: return false
        evaluatedTarget = null
        retriedTarget = null
        correctedTarget = null
        prepositionedTarget = null
        pendingTarget = target
        pendingDirection = activeDirection
        pendingAnchorAtTop = explicitRevealTarget == target
        requestRevision++
        return true
    }

    fun claimCorrectiveScroll(target: ConversationTtsFollowTarget): Boolean {
        if (!isCurrentTarget(target) || correctedTarget == target) return false
        correctedTarget = target
        return true
    }

    /** Returns true when one bounded retry was scheduled for the current sentence. */
    fun retryFailedFollowAttempt(target: ConversationTtsFollowTarget): Boolean {
        if (!isCurrentTarget(target)) return false
        return if (retriedTarget == target) {
            if (explicitRevealTarget == target) explicitRevealTarget = null
            pendingAnchorAtTop = false
            false
        } else {
            retriedTarget = target
            evaluatedTarget = null
            pendingTarget = target
            pendingDirection = activeDirection
            requestRevision++
            true
        }
    }

    fun isCurrentTarget(target: ConversationTtsFollowTarget): Boolean {
        val followsCurrentTarget = activeTarget == target && !awaitingPreparedPassage
        val canReveal = isSpeaking || explicitRevealTarget == target
        return isFollowEnabled && canReveal && followsCurrentTarget
    }

    fun onFollowSucceeded(target: ConversationTtsFollowTarget) {
        if (explicitRevealTarget == target) explicitRevealTarget = null
    }

    fun onUserDrag() {
        if (activeTarget == null) return
        isFollowEnabled = false
        showResumeAction = true
        pendingTarget = null
        pendingAnchorAtTop = false
        explicitRevealTarget = null
    }

    fun resumeFollow() {
        // Reveal the paused cursor too; this changes scroll ownership, never audio playback.
        requestExplicitReveal()
    }

    fun reset() {
        sessionId = null
        activeTarget = null
        activeMessageIndex = null
        activeDirection = TtsFollowDirection.Forward
        evaluatedTarget = null
        pendingTarget = null
        pendingAnchorAtTop = false
        retriedTarget = null
        explicitRevealTarget = null
        prepositionedTarget = null
        correctedTarget = null
        isSpeaking = false
        awaitingPreparedPassage = false
        isFollowEnabled = false
        showResumeAction = false
    }

    companion object {
        val Saver: Saver<ConversationTtsFollowPolicy, Any> =
            listSaver(
                save = {
                    listOf(
                        it.sessionId,
                        it.isFollowEnabled,
                        it.activeDirection == TtsFollowDirection.Reverse,
                        it.activeTarget?.messageIdHex,
                        it.activeTarget?.sentenceIndex,
                        it.activeTarget?.sentenceCount,
                        it.activeTarget?.projectionId,
                        it.activeTarget?.timelineAt?.toLong(),
                        it.activeMessageIndex,
                    )
                },
                restore = { restored ->
                    val restoredSessionId = restored[0] as Long?
                    val restoredTarget =
                        restoredSessionId?.let { targetSessionId ->
                            val messageIdHex = restored.getOrNull(3) as? String ?: return@let null
                            val sentenceIndex = restored.getOrNull(4) as? Int ?: return@let null
                            val sentenceCount = restored.getOrNull(5) as? Int ?: return@let null
                            val projectionId = restored.getOrNull(6) as? String ?: return@let null
                            val timelineAt = restored.getOrNull(7) as? Long ?: return@let null
                            ConversationTtsFollowTarget(
                                sessionId = targetSessionId,
                                messageIdHex = messageIdHex,
                                sentenceIndex = sentenceIndex,
                                sentenceCount = sentenceCount,
                                projectionId = projectionId,
                                timelineAt = timelineAt.toULong(),
                            )
                        }
                    ConversationTtsFollowPolicy(
                        sessionId = restoredSessionId,
                        initialFollowEnabled = restored[1] as Boolean,
                        activeTarget = restoredTarget,
                        activeMessageIndex =
                            (restored.getOrNull(8) as? Int).takeIf { restoredTarget != null },
                        activeDirection =
                            if (restoredTarget != null && restored.getOrNull(2) == true) {
                                TtsFollowDirection.Reverse
                            } else {
                                TtsFollowDirection.Forward
                            },
                    )
                },
            )
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
    @Suppress("ReturnCount", "UnusedParameter", "UNUSED_PARAMETER")
    fun decide(
        viewportStart: Int,
        viewportEnd: Int,
        itemOffset: Int,
        sentenceTop: Int,
        sentenceBottom: Int,
        direction: TtsFollowDirection,
        anchorAtTop: Boolean,
        reverseLayout: Boolean = false,
    ): TtsFollowViewportDecision {
        if (viewportEnd <= viewportStart || sentenceBottom <= sentenceTop) {
            return TtsFollowViewportDecision.Stay
        }
        if (!anchorAtTop && sentenceTop >= viewportStart && sentenceBottom <= viewportEnd) {
            return TtsFollowViewportDecision.Stay
        }
        // A sentence that is not already fully visible goes to the top of the
        // viewport, whatever clipped it. Moving by the bottom overflow instead
        // leaves the sentence hugging the bottom edge, where the next few words
        // clip again immediately and the reader chases the text down the
        // screen. Direction is intentionally irrelevant: reverse navigation
        // should not revive the old bottom/middle-band contract.
        val offset =
            if (reverseLayout) {
                // Reversed offsets start at the row's bottom. In window coordinates the
                // native item request is clearTop - measuredSentenceTop - itemOffset.
                viewportStart - sentenceTop - itemOffset
            } else {
                sentenceTop - itemOffset - viewportStart
            }
        return TtsFollowViewportDecision.ScrollToItemOffset(offset)
    }

    /** Equal-fraction row estimate retained only for provisional remount positioning. */
    fun targetItemScrollOffset(
        viewportSize: Int,
        itemSize: Int,
        sentenceIndex: Int,
        sentenceCount: Int,
    ): Int {
        if (viewportSize <= 0) return 0
        val count = sentenceCount.coerceAtLeast(1)
        val index = sentenceIndex.coerceIn(0, count - 1)
        val sentenceOffsetInItem = itemSize.coerceAtLeast(0) * (index.toDouble() / count)
        return sentenceOffsetInItem.roundToInt()
    }
}

private const val TTS_FOLLOW_LAYOUT_TIMEOUT_MS = 750L

private data class CompleteTtsSentenceLayout(
    val sentenceBoundsInWindow: Rect,
    val viewportBoundsInWindow: Rect,
)

/** Suspends until the target sentence has a complete layout in the registry. */
private suspend fun awaitCompleteTtsSentenceLayout(
    target: ConversationTtsFollowTarget,
    registry: ConversationTtsSentenceLayoutRegistry,
    isCurrentTarget: () -> Boolean,
): CompleteTtsSentenceLayout? =
    withTimeoutOrNull(TTS_FOLLOW_LAYOUT_TIMEOUT_MS) {
        snapshotFlow {
            registry.revision
            val sentenceBounds = registry.completeSentenceBounds(target)
            val viewportBounds = registry.viewportBoundsInWindow
            if (!isCurrentTarget() || sentenceBounds == null || viewportBounds == null) {
                null
            } else {
                CompleteTtsSentenceLayout(sentenceBounds, viewportBounds)
            }
        }.filterNotNull().first()
    }

/** Scrolls the viewport so the Read Aloud target is visible, honouring the follow direction. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun followTtsTargetInViewport(
    target: ConversationTtsFollowTarget,
    direction: TtsFollowDirection,
    anchorAtTop: Boolean = false,
    itemKey: Any,
    targetIndex: Int,
    estimatedItemHeightPx: Int?,
    listState: LazyListState,
    scrollCoordinator: ConversationScrollCoordinator,
    sentenceLayouts: ConversationTtsSentenceLayoutRegistry,
    claimPreposition: () -> Boolean,
    claimCorrectiveScroll: () -> Boolean,
    resolveTargetIndex: () -> Int?,
    isCurrentTarget: () -> Boolean,
    currentScrollAnchor: () -> ConversationScrollAnchor,
    timelineViewport: ConversationTimelineViewport? = null,
): Boolean {
    if (!isCurrentTarget()) return false
    var completed = false
    val commandCompleted =
        scrollCoordinator.programmaticJump(
            targetMessageId = target.messageIdHex,
            reason = ConversationScrollReason.ReadAloudFollow,
        ) {
            var layoutInfo = (timelineViewport?.readingLayoutInfo() ?: listState.layoutInfo)
            val viewportSize = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
            if (!isCurrentTarget() || viewportSize <= 0) return@programmaticJump
            var visibleTarget = layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
            if (visibleTarget == null && claimPreposition()) {
                val provisionalOffset =
                    TtsFollowViewport
                        .targetItemScrollOffset(
                            viewportSize = viewportSize,
                            itemSize = estimatedItemHeightPx ?: 0,
                            sentenceIndex = target.sentenceIndex,
                            sentenceCount = target.sentenceCount,
                        ).coerceAtMost(0)
                if (!animateScrollToItem(targetIndex, provisionalOffset, resolveIndex = resolveTargetIndex)) {
                    return@programmaticJump
                }
            }
            if (!isCurrentTarget()) return@programmaticJump
            val measured =
                awaitCompleteTtsSentenceLayout(target, sentenceLayouts, isCurrentTarget)
                    ?: return@programmaticJump
            if (!isCurrentTarget()) return@programmaticJump
            layoutInfo = (timelineViewport?.readingLayoutInfo() ?: listState.layoutInfo)
            visibleTarget = layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey } ?: return@programmaticJump
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportWindowTop = measured.viewportBoundsInWindow.top
            val sentenceTop =
                (measured.sentenceBoundsInWindow.top - viewportWindowTop).roundToInt() + viewportStart
            val sentenceBottom =
                (measured.sentenceBoundsInWindow.bottom - viewportWindowTop).roundToInt() + viewportStart
            when (
                val decision =
                    TtsFollowViewport.decide(
                        viewportStart = layoutInfo.viewportStartOffset,
                        viewportEnd = layoutInfo.viewportEndOffset,
                        itemOffset = visibleTarget.offset,
                        sentenceTop = sentenceTop,
                        sentenceBottom = sentenceBottom,
                        direction = direction,
                        anchorAtTop = anchorAtTop,
                        reverseLayout = layoutInfo.reverseLayout,
                    )
            ) {
                TtsFollowViewportDecision.Stay -> completed = true
                is TtsFollowViewportDecision.ScrollToItemOffset -> {
                    if (!claimCorrectiveScroll() || !isCurrentTarget()) return@programmaticJump
                    completed = animateScrollToItem(targetIndex, decision.offset, resolveIndex = resolveTargetIndex)
                }
            }
        }
    if (commandCompleted && completed) withFrameNanos { }
    val succeeded =
        commandCompleted && completed && isCurrentTarget() &&
            ttsSentenceWasRevealed(
                sentenceLayouts.completeSentenceBounds(target),
                sentenceLayouts.viewportBoundsInWindow,
            )
    if (succeeded) scrollCoordinator.settleReadingAt(currentScrollAnchor())
    return succeeded
}

/** Transport moves across messages before comparing sentence positions within one message. */
private fun followDirection(
    previousTarget: ConversationTtsFollowTarget,
    target: ConversationTtsFollowTarget,
    previousMessageIndex: Int?,
    messageIndex: Int,
): TtsFollowDirection =
    when {
        previousMessageIndex != null && messageIndex < previousMessageIndex -> TtsFollowDirection.Reverse
        previousMessageIndex != null && messageIndex > previousMessageIndex -> TtsFollowDirection.Forward
        target.sentenceIndex < previousTarget.sentenceIndex -> TtsFollowDirection.Reverse
        else -> TtsFollowDirection.Forward
    }
