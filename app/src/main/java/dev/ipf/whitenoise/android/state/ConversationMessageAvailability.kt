package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.ReplyNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether an exact message was materialized, proved absent, or can be retried after a window delay. */
internal enum class MessageAvailability {
    AVAILABLE,
    MISSING,
    RETRYABLE,
}

/** Exact-message jump result before the optional legacy timeline fallback. */
internal enum class ConversationWindowJumpResult {
    AVAILABLE,
    MISSING,
    RETRYABLE,
    UNSUPPORTED,
}

/**
 * Loads one exact message without reporting a retryable window delay as a missing projection. An available message is
 * reported only once it is on screen, since every caller then looks it up among the shown rows: a page committed
 * before the conversation's first presentation is ready is indexed but held unpublished for its event cards until
 * then (#2238).
 */
internal suspend fun ConversationController.loadMessageAvailability(
    messageIdHex: String,
    maxOlderPages: Int = ReplyNavigation.MaxOlderPages,
): MessageAvailability =
    materializeMessage(messageIdHex, maxOlderPages).also { availability ->
        if (availability == MessageAvailability.AVAILABLE) awaitEventCardHoldReleased()
    }

/** Indexes one exact message by a window jump, or by older pages where jumps are unsupported, and classifies it. */
@Suppress("ReturnCount")
private suspend fun ConversationController.materializeMessage(
    messageIdHex: String,
    maxOlderPages: Int,
): MessageAvailability {
    if (timelineRecords.containsKey(messageIdHex)) {
        return if (isMessagePublished(messageIdHex)) MessageAvailability.AVAILABLE else MessageAvailability.RETRYABLE
    }
    when (jumpWindowToMessage(messageIdHex)) {
        ConversationWindowJumpResult.AVAILABLE -> return MessageAvailability.AVAILABLE
        ConversationWindowJumpResult.MISSING -> return MessageAvailability.MISSING
        ConversationWindowJumpResult.RETRYABLE -> return MessageAvailability.RETRYABLE
        ConversationWindowJumpResult.UNSUPPORTED -> Unit
    }
    var loadedPageCount = 0
    while (
        ReplyNavigation.shouldLoadOlder(
            targetLoaded = timelineRecords.containsKey(messageIdHex),
            hasMoreBefore = hasMoreBefore,
            loadedPageCount = loadedPageCount,
            maxOlderPages = maxOlderPages,
        )
    ) {
        if (loadOlderPageInternal() != ConversationPageLoad.ADVANCED) break
        loadedPageCount += 1
    }
    return currentMessageAvailability(messageIdHex)
}

private fun ConversationController.currentMessageAvailability(messageIdHex: String): MessageAvailability =
    when {
        isMessagePublished(messageIdHex) -> MessageAvailability.AVAILABLE
        olderPageBlocked || hasMoreBefore || timelineRecords.containsKey(messageIdHex) -> MessageAvailability.RETRYABLE
        else -> MessageAvailability.MISSING
    }

/** Availability is actionable only once the row belongs to the published transcript. */
private fun ConversationController.isMessagePublished(id: String) = timeline.any { it.record.messageIdHex == id }

/** Compatibility boolean for callers that do not present a missing-target error themselves. */
suspend fun ConversationController.loadUntilMessageAvailable(
    messageIdHex: String,
    maxOlderPages: Int = ReplyNavigation.MaxOlderPages,
): Boolean = loadMessageAvailability(messageIdHex, maxOlderPages) == MessageAvailability.AVAILABLE

/** Recenters the active window while preserving retryable, missing and unsupported outcomes. */
private suspend fun ConversationController.jumpWindowToMessage(messageIdHex: String): ConversationWindowJumpResult =
    retryConversationWindowJump(
        subscription = timelineSubscription,
        messageIdHex = messageIdHex,
        targetLoaded = { isMessagePublished(messageIdHex) },
        jumpIfActive = ::jumpIfSubscriptionActive,
        installPage = { page ->
            // The jumped-to window shows once its cards are read, so the landing never composes a card Loading.
            val warmed = warmLaterPageEventCards(page, anchorMessageId = messageIdHex)
            tracedPagingSection(ConversationPagingTraceSection.APPLY) {
                applyTimelinePage(
                    warmed,
                    replaceWindow = false,
                    updatePagination = true,
                    reconcileNewExtendedRecords = true,
                )
            }
            // The reader landed somewhere else in history, so an older prefetch that stood
            // down at the previous edge may ask again from here (#2727).
            automaticPaging.older.reset()
            // The jump must classify its target after the held page becomes the published transcript.
            awaitEventCardHoldReleased()
        },
    )

