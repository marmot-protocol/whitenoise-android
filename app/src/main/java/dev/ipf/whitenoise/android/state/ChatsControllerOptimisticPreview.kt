package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import kotlinx.coroutines.flow.first

/** Captures in-flight previews before a same-process controller replacement. */
internal fun ChatsController.captureOptimisticPreviewHandoff(): OptimisticChatListPreviewHandoff? {
    val owner = accountRef
    return if (owner == null || optimisticChatListPreviewByGroup.isEmpty()) {
        null
    } else {
        OptimisticChatListPreviewHandoff(
            accountRef = owner,
            rowsByGroup = LinkedHashMap(chatRowsByGroup),
            activitySequenceByGroup = LinkedHashMap(activitySequenceByGroup),
            statesByGroup = optimisticChatListPreviewByGroup.mapValues { (_, state) -> state.snapshot() },
            nextActivitySequence = nextActivitySequence,
        )
    }
}

/** Restores only a same-account handoff before the replacement is exposed. */
internal fun ChatsController.restoreOptimisticPreviewHandoff(
    handoff: OptimisticChatListPreviewHandoff,
    expectedAccountRef: String?,
): Boolean {
    if (!canRestoreOptimisticPreviewHandoff(handoff, expectedAccountRef)) return false
    val restoredAccountRef = requireNotNull(expectedAccountRef)
    accountRef = restoredAccountRef
    boundAccountRef = restoredAccountRef
    chatRowsByGroup.clear()
    chatRowsByGroup.putAll(handoff.rowsByGroup)
    activitySequenceByGroup.clear()
    activitySequenceByGroup.putAll(handoff.activitySequenceByGroup)
    optimisticChatListPreviewByGroup.clear()
    optimisticChatListPreviewByGroup.putAll(
        handoff.statesByGroup.mapValues { (_, state) -> state.snapshot() },
    )
    nextActivitySequence = handoff.nextActivitySequence
    optimisticChatListPreviewByGroup.forEach { (rowKey, state) ->
        materializeOptimisticChatListPreview(rowKey, state)
    }
    hasLoadedLocalSnapshot = true
    isLoading = false
    recompute(scheduleBackgroundEnrichment = false)
    return true
}

/** Rejects cleared, cross-account, and already-bound replacements before any state is copied. */
internal fun ChatsController.canRestoreOptimisticPreviewHandoff(
    handoff: OptimisticChatListPreviewHandoff,
    expectedAccountRef: String?,
): Boolean =
    !isCleared &&
        expectedAccountRef != null &&
        handoff.accountRef == expectedAccountRef &&
        (boundAccountRef == null || boundAccountRef == expectedAccountRef)

internal fun ChatsController.chatListActivityAdvanced(
    previous: ChatListRowFfi,
    current: ChatListRowFfi,
): Boolean = compareChatListActivity(previous, current) > 0

/** Chooses when a materialized row receives a fresh in-memory order token. */
internal fun ChatsController.shouldAdvanceChatActivitySequence(
    current: ChatListRowFfi?,
    folded: ChatListRowFfi,
    observesNewActivity: Boolean,
): Boolean = current == null || chatListActivityAdvanced(current, folded) || observesNewActivity

internal fun ChatsController.compareChatListActivity(
    previous: ChatListRowFfi,
    current: ChatListRowFfi,
): Int {
    val lastMessageCompare = compareChatListLastMessageActivity(previous, current)
    return if (lastMessageCompare != 0) {
        lastMessageCompare
    } else {
        current.activitySortAt.compareTo(previous.activitySortAt)
    }
}

internal fun ChatsController.compareChatListLastMessageActivity(
    previous: ChatListRowFfi,
    current: ChatListRowFfi,
): Int {
    val currentLast = current.lastMessage
    val previousLast = previous.lastMessage
    return when {
        currentLast == null && previousLast == null -> 0
        currentLast == null -> -1
        previousLast == null -> 1
        else ->
            compareTimelineAtMessageIdHex(
                currentLast.timelineAt,
                currentLast.messageIdHex,
                previousLast.timelineAt,
                previousLast.messageIdHex,
            )
    }
}

internal fun ChatsController.rememberBaselineActivitySequence(
    state: OptimisticChatListPreviewState,
    row: ChatListRowFfi,
    activitySequence: ULong,
) {
    val activity = chatListLastMessageActivity(row)
    state.baselineActivitySequenceByLastMessage.remove(activity)
    state.baselineActivitySequenceByLastMessage[activity] = activitySequence
    while (state.baselineActivitySequenceByLastMessage.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
        state.baselineActivitySequenceByLastMessage.remove(state.baselineActivitySequenceByLastMessage.keys.first())
    }
}

