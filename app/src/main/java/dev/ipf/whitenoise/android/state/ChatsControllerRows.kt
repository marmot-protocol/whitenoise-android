package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Applies the merged rows only after checking an unexpected active-row drop against MDK. */
@VisibleForTesting
internal suspend fun ChatsController.applyChatListWindowRows(
    accountRef: String,
    windows: ChatListWindowSet,
): Boolean {
    val folder = folderWindows
    val folderRevision = folder?.currentRevision
    val frame = windows.frame().withFolder(folder)
    val valid = validateChatListWindowRows(accountRef, windows, frame.rows)
    // A shown row's changed picture is decoded first, so it never drops to initials while local bytes decode.
    if (valid && windows.isCurrent(frame)) {
        appState.prepareChangedChatListAvatars(
            accountRef,
            frame.rows,
            chatRowsByGroup.keys.associateWith { selectedAvatarAssetsByGroup[it] },
        )
    }
    // The plain revision guards only the plain rows; a folder swap or install since capture makes this frame
    // stale too, and the change that superseded it publishes its own frame.
    val folderCurrent = folderWindows === folder && folder?.currentRevision == folderRevision
    return when {
        windows.closed || chatListWindows !== windows -> {
            windows.close()
            false
        }
        !windows.isCurrent(frame) || !folderCurrent -> true
        !valid -> {
            windows.close()
            false
        }
        else -> {
            windows.publishIfCurrent(frame) { rows ->
                replacePresentedChatRows(rows, windows.installed(ChatListViewFfi.CHATS))
                stagedChatListFrame = ChatListFrameMark(windows, frame.revision)
                scheduleRecompute()
            }
            !windows.closed
        }
    }
}

// A failed keyed read or a confirmed missing row must abort before replacing the coherent frame.
@Suppress("ReturnCount")
internal suspend fun ChatsController.validateChatListWindowRows(
    accountRef: String,
    windows: ChatListWindowSet,
    rows: List<PresentedChatRowFfi>,
): Boolean {
    val lookup = liveSubscriptions.presentedRowByGroup ?: return true
    val activeWindow = windows.installed(ChatListViewFfi.CHATS)
    val previous =
        chatRowsByGroup.map { (key, row) -> optimisticChatListPreviewByGroup[key]?.baselineRow ?: row }
    val candidates = missingActiveTopChatRows(previous, rows, activeWindow)
    for (old in candidates) {
        val authoritative =
            runCatchingCancellable { lookup(accountRef, old.groupIdHex) }
                .getOrElse { failure ->
                    Log.w("DMChats", "CHAT_LIST_ROW_CHECK_FAILED reason=${failure.javaClass.simpleName}")
                    return false
                }?.row ?: continue
        if (authoritative.belongsInActiveChats() && activeWindow?.shouldContain(authoritative) == true) {
            Log.w(
                "DMChats",
                "CHAT_LIST_INCOMPLETE account=${chatListLogHash(accountRef)} " +
                    "generation=${chatListLogHash(activeWindow.subscriptionGeneration)} " +
                    "sequence=${activeWindow.sequence} previous=${previous.size} incoming=${rows.size}",
            )
            return false
        }
    }
    return true
}

/**
 * Atomically replaces both base rows and their matching selected presentation. [activeWindow] is the
 * active-chats window this frame came from, which tells a row evicted past its tail from a removed one.
 */
internal fun ChatsController.replacePresentedChatRows(
    rows: List<PresentedChatRowFfi>,
    activeWindow: ChatListWindowSnapshotFfi? = null,
) {
    selectedPresentationsByGroup =
        rows.associate { presented ->
            chatRowKey(presented.row.groupIdHex) to
                localGroupNames.reconciledPresentation(presented.row, presented.presentation)
        }
    // MarmotKit 0.10.1 stores avatars durably and names each row's asset here; the row prefers those
    // bytes over fetching its URL, so a cached avatar survives being offline.
    selectedAvatarAssetsByGroup =
        rows
            .mapNotNull { presented ->
                presented.avatarAsset?.let { chatRowKey(presented.row.groupIdHex) to it }
            }.toMap()
    selectedPreviewsByGroup =
        rows.associate { presented -> chatRowKey(presented.row.groupIdHex) to presented.preview }
    selectedActionsByGroup =
        rows.associate { presented -> chatRowKey(presented.row.groupIdHex) to presented.actions }
    rows.forEach { requestChatRowProfiles(it.row) }
    replaceChatRows(rows.map(PresentedChatRowFfi::row), activeWindow)
}

