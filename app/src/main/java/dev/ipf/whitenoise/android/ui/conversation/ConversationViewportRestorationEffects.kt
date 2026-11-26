package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.isLoadingPage
import dev.ipf.whitenoise.android.state.loadUntilMessageAvailable
import dev.ipf.whitenoise.android.state.returnToLatestWindow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

internal data class ConversationViewportRestorationInputs(
    val scrollRestore: ConversationScrollSnapshot?,
    val presentation: ConversationViewportPresentation,
    val structure: ConversationTimelineStructure,
    val entryUnread: ConversationEntryUnreadSnapshot,
    val entryProjectionAvailable: Boolean,
    val notificationOpenRequestId: Long,
    val seedTailAwaitingAuthoritative: Boolean,
    // The tapped card's message, already verified against this controller's account and group.
    val notificationTargetMessageId: String? = null,
    val savedBackfillRetryGeneration: Long = 0L,
)

internal data class ConversationViewportRestorationCallbacks(
    val navigation: ConversationViewportNavigation,
    val onAnchored: (latestItemId: String?) -> Unit,
    val retireUnreadDivider: () -> Unit,
    val notification: ConversationNotificationLandingCallbacks = ConversationNotificationLandingCallbacks(),
    val onSavedBackfillStalled: (Boolean) -> Unit = {},
)

/** Initial/unread/saved positioning and post-initial geometry, not IME or lifecycle ownership. */
@Suppress("FunctionNaming") // Compose functions use UpperCamelCase.
@Composable
internal fun ConversationViewportRestorationEffects(
    controller: ConversationController,
    viewport: ConversationTimelineViewport,
    owner: ConversationViewportRestorationOwner,
    inputs: ConversationViewportRestorationInputs,
    callbacks: ConversationViewportRestorationCallbacks,
) {
    val currentInputs by rememberUpdatedState(inputs)
    val currentCallbacks by rememberUpdatedState(callbacks)
    LaunchedEffect(viewport, owner) {
        snapshotFlow { viewport.readingLayoutInfo().viewportSize.height }.collect { height ->
            owner.onViewportHeight(height, currentInputs.presentation, currentCallbacks.navigation)
        }
    }
    // A notified message's row can grow after it lands, so late media measurement reruns its settle.
    LaunchedEffect(viewport, owner) {
        snapshotFlow { owner.readingStartGeometry().takeUnless { currentInputs.presentation.imeIsOpen } }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { owner.onReadingStartGeometry(currentCallbacks.navigation.currentAnchor) }
    }
    ConversationSavedViewportEffect(controller, viewport, owner, inputs, callbacks)
    ConversationEntryViewportEffect(controller, viewport, owner, inputs, callbacks)
}

/** Kept after append-follow in the screen, preserving existing effect ordering and keys. */
@Suppress("FunctionNaming") // Compose functions use UpperCamelCase.
@Composable
internal fun ConversationViewportStructureEffect(
    controller: ConversationController,
    owner: ConversationViewportRestorationOwner,
    structure: ConversationTimelineStructure,
    anchored: Boolean,
    resolveAnchor: (ConversationScrollAnchor) -> Int?,
    currentAnchor: () -> ConversationScrollAnchor? = { null },
) {
    // Keep the existing trigger set: same-row hydration does not restart anchoring.
    LaunchedEffect(
        controller,
        structure.rowKeys,
        structure.olderHeaderCount,
        structure.inlineTopErrorCount,
        anchored,
        owner,
    ) {
        owner.onStructure(structure, anchored, currentAnchor, resolveAnchor)
    }
}