internal fun ChatsController.chatListLastMessageActivity(row: ChatListRowFfi): ChatListLastMessageActivity =
    ChatListLastMessageActivity(
        activitySortAt = row.activitySortAt,
        timelineAt = row.lastMessage?.timelineAt,
        messageIdHex = row.lastMessage?.messageIdHex,
    )

internal fun ChatsController.representsSameChatListActivity(
    optimistic: ChatListMessagePreviewFfi,
    authoritative: ChatListMessagePreviewFfi,
): Boolean =
    optimistic.timelineAt == authoritative.timelineAt &&
        optimistic.sender == authoritative.sender &&
        optimistic.plaintext == authoritative.plaintext &&
        optimistic.kind == authoritative.kind &&
        optimistic.deleted == authoritative.deleted &&
        optimistic.attachmentKind == authoritative.attachmentKind &&
        optimistic.attachmentCount == authoritative.attachmentCount

internal fun ChatsController.matchingOptimisticPreview(
    state: OptimisticChatListPreviewState,
    row: ChatListRowFfi,
): OptimisticChatListPreviewMatch? {
    val authoritative = row.lastMessage ?: return null
    val matchingEntry =
        state.entries.entries.firstOrNull { (_, entry) ->
            entry.confirmedMessageIdHex == authoritative.messageIdHex
        } ?: state.entries.entries.firstOrNull { (_, entry) ->
            entry.confirmedMessageIdHex == null &&
                representsSameChatListActivity(entry.preview, authoritative)
        }
    return matchingEntry?.let { (entryKey, entry) ->
        OptimisticChatListPreviewMatch(entryKey, entry.activitySequence)
    } ?: state.failedFallbackEntry
        ?.takeIf { entry -> representsSameChatListActivity(entry.preview, authoritative) }
        ?.let { entry ->
            OptimisticChatListPreviewMatch(entryKey = null, activitySequence = entry.activitySequence)
        }
        ?: state.confirmedActivitySequenceById[authoritative.messageIdHex]?.let { activitySequence ->
            OptimisticChatListPreviewMatch(entryKey = null, activitySequence = activitySequence)
        }
}

/** Uses the canonical accepted-pending ID before falling back to render-shape matching. */
internal fun ChatsController.authoritativeOptimisticPreviewMatch(
    state: OptimisticChatListPreviewState,
    row: ChatListRowFfi,
): OptimisticChatListPreviewMatch? {
    val acceptedPendingEntryKey =
        row.lastMessage
            ?.takeIf { it.deliveryState == ChatListMessageDeliveryStateFfi.DELIVERED }
            ?.messageIdHex
            ?.let { confirmedMessageIdHex ->
                appState.acceptedPendingTextOptimisticId(
                    accountRef = accountRef,
                    groupIdHex = row.groupIdHex,
                    confirmedMessageIdHex = confirmedMessageIdHex,
                )
            }
    return acceptedPendingEntryKey
        ?.let { entryKey ->
            state.entries[entryKey]?.let { entry ->
                OptimisticChatListPreviewMatch(entryKey, entry.activitySequence)
            }
        } ?: matchingOptimisticPreview(state, row)
}

internal fun ChatsController.optimisticMatchIsStale(
    match: OptimisticChatListPreviewMatch,
    baselineRow: ChatListRowFfi,
    row: ChatListRowFfi,
    baselineActivitySequence: ULong,
    activityCompare: Int,
): Boolean =
    match.activitySequence < baselineActivitySequence ||
        activityCompare < 0 ||
        (
            match.activitySequence == baselineActivitySequence &&
                row.lastMessage?.messageIdHex != baselineRow.lastMessage?.messageIdHex
        )

/**
 * Keep only the newest failed preview that a later provisional entry is
 * currently hiding. If that newer entry is abandoned, the row can reveal
 * this fallback instead of jumping to the pre-send baseline. The scalar
 * slot preserves the decrypted-preview memory bound: repeated failures
 * replace one fallback rather than accumulating one entry per send.
 */