/** Runs one exact-message jump under the active-call guard so teardown cannot close the handle mid-call. */
private suspend fun ConversationController.jumpIfSubscriptionActive(
    subscription: ConversationTimelineSubscriptionHandle,
    messageIdHex: String,
): ConversationJumpOutcome? =
    timelineSubscriptionActiveCallMutex.withLock {
        val stillActive =
            synchronized(liveSubscriptionLock) {
                !accountTeardownRequested && timelineSubscription === subscription
            }
        if (!stillActive) return@withLock null
        runCatchingCancellable {
            tracedPagingSection(ConversationPagingTraceSection.WINDOW) {
                withContext(Dispatchers.IO) { subscription.jumpToMessage(messageIdHex) }
            }
        }.getOrNull()
    }

/**
 * Retries revision races and window preparation without collapsing them into a missing-target result.
 * An advanced replacement counts as available only after its exact target is installed.
 */
@Suppress("ReturnCount")
internal suspend fun retryConversationWindowJump(
    subscription: ConversationTimelineSubscriptionHandle?,
    messageIdHex: String,
    targetLoaded: () -> Boolean,
    jumpIfActive: suspend (ConversationTimelineSubscriptionHandle, String) -> ConversationJumpOutcome?,
    installPage: suspend (TimelinePageFfi) -> Unit,
): ConversationWindowJumpResult {
    if (subscription == null) return ConversationWindowJumpResult.RETRYABLE
    repeat(CONVERSATION_JUMP_RETRY_ATTEMPTS) { attempt ->
        when (val jump = jumpIfActive(subscription, messageIdHex)) {
            null -> return ConversationWindowJumpResult.RETRYABLE
            ConversationJumpOutcome.Missing -> return ConversationWindowJumpResult.MISSING
            ConversationJumpOutcome.Unsupported -> return ConversationWindowJumpResult.UNSUPPORTED
            is ConversationJumpOutcome.Window ->
                when (val outcome = jump.outcome) {
                    is TimelinePageOutcome.Advanced -> {
                        installPage(outcome.page)
                        return if (targetLoaded()) {
                            ConversationWindowJumpResult.AVAILABLE
                        } else {
                            ConversationWindowJumpResult.RETRYABLE
                        }
                    }
                    is TimelinePageOutcome.Unchanged -> {
                        if (targetLoaded()) return ConversationWindowJumpResult.AVAILABLE
                        val retry =
                            outcome.reason == ConversationWindowUnchangedReason.NOT_READY ||
                                outcome.reason == ConversationWindowUnchangedReason.SUPERSEDED
                        if (!retry || attempt == CONVERSATION_JUMP_RETRY_ATTEMPTS - 1) {
                            return ConversationWindowJumpResult.RETRYABLE
                        }
                        delay(
                            if (outcome.reason == ConversationWindowUnchangedReason.NOT_READY) {
                                CONVERSATION_WINDOW_NOT_READY_RETRY_MS
                            } else {
                                CONVERSATION_WINDOW_SUPERSEDED_RETRY_MS
                            },
                        )
                    }
                }
        }
    }
    return ConversationWindowJumpResult.RETRYABLE
}

/**
 * Move the subscription-owned bounded window toward [match] until that
 * exhaustive local-search result is present or the relevant side of local
 * history is exhausted. Timestamp + id let traversal page in either
 * direction after an earlier jump shifted the capped window.
 *
 * [isCurrent] is checked before every page, so a navigation the reader superseded mid-traversal, such as a search
 * return interrupted by a drag, requests no further page (#4224).
 */
internal suspend fun ConversationController.loadSearchResultMessageAvailable(
    match: ConversationSearchMatch,
    isCurrent: () -> Boolean = { true },
): Boolean {
    var progressing = true
    while (progressing && !timelineRecords.containsKey(match.messageIdHex) && isCurrent()) {
        progressing = pageTowardSearchMatch(match)
    }
    return timelineRecords.containsKey(match.messageIdHex)
}

/** Pages the window once toward [match]; false when it is empty, has no history left that way, or made no progress. */
private suspend fun ConversationController.pageTowardSearchMatch(match: ConversationSearchMatch): Boolean {
    val order = compareBy<TimelineMessageRecordFfi>({ it.timelineAt }, { it.messageIdHex })
    val oldest = timelineRecords.values.minWithOrNull(order) ?: return false
    val newest = timelineRecords.values.maxWith(order)
    val direction =
        conversationSearchPageDirection(
            match = match,
            oldestTimelineAt = oldest.timelineAt,
            oldestMessageId = oldest.messageIdHex,
            newestTimelineAt = newest.timelineAt,
            newestMessageId = newest.messageIdHex,
            hasMoreBefore = hasMoreBefore,
            hasMoreAfter = hasMoreAfter,
        )
    return when (direction) {
        // Anchor at the oldest held row: an unanchored page at the window cap stays
        // head-anchored and trims what it just fetched, so it never reaches the match (#2873).
        ConversationSearchPageDirection.OLDER -> loadOlderPage(anchorMessageIdHex = oldest.messageIdHex)
        ConversationSearchPageDirection.NEWER -> loadNewerPage()
        null -> false
    }
}
