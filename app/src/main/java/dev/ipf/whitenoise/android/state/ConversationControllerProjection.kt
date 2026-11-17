package dev.ipf.whitenoise.android.state

import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.withAuthoritativeEdits
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal fun ConversationController.pruneConfirmedOptimisticMessages() {
    confirmedOptimisticMessageKeys(
        optimisticKeys = optimisticMessages.keys,
        projectedMessageIds = timelineRecords.keys,
    ).forEach(optimisticMessages::remove)
}

/** Settles reaction overlays once the authoritative projection matches their intended state. */
internal fun ConversationController.pruneConfirmedOptimisticReactions() {
    confirmedOptimisticReactionKeys(
        activeAccountIdHex = conversationAccountIdHex,
        optimisticChanges = optimisticReactionChanges,
        confirmedSendersByTarget = baseReactionSenders(),
    ).forEach(optimisticReactionChanges::remove)
}

/** Only newly delivered live events are timed; initial, paging and refresh rows are excluded. */
internal fun ConversationController.recordMembershipProjectionArrival(change: TimelineMessageChangeFfi.Upsert) {
    if (change.trigger != TimelineUpdateTriggerFfi.NEW_MESSAGE &&
        change.trigger != TimelineUpdateTriggerFfi.GROUP_SYSTEM
    ) {
        return
    }
    val record = change.message
    val type = record.groupSystem?.systemType
    val isMembershipEvent = type == "member_added" || type == "member_removed"
    if (record.messageIdHex !in timelineRecords &&
        isMembershipEvent &&
        GroupSystemEvents.resolve(record)?.fromAuthenticatedStateProjection == true
    ) {
        membershipTimings.projectionArrived(record.messageIdHex)
    }
}