internal fun ChatsController.pruneUnreachableFailedEntries(state: OptimisticChatListPreviewState) {
    state.failedFallbackEntry =
        state.failedFallbackEntry?.takeIf {
            it.activitySequence > state.baselineActivitySequence
        }
    val newestSequence = state.entries.values.maxOfOrNull { it.activitySequence } ?: return
    val newestSupersededFailure =
        state.entries.values
            .asSequence()
            .filter { entry ->
                entry.confirmedMessageIdHex == null &&
                    entry.preview.deliveryState == ChatListMessageDeliveryStateFfi.FAILED &&
                    entry.activitySequence > state.baselineActivitySequence &&
                    entry.activitySequence < newestSequence
            }.plus(state.failedFallbackEntry?.let(::sequenceOf).orEmpty())
            .maxByOrNull { it.activitySequence }
    state.failedFallbackEntry = newestSupersededFailure
    state.entries.entries.removeAll { (_, entry) ->
        entry.confirmedMessageIdHex == null &&
            entry.preview.deliveryState == ChatListMessageDeliveryStateFfi.FAILED &&
            (entry.activitySequence <= state.baselineActivitySequence || entry.activitySequence < newestSequence)
    }
}

internal fun ChatsController.retireCommittedOptimisticEntriesThrough(
    state: OptimisticChatListPreviewState,
    activitySequence: ULong,
) {
    state.entries.entries.removeAll { (_, entry) ->
        entry.confirmedMessageIdHex != null &&
            entry.activitySequence <= activitySequence
    }
}

/**
 * Folds [row] into the optimistic baseline and returns the row that is safe
 * to expose to conversation-scoped consumers. A pending matching echo is
 * intentionally returned while parked; a rejected stale replay returns the
 * already-accepted baseline instead.
 */
internal fun ChatsController.foldOptimisticChatListBaseline(
    state: OptimisticChatListPreviewState,
    row: ChatListRowFfi,
    acceptBackwardActivity: Boolean = false,
    observesNewActivity: Boolean = false,
): ChatListRowFfi {
    val activityCompare = compareChatListActivity(state.baselineRow, row)
    val match =
        authoritativeOptimisticPreviewMatch(state, row)
            ?.takeUnless { acceptBackwardActivity && activityCompare < 0 }
    acceptDeliveredOptimisticPreview(state, row)
    // A failed send is terminal: its callback will never report a confirmed
    // id, so parking an authoritative row against it would strand the row
    // on FAILED and discard every later update for that message.
    val pendingEntryKey =
        match?.entryKey?.takeIf {
            val entry = state.entries[it]
            entry != null &&
                entry.confirmedMessageIdHex == null &&
                entry.preview.deliveryState != ChatListMessageDeliveryStateFfi.FAILED
        }
    if (pendingEntryKey != null) {
        // The stream can expose the locally committed row before send()
        // reports whether publishing succeeded. Keep it provisional so a
        // later failure still restores the pre-send baseline.
        state.entries[pendingEntryKey] = state.entries.getValue(pendingEntryKey).copy(pendingAuthoritativeRow = row)
        return row
    }
    var acceptRow = true
    if (match != null) {
        // A coalesced snapshot can skip every earlier successful send.
        // Retire older committed previews, whose compact id/sequence
        // tombstones still recognize queued echoes. Pending previews stay
        // until their callback can record a confirmed id or roll them back.
        retireCommittedOptimisticEntriesThrough(state, match.activitySequence)
        // The echo keeps the order assigned when the local send was
        // accepted. If a later authoritative activity already owns the
        // row, consuming this stale echo must not move the row backward.
        val staleMatch =
            optimisticMatchIsStale(
                match,
                state.baselineRow,
                row,
                state.baselineActivitySequence,
                activityCompare,
            )
        if (staleMatch && !acceptBackwardActivity) {
            acceptRow = false
        } else {
            state.baselineActivitySequence = match.activitySequence
            rememberBaselineActivitySequence(state, row, match.activitySequence)
        }
    } else if (acceptBackwardActivity && foldSnapshotKeepingConfirmedSend(state, row)) {
        acceptRow = false
    } else {
        acceptRow =
            foldUnmatchedChatListRow(
                state,
                row,
                activityCompare,
                acceptBackwardActivity,
                observesNewActivity,
            )
    }
    if (acceptRow) state.baselineRow = row
    return if (acceptRow) row else state.baselineRow
}

/**
 * Treats a delivered authoritative row as the exact optimistic ID handoff that an open conversation
 * normally performs. This keeps the chat-list subscription sufficient after navigation disposes the route.
 */
