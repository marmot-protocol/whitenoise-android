package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Resolves the seeded tail owner from the cached projection available at route entry. */
internal fun conversationFirstFrameSeedPresentation(
    controller: ConversationController,
    entryUnreadCount: Int,
    projectionAvailable: Boolean,
    hasScrollRestore: Boolean,
    hasFocusedDestination: Boolean,
    notificationOpenRequestId: Long,
): ConversationFirstFrameSeedPresentation {
    val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
    val anchorTailImmediately =
        shouldAnchorConversationTailOnFirstFrame(
            entryUnreadCount = entryUnreadCount,
            projectionAvailable = projectionAvailable,
            hasScrollRestore = hasScrollRestore,
            hasFocusedDestination = hasFocusedDestination,
            notificationOpenRequestId = notificationOpenRequestId,
        )
    return ConversationFirstFrameSeedPresentation(
        anchorTailImmediately = anchorTailImmediately,
        // Start on the real keyed tail row. Compose then keeps that message key
        // stable while the local page reconciles, without a zero-sized sentinel.
        initialListIndex = if (anchorTailImmediately) seededConversationTailListIndex(rendered.size) else 0,
        latestTimelineId = rendered.lastOrNull()?.id.takeIf { anchorTailImmediately },
        awaitingAuthoritativeTimeline = anchorTailImmediately && !controller.hasPublishedAuthoritativeTimeline,
    )
}

internal data class ConversationFirstFrameSeedPresentation(
    val anchorTailImmediately: Boolean,
    val initialListIndex: Int,
    val latestTimelineId: String?,
    val awaitingAuthoritativeTimeline: Boolean,
)

/** Allows immediate tail ownership only when no unread, restore, focus, or notification owner wins. */
internal fun shouldAnchorConversationTailOnFirstFrame(
    entryUnreadCount: Int,
    projectionAvailable: Boolean,
    hasScrollRestore: Boolean,
    hasFocusedDestination: Boolean,
    notificationOpenRequestId: Long,
): Boolean =
    // A provisional open without a projection cannot know its unread boundary
    // yet — an entry count of zero there is absence of data, not "fully read".
    // Defer to the authoritative initial-anchor path instead of tail-anchoring.
    projectionAvailable &&
        entryUnreadCount <= 0 &&
        !hasScrollRestore &&
        !hasFocusedDestination &&
        notificationOpenRequestId == 0L

/** Index of the real final row for a tail-seeded transcript with one top spacer. */
internal fun seededConversationTailListIndex(renderedTimelineSize: Int): Int = renderedTimelineSize.coerceAtLeast(0)

/**
 * Reveals the transcript only after its logical anchor and any required physical tail correction agree.
 * Short seeded timelines are already at their physical end and may paint without waiting an extra frame.
 */
internal fun conversationTranscriptVisibilityCommitted(
    initialTimelineAnchored: Boolean,
    anchorTailImmediately: Boolean,
    seededTailAlignmentCommitted: Boolean,
    viewportMeasured: Boolean,
    canScrollForward: Boolean,
): Boolean =
    initialTimelineAnchored &&
        (
            !anchorTailImmediately ||
                seededTailAlignmentCommitted ||
                (viewportMeasured && !canScrollForward)
        )

/**
 * Baselines an immediately seeded transcript after its first measure. If the
 * real final row is taller than the viewport, it is snapped to its measured
 * physical end before [onTailAlignmentCommitted] opens the one-shot draw gate.
 */
@Composable
@Suppress("FunctionNaming")
internal fun SeededConversationAnchorBaselineEffect(
    enabled: Boolean,
    retryGeneration: Long,
    listState: LazyListState,
    scrollCoordinator: ConversationScrollCoordinator,
    currentTailIndex: () -> Int,
    postInitialReanchorGate: ConversationPostInitialReanchorGate,
    timelineStructure: ConversationTimelineStructure,
    onTailAlignmentCommitted: () -> Unit,
    onTailAlignmentExhausted: () -> Unit,
) {
    val currentTailIndexProvider = rememberUpdatedState(currentTailIndex)
    val currentTimelineStructure = rememberUpdatedState(timelineStructure)
    val currentAlignmentCallback = rememberUpdatedState(onTailAlignmentCommitted)
    val currentExhaustionCallback = rememberUpdatedState(onTailAlignmentExhausted)
    LaunchedEffect(enabled, retryGeneration, listState, scrollCoordinator, postInitialReanchorGate) {
        if (!enabled) return@LaunchedEffect
        withFrameNanos { }
        // A short final row is already bottom-aligned by the LazyColumn's
        // arrangement. An oversized final row needs its measured height before
        // it can reach the physical end; do that before the draw gate opens.
        val alignmentMayCommit =
            awaitSeededTailAlignment(
                listState = listState,
                scrollCoordinator = scrollCoordinator,
                currentTailIndex = { currentTailIndexProvider.value() },
            )
        if (!alignmentMayCommit) {
            currentExhaustionCallback.value()
            awaitSeededTailAlignmentSafeFallback(
                isFollowingTail = { scrollCoordinator.isFollowingTail },
                canScrollForward = { listState.canScrollForward },
                awaitSafeState = { safeToReveal ->
                    snapshotFlow { scrollCoordinator.mode to safeToReveal() }.first { it.second }
                },
            )
        }
        postInitialReanchorGate.commit(
            structure = currentTimelineStructure.value,
            viewportHeight = listState.layoutInfo.viewportSize.height,
        )
        currentAlignmentCallback.value()
    }
}

