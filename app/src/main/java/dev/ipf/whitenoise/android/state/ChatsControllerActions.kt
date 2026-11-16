package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListViewQueryFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Shares the existing whole-selection overlay owner rather than introducing a second archive flow.
 * Classify the complete scope before its shared overlay; fence ownership before and after native reads.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
internal suspend fun ChatsController.runChatListArchiveBatch(
    targets: List<String>,
    command: ChatListBatchCommand,
    owner: ChatListActionOwner,
    onProgress: (Int, Int) -> Unit,
): ChatListBatchResult {
    val outcomes = targets.associateWith { ChatListBatchOutcome.NOT_STARTED }.toMutableMap()
    val attemptedIds = mutableSetOf<String>()
    val timedOutIds = mutableSetOf<String>()
    val rows = mutableListOf<ChatListRowFfi>()
    for (id in targets) {
        if (!chatListActionIsCurrent(owner)) break
        val read =
            runCatchingCancellable {
                val loaded =
                    kotlinx.coroutines.withTimeoutOrNull(CHAT_LIST_BATCH_STEP_TIMEOUT_MS) {
                        listOf(liveSubscriptions.presentedRowByGroup?.invoke(owner.accountRef, id))
                    } ?: error("Native chat lookup timed out")
                loaded.single()?.also { row ->
                    check(row.row.groupIdHex.equals(id, ignoreCase = true))
                }
            }
        if (!chatListActionIsCurrent(owner)) break
        val row = read.getOrNull()
        if (read.isFailure) {
            outcomes[id] = ChatListBatchOutcome.FAILED
        } else if (row == null) {
            outcomes[id] = ChatListBatchOutcome.SKIPPED
        } else {
            val disposition = chatListBatchDisposition(row, command)
            if (disposition == null) rows += row.row else outcomes[id] = disposition
        }
    }
    if (chatListActionIsCurrent(owner)) {
        val commitContext = currentCoroutineContext()
        val commitIsCurrent = { commitContext.isActive && chatListActionIsCurrent(owner) }
        archiveActions.setArchivedRows(
            owner.accountRef,
            owner.bindEpoch,
            ResolvedArchiveRows(rows, commitIsCurrent),
            command == ChatListBatchCommand.ARCHIVE,
            false,
            onAttempted = { id ->
                val key = id.lowercase(java.util.Locale.ROOT)
                attemptedIds += key
                outcomes[key] = ChatListBatchOutcome.UNCONFIRMED
            },
            onSettled = { id, changed ->
                if (commitIsCurrent()) {
                    outcomes[id.lowercase(java.util.Locale.ROOT)] =
                        if (changed) ChatListBatchOutcome.CHANGED else ChatListBatchOutcome.UNCONFIRMED
                    onProgress(outcomes.values.count { it != ChatListBatchOutcome.NOT_STARTED }, targets.size)
                }
            },
            stepTimeoutMillis = CHAT_LIST_BATCH_STEP_TIMEOUT_MS,
            onTimedOut = { id -> timedOutIds += id.lowercase(java.util.Locale.ROOT) },
            notifyFailures = false,
        )
    }
    for (id in targets) {
        if (!chatListActionIsCurrent(owner)) break
        // A timed-out native attempt stays uncertain until an explicit read-only Check results.
        if (id in attemptedIds && id !in timedOutIds) {
            val confirmed = reconcileChatListBatchTarget(id, command, owner)
            outcomes[id] =
                if (confirmed == ChatListBatchOutcome.SATISFIED) ChatListBatchOutcome.CHANGED else confirmed
        }
    }
    return ChatListBatchResult(outcomes.toMap(), attemptedIds.toSet())
}

/** Read the captured target again without replaying its native mutation. */
internal suspend fun ChatsController.reconcileChatListBatchTarget(
    id: String,
    command: ChatListBatchCommand,
    owner: ChatListActionOwner,
): ChatListBatchOutcome =
    runCatchingCancellable {
        kotlinx.coroutines.withTimeoutOrNull(CHAT_LIST_BATCH_STEP_TIMEOUT_MS) {
            if (
                (
                    command == ChatListBatchCommand.PIN ||
                        command == ChatListBatchCommand.UNPIN
                ) &&
                !reconcileNativePinState(owner)
            ) {
                return@withTimeoutOrNull ChatListBatchOutcome.UNCONFIRMED
            }
            val row = liveSubscriptions.presentedRowByGroup?.invoke(owner.accountRef, id)
            if (!chatListActionIsCurrent(owner)) return@withTimeoutOrNull ChatListBatchOutcome.UNCONFIRMED
            if (row == null) return@withTimeoutOrNull ChatListBatchOutcome.UNCONFIRMED
            check(row.row.groupIdHex.equals(id, ignoreCase = true))
            val disposition = chatListBatchDisposition(row, command)
            if (disposition == ChatListBatchOutcome.SATISFIED) applyChatListActionRow(row.row)
            disposition ?: ChatListBatchOutcome.FAILED
        } ?: ChatListBatchOutcome.UNCONFIRMED
    }.getOrDefault(ChatListBatchOutcome.UNCONFIRMED)