internal fun ChatsController.acceptDeliveredOptimisticPreview(
    state: OptimisticChatListPreviewState,
    row: ChatListRowFfi,
) {
    val delivered =
        row.lastMessage?.takeIf {
            it.deliveryState == ChatListMessageDeliveryStateFfi.DELIVERED
        } ?: return
    appState.pendingSendDiagnostics.surfaceSettled(
        delivered.messageIdHex,
        PerformancePhase.CHAT_LIST_SETTLED,
    )
    val entryKey =
        delivered.messageIdHex.let { confirmedMessageIdHex ->
            appState.acceptedPendingTextOptimisticId(
                accountRef = accountRef,
                groupIdHex = row.groupIdHex,
                confirmedMessageIdHex = confirmedMessageIdHex,
            )
        }
    entryKey?.let { acceptedEntryKey ->
        state.entries[acceptedEntryKey]
            ?.takeIf { it.confirmedMessageIdHex == null }
            ?.let { entry ->
                state.confirmedActivitySequenceById[delivered.messageIdHex] = entry.activitySequence
                while (state.confirmedActivitySequenceById.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
                    state.confirmedActivitySequenceById.remove(state.confirmedActivitySequenceById.keys.first())
                }
                state.entries[acceptedEntryKey] =
                    entry.copy(
                        preview = delivered,
                        confirmedMessageIdHex = delivered.messageIdHex,
                        pendingAuthoritativeRow = null,
                    )
            }
    }
}

/**
 * Sequencing for a row that matches no optimistic preview: reuse the
 * sequence already accepted for this activity when it is known, take a
 * fresh one for genuinely newer activity, and otherwise keep the accepted
 * ordering. Returns whether the row may replace the baseline.
 */
internal fun ChatsController.foldUnmatchedChatListRow(
    state: OptimisticChatListPreviewState,
    row: ChatListRowFfi,
    activityCompare: Int,
    acceptBackwardActivity: Boolean,
    observesNewActivity: Boolean,
): Boolean {
    val knownActivitySequence =
        state.baselineActivitySequenceByLastMessage[chatListLastMessageActivity(row)]
    var acceptRow = true
    when {
        knownActivitySequence != null -> {
            if (knownActivitySequence < state.baselineActivitySequence && !acceptBackwardActivity) {
                acceptRow = false
            } else {
                state.baselineActivitySequence = knownActivitySequence
                rememberBaselineActivitySequence(state, row, knownActivitySequence)
            }
        }

        activityCompare > 0 || observesNewActivity -> {
            state.baselineActivitySequence = nextChatActivitySequence()
            rememberBaselineActivitySequence(state, row, state.baselineActivitySequence)
            state.entries.entries.removeAll { (_, entry) ->
                entry.confirmedMessageIdHex != null &&
                    entry.activitySequence < state.baselineActivitySequence
            }
        }

        activityCompare < 0 && acceptBackwardActivity -> {
            state.baselineActivitySequence = 0uL
            rememberBaselineActivitySequence(state, row, 0uL)
        }

        // Subscription rows remain authoritative for content even when
        // their last-message tuple compares backward. Keep the accepted
        // ordering sequence without leaving other fields stale after
        // same-second id order or preview loss.
        else -> rememberBaselineActivitySequence(state, row, state.baselineActivitySequence)
    }
    return acceptRow
}

/**
 * Handles a snapshot replay that lags a send the engine already confirmed.
 * Rewinding the row to the older last message would regress the list below
 * the conversation's own state, but dropping the row wholesale
 * would strand its unread and read-state — subscription rows stay
 * authoritative for content. So the confirmed send keeps the row's last
 * message and sort position while every other field comes from the fresh
 * snapshot. Returns false when this does not apply, leaving the caller's
 * normal handling in charge.
 */
internal fun ChatsController.foldSnapshotKeepingConfirmedSend(
    state: OptimisticChatListPreviewState,
    row: ChatListRowFfi,
): Boolean {
    val confirmedLastMessage =
        state.baselineRow.lastMessage?.takeIf { baselineLastMessage ->
            state.confirmedActivitySequenceById.containsKey(baselineLastMessage.messageIdHex) &&
                row.lastMessage?.messageIdHex != baselineLastMessage.messageIdHex &&
                compareChatListActivity(state.baselineRow, row) < 0
        } ?: return false
    val merged =
        row.copy(
            lastMessage = confirmedLastMessage,
            activitySortAt = state.baselineRow.activitySortAt,
        )
    state.baselineRow = merged
    rememberBaselineActivitySequence(state, merged, state.baselineActivitySequence)
    return true
}