/** Installs a native row; peer receipts reconcile only exact send ownership, never same-content legacy guesses. */
internal fun ConversationController.upsertProjectedRecord(
    record: TimelineMessageRecordFfi,
    reconcileOptimistic: Boolean = false,
    allowDelayedProjection: Boolean = false,
    displayedProjectedStreamItemIds: Set<String> = emptySet(),
    preparedAction: AppMessageRecordFfi? = null,
): AppMessageRecordFfi {
    val reconcilesSend = reconcileOptimistic || record.recipientConfirmed
    pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds - record.messageIdHex
    // Defensive guard against the Rust core re-emitting an identical
    // record (own-publish + own-relay-echo both fire
    // `timeline_changes_for_event` for the same kind-9). Without this,
    // the remove-then-reinsert pair below briefly empties the bubble
    // from the timeline and Compose renders the "vanished + reappeared"
    // frame as a visible duplicate flash on large media bubbles.
    // Upstream fix tracked separately. See docs/design/white-noise-double-upsert.md.
    //
    // Compare on RENDER-RELEVANT fields only. `receivedAt` and other
    // ephemeral fields differ between the two emits (the Rust core
    // records them with distinct local timestamps), so full equality
    // would never fire — but the bubble's content is identical.
    //
    // Also require the by-id index to already hold the item: only skip the
    // re-projection when the bubble is genuinely still on screen. If the
    // record is held but unprojected, fall through and (re)build it.
    val existing = timelineRecords[record.messageIdHex]
    val previousItemId = existing?.let(::projectedItemId)
    val stillProjected = previousItemId != null && timelineItemsById.containsKey(previousItemId)
    if (existing != null && stillProjected && timelineRecordsRenderEqual(existing, record)) {
        recoverPendingEditStatusForProjection(record)
        return preparedAction ?: TimelineProjector.toAppMessageRecord(record)
    }
    var retentionAtSendSeconds =
        previousItemId?.let { itemId -> timelineItemsById[itemId]?.retentionAtSendSeconds }
    if (previousItemId != null) {
        timelineItemsById.remove(previousItemId)
        timelineOrder.remove(previousItemId)
    }
    // If multiple `_media_pending` siblings are in flight AND no exact
    // bridge has been inserted yet, the projection's owning send is
    // still mid-`sendMediaAttachments` — writing the bubble visibly now
    // would put it at timelineOrder=0uL until the bridge insert stamps
    // the override (visible flip). Stash the record and bail; the
    // bridge insert path will drain `pendingProjectionsAwaitingBridge`
    // and write the projection with the override already in place. The
    // bridge in `optimisticMessages` covers the bubble in the meantime
    // — same content, same position.
    val draftAction = preparedAction ?: TimelineProjector.toAppMessageRecord(record)
    val projectedIsMediaUpsert = draftAction.tags.any { it.values.firstOrNull() == "imeta" }
    val hasAcceptedPendingTextBridge =
        reconcilesSend && record.messageIdHex in acceptedPendingTextOptimisticIds
    val acceptedPendingTextOptimisticId =
        if (hasAcceptedPendingTextBridge) {
            acceptedPendingTextOptimisticIdForProjection(record.messageIdHex)
        } else {
            null
        }
    val acceptedPendingMediaOptimisticId =
        acceptedPendingMediaOptimisticIdForProjection(record.messageIdHex).takeIf { reconcilesSend }
    val acceptedPendingOptimisticId =
        record.clientToken?.takeIf { reconcilesSend && "msg:$it" in optimisticMessages }
            ?: acceptedPendingTextOptimisticId ?: acceptedPendingMediaOptimisticId
    val hasExactBridge =
        optimisticMessages.values.any { it.record.messageIdHex == record.messageIdHex } ||
            hasAcceptedPendingTextBridge ||
            acceptedPendingOptimisticId != null
    val reconcileLegacyMedia = projectedIsMediaUpsert && record.clientToken == null && reconcileOptimistic
    if (reconcileLegacyMedia && !hasExactBridge) {
        val pendingMediaCount =
            optimisticMessages.values.count {
                isSendableOptimisticStatus(it.status, allowDelayedProjection) &&
                    it.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
            }
        if (pendingMediaCount > 1) {
            pendingProjectionsAwaitingBridge[record.messageIdHex] = record
            return draftAction
        }
    }
    pendingProjectionsAwaitingBridge.remove(record.messageIdHex)
    val actionRecord = draftAction
    if (
        record.clientToken == null &&
        record.invalidationStatus != null &&
        failedOptimisticMessageIdForInvalidatedProjection(optimisticMessages.values, actionRecord) != null
    ) {
        timelineRecords.remove(record.messageIdHex)
        authoritativeTimelineOrderByMessageId.remove(record.messageIdHex)
        projectedMessageIds.remove(record.messageIdHex)
        messageById.remove(record.messageIdHex)
        if (previousItemId != null) {
            timelineItemsById.remove(previousItemId)
            timelineOrder.remove(previousItemId)
        }
        return actionRecord
    }
    val unownedLegacyRecord =
        record.clientToken == null && record.sourceMessageIdHex == null && !record.recipientConfirmed
    if (
        unownedLegacyRecord &&
        record.invalidationStatus == null &&
        failedOptimisticMessageIdForInvalidatedProjection(optimisticMessages.values, actionRecord) != null
    ) {
        timelineRecords[record.messageIdHex] = record
        projectedMessageIds.add(record.messageIdHex)
        messageById[record.messageIdHex] = actionRecord
        return actionRecord
    }
    if (record.invalidationStatus == null && !record.recipientConfirmed) {
        invalidatedProjectionIdsMatchingMessage(timelineRecords, actionRecord)
            .filterNot { it == record.messageIdHex }
            .forEach(::removeProjectedRecord)
    }
    timelineRecords[record.messageIdHex] = record
    projectedMessageIds.add(record.messageIdHex)
    preserveOptimisticDisplayPosition(record.messageIdHex, record.messageIdHex)
    preserveStreamFinalDisplayPosition(
        record.messageIdHex,
        actionRecord,
        displayedProjectedStreamItemIds,
    )
    // A peer receipt proves delivery of this native row, not ownership of a same-content optimistic send.
    val reconciledOptimisticId =
        acceptedPendingOptimisticId
            ?: if (hasAcceptedPendingTextBridge || record.clientToken != null || record.recipientConfirmed) {
                null
            } else {
                optimisticMessageIdForProjection(
                    optimisticMessages.values,
                    actionRecord,
                    allowDelayedProjection = allowDelayedProjection,
                ).takeIf { reconcileOptimistic }
            }
    acceptedPendingOptimisticId?.let { optimisticId ->
        appState.commitOptimisticSentPreview(
            accountRef = conversationAccountRef,
            groupIdHex = record.groupIdHex,
            optimisticMessageIdHex = optimisticId,
            confirmedMessageIdHex = record.messageIdHex,
        )
    }
    retentionAtSendSeconds =
        retentionAtSendForProjection(
            messageId = record.messageIdHex,
            projectedRetentionSeconds = actionRecord.retentionSeconds,
            optimisticMessageId = reconciledOptimisticId,
        )
    reconciledOptimisticId?.let { optimisticId ->
        handoffPendingMessageEdit(optimisticId, record.messageIdHex, ready = true)
        preserveOptimisticDisplayPosition(record.messageIdHex, optimisticId)
        val optimisticKey = "msg:$optimisticId"
        // An authoritative own-message projection proves the engine has
        // durably accepted this optimistic send even if the synchronous
        // FFI response was lost. Clear the captured draft at this point;
        // a later send exception may be only an ambiguous acknowledgement
        // failure and must not undo durable acceptance.
        completeDurableAcceptance(optimisticKey)
        // Hand off own-sent media bytes from the pending optimistic to
        // the projection's cache key BEFORE the bubble's LaunchedEffect
        // can fire and ask Blossom for them. Without this, the projected
        // bubble starts rendering, finds the thumbnail/plaintext caches
        // empty for the confirmed messageIdHex, and triggers an FFI
        // downloadMedia round-trip — re-downloading bytes we literally
        // just uploaded.
        handoffOwnMediaCacheOnReconcile(optimisticKey, record.messageIdHex)
        optimisticMessages.remove(optimisticKey)
        optimisticSendPhases.remove(optimisticKey)
        messageById.remove(optimisticId)
        if (optimisticId == acceptedPendingTextOptimisticId) {
            acceptedPendingTextOptimisticIds.remove(record.messageIdHex)
        }
        // The engine echo just flipped this pending bubble to a
        // projected record. If we're tracing this send, this is the
        // "self-echo drives the sent flip" path (issue #913).
        appState.pendingSendDiagnostics.recordEchoReconcile(optimisticId)
    }
    messageById[record.messageIdHex] = actionRecord
    recoverPendingEditStatusForProjection(record)
    record.clientToken?.let { clientToken ->
        // A newly mounted controller may see the native projection after
        // the editor's original controller was disposed. The process-owned
        // handoff still knows this token and can publish its queued edit.
        if (pendingMessageEditHandoff.hasSession(pendingEditKey(clientToken))) {
            handoffPendingMessageEdit(clientToken, record.messageIdHex, ready = true)
        }
    }
    val item =
        timelineMessageFromProjection(
            record = record,
            actionRecord = actionRecord,
            retentionAtSendSeconds = retentionAtSendSeconds,
        )
    timelineItemsById[item.id] = item
    insertTimelineItemId(item.id)
    return actionRecord
}