/** Folds one authoritative row and mirrors it into any mounted conversation. */
internal fun ChatsController.foldChatRow(
    incoming: ChatListRowFfi,
    trigger: ChatListUpdateTriggerFfi? = null,
) {
    val row = localGroupNames.reconciled(incoming)
    if (trigger == ChatListUpdateTriggerFfi.MUTE_CHANGED || trigger == ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH) {
        appState.acceptAuthoritativeMuteProjection(accountRef, row.groupIdHex, row.muted, row.mutedUntilMs)
    }
    val key = chatRowKey(row.groupIdHex)
    val state = optimisticChatListPreviewByGroup[key]
    val current = state?.baselineRow ?: chatRowsByGroup[key]
    val folded =
        when {
            current == null -> row
            trigger != null -> reduceSubscriptionChatListRow(current, row, trigger)
            else -> row
        }
    val observesNewActivity = observesSubscriptionActivity(current, folded, trigger)
    if (current != null && row.unreadCount > folded.unreadCount) {
        logStaleChatListUnreadRejected(
            keptUnread = folded.unreadCount,
            rejectedUnread = row.unreadCount,
        )
    }
    if (folded == current) {
        appState.publishConversationChatListRow(accountRef, folded)
        return
    }
    val membershipChanged = current == null
    val effectivePublicationRow =
        if (state == null) {
            chatRowsByGroup[key] = folded
            if (shouldAdvanceChatActivitySequence(current, folded, observesNewActivity)) {
                activitySequenceByGroup[key] = nextChatActivitySequence()
            }
            folded
        } else {
            foldOptimisticChatListBaseline(
                state,
                folded,
                acceptBackwardActivity = trigger == ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
                observesNewActivity = observesNewActivity,
            ).also {
                materializeOptimisticChatListPreview(key, state)
            }
        }
    appState.publishConversationChatListRow(accountRef, effectivePublicationRow)
    if (membershipChanged) noteMaterializedGroupMembershipChanged(removal = MembershipRemoval.None)
    scheduleRecompute()
}

/** Replaces one snapshot row and returns the metadata row safe to publish. */
internal fun ChatsController.replaceChatRow(
    key: String,
    row: ChatListRowFfi,
    previous: ChatListRowFfi?,
    previousSequence: ULong?,
    wasMaterialized: Boolean,
    optimisticState: OptimisticChatListPreviewState?,
): ChatListRowFfi {
    if (optimisticState != null) {
        return foldOptimisticChatListBaseline(optimisticState, row, acceptBackwardActivity = true).also {
            materializeOptimisticChatListPreview(key, optimisticState)
        }
    }
    chatRowsByGroup[key] = row
    activitySequenceByGroup[key] =
        when {
            previous == null && wasMaterialized -> nextChatActivitySequence()
            previous != null && chatListActivityAdvanced(previous, row) -> nextChatActivitySequence()
            else -> previousSequence ?: 0uL
        }
    return row
}

/**
 * Replaces the chat-list window and refreshes mounted conversation metadata. [activeWindow], when known,
 * classifies rows that left because they now rank outside its retained span as evictions rather than removals.
 */
internal fun ChatsController.replaceChatRows(
    incoming: List<ChatListRowFfi>,
    activeWindow: ChatListWindowSnapshotFfi? = null,
) {
    val rows = incoming.map(localGroupNames::reconciled)
    rows.forEach {
        appState.acceptAuthoritativeMuteProjection(accountRef, it.groupIdHex, it.muted, it.mutedUntilMs)
    }
    val previousRowsByGroup =
        chatRowsByGroup.keys.associateWith { key ->
            optimisticChatListPreviewByGroup[key]?.baselineRow ?: chatRowsByGroup.getValue(key)
        }
    val previousKeys = previousRowsByGroup.keys
    val previousSequences =
        previousRowsByGroup.keys.associateWith { key ->
            optimisticChatListPreviewByGroup[key]?.baselineActivitySequence
                ?: activitySequenceByGroup[key]
                ?: 0uL
        }
    val wasMaterialized = previousRowsByGroup.isNotEmpty()
    chatRowsByGroup.clear()
    activitySequenceByGroup.clear()
    rows.forEach { row ->
        val key = chatRowKey(row.groupIdHex)
        val previous = previousRowsByGroup[key]
        val effectivePublicationRow =
            replaceChatRow(
                key = key,
                row = row,
                previous = previous,
                previousSequence = previousSequences[key],
                wasMaterialized = wasMaterialized,
                optimisticState = optimisticChatListPreviewByGroup[key],
            )
        appState.publishConversationChatListRow(accountRef, effectivePublicationRow)
    }
    optimisticChatListPreviewByGroup.keys.retainAll(chatRowsByGroup.keys)
    val liveGroupIds = rows.mapTo(mutableSetOf()) { it.groupIdHex }
    presentationMembersByGroup = presentationMembersByGroup.filterKeys { it in liveGroupIds }
    selfOnlyDirectGraceRetryGroups.retainAll(liveGroupIds)
    memberFetchRetryJobsByGroup.keys
        .filterNot { it in liveGroupIds }
        .toList()
        .forEach(::cancelMemberSnapshotRetry)
    memberFetchRetryBackoffTierByGroup.keys.retainAll(liveGroupIds)
    failedMemberFetches.retainAll(liveGroupIds)
    if (previousKeys != chatRowsByGroup.keys.toSet()) {
        // A bounded window evicts its tail whenever an off-window chat moves in, and a scrolled window also
        // evicts past its first row. Those chats still exist, so an earlier full read still covers them; only a
        // row the window's span should still hold is an ambiguous removal. A frame carries no new keys for a
        // dropped row, so a mid-list chat that rose to the head on a new message counts as ambiguous too.
        val dropped =
            previousRowsByGroup.any { (key, row) ->
                key !in chatRowsByGroup && !row.evictedFrom(activeWindow)
            }
        noteMaterializedGroupMembershipChanged(
            removal = if (dropped) MembershipRemoval.Ambiguous else MembershipRemoval.None,
        )
    }
}