// A saved logical position is reapplied after materialization because LazyListState
// can clamp while the bounded window is empty. It takes precedence over entry unread.
@Suppress("FunctionNaming") // Compose functions use UpperCamelCase.
@Composable
private fun ConversationSavedViewportEffect(
    controller: ConversationController,
    viewport: ConversationTimelineViewport,
    owner: ConversationViewportRestorationOwner,
    inputs: ConversationViewportRestorationInputs,
    callbacks: ConversationViewportRestorationCallbacks,
) {
    val restore = inputs.scrollRestore
    val invitePending = controller.inviteBlocksHistory
    LaunchedEffect(controller, restore, owner, invitePending, inputs.savedBackfillRetryGeneration) {
        // A replacement coordinator must not replay an old saved position after
        // this screen has established its current reading position.
        if (restore == null || inputs.presentation.anchored || invitePending) return@LaunchedEffect
        controller.awaitSavedViewportTimeline()
        val isCurrent = { owner.isActive && !controller.inviteBlocksHistory }
        if (!isCurrent()) return@LaunchedEffect
        restore.anchorMessageIdHex?.takeIf { it.isNotBlank() }?.let { controller.loadUntilMessageAvailable(it) }
        val result = backfillSavedViewport(controller, isCurrent)
        if (!isCurrent()) return@LaunchedEffect
        val exhausted = result == ConversationInitialTimelineBackfillResult.Exhausted
        when {
            exhausted && controller.timeline.none(::conversationTimelineRowVisible) -> {
                if (owner.completeEmptyPosition(controller.conversationTimelineStructure(), viewport.height())) {
                    callbacks.onAnchored(null)
                }
            }
            result == ConversationInitialTimelineBackfillResult.Superseded -> Unit
            else -> controller.commitSavedViewport(restore, viewport, owner, callbacks, isCurrent)
        }
    }
}

internal val ConversationController.inviteBlocksHistory: Boolean
    get() = group.pendingConfirmation || inviteAcceptanceResolutionPending

private suspend fun ConversationController.awaitSavedViewportTimeline() {
    snapshotFlow {
        // The roster can still be loading after the timeline is safe to restore.
        val published = hasPublishedAuthoritativeTimeline || error != null
        val busy = initialTimelineSeedActive || isLoadingPage
        published && !busy
    }.first { it }
}

/** A saved target can load a centered, background-only window. Fall back to visible history, then to Latest. */
private suspend fun backfillSavedViewport(
    controller: ConversationController,
    isCurrent: () -> Boolean,
): ConversationInitialTimelineBackfillResult {
    suspend fun backfill() =
        backfillInitialConversationTimeline(
            snapshot = controller::initialTimelineBackfillSnapshot,
            loadOlder = controller::loadOlderTimelinePage,
            isCurrent = isCurrent,
        )
    val beganAtLatest = !controller.hasMoreAfterTimeline
    val result = backfill()
    if (result != ConversationInitialTimelineBackfillResult.Exhausted || !controller.hasMoreAfterTimeline) return result
    return when {
        !isCurrent() -> ConversationInitialTimelineBackfillResult.Superseded
        !controller.returnToLatestWindow() -> ConversationInitialTimelineBackfillResult.NoProgress
        beganAtLatest -> result
        else -> {
            val latest = backfill()
            val exhausted = latest == ConversationInitialTimelineBackfillResult.Exhausted
            val detachedEmpty = exhausted && controller.hasMoreAfterTimeline
            if (detachedEmpty && isCurrent() && !controller.returnToLatestWindow()) {
                ConversationInitialTimelineBackfillResult.NoProgress
            } else {
                latest
            }
        }
    }
}

private suspend fun ConversationController.commitSavedViewport(
    restore: ConversationScrollSnapshot,
    viewport: ConversationTimelineViewport,
    owner: ConversationViewportRestorationOwner,
    callbacks: ConversationViewportRestorationCallbacks,
    isCurrent: () -> Boolean,
) {
    while (isCurrent()) {
        val (restored, _) =
            snapshotFlow { savedViewportPosition(this, restore) to (error == null) }.first { (position, canRetry) ->
                if (isCurrent()) callbacks.onSavedBackfillStalled(position == null && canRetry)
                position != null || !isCurrent()
            }
        val position = restored?.takeIf { isCurrent() } ?: break
        if (owner.commitInitialPosition(position, { viewport.initialAnchorLayout(position.index) }) && isCurrent()) {
            val completion = savedViewportCompletion(position, restore)
            if (owner.completeInitialPosition(completion.position, completion.structure, viewport.height())) {
                callbacks.onAnchored(completion.latestItemId)
            }
            return
        }
        withFrameNanos { }
    }
}