/** A pin may commit before native row hydration fails; recover all neighbouring positions too. */
@Suppress("ReturnCount") // Reject stale or unavailable complete pin state before applying a projection.
internal suspend fun ChatsController.reconcileNativePinState(owner: ChatListActionOwner): Boolean {
    val order = authoritativePinOrder(owner) ?: return false
    if (!chatListActionIsCurrent(owner)) return false
    applyPinState(ChatPinStateFfi(order))
    return true
}

/** Read the current native pin universe before applying any complete-order projection. */
@Suppress("ReturnCount") // Refuse retired or unavailable whole-list pin reads before they can alter a projection.
internal suspend fun ChatsController.authoritativePinOrder(owner: ChatListActionOwner): List<String>? {
    if (!chatListActionIsCurrent(owner)) return null
    val rows = liveSubscriptions.presentedChatList?.invoke(owner.accountRef, false) ?: return null
    if (!chatListActionIsCurrent(owner)) return null
    val order =
        rows
            .map { it.row }
            .filter { it.pinned }
            .sortedBy { it.pinnedPosition }
            .map { it.groupIdHex }
    check(order.distinctBy { it.lowercase(java.util.Locale.ROOT) }.size == order.size)
    return order
}

/** Apply direct state commands after archive and folder requests have selected their dedicated owners. */
internal suspend fun ChatsController.applyChatListBatchCommand(
    item: ChatListItem,
    command: ChatListBatchCommand,
    owner: ChatListActionOwner,
): Boolean =
    when (command) {
        ChatListBatchCommand.READ -> markAllRead(item, owner)
        ChatListBatchCommand.UNREAD -> markUnread(item, owner)
        ChatListBatchCommand.PIN -> setPinned(item, true, owner)
        ChatListBatchCommand.UNPIN -> setPinned(item, false, owner)
        ChatListBatchCommand.MUTE, ChatListBatchCommand.UNMUTE ->
            appState.setConversationMutedAwaited(
                owner.accountRef,
                item.group.groupIdHex,
                command == ChatListBatchCommand.MUTE,
            ) { chatListActionIsCurrent(owner) }
        else -> error("Command $command must use its dedicated batch owner")
    }

/** Exclude the captured ID only from the still-current captured folder preference owner. */
@Suppress("ReturnCount") // Guard missing folders and satisfied exclusions before the existing preference mutation.
internal fun ChatsController.excludeChatListBatchTarget(
    id: String,
    folderId: String?,
    owner: ChatListActionOwner,
): ChatListBatchOutcome {
    val preferences = appState.chatFolderPreferences
    if (folderId == null || preferences.foldersFor(owner.accountRef).none { it.id == folderId }) {
        return ChatListBatchOutcome.SKIPPED
    }
    if (id in preferences.excludedChats(owner.accountRef, folderId)) return ChatListBatchOutcome.SATISFIED
    if (!chatListActionIsCurrent(owner)) return ChatListBatchOutcome.NOT_STARTED
    return if (preferences.excludeChat(owner.accountRef, folderId, id)) {
        ChatListBatchOutcome.CHANGED
    } else {
        ChatListBatchOutcome.FAILED
    }
}

/**
 * Drops captured IDs that no longer match [folder] in any view it covers; keeps caller order, never adds. A
 * departed group the folder showed from loaded rows stays selected while MarmotKit still files it under Left.
 */