/** Bridges a newly projected row through the position of its optimistic bubble. */
internal fun ConversationController.preserveOptimisticDisplayPosition(
    projectedId: String,
    optimisticId: String,
) {
    val optimistic = optimisticMessages["msg:$optimisticId"] ?: return
    timelinePresentationIds[projectedId] = optimistic.presentationId
    durableStreamPositionOverrideIds.remove(projectedId)
    durableStreamDisplayParentByMessageId.remove(projectedId)
    preservedTimelinePositionOverrideIds.add(projectedId)
    optimisticSendPositionPreserves.add(projectedId)
    localTimelineOrderOverrides[projectedId] = optimistic.timelineOrder
    localTimelineTimestampOverrides[projectedId] = optimistic.record.recordedAt
}

internal fun ConversationController.rememberRetentionAtSend(
    messageId: String,
    retentionSeconds: ULong?,
): ULong? =
    retentionSeconds?.takeIf { it > 0uL }?.also { snapshot ->
        retentionAtSendByMessageId[messageId] = snapshot
    }

internal fun ConversationController.transferRetentionAtSend(
    fromMessageId: String,
    toMessageId: String,
): ULong? {
    if (fromMessageId == toMessageId) return retentionAtSendByMessageId[toMessageId]
    val snapshot = retentionAtSendByMessageId.remove(fromMessageId) ?: retentionAtSendByMessageId[toMessageId]
    if (snapshot != null) retentionAtSendByMessageId[toMessageId] = snapshot
    return snapshot
}