/**
 * Spends one finite frame-and-attempt budget on initial tail correction.
 * Competing commands make the guarded follow operation refuse that frame;
 * the caller owns the visible recovery path after the budget is exhausted.
 */
private suspend fun awaitSeededTailAlignment(
    listState: LazyListState,
    scrollCoordinator: ConversationScrollCoordinator,
    currentTailIndex: () -> Int,
): Boolean =
    awaitSeededTailAlignmentUntilCommit(
        followTail = {
            scrollCoordinator.followTailIfAllowed(
                resolveTailIndex = currentTailIndex,
                reason = ConversationScrollReason.InitialAnchor,
                frameCount = 0,
            )
        },
        isFollowingTail = { scrollCoordinator.isFollowingTail },
        canScrollForward = { listState.canScrollForward },
        awaitFrame = { withFrameNanos { } },
    )

/**
 * Runs one finite frame-and-attempt budget until the tail is positioned or
 * newer intent legitimately owns the viewport. A transient owner simply makes
 * the guarded follow operation refuse that frame; there is no unbounded wait
 * between retries.
 */
internal suspend fun awaitSeededTailAlignmentUntilCommit(
    followTail: suspend () -> Boolean,
    isFollowingTail: () -> Boolean,
    canScrollForward: () -> Boolean,
    awaitFrame: suspend () -> Unit,
    maxAttempts: Int = SEEDED_TAIL_ALIGNMENT_MAX_ATTEMPTS,
): Boolean {
    if (seededTailAlignmentMayCommit(false, isFollowingTail(), canScrollForward())) return true
    val positioned =
        reconcileSeededTailAnchor(
            followTail = followTail,
            isFollowingTail = isFollowingTail,
            awaitFrame = awaitFrame,
            maxAttempts = maxAttempts,
        )
    return seededTailAlignmentMayCommit(positioned, isFollowingTail(), canScrollForward())
}

/**
 * Observes safe recovery exit without issuing seeded initial writer work. A
 * later physical tail or newer history/focus owner can reveal automatically.
 */
internal suspend fun awaitSeededTailAlignmentSafeFallback(
    isFollowingTail: () -> Boolean,
    canScrollForward: () -> Boolean,
    awaitSafeState: suspend (safeToReveal: () -> Boolean) -> Unit,
) {
    awaitSafeState {
        seededTailAlignmentMayCommit(
            positioned = false,
            isFollowingTail = isFollowingTail(),
            canScrollForward = canScrollForward(),
        )
    }
}

/**
 * Allows reveal after physical alignment or after newer history intent takes
 * ownership, but never after a refused write leaves tail pixels unread.
 */
internal fun seededTailAlignmentMayCommit(
    positioned: Boolean,
    isFollowingTail: Boolean,
    canScrollForward: Boolean,
): Boolean = positioned || !isFollowingTail || !canScrollForward

/**
 * Positions the tail before the seeded transcript is revealed. A refused
 * follow while the coordinator still owns the tail is a superseded command —
 * retry across frames. A refusal because tail-following ended means another
 * navigation owns the position, so reveal there instead of forcing the tail.
 * [maxAttempts] bounds the whole caller-supplied correction epoch. Every
 * refused tail-follow frame consumes one attempt; callers must present their
 * recovery UI instead of silently restarting this helper.
 */
internal suspend fun reconcileSeededTailAnchor(
    followTail: suspend () -> Boolean,
    isFollowingTail: () -> Boolean,
    awaitFrame: suspend () -> Unit,
    maxAttempts: Int = SEEDED_TAIL_ANCHOR_MAX_ATTEMPTS,
): Boolean {
    var positioned = false
    for (attempt in 0 until maxAttempts.coerceAtLeast(1)) {
        positioned = followTail()
        if (positioned || !isFollowingTail()) break
        awaitFrame()
    }
    return positioned
}

internal const val SEEDED_TAIL_ANCHOR_MAX_ATTEMPTS = 8
internal const val SEEDED_TAIL_ALIGNMENT_MAX_ATTEMPTS = 24

/**
 * Residual loading feedback for genuinely uncached or deliberately hidden
 * anchor paths. The grace period prevents a fast local open from flashing an
 * indicator while keeping slow direct routes honest.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming")
internal fun ConversationInitialLoadingOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    graceMillis: Long = CONVERSATION_INITIAL_LOADING_GRACE_MILLIS,
) {
    var graceElapsed by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        delay(graceMillis.coerceAtLeast(0L))
        graceElapsed = true
    }
    if (visible && graceElapsed) {
        Box(
            modifier = modifier.fillMaxSize().testTag(CONVERSATION_INITIAL_LOADING_TEST_TAG),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator(modifier = Modifier.size(40.dp))
        }
    }
}

internal const val CONVERSATION_INITIAL_LOADING_TEST_TAG = "conversation.initial_loading"
private const val CONVERSATION_INITIAL_LOADING_GRACE_MILLIS = 150L
internal const val CONVERSATION_ANCHORED_LOADING_GRACE_MILLIS = 300L