internal fun ChatsController.snapshotChatRowForRemoval(groupIdHex: String): RemovedChatRowSnapshot? {
    val rowKey = chatRowKey(groupIdHex)
    val optimisticState = optimisticChatListPreviewByGroup[rowKey]
    val row = optimisticState?.baselineRow ?: chatRowsByGroup[rowKey] ?: return null
    return RemovedChatRowSnapshot(
        row = row,
        activitySequence = optimisticState?.baselineActivitySequence ?: activitySequenceByGroup[rowKey] ?: 0uL,
        optimisticState = optimisticState?.snapshot(),
    )
}

/** Captures rollback state and hides the row together at admitted local-removal time. */
internal fun ChatsController.removeChatRowOptimistically(groupIdHex: String): RemovedChatRowSnapshot? {
    val snapshot = snapshotChatRowForRemoval(groupIdHex)
    removeChatRow(groupIdHex, optimistic = true, removal = MembershipRemoval.LocalDeletion)
    return snapshot
}

/**
 * Drops one row from the retained window. [removal] says whether the chat is known to be gone — a local
 * deletion — or whether, as for a native removal, the row may only have left the bounded window.
 */
internal fun ChatsController.removeChatRow(
    groupIdHex: String,
    optimistic: Boolean = false,
    removal: MembershipRemoval = MembershipRemoval.Ambiguous,
) {
    val rowKey = chatRowKey(groupIdHex)
    val removedRow = chatRowsByGroup.remove(rowKey)
    if (removedRow != null) {
        activitySequenceByGroup.remove(rowKey)
        optimisticChatListPreviewByGroup.remove(rowKey)
        if (!optimistic) finishRemovedChatRowClientState(removedRow.groupIdHex)
    }
    // Off-window rows can still belong to a mounted full-list read. Every removal changes membership
    // and schedules its replacement, even when this bounded window held no row.
    noteMaterializedGroupMembershipChanged(removal = removal, groupIdHex = groupIdHex)
    scheduleRecompute()
}

internal fun ChatsController.finishRemovedChatRowClientState(groupIdHex: String) {
    val rowKey = chatRowKey(groupIdHex)
    selectedPresentationsByGroup = selectedPresentationsByGroup - rowKey
    selectedPreviewsByGroup = selectedPreviewsByGroup - rowKey
    selectedActionsByGroup = selectedActionsByGroup - rowKey
    cancelMemberSnapshotRetry(groupIdHex)
    memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
    failedMemberFetches.remove(groupIdHex)
    selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
    presentationMembersByGroup = presentationMembersByGroup - groupIdHex
    localGroupNames.forget(groupIdHex)
}

/** Waits for admitted removals without serializing independent full native reads. */
internal suspend fun ChatsController.readAfterRemovals(read: suspend () -> List<PresentedChatRowFfi>?): List<PresentedChatRowFfi>? {
    val runtime = appState.runtimeGeneration
    val sameRuntime =
        appState.localGroupDeleteCleanupMutex.withLock {
            appState.runtimeGeneration == runtime
        }
    // A deletion admitted after this barrier changes the revision captured by the caller before
    // admission, so its in-flight snapshot cannot restore pre-delete rows. The native read itself
    // must remain concurrent with other readers, including a superseding profile request.
    return if (sameRuntime) read().takeIf { appState.runtimeGeneration == runtime } else null
}

/** Bumps the membership version, and records what a removal means for an earlier full-list read. */
internal fun ChatsController.noteMaterializedGroupMembershipChanged(
    removal: MembershipRemoval,
    groupIdHex: String? = null,
) {
    materializedGroupsRevision += 1L
    when (removal) {
        MembershipRemoval.None -> Unit
        MembershipRemoval.Ambiguous -> ambiguousMembershipRemovalsRevision += 1L
        MembershipRemoval.LocalDeletion ->
            locallyDeletedGroupIds += requireNotNull(groupIdHex).lowercase(Locale.ROOT)
    }
}
