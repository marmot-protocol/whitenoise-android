package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
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
)

internal data class ConversationViewportRestorationCallbacks(
    val navigation: ConversationViewportNavigation,
    val onAnchored: (latestItemId: String?) -> Unit,
    val retireUnreadDivider: () -> Unit,
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
    ConversationSavedViewportEffect(controller, viewport, owner, inputs.scrollRestore, callbacks.onAnchored)
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
        owner.onStructure(structure, anchored, resolveAnchor)
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
    restore: ConversationScrollSnapshot?,
    onAnchored: (String?) -> Unit,
) {
    LaunchedEffect(controller, restore, owner) {
        if (restore == null) return@LaunchedEffect
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
        ) return@LaunchedEffect
        val unreadId =
            resolveConversationEntryUnreadMessageId(
                snapshot = inputs.entryUnread,
                timeline = { controller.timeline },
                loadUntilMessageAvailable = controller::loadConversationEntryUnreadMessageAvailable,
            )
        if (!owner.isActive) return@LaunchedEffect
        val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val structure = controller.conversationTimelineStructure()
        val position =
            conversationViewportEntryPosition(
                rendered.map { it.id to it.record.messageIdHex },
                unreadId,
                controller.conversationTrailingRowCount(rendered.size),
            ) ?: return@LaunchedEffect
        if (hasSentMessageAfterUnreadBoundary(rendered, unreadId)) callbacks.retireUnreadDivider()
        while (!owner.commitInitialPosition(position, { viewport.initialAnchorLayout(position.index) })) {
            if (!owner.isActive) return@LaunchedEffect
            withFrameNanos { }
        }
        val committedStructure =
            structure.copy(groupRecoveryCount = if (controller.conversationGroupRecoveryRowVisible()) 1 else 0)
        if (owner.completeInitialPosition(position, committedStructure, viewport.height())) {
            callbacks.onAnchored(rendered.lastOrNull()?.id)
        }
    }
}

private fun ConversationTimelineViewport.initialAnchorLayout(index: Int): ConversationInitialAnchorLayout {
    val layout = readingLayoutInfo()
    return ConversationInitialAnchorLayout(
        viewportHeight = layout.viewportSize.height,
        targetItemSize = layout.visibleItemsInfo.firstOrNull { it.index == index }?.size,
    )
}

private fun ConversationTimelineViewport.height(): Int = readingLayoutInfo().viewportSize.height