internal suspend fun ChatsController.revalidateFolderChatListIds(
    folder: ChatFolderQuery,
    groupIds: List<String>,
    owner: ChatListActionOwner,
): List<String>? {
    val revalidate = liveSubscriptions.revalidateChatListQueryIds?.takeIf { chatListActionSessionIsCurrent(owner) }
    return when {
        groupIds.isEmpty() -> emptyList()
        revalidate == null -> null
        else -> {
            val kept = hashSetOf<String>()
            folder.views.forEach { view ->
                val matching = revalidate(owner.accountRef, folder.forView(view), groupIds)
                matching.mapTo(kept) { it.lowercase(Locale.ROOT) }
            }
            val outside = groupIds.filter { it.lowercase(Locale.ROOT) !in kept }
            // Deliberately unfiltered, like the plain path: a departed chat the folder showed was matched by the
            // loaded-rows evaluator, and MarmotKit's Left scope cannot re-evaluate rules such as Unread there, so
            // a selected departed chat stays selected for as long as it is still departed.
            if (outside.isNotEmpty()) {
                revalidate(owner.accountRef, ChatListViewQueryFfi(ChatListViewFfi.LEFT, emptyList()), outside)
                    .mapTo(kept) { it.lowercase(Locale.ROOT) }
            }
            groupIds.filter { it.lowercase(Locale.ROOT) in kept }.takeIf { chatListActionSessionIsCurrent(owner) }
        }
    }
}

internal suspend fun ChatsController.resolveChatListActionRow(
    groupIdHex: String,
    owner: ChatListActionOwner,
): PresentedChatRowFfi? {
    val lookup =
        liveSubscriptions.presentedRowByGroup?.takeIf {
            chatListActionIsCurrent(owner) && owner.activeAccountRef == owner.accountRef
        } ?: return null
    val revision = materializedGroupsRevision
    val rows =
        runCatchingCancellable {
            readAfterRemovals { listOfNotNull(lookup(owner.accountRef, groupIdHex)) }
        }.getOrNull()
    return rows?.singleOrNull()?.takeIf {
        chatListActionIsCurrent(owner) &&
            materializedGroupsRevision == revision &&
            it.row.groupIdHex.equals(groupIdHex, ignoreCase = true)
    }
}

/** Updates retained native rows without inserting an off-window command result into the bounded list. */
internal fun ChatsController.applyChatListActionRow(row: ChatListRowFfi?) {
    if (row != null && containsGroup(row.groupIdHex)) applyChatListRow(row) else scheduleRecompute()
}

/** Boxes a native result so a null row still counts as admitted. */
internal class AdmittedChatListAction<T>(
    val value: T,
)

/**
 * Runs one chat-list row command: [command] only while [owner] is still current, and [apply]
 * only while its session still owns the result. Failures stay quiet for the user and are
 * logged in debug builds, since these are idempotent affordances.
 */
internal suspend fun <T> ChatsController.runAdmittedChatListAction(
    owner: ChatListActionOwner,
    label: String,
    command: suspend MarmotInterface.() -> T,
    apply: suspend (T) -> Unit,
): Boolean =
    runCatchingCancellable {
        val admitted =
            appState.marmotIo {
                if (chatListActionIsCurrent(owner)) AdmittedChatListAction(command()) else null
            }
        if (admitted == null || !chatListActionSessionIsCurrent(owner)) {
            false
        } else {
            apply(admitted.value)
            true
        }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMChatsController", "$label failed", it)
    }.getOrDefault(false)

/**
 * Fold the engine's normalized pin order into the held rows so the list
 * reorders immediately; the PIN_ORDER_CHANGED trigger (or a snapshot)
 * re-delivers the same state authoritatively moments later.
 */
internal fun ChatsController.applyPinState(state: ChatPinStateFfi) {
    val positionByGroup =
        state.orderedGroupIds
            .withIndex()
            .associate { (index, id) -> id.lowercase() to index.toUInt() }
    var changed = false
    chatRowsByGroup.keys.toList().forEach { key ->
        val position = positionByGroup[key]
        val optimisticState = optimisticChatListPreviewByGroup[key]
        val row = optimisticState?.baselineRow ?: chatRowsByGroup[key] ?: return@forEach
        val updated =
            if (position != null) {
                row.copy(pinned = true, pinnedPosition = position)
            } else if (row.pinned) {
                row.copy(pinned = false, pinnedPosition = null)
            } else {
                row
            }
        if (updated == row) return@forEach
        changed = true
        if (optimisticState == null) {
            chatRowsByGroup[key] = updated
        } else {
            optimisticState.baselineRow = updated
            materializeOptimisticChatListPreview(key, optimisticState)
        }
    }
    if (changed) scheduleRecompute()
}
