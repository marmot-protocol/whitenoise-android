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
import dev.ipf.whitenoise.android.state.TimelineMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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
    listState: LazyListState,
    scrollCoordinator: ConversationScrollCoordinator,
    currentTailIndex: () -> Int,
    postInitialReanchorGate: ConversationPostInitialReanchorGate,
    timelineStructure: ConversationTimelineStructure,
    onTailAlignmentCommitted: () -> Unit,
) {
    val currentTailIndexProvider = rememberUpdatedState(currentTailIndex)
    val currentTimelineStructure = rememberUpdatedState(timelineStructure)
    val currentAlignmentCallback = rememberUpdatedState(onTailAlignmentCommitted)
    LaunchedEffect(enabled, listState, scrollCoordinator, postInitialReanchorGate) {
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
            return@LaunchedEffect
        }
        postInitialReanchorGate.commit(
            structure = currentTimelineStructure.value,
            viewportHeight = listState.layoutInfo.viewportSize.height,
        )
        currentAlignmentCallback.value()
    }
}

/**
 * Waits across a competing command's ownership epoch before retrying the
 * bounded tail correction. A newer history intent may reveal at its chosen
 * position; persistent tail intent may reveal only after a correction lands.
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
        tailWorkAvailable = { scrollCoordinator.mode is ConversationScrollMode.FollowingTail },
        awaitFrame = { withFrameNanos { } },
        awaitRetryOpportunity = { retryMayProceed ->
            snapshotFlow { retryMayProceed() }.first { it }
        },
    )

/**
 * Runs finite alignment batches until the tail is positioned or newer intent
 * legitimately owns the viewport. Retry waiting is level-triggered so an owner
 * release during the final refused frame cannot be missed.
 */
internal suspend fun awaitSeededTailAlignmentUntilCommit(
    followTail: suspend () -> Boolean,
    isFollowingTail: () -> Boolean,
    canScrollForward: () -> Boolean,
    tailWorkAvailable: () -> Boolean,
    awaitFrame: suspend () -> Unit,
    awaitRetryOpportunity: suspend (retryMayProceed: () -> Boolean) -> Unit,
): Boolean {
    var alignmentMayCommit =
        seededTailAlignmentMayCommit(
            positioned = false,
            isFollowingTail = isFollowingTail(),
            canScrollForward = canScrollForward(),
        )
    while (!alignmentMayCommit) {
        val positioned =
            reconcileSeededTailAnchor(
                followTail = followTail,
                isFollowingTail = isFollowingTail,
                awaitFrame = awaitFrame,
            )
        alignmentMayCommit =
            seededTailAlignmentMayCommit(
                positioned = positioned,
                isFollowingTail = isFollowingTail(),
                canScrollForward = canScrollForward(),
            )
        if (!alignmentMayCommit) {
            awaitRetryOpportunity {
                tailWorkAvailable() || !isFollowingTail() || !canScrollForward()
            }
        }
    }
    return alignmentMayCommit
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

@Composable
@Suppress("FunctionNaming")
internal fun SeededConversationAuthoritativeReconciliationEffect(
    authoritativeTimelinePublished: Boolean,
    awaitingAuthoritativeTimeline: Boolean,
    renderedTimeline: List<TimelineMessage>,
    scrollCoordinator: ConversationScrollCoordinator,
    tailIndex: Int,
    onReconciled: (latestTimelineId: String?) -> Unit,
) {
    LaunchedEffect(
        authoritativeTimelinePublished,
        awaitingAuthoritativeTimeline,
        renderedTimeline,
        tailIndex,
    ) {
        if (!awaitingAuthoritativeTimeline || !authoritativeTimelinePublished) return@LaunchedEffect
        val latestId = renderedTimeline.lastOrNull()?.id
        if (latestId != null) {
            reconcileSeededTailAnchor(
                followTail = {
                    scrollCoordinator.followTailIfAllowed(
                        resolveTailIndex = { tailIndex },
                        reason = ConversationScrollReason.InitialAnchor,
                    )
                },
                isFollowingTail = { scrollCoordinator.isFollowingTail },
                awaitFrame = { withFrameNanos { } },
            )
        }
        // Mutating this effect's key cancels the current coroutine. Commit only
        // after the scroll write so reconciliation cannot cancel its own anchor.
        onReconciled(latestId)
    }
}

/**
 * Positions the tail before the seeded transcript is revealed. A refused
 * follow while the coordinator still owns the tail is a superseded command —
 * retry across frames. A refusal because tail-following ended means another
 * navigation owns the position, so reveal there instead of forcing the tail.
 * Attempts stay bounded within an ownership epoch; the caller can then wait
 * for the competing command to settle before starting another batch.
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
