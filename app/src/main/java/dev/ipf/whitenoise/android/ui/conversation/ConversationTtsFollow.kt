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
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.StalenessGuard
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

    private data class StampedReport(
        val report: ConversationTtsSentenceLayoutReport,
        val viewportGeometryRevision: Long,
    )

    private val activeRows = mutableStateMapOf<String, Any>()
    private val reports = mutableStateMapOf<ReportKey, StampedReport>()
    private val viewportGeometryLifetime = StalenessGuard()

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

    /** Records sentence geometry under the current viewport lifetime. */
    override fun report(report: ConversationTtsSentenceLayoutReport) {
        if (activeRows[report.target.messageIdHex] !== report.rowInstance) return
        reports[ReportKey(report.target, report.rowInstance, report.renderedLeafId)] =
            StampedReport(report, viewportGeometryLifetime.capture())
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

    /** Invalidates measured sentences when the visible viewport geometry changes. */
    fun updateViewportBounds(boundsInWindow: Rect) {
        if (viewportBoundsInWindow == boundsInWindow) return
        if (viewportBoundsInWindow != null) viewportGeometryLifetime.advance()
        viewportBoundsInWindow = boundsInWindow
        revision++
    }

    /** Returns complete current-viewport bounds after every rendered leaf reports. */
    @Suppress("ReturnCount")
    fun completeSentenceBounds(target: ConversationTtsFollowTarget): Rect? {
        val activeRow = activeRows[target.messageIdHex] ?: return null
        val matching =
            reports.values
                .filter { stamped ->
                    viewportGeometryLifetime.isCurrent(stamped.viewportGeometryRevision) &&
                        stamped.report.target == target &&
                        stamped.report.rowInstance === activeRow
                }.map(StampedReport::report)
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
}

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
    private var activeTarget: ConversationTtsFollowTarget? = null,
    private var activeMessageIndex: Int? = null,
    private var activeDirection: TtsFollowDirection = TtsFollowDirection.Forward,
) {
    constructor() : this(sessionId = null, initialFollowEnabled = false)

    var isFollowEnabled: Boolean by mutableStateOf(initialFollowEnabled)
        private set

    var showResumeAction: Boolean by mutableStateOf(sessionId != null && !initialFollowEnabled)
        private set

    private var evaluatedTarget: ConversationTtsFollowTarget? = null
    private var pendingTarget: ConversationTtsFollowTarget? = null
    private var pendingDirection = TtsFollowDirection.Forward
    private var pendingAnchorAtTop = false
    private var restoredTargetNeedsTopAnchor = activeTarget != null
    private var retriedTarget: ConversationTtsFollowTarget? = null
    private var explicitRevealTarget: ConversationTtsFollowTarget? = null
    private var prepositionedTarget: ConversationTtsFollowTarget? = null
    private var correctedTarget: ConversationTtsFollowTarget? = null
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

        val previousTarget = activeTarget
        val previousMessageIndex = activeMessageIndex
        val newSession = sessionId != state.sessionId
        val newSentence = previousTarget != target
        val newMessage = previousTarget?.messageIdHex != target.messageIdHex
        if (newSession) {
            activeDirection = TtsFollowDirection.Forward
        } else if (newSentence && previousTarget != null) {
            activeDirection =
                when {
                    previousMessageIndex != null && state.messageIndex < previousMessageIndex ->
                        TtsFollowDirection.Reverse
                    previousMessageIndex != null && state.messageIndex > previousMessageIndex ->
                        TtsFollowDirection.Forward
                    target.sentenceIndex < previousTarget.sentenceIndex -> TtsFollowDirection.Reverse
                    else -> TtsFollowDirection.Forward
                }
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
        } else if (newSentence && isFollowEnabled) {
            evaluatedTarget = null
            retriedTarget = null
            explicitRevealTarget = null
        }

        val automaticPending =
            target.takeIf {
                isFollowEnabled &&
                    isSpeaking &&
                    evaluatedTarget != target
            }
        if (automaticPending != null) {
            pendingTarget = automaticPending
            pendingDirection = activeDirection
            pendingAnchorAtTop = newSession || newMessage || restoredTargetNeedsTopAnchor
            restoredTargetNeedsTopAnchor = false
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
        val target = pendingTarget?.takeIf { isFollowEnabled } ?: return null
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

    fun claimCorrectiveScroll(target: ConversationTtsFollowTarget): Boolean {
        if (!isCurrentTarget(target) || correctedTarget == target) return false
        correctedTarget = target
        return true
    }

    /** Returns true when one bounded retry was scheduled for the current sentence. */
    fun retryFailedFollowAttempt(target: ConversationTtsFollowTarget): Boolean {
        if (!isCurrentTarget(target) || retriedTarget == target) return false
        retriedTarget = target
        evaluatedTarget = null
        pendingTarget = target
        pendingDirection = activeDirection
        return true
    }

    fun isCurrentTarget(target: ConversationTtsFollowTarget): Boolean {
        val followsCurrentTarget = activeTarget == target
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
        restoredTargetNeedsTopAnchor = false
        explicitRevealTarget = null
    }

    fun resumeFollow() {
        val target = activeTarget ?: return
        isFollowEnabled = true
        showResumeAction = false
        evaluatedTarget = null
        retriedTarget = null
        explicitRevealTarget = null
        prepositionedTarget = null
        correctedTarget = null
        pendingTarget = target.takeIf { isSpeaking }
        pendingDirection = activeDirection
        pendingAnchorAtTop = true
    }

    fun reset() {
        sessionId = null
        activeTarget = null
        activeMessageIndex = null
        activeDirection = TtsFollowDirection.Forward
        evaluatedTarget = null
        pendingTarget = null
        pendingAnchorAtTop = false
        restoredTargetNeedsTopAnchor = false
        retriedTarget = null
        explicitRevealTarget = null
        prepositionedTarget = null
        correctedTarget = null
        isSpeaking = false
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
        return TtsFollowViewportDecision.ScrollToItemOffset(sentenceTop - itemOffset - viewportStart)
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
): Boolean {
    if (!isCurrentTarget()) return false
    var completed = false
    val commandCompleted =
        scrollCoordinator.programmaticJump(
            targetMessageId = target.messageIdHex,
            reason = ConversationScrollReason.ReadAloudFollow,
        ) {
            var layoutInfo = listState.layoutInfo
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
                if (!animateScrollToItem(targetIndex, provisionalOffset, resolveTargetIndex)) {
                    return@programmaticJump
                }
            }
            if (!isCurrentTarget()) return@programmaticJump
            val measured =
                awaitCompleteTtsSentenceLayout(target, sentenceLayouts, isCurrentTarget)
                    ?: return@programmaticJump
            if (!isCurrentTarget()) return@programmaticJump
            layoutInfo = listState.layoutInfo
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
                    )
            ) {
                TtsFollowViewportDecision.Stay -> completed = true
                is TtsFollowViewportDecision.ScrollToItemOffset -> {
                    if (!claimCorrectiveScroll() || !isCurrentTarget()) return@programmaticJump
                    completed = animateScrollToItem(targetIndex, decision.offset, resolveTargetIndex)
                }
            }
        }
    val succeeded = commandCompleted && completed
    if (succeeded) scrollCoordinator.settleReadingAt(currentScrollAnchor())
    return succeeded
}