private fun savedViewportPosition(
    controller: ConversationController,
    restore: ConversationScrollSnapshot,
): ConversationViewportInitialPosition? {
    val rows = controller.timeline.filter(::conversationTimelineRowVisible).map { it.id to it.record.messageIdHex }
    val trailing = controller.conversationTrailingRowCount(rows.size)
    val position = conversationViewportSavedPosition(restore, rows, trailing) ?: return null
    val index = conversationTimelineIndexForListIndex(position.index, rows.size, trailing)
    return if (index in rows.indices) {
        position
    } else {
        val (itemId, messageId) = rows.last()
        val fallback =
            restore.copy(anchorItemId = itemId, anchorMessageIdHex = messageId, firstVisibleItemScrollOffset = 0)
        conversationViewportSavedPosition(fallback, rows, trailing)
    }
}

private data class ConversationSavedViewportCompletion(
    val position: ConversationViewportInitialPosition,
    val structure: ConversationTimelineStructure,
    val latestItemId: String?,
)

private fun ConversationController.savedViewportCompletion(
    position: ConversationViewportInitialPosition,
    restore: ConversationScrollSnapshot,
): ConversationSavedViewportCompletion {
    // Settle with the row at the committed index, exactly as the screen did,
    // retaining the saved identity fallback when a row is no longer present.
    val rendered = timeline.filter(::conversationTimelineRowVisible)
    val row =
        rendered.getOrNull(
            conversationTimelineIndexForListIndex(
                position.index,
                rendered.size,
                conversationTrailingRowCount(rendered.size),
            ),
        )
    val settledPosition =
        position.copy(
            anchor =
                position.anchor.copy(
                    itemId = row?.id ?: restore.anchorItemId,
                    messageId = row?.record?.messageIdHex ?: restore.anchorMessageIdHex,
                ),
        )
    return ConversationSavedViewportCompletion(
        settledPosition,
        conversationTimelineStructure(),
        rendered.lastOrNull()?.id,
    )
}

/** Positions the transcript once its entry inputs are ready, deferring to [ConversationEntryPositioning]. */
@Suppress("FunctionNaming") // Compose functions use UpperCamelCase.
@Composable
private fun ConversationEntryViewportEffect(
    controller: ConversationController,
    viewport: ConversationTimelineViewport,
    owner: ConversationViewportRestorationOwner,
    inputs: ConversationViewportRestorationInputs,
    callbacks: ConversationViewportRestorationCallbacks,
) {
    LaunchedEffect(
        controller,
        inputs.structure.rowKeys.isNotEmpty(),
        inputs.notificationOpenRequestId,
        inputs.entryProjectionAvailable,
        controller.initialTimelineSeedActive,
        inputs.seedTailAwaitingAuthoritative,
        owner,
    ) {
        if (inputs.seedTailAwaitingAuthoritative || controller.initialTimelineSeedActive) return@LaunchedEffect
        if (
            !shouldCommitConversationInitialAnchor(
                hasRenderedTimeline = inputs.structure.rowKeys.isNotEmpty(),
                projectionAvailable = inputs.entryProjectionAvailable,
                initialTimelineAnchored = inputs.presentation.anchored,
                hasScrollRestore = inputs.scrollRestore != null,
            )
        ) {
            return@LaunchedEffect
        }
        ConversationEntryPositioning(controller, viewport, owner, inputs, callbacks).run()
    }
}

/** Reads the clear viewport height and the measured size of the row at [index], for the hidden anchor settle. */
internal fun ConversationTimelineViewport.initialAnchorLayout(index: Int): ConversationInitialAnchorLayout {
    val layout = readingLayoutInfo()
    return ConversationInitialAnchorLayout(
        viewportHeight = layout.viewportSize.height,
        targetItemSize = layout.visibleItemsInfo.firstOrNull { it.index == index }?.size,
    )
}

/** The measured clear viewport height, which the post-initial reanchor gate baselines. */
internal fun ConversationTimelineViewport.height(): Int = readingLayoutInfo().viewportSize.height
