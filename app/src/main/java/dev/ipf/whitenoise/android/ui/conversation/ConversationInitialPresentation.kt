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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import kotlinx.coroutines.delay

internal data class ConversationFirstFrameSeedPresentation(
    val anchorTailImmediately: Boolean,
    val initialListIndex: Int,
    val latestTimelineId: String?,
    val awaitingAuthoritativeTimeline: Boolean,
)

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
        // Even without a safe chat-list seed, start on the keyed bottom spacer.
        // Compose then keeps that key stable while the local page inserts above
        // it, avoiding a visible top-to-bottom jump.
        initialListIndex = if (anchorTailImmediately) seededConversationTailListIndex(rendered.size) else 0,
        latestTimelineId = rendered.lastOrNull()?.id.takeIf { anchorTailImmediately },
        awaitingAuthoritativeTimeline = anchorTailImmediately && !controller.hasPublishedAuthoritativeTimeline,
    )
}

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

/** Index of the stable bottom spacer for a tail-seeded non-history transcript. */
internal fun seededConversationTailListIndex(renderedTimelineSize: Int): Int = renderedTimelineSize.coerceAtLeast(0) + 1

@Composable
internal fun SeededConversationAnchorBaselineEffect(
    enabled: Boolean,
    listState: LazyListState,
    postInitialReanchorGate: ConversationPostInitialReanchorGate,
    timelineStructure: ConversationTimelineStructure,
) {
    LaunchedEffect(enabled, listState, postInitialReanchorGate) {
        if (!enabled) return@LaunchedEffect
        withFrameNanos { }
        postInitialReanchorGate.commit(
            structure = timelineStructure,
            viewportHeight = listState.layoutInfo.viewportSize.height,
        )
    }
}

@Composable
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
            scrollCoordinator.followTailIfAllowed(
                resolveTailIndex = { tailIndex },
                reason = ConversationScrollReason.InitialAnchor,
            )
        }
        // Mutating this effect's key cancels the current coroutine. Commit only
        // after the scroll write so reconciliation cannot cancel its own anchor.
        onReconciled(latestId)
    }
}

/**
 * Residual loading feedback for genuinely uncached or deliberately hidden
 * anchor paths. The grace period prevents a fast local open from flashing an
 * indicator while keeping slow direct routes honest.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