internal fun ConversationController.retentionAtSendForProjection(
    messageId: String,
    projectedRetentionSeconds: ULong?,
    optimisticMessageId: String?,
): ULong? {
    val optimisticSnapshot =
        optimisticMessageId?.let { optimisticId ->
            retentionAtSendByMessageId[optimisticId]
                ?: optimisticMessages["msg:$optimisticId"]?.retentionAtSendSeconds
        }
    if (optimisticMessageId != null && optimisticMessageId != messageId) {
        retentionAtSendByMessageId.remove(optimisticMessageId)
    }
    val snapshot =
        retentionHintForProjection(
            projectedRetentionSeconds = projectedRetentionSeconds,
            currentGroupRetentionSeconds = group.disappearingMessageSecs,
            optimisticSnapshot = optimisticSnapshot,
            rememberedSnapshot = retentionAtSendByMessageId[messageId],
        )
    if (snapshot == null) {
        retentionAtSendByMessageId.remove(messageId)
    } else {
        retentionAtSendByMessageId[messageId] = snapshot
    }
    return snapshot
}

/** Rebuilds durable stream overrides and their immediate rendered-parent links. */
internal fun ConversationController.applyDurableStreamPositions(positions: Map<String, StreamFinalDisplayPosition>) {
    val staleIds = durableStreamPositionOverrideIds + durableStreamDisplayParentByMessageId.keys - positions.keys
    staleIds.forEach(::clearDurableStreamPosition)
    positions.forEach { (messageId, position) ->
        // A live preview is the row the user is already reading. Its
        // position wins for this controller session; the durable link is
        // the reload/cold-projection fallback.
        val parentId = position.afterMessageId ?: return@forEach
        val parent = timelineRecords[parentId] ?: return@forEach
        if (
            messageId in preservedTimelinePositionOverrideIds ||
            messageId in optimisticSendPositionPreserves
        ) {
            durableStreamDisplayParentByMessageId[messageId] = parentId
            refreshProjectedTimelinePosition(messageId)
            return@forEach
        }
        if (messageId !in timelineRecords) return@forEach
        val parentRecordedAt = localTimelineTimestampOverrides[parentId] ?: parent.timelineAt
        val effectivePosition =
            resolvedDurableStreamDisplayPosition(
                candidate = position,
                parentRecordedAt = parentRecordedAt,
                parentTimelineOrder = localTimelineOrderOverrides[parentId] ?: 0uL,
            ) ?: run {
                clearDurableStreamPosition(messageId)
                return@forEach
            }

        durableStreamDisplayParentByMessageId[messageId] = parentId
        localTimelineOrderOverrides[messageId] = effectivePosition.timelineOrder
        localTimelineTimestampOverrides[messageId] = effectivePosition.recordedAt
        durableStreamPositionOverrideIds.add(messageId)
        refreshProjectedTimelinePosition(messageId)
    }
}

/** Releases a durable override and any parent link used only for display ordering. */
private fun ConversationController.clearDurableStreamPosition(messageId: String) {
    val removedOverride = durableStreamPositionOverrideIds.remove(messageId)
    val removedParent = durableStreamDisplayParentByMessageId.remove(messageId) != null
    if (!removedOverride && !removedParent) return
    if (removedOverride) {
        localTimelineOrderOverrides.remove(messageId)
        localTimelineTimestampOverrides.remove(messageId)
    }
    refreshProjectedTimelinePosition(messageId)
}

