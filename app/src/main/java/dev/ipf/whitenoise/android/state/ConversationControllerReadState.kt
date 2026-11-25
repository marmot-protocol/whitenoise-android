package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.whitenoise.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal suspend fun ConversationController.initializeReadState(account: String) {
    runCatchingCancellable {
        appState.marmotIo { initializeChatReadState(account, group.groupIdHex) }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMConversation", "initialize read state failed", it)
    }
}

/**
 * Submits the visit's visible cursor to MDK, including a saved cursor with pending manual attention.
 * Successful rows fold monotonically; after failures, only the latest remaining request restores display state.
 * A latest failure clears request dedupe for retry, and cancellation is rethrown. A null success acknowledges
 * this row instance until a new native row arrives. Already-confirmed reads do not restart retention deadlines.
 */
internal suspend fun ConversationController.submitVisibleReadUpTo(
    account: String,
    trimmed: String,
) {
    val consumeManualReminder = shouldConsumeManualUnreadReminder(account)
    if (trimmed == lastSubmittedReadMessageId && !consumeManualReminder) return
    val readWasAlreadyConfirmed = trimmed == latestChatListRow?.lastReadMessageIdHex
    val requestId = ++nextReadRequestId
    lastReadRequestId = requestId
    pendingReadRequestIds += requestId
    lastReadMessageId = trimmed
    lastSubmittedReadMessageId = trimmed
    val markReadResult =
        runCatching {
            appState.marmotIo { markTimelineMessageRead(account, group.groupIdHex, trimmed) }
        }
    pendingReadRequestIds -= requestId
    val markReadFailure = markReadResult.exceptionOrNull()
    if (markReadFailure != null) {
        restoreFailedReadRequest(requestId)
        if (markReadFailure is CancellationException) throw markReadFailure
        if (BuildConfig.DEBUG) Log.w("DMConversation", "mark read failed", markReadFailure)
        return
    }
    markReadResult.getOrNull()?.let { row -> foldVisibleReadRow(account, row) }
    if (requestId > lastSuccessfulReadRequestId) {
        lastSuccessfulReadRequestId = requestId
        successfulReadWithoutRow = if (markReadResult.getOrNull() == null) latestChatListRow to trimmed else null
    }
    if (lastReadRequestId == requestId) {
        lastReadMessageId = confirmedReadMessageId()
        lastSubmittedReadMessageId = trimmed
        if (consumeManualReminder) manualUnreadReminderConsumed = true
    }
    // A visit-level attention acknowledgement must not restart an
    // already-confirmed message's disappearing deadline after reopening.
    if (!readWasAlreadyConfirmed) {
        val anchoredAtSeconds = (clockMillis() / 1_000L).toULong()
        anchorReadExpiryUpTo(trimmed, anchoredAtSeconds)
    }
    runCatchingCancellable {
        appState.dismissConversationNotifications(account, group.groupIdHex)
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMConversation", "dismiss read notifications failed", it)
    }
}

/** Restores only the latest failed request; finished requests never regain ownership or suppress retries. */
private fun ConversationController.restoreFailedReadRequest(requestId: Long) {
    if (lastReadRequestId != requestId) return
    lastReadMessageId = confirmedReadMessageId()
    manualUnreadReminderConsumed = false
    lastSubmittedReadMessageId = null
    lastReadRequestId = pendingReadRequestIds.maxOrNull() ?: 0L
}

/** Uses the native cursor, or a successful null-result cursor only while its original row remains current. */
private fun ConversationController.confirmedReadMessageId(): String? =
    successfulReadWithoutRow?.takeIf { it.first === latestChatListRow }?.second
        ?: latestChatListRow?.lastReadMessageIdHex?.takeIf { it.isNotBlank() }

/** Folds MDK's read result into the matching list, or monotonically into this conversation if no list is bound. */
private fun ConversationController.foldVisibleReadRow(
    accountRef: String,
    row: ChatListRowFfi,
) {
    persistedLastReadTimelineAt =
        foldMarkReadReturnedRow(row, persistedLastReadTimelineAt) { incoming ->
            if (!appState.applyChatListRowFromMarkRead(accountRef, incoming)) {
                val folded =
                    latestChatListRow?.let { current ->
                        reduceSubscriptionChatListRow(current, incoming, ChatListUpdateTriggerFfi.UNREAD_CHANGED)
                    } ?: incoming
                applyAuthoritativeChatListRow(accountRef, folded)
            }
        }
}

/** Retries an unconsumed native manual reminder only while this account's conversation is visibly owned. */
private fun ConversationController.shouldConsumeManualUnreadReminder(accountRef: String): Boolean =
    !manualUnreadReminderConsumed &&
        appState.hasManualUnreadReminder(accountRef, group.groupIdHex, latestChatListRow)

/** Anchors local disappearing expiry for [messageId] and every older loaded row in display order. */
private fun ConversationController.anchorReadExpiryUpTo(
    messageId: String,
    anchoredAtSeconds: ULong,
) {
    val ordered = loadedTimelineMessagesInDisplayOrder().map { it.record.messageIdHex }
    val upToIdx = ordered.indexOf(messageId)
    if (upToIdx < 0) {
        readAnchoredAtSeconds.putIfAbsent(messageId, anchoredAtSeconds)
        return
    }
    for (index in 0..upToIdx) {
        ordered.getOrNull(index)?.let { readAnchoredAtSeconds.putIfAbsent(it, anchoredAtSeconds) }
    }
}