/** Materializes the newest visible optimistic entry without allowing sequence time to regress. */
internal fun ChatsController.materializeOptimisticChatListPreview(
    rowKey: String,
    state: OptimisticChatListPreviewState,
) {
    pruneUnreachableFailedEntries(state)
    val latestEntry =
        state.entries.values
            .asSequence()
            .plus(state.failedFallbackEntry?.let(::sequenceOf).orEmpty())
            .maxByOrNull { it.activitySequence }
    val visibleEntry = latestEntry?.takeIf { it.activitySequence > state.baselineActivitySequence }
    chatRowsByGroup[rowKey] =
        visibleEntry?.let { entry ->
            state.baselineRow.copy(
                lastMessage = entry.preview,
                activitySortAt = maxOf(state.baselineRow.activitySortAt, entry.preview.timelineAt),
                updatedAt = maxOf(state.baselineRow.updatedAt, entry.preview.timelineAt),
            )
        } ?: state.baselineRow
    activitySequenceByGroup[rowKey] = visibleEntry?.activitySequence ?: state.baselineActivitySequence
    if (state.hasNoOptimisticPreviewWork()) {
        optimisticChatListPreviewByGroup.remove(rowKey)
    }
}

/**
 * Publishes a ready optimistic preview with publication-time activity order.
 * Returning to the list shows it immediately while the real stream catches up (#900).
 */
internal fun ChatsController.applyOptimisticSentPreview(
    groupIdHex: String,
    preview: ChatListMessagePreviewFfi,
): Boolean {
    val rowKey = chatRowKey(groupIdHex)
    val row = chatRowsByGroup[rowKey].takeIf { accountRef != null } ?: return false
    val state =
        optimisticChatListPreviewByGroup.getOrPut(rowKey) {
            val baselineActivitySequence = activitySequenceByGroup[rowKey] ?: 0uL
            OptimisticChatListPreviewState(
                baselineRow = row,
                baselineActivitySequence = baselineActivitySequence,
            ).also { state ->
                rememberBaselineActivitySequence(state, row, baselineActivitySequence)
            }
        }
    if (state.failedFallbackEntry?.preview?.messageIdHex == preview.messageIdHex) {
        state.failedFallbackEntry = null
    }
    state.entries[preview.messageIdHex] =
        OptimisticChatListPreviewEntry(
            preview = preview,
            activitySequence = nextChatActivitySequence(),
        )
    materializeOptimisticChatListPreview(rowKey, state)
    scheduleRecompute()
    return true
}

/**
 * Reserves a text send's chat-list order at acceptance without publishing
 * its raw Markdown. Parsing may complete after newer list activity, so the
 * later styled/fallback publication must reuse this order rather than gain
 * a fresh sequence merely because its parser was slower.
 */
internal fun ChatsController.reserveOptimisticSentPreview(
    groupIdHex: String,
    optimisticMessageIdHex: String,
): Boolean {
    val rowKey = chatRowKey(groupIdHex)
    val row = chatRowsByGroup[rowKey].takeIf { accountRef != null } ?: return false
    val state =
        optimisticChatListPreviewByGroup.getOrPut(rowKey) {
            val baselineActivitySequence = activitySequenceByGroup[rowKey] ?: 0uL
            OptimisticChatListPreviewState(
                baselineRow = row,
                baselineActivitySequence = baselineActivitySequence,
            ).also { newState ->
                rememberBaselineActivitySequence(newState, row, baselineActivitySequence)
            }
        }
    state.reservedActivitySequenceById[optimisticMessageIdHex] = nextChatActivitySequence()
    scheduleRecompute()
    while (state.reservedActivitySequenceById.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
        state.reservedActivitySequenceById.remove(state.reservedActivitySequenceById.keys.first())
    }
    return true
}

/** Publishes a parsed text preview using its acceptance-time order. */
internal fun ChatsController.applyReservedOptimisticSentPreview(
    groupIdHex: String,
    preview: ChatListMessagePreviewFfi,
): Boolean {
    val rowKey = chatRowKey(groupIdHex)
    val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return false
    val activitySequence = state.reservedActivitySequenceById.remove(preview.messageIdHex)
    return if (activitySequence == null) {
        false
    } else {
        if (state.failedFallbackEntry?.preview?.messageIdHex == preview.messageIdHex) {
            state.failedFallbackEntry = null
        }
        state.entries[preview.messageIdHex] =
            OptimisticChatListPreviewEntry(
                preview = preview,
                activitySequence = activitySequence,
            )
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
        true
    }
}