/** Reprojects one indexed row after a transient display position changes. */
private fun ConversationController.refreshProjectedTimelinePosition(messageId: String) {
    val projected = timelineRecords[messageId] ?: return
    val itemId = projectedItemId(projected)
    val currentItem = timelineItemsById[itemId] ?: return
    val actionRecord = TimelineProjector.toAppMessageRecord(projected)
    timelineItemsById[itemId] =
        timelineMessageFromProjection(
            record = projected,
            actionRecord = actionRecord,
            retentionAtSendSeconds = currentItem.retentionAtSendSeconds,
        )
    timelineOrder.remove(itemId)
    insertTimelineItemId(itemId)
}

/**
 * Keeps send bridges while media siblings remain pending, then lets projected
 * rows settle back to their authoritative positions in MDK's bounded window.
 */
private fun ConversationController.releaseOrphanedOptimisticSendPreserves(): Boolean {
    // Keep completed rows beside their still-pending send siblings. MDK order wins
    // after the last media handoff, failure or discard removes this transient bridge.
    val pendingMedia =
        optimisticMessages.values.any { message ->
            message.status == MessageStatus.Pending &&
                message.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
        }
    if (pendingMedia) return false
    val orphaned =
        optimisticSendPositionPreserves.releaseOrphaned(
            optimisticKeys = optimisticMessages.keys,
            projectedMessageIds = projectedMessageIds,
        )
    orphaned.forEach { id ->
        localTimelineOrderOverrides.remove(id)
        localTimelineTimestampOverrides.remove(id)
        preservedTimelinePositionOverrideIds.remove(id)
        refreshProjectedTimelinePosition(id)
    }
    return orphaned.isNotEmpty()
}

/**
 * Reports unexpected adjacent fallback-order inversions once per pair in debug
 * builds; authoritative pairs may intentionally invert wall time for epoch safety.
 */
private fun ConversationController.logTimelineInversionsForDebug(
    rows: List<TimelineMessage>,
    expectedHandoffPreserves: Set<String>,
) {
    if (!BuildConfig.DEBUG) return
    val inversionsByPair =
        adjacentTimelineInversions(rows)
            .filterNot { inversion ->
                (inversion.above.authoritativeOrder != null && inversion.below.authoritativeOrder != null) ||
                    checkNotNull(inversion.above.projected).messageIdHex in expectedHandoffPreserves ||
                    checkNotNull(inversion.below.projected).messageIdHex in expectedHandoffPreserves
            }.associateBy { inversion ->
                checkNotNull(inversion.above.projected).messageIdHex to
                    checkNotNull(inversion.below.projected).messageIdHex
            }
    // De-dup so a persistent inversion logs once, not every publish.
    (inversionsByPair.keys - loggedTimelineOrderingInversionPairs).forEach { pair ->
        Log.w("TimelineOrder", describeTimelineInversion(inversionsByPair.getValue(pair)))
    }
    loggedTimelineOrderingInversionPairs = inversionsByPair.keys
}

/** Formats both rows and controller override state for an inversion diagnostic. */
private fun ConversationController.describeTimelineInversion(inversion: TimelineAdjacentInversion): String {
    val aboveSource = checkNotNull(inversion.above.projected)
    val belowSource = checkNotNull(inversion.below.projected)
    return "inversion(#1578) group=${group.groupIdHex.take(8)} " +
        "source=${inversion.sourceTimelineInverted} arrival=${inversion.arrivalInverted} " +
        "above[${describeTimelineInversionRow(inversion.above, aboveSource)}] " +
        "below[${describeTimelineInversionRow(inversion.below, belowSource)}]"
}

