package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.state.MessageAvailability
import dev.ipf.whitenoise.android.state.isRetentionExpiredForSearch
import java.util.Locale

/** Where a global-search result lands once its conversation is open (#1945). */
internal enum class SearchTargetLanding {
    /** The exact message is in the bounded window: center it, announce it and highlight it. */
    NAVIGATE,

    /** The message is gone for this reader — deleted, hidden, expired or never accepted — and stays unshown. */
    UNAVAILABLE,

    /** The window could not be loaded right now; search state stays and a localized Retry is offered. */
    RETRY,
}

/**
 * Decides a result's landing from what the bounded window load reported. A hidden message is unavailable before
 * any load. A retryable window delay is never reported as a missing message, and a message that loaded but is now
 * deleted, invalidated or past its disappearing deadline is unavailable rather than shown as a tombstone the search
 * snippet once described.
 */
internal fun searchTargetLanding(
    hidden: Boolean,
    availability: MessageAvailability?,
    target: TimelineMessageRecordFfi?,
    nowMillis: Long,
): SearchTargetLanding =
    when {
        hidden || availability == MessageAvailability.MISSING -> SearchTargetLanding.UNAVAILABLE
        availability != MessageAvailability.AVAILABLE -> SearchTargetLanding.RETRY
        target != null && target.noLongerShowable(nowMillis) -> SearchTargetLanding.UNAVAILABLE
        else -> SearchTargetLanding.NAVIGATE
    }

/** Whether a loaded row may no longer be presented as the message search found. */
private fun TimelineMessageRecordFfi.noLongerShowable(nowMillis: Long): Boolean =
    deleted || invalidationStatus != null || isRetentionExpiredForSearch(this, nowMillis)

/** The canonical spelling of a message id, as MDK stores it: trimmed lowercase hex. */
internal fun canonicalMessageId(messageIdHex: String): String = messageIdHex.trim().lowercase(Locale.ROOT)

/**
 * Whether an opened conversation lands its focus target as a global-search result. Only an open that came from
 * search does; a voice, playback or attachment-speech destination keeps its own landing and wording, even though it
 * also names a message to focus.
 */
internal fun landsAsSearchResult(
    focusMessageId: String?,
    focusFromSearch: Boolean,
    ttsFocusSessionId: Long?,
): Boolean = focusFromSearch && focusMessageId != null && ttsFocusSessionId == null

/**
 * The conversation-side steps of landing one result, injected so the order and supersession rules are testable.
 * [isCurrent] is false once a newer tap, a user gesture, or a conversation or account change owns navigation;
 * [ownsNavigation] is the narrower check used once centering ran, since centering itself records a new reading
 * intent. [restoreAnchor] puts the reader back where they were when a loaded window turned out to hold nothing
 * to show.
 */
internal class SearchResultLandingSteps(
    val isCurrent: () -> Boolean,
    val ownsNavigation: () -> Boolean,
    val hidden: () -> Boolean,
    val loadWindow: suspend () -> MessageAvailability,
    val loadedRecord: () -> TimelineMessageRecordFfi?,
    val center: suspend () -> Boolean,
    val highlight: suspend () -> Unit,
    val unavailable: () -> Unit,
    val failed: () -> Unit,
    val restoreAnchor: suspend () -> Unit = {},
    val nowMillis: () -> Long = System::currentTimeMillis,
)

/**
 * Lands one global-search result: loads only the bounded window around the exact message (MDK's window jump, never
 * a walk through intervening history), then centers it and applies the temporary highlight, whose polite live
 * region tells TalkBack where the reader landed. A window that loaded but holds nothing to show, or could not load,
 * restores the reader's previous position before reporting. A message that loaded but could not be centered is
 * offered for Retry rather than dropped silently. Returns the landing, or null when a newer navigation superseded
 * this one, in which case nothing is highlighted or reported.
 */
@Suppress("ReturnCount") // Each supersession point ends the landing where it was detected.
internal suspend fun landSearchResult(steps: SearchResultLandingSteps): SearchTargetLanding? {
    val hidden = steps.hidden()
    val availability = if (hidden) null else steps.loadWindow()
    if (!steps.isCurrent()) return null
    val landing = searchTargetLanding(hidden, availability, steps.loadedRecord(), steps.nowMillis())
    when (landing) {
        SearchTargetLanding.UNAVAILABLE -> {
            if (availability != null) steps.restoreAnchor()
            steps.unavailable()
        }
        SearchTargetLanding.RETRY -> {
            steps.restoreAnchor()
            steps.failed()
        }
        SearchTargetLanding.NAVIGATE -> {
            val centered = steps.center()
            if (!steps.ownsNavigation()) return null
            if (!centered) {
                steps.failed()
                return SearchTargetLanding.RETRY
            }
            steps.highlight()
        }
    }
    return landing
}

/**
 * Puts the reader back where they were before a landing loaded a window that turned out to hold nothing to show.
 * The window jump may have recentered the bounded window on the dead target, so the saved anchor is resolved in the
 * current window only: a reader who was following the newest rows returns to them through [returnToNewest], and a
 * reader deeper in history has their anchor message loaded again by [rematerialize] first. It never falls back to
 * a raw list index from another window; when the anchor cannot be brought back, the reader stays put.
 */
@Suppress("ReturnCount") // Each unrecoverable step leaves the reader where the failed landing left them.
internal suspend fun ConversationScrollCoordinator.restoreReaderAfterLanding(
    bookmark: ConversationScrollBookmark,
    resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
    rematerialize: suspend (messageId: String) -> Boolean,
    returnToNewest: suspend () -> Int?,
): Boolean {
    if (bookmark.settledMode is ConversationScrollMode.FollowingTail) {
        val tail = returnToNewest() ?: return false
        return jumpToNewest(targetIndex = tail)
    }
    if (resolveAnchorIndex(bookmark.anchor) == null) {
        val messageId = bookmark.anchor.messageId ?: return false
        if (!rematerialize(messageId) || resolveAnchorIndex(bookmark.anchor) == null) return false
    }
    // The window move itself may have recorded an intent; only a user gesture should cancel this restore.
    return restoreBookmark(bookmark, expectedIntent = intentToken) { saved -> resolveAnchorIndex(saved) }
}
