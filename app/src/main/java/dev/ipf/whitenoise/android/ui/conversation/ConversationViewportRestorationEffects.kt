package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.loadUntilMessageAvailable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
)

internal data class ConversationViewportRestorationCallbacks(
    val navigation: ConversationViewportNavigation,
    val onAnchored: (latestItemId: String?) -> Unit,
    val retireUnreadDivider: () -> Unit,
    val notification: ConversationNotificationLandingCallbacks = ConversationNotificationLandingCallbacks(),
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
            .collect { owner.onReadingStartGeometry() }
    }
    ConversationSavedViewportEffect(controller, viewport, owner, inputs, callbacks.onAnchored)
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
    onAnchored: (String?) -> Unit,
) {
    val restore = inputs.scrollRestore
    LaunchedEffect(controller, restore, owner) {
        // A replacement coordinator must not replay an old saved position after
        // this screen has established its current reading position.
        if (restore == null || inputs.presentation.anchored) return@LaunchedEffect
        snapshotFlow { controller.initialTimelineSeedActive }.filter { !it }.first()
        restore.anchorMessageIdHex?.takeIf { it.isNotBlank() }?.let { controller.loadUntilMessageAvailable(it) }
        if (!owner.isActive) return@LaunchedEffect
        val position =
            snapshotFlow {
                val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
                conversationViewportSavedPosition(
                    restore,
                    rendered.map { it.id to it.record.messageIdHex },
                    controller.conversationTrailingRowCount(rendered.size),
                )
            }.filterNotNull().first()
        while (!owner.commitInitialPosition(position, { viewport.initialAnchorLayout(position.index) })) {
            if (!owner.isActive) return@LaunchedEffect
            withFrameNanos { }
        }
        val completion = controller.savedViewportCompletion(position, restore)
        val completed = owner.completeInitialPosition(completion.position, completion.structure, viewport.height())
        if (completed) onAnchored(completion.latestItemId)
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
    val rendered = timeline.filterNot { MessageProjector.isEdit(it.record) }
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