/** Formats the source and effective positions of one diagnostic row. */
private fun ConversationController.describeTimelineInversionRow(
    row: TimelineMessage,
    source: TimelineMessageRecordFfi,
): String =
    "id=${source.messageIdHex.take(8)} direction=${row.record.direction} " +
        "displayAt=${row.record.recordedAt} sourceAt=${source.timelineAt} " +
        "receivedAt=${source.receivedAt} order=${row.timelineOrder} authoritative=${row.authoritativeOrder} " +
        "timestampOverride=${source.messageIdHex in localTimelineTimestampOverrides} " +
        "orderOverride=${source.messageIdHex in localTimelineOrderOverrides} " +
        "durable=${source.messageIdHex in durableStreamPositionOverrideIds} " +
        "optimisticPreserve=${source.messageIdHex in optimisticSendPositionPreserves}"

/** Keeps a durable stream final at the position of the preview it replaces. */
private fun ConversationController.preserveStreamFinalDisplayPosition(
    projectedId: String,
    actionRecord: AppMessageRecordFfi,
    displayedProjectedStreamItemIds: Set<String>,
) {
    val streamId = MessageProjector.streamId(actionRecord) ?: return
    val itemId = "stream:$streamId"
    val displayedStream =
        optimisticMessages[itemId]
            ?: timelineItemsById[itemId]?.takeIf { itemId in displayedProjectedStreamItemIds }
    val position = streamFinalDisplayPosition(actionRecord, displayedStream) ?: return
    durableStreamPositionOverrideIds.remove(projectedId)
    preservedTimelinePositionOverrideIds.add(projectedId)
    // A stream-final live preview holds its position for the whole session, so
    // it is not an orphan-releasable optimistic-send preserve (#1578).
    optimisticSendPositionPreserves.remove(projectedId)
    localTimelineOrderOverrides[projectedId] = position.timelineOrder
    localTimelineTimestampOverrides[projectedId] = position.recordedAt
}

internal fun ConversationController.suppressProjectedTimelineItems(messageIds: List<String>) {
    messageIds.forEach { messageIdHex ->
        val itemId = timelineRecords[messageIdHex]?.let(::projectedItemId) ?: "msg:$messageIdHex"
        timelineItemsById.remove(itemId)
        timelineOrder.remove(itemId)
    }
}

// Drop the oldest live-projected records beyond [maxItems], using the same
// total order the publish sorts by. When older history was deliberately
// loaded, [protectedTimelineMessageIds] is excluded from the trim.
internal fun ConversationController.trimLiveTimelineWindow(maxItems: Int) {
    val protectedIds = if (hasLoadedOlderPages) protectedTimelineMessageIds else emptySet()
    val evictedIds =
        timelineMessageIdsExceedingLiveCap(
            items = timelineItemsById.values,
            protectedIds = protectedIds,
            maxLiveItems = maxItems,
        )
    if (evictedIds.isNotEmpty()) {
        evictedIds.forEach(::removeProjectedRecord)
    }
    readAnchoredAtSeconds.retainTimelineWindow()
}

internal fun ConversationController.pruneMessageOverlaysToWindow() {
    if (deletedMessageIds.isEmpty() && optimisticReactionChanges.isEmpty()) return
    val optimisticIds = optimisticMessages.values.map { it.record.messageIdHex }
    val overlayTargets =
        buildList {
            addAll(deletedMessageIds)
            optimisticReactionChanges.values.forEach { add(it.targetMessageId) }
        }
    val retained =
        retainedMessageOverlayTargets(
            timelineMessageIds = timelineRecords.keys,
            optimisticMessageIds = optimisticIds,
            overlayTargetIds = overlayTargets,
        )
    if (deletedMessageIds.any { it !in retained }) {
        deletedMessageIds = deletedMessageIds.filterTo(linkedSetOf()) { it in retained }
    }
    optimisticReactionChanges.entries.removeAll { (_, change) -> change.targetMessageId !in retained }
}

