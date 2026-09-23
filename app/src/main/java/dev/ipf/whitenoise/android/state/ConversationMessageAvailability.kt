package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelinePageFfi
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

/** Loads one exact message without reporting a retryable window delay as a missing projection. */
@Suppress("ReturnCount")
internal suspend fun ConversationController.loadMessageAvailability(
    messageIdHex: String,
    maxOlderPages: Int = ReplyNavigation.MaxOlderPages,
): MessageAvailability {
    if (timelineRecords.containsKey(messageIdHex)) return MessageAvailability.AVAILABLE
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
    return when {
        timelineRecords.containsKey(messageIdHex) -> MessageAvailability.AVAILABLE
        olderPageBlocked || hasMoreBefore -> MessageAvailability.RETRYABLE
        else -> MessageAvailability.MISSING
    }
}

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
        targetLoaded = { timelineRecords.containsKey(messageIdHex) },
        jumpIfActive = ::jumpIfSubscriptionActive,
        installPage = { page ->
            tracedPagingSection(ConversationPagingTraceSection.APPLY) {
                applyTimelinePage(page, replaceWindow = true, updatePagination = true)
            }
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
                        if (outcome.reason == ConversationWindowUnchangedReason.NOT_READY) {
                            delay(CONVERSATION_WINDOW_NOT_READY_RETRY_MS)
                        }
                    }
                }
        }
    }
    return ConversationWindowJumpResult.RETRYABLE
}