/** Commits the accepted optimistic entry while preserving its original list-order sequence. */
internal fun ChatsController.commitOptimisticSentPreview(
    groupIdHex: String,
    optimisticMessageIdHex: String,
    confirmedMessageIdHex: String,
) {
    val rowKey = chatRowKey(groupIdHex)
    val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
    val entry = state.entries[optimisticMessageIdHex] ?: return
    state.failedFallbackEntry =
        state.failedFallbackEntry?.takeIf {
            it.activitySequence >= entry.activitySequence
        }
    state.confirmedActivitySequenceById[confirmedMessageIdHex] = entry.activitySequence
    while (state.confirmedActivitySequenceById.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
        state.confirmedActivitySequenceById.remove(state.confirmedActivitySequenceById.keys.first())
    }
    state.entries[optimisticMessageIdHex] =
        entry.copy(
            preview =
                entry.preview.copy(
                    messageIdHex = confirmedMessageIdHex,
                    groupSystem = null,
                    deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED,
                ),
            confirmedMessageIdHex = confirmedMessageIdHex,
            pendingAuthoritativeRow = null,
        )
    // Re-evaluate an echo that arrived while this send was still pending.
    // An exact confirmed id retires this entry; an id mismatch is offered to
    // the next still-pending identical send instead.
    entry.pendingAuthoritativeRow?.let { row -> foldOptimisticChatListBaseline(state, row) }
    state.entries[optimisticMessageIdHex]?.let { committedEntry ->
        state.entries.entries.removeAll { (messageIdHex, candidate) ->
            messageIdHex != optimisticMessageIdHex &&
                candidate.confirmedMessageIdHex != null &&
                candidate.activitySequence < committedEntry.activitySequence
        }
        val supersededByNewerCommit =
            state.entries.any { (messageIdHex, candidate) ->
                messageIdHex != optimisticMessageIdHex &&
                    candidate.confirmedMessageIdHex != null &&
                    candidate.activitySequence > committedEntry.activitySequence
            }
        if (committedEntry.activitySequence <= state.baselineActivitySequence || supersededByNewerCommit) {
            state.entries.remove(optimisticMessageIdHex)
        }
    }
    materializeOptimisticChatListPreview(rowKey, state)
    scheduleRecompute()
}

/**
 * Flips a still-tracked optimistic preview to FAILED instead of erasing
 * it. A failed send must stay visible as failed on the row — silently
 * reverting the list to the prior message while the conversation shows a
 * failed bubble breaks trust in the list. Genuine abandonment
 * (discard, own eviction) still uses [rollbackOptimisticSentPreview].
 */
internal fun ChatsController.failOptimisticSentPreview(
    groupIdHex: String,
    optimisticMessageIdHex: String,
) {
    val rowKey = chatRowKey(groupIdHex)
    val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
    val entry = state.entries[optimisticMessageIdHex] ?: return
    state.entries[optimisticMessageIdHex] =
        entry.copy(
            preview = entry.preview.copy(deliveryState = ChatListMessageDeliveryStateFfi.FAILED),
        )
    materializeOptimisticChatListPreview(rowKey, state)
    scheduleRecompute()
}

/** Removes a reservation or entry only when that exact optimistic id is still tracked. */
internal fun ChatsController.rollbackOptimisticSentPreview(
    groupIdHex: String,
    optimisticMessageIdHex: String,
) {
    val rowKey = chatRowKey(groupIdHex)
    val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
    val removedReservation = state.reservedActivitySequenceById.remove(optimisticMessageIdHex) != null
    val removedEntry = state.entries.remove(optimisticMessageIdHex)
    val removedFallback = state.failedFallbackEntry?.preview?.messageIdHex == optimisticMessageIdHex
    if (removedFallback) state.failedFallbackEntry = null
    if (!removedReservation && removedEntry == null && !removedFallback) return
    removedEntry
        ?.pendingAuthoritativeRow
        ?.takeIf { row -> row.lastMessage?.deliveryState == ChatListMessageDeliveryStateFfi.DELIVERED }
        ?.let { row -> foldOptimisticChatListBaseline(state, row) }
    materializeOptimisticChatListPreview(rowKey, state)
    scheduleRecompute()
}