/** Projects one MDK row with its authoritative rank and any transient display bridge. */
internal fun ConversationController.timelineMessageFromProjection(
    record: TimelineMessageRecordFfi,
    actionRecord: AppMessageRecordFfi = TimelineProjector.toAppMessageRecord(record),
    retentionAtSendSeconds: ULong? = null,
): TimelineMessage {
    val streamId = MessageProjector.streamId(actionRecord).takeIf { MessageProjector.isStreamStart(actionRecord) }
    val displayRecord =
        if (streamId != null) {
            actionRecord.copy(plaintext = actionRecord.plaintext.ifBlank { copy.waitingForStream })
        } else {
            actionRecord
        }.withRecordedAtOverride(localTimelineTimestampOverrides[record.messageIdHex])
    return TimelineMessage(
        id = streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}",
        record = displayRecord,
        status =
            when {
                streamId != null -> MessageStatus.Streaming
                MessageProjector.isMine(actionRecord, conversationAccountIdHex) ->
                    // Only this installation's own send can still be waiting on delivery. The account's rows from
                    // another device or from imported history are already delivered, whatever their source event.
                    if (MessageProjector.isSentFromThisDevice(actionRecord) &&
                        record.sourceMessageIdHex == null &&
                        !record.recipientConfirmed
                    ) {
                        MessageStatus.Pending
                    } else {
                        MessageStatus.Sent
                    }
                else -> MessageStatus.Received
            },
        projected = record,
        timelineOrder = localTimelineOrderOverrides[record.messageIdHex] ?: 0uL,
        authoritativeOrder =
            authoritativeTimelineOrderByMessageId[record.messageIdHex]
                ?.takeUnless {
                    record.messageIdHex in localTimelineOrderOverrides ||
                        record.messageIdHex in localTimelineTimestampOverrides
                },
        displayAfterMessageIdHex = durableStreamDisplayParentByMessageId[record.messageIdHex],
        retentionAtSendSeconds = retentionAtSendSeconds.takeIf { actionRecord.retentionSeconds == null },
        presentationId = timelinePresentationIds[record.messageIdHex] ?: projectedItemId(record),
    )
}

internal fun ConversationController.projectedItemId(record: TimelineMessageRecordFfi): String {
    val actionRecord = TimelineProjector.toAppMessageRecord(record)
    val streamId = MessageProjector.streamId(actionRecord).takeIf { MessageProjector.isStreamStart(actionRecord) }
    return streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}"
}

/** Adds an item to the membership index; publication performs display ordering. */
internal fun ConversationController.insertTimelineItemId(itemId: String) {
    // Append in O(1). Position is irrelevant: publishTimelineFromIndexes
    // orders authoritative rows by MDK ordinal and merges local overlays,
    // so timelineOrder is only a membership set. The previous sorted insert
    // did an O(n) scan per item, making each page load O(n²). See #74.
    timelineOrder.add(itemId)
}

internal inline fun ConversationController.coalesceTimelinePublishes(block: () -> Unit) {
    assertMainThread { "coalesceTimelinePublishes" }
    publishSuppressionDepth += 1
    try {
        block()
    } finally {
        publishSuppressionDepth -= 1
        if (publishSuppressionDepth == 0 && publishPending) {
            publishPending = false
            publishTimelineFromIndexesInternal()
        }
    }
}

internal fun ConversationController.publishTimelineFromIndexes() {
    assertMainThread { "publishTimelineFromIndexes" }
    if (publishSuppressionDepth > 0) {
        publishPending = true
        return
    }
    publishTimelineFromIndexesInternal()
}

private fun ConversationController.scheduleTimelinePositionSettlement() {
    timelinePositionSettlementJob =
        deferTimelinePositionSettlement(
            scope = inviteStreamScope,
            currentJob = timelinePositionSettlementJob,
            awaitBoundary = ::awaitRenderedTimelineFrame,
        ) {
            timelinePositionSettlementJob = null
            publishPending = false
            publishTimelineFromIndexes()
        }
}

private suspend fun ConversationController.awaitRenderedTimelineFrame() {
    // Choreographer callbacks run before traversal. Waiting for two callbacks
    // guarantees the preserved state gets one complete draw before settlement
    // updates the state for the following frame.
    repeat(2) {
        suspendCancellableCoroutine { continuation ->
            val choreographer = Choreographer.getInstance()
            val callback =
                Choreographer.FrameCallback {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            continuation.invokeOnCancellation {
                choreographer.removeFrameCallback(callback)
            }
            choreographer.postFrameCallback(callback)
        }
    }
}

/** Publishes the indexed window after row-owned expiry and visibility filters. */
internal fun ConversationController.publishTimelineFromIndexesInternal() {
    // A first page held for its event cards is published whole by its release, with every change made meanwhile.
    if (isHoldingInitialPageForEventCards) return
    // Preserve the handoff snapshot for one complete frame. Any projection
    // or optimistic mutation arriving during that frame is already reflected
    // in the indexes and is folded into the scheduled settlement publish.
    if (timelinePositionSettlementJob?.isActive == true) {
        publishPending = true
        return
    }
    val projected = timelineOrder.mapNotNull { timelineItemsById[it] }
    // Read-anchored local expiry (#797) uses only the policy pinned to each
    // row. The mutable group policy cannot reinterpret projected history.
    // When no row owns a deadline, keep the no-filter-allocation fast path.
    val candidates = optimisticMessages.values + projected
    val hasExpiryCandidate =
        candidates.any { message ->
            message.record.retentionExpiresAt?.let { it > 0uL } == true ||
                message.retentionAtSendSeconds?.let { it > 0uL } == true
        }
    val live =
        if (!hasExpiryCandidate) {
            candidates
        } else {
            val nowMillis = clockMillis()
            val nowSeconds = (nowMillis.coerceAtLeast(0L) / 1_000L).toULong()
            val messageOrder =
                firstMessageOrder(
                    buildList {
                        optimisticMessages.values.forEach { add(it.record.messageIdHex) }
                        projected.forEach { add(it.record.messageIdHex) }
                    },
                )
            candidates.filter { message ->
                val record = message.record
                if (record.direction == "sent" && message.projected == null) {
                    readAnchoredAtSeconds.putIfAbsent(record.messageIdHex, nowSeconds)
                }
                !isTimelineRecordLocallyExpired(
                    nowMillis = nowMillis,
                    record = record,
                    row = localExpiryRow(message, messageOrder),
                )
            }
        }
    val hiddenIds = appState.hiddenMessageIdsInGroup(conversationAccountRef, group.groupIdHex)
    val visible =
        if (hiddenIds.isEmpty()) {
            live
        } else {
            live.filter { message ->
                isTimelineMessageVisible(message.record.messageIdHex, hiddenIds)
            }
        }
    val aggregated = withAuthoritativeEdits(emptyMap(), authoritativeEditsOf(timelineRecords.values))
    // Admission projects the revised body before publication. The original row's
    // delivery state cannot confirm its edit; retain the overlay until the edit's
    // own native publication and exact revision agree.
    optimisticEdits.entries
        .filter { (target, edit) ->
            isConfirmedOptimisticEdit(edit, aggregated[target], timelineRecords[target])
        }.map { it.key }
        .forEach(optimisticEdits::remove)
    timeline =
        orderTimelineMessagesForDisplay(
            (visible + streamDebugTimelineItems.values).map { message ->
                message.withOptimisticEditStatus(optimisticEdits[message.record.messageIdHex])
            },
        )
    timelinePresentationIds.keys.retainAll(timeline.mapTo(HashSet()) { it.record.messageIdHex })
    // The optimistic→confirmed handoff snapshot is intentionally preserved;
    // do not report it as the stale-override symptom this detector targets.
    logTimelineInversionsForDebug(timeline, optimisticSendPositionPreserves.snapshot())
    // Publish the confirmed row once at its optimistic position before
    // cleaning the transient bridge. Cleanup rebuilds only the internal
    // indexes; the immutable snapshot above remains stable for observers,
    // and the next publish settles the row to its projected position.
    if (releaseOrphanedOptimisticSendPreserves()) {
        scheduleTimelinePositionSettlement()
    }
    editsByTarget = applyOptimisticEdits(aggregated, optimisticEdits)
    signalForegroundSweepScheduleChanged()
}

internal fun ConversationController.nextOptimisticTimelineOrder(): ULong =
    nextTimelineOrder(
        published = timeline.asSequence().map { it.timelineOrder },
        pending = optimisticMessages.values.asSequence().map { it.timelineOrder },
    )
