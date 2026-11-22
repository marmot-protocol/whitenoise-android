package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.core.ConversationSearchMatch

/** Delivery changes can carry the peer reaction that confirms a previously failed send. */
internal fun TimelineUpdateTriggerFfi.recomputesReactions(): Boolean =
    when (this) {
        TimelineUpdateTriggerFfi.REACTION_ADDED,
        TimelineUpdateTriggerFfi.REACTION_REMOVED,
        TimelineUpdateTriggerFfi.MESSAGE_DELETED,
        TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
        TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH,
        TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED,
        -> true
        TimelineUpdateTriggerFfi.NEW_MESSAGE,
        TimelineUpdateTriggerFfi.REPLY_PREVIEW_CHANGED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_STARTED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_FINISHED,
        TimelineUpdateTriggerFfi.RECEIPT_CHANGED,
        // Typed agent, group-system, and custom-event updates do not mutate
        // reaction tallies, so they fall in the false bucket — kept explicit
        // so a future trigger that *does* change
        // reactions fails the exhaustiveness check rather than silently
        // missing a recompute.
        TimelineUpdateTriggerFfi.AGENT_ACTIVITY,
        TimelineUpdateTriggerFfi.AGENT_OPERATION,
        TimelineUpdateTriggerFfi.GROUP_SYSTEM,
        TimelineUpdateTriggerFfi.CUSTOM_EVENT,
        -> false
    }

@Suppress("MayBeConstant")
internal val ConversationTimelinePageLimit = 50u

internal fun compareConversationTimelinePosition(
    firstAt: ULong,
    firstId: String,
    secondAt: ULong,
    secondId: String,
): Int = firstAt.compareTo(secondAt).takeIf { it != 0 } ?: firstId.compareTo(secondId)

internal enum class ConversationSearchPageDirection { OLDER, NEWER }

internal enum class ConversationLoadFailureEdge { TOP, BOTTOM }

internal fun conversationLoadFailureEdge(
    hasPageFailure: Boolean,
    failedPageDirection: ConversationSearchPageDirection?,
): ConversationLoadFailureEdge =
    if (hasPageFailure && failedPageDirection == ConversationSearchPageDirection.NEWER) {
        ConversationLoadFailureEdge.BOTTOM
    } else {
        ConversationLoadFailureEdge.TOP
    }

internal fun conversationSearchPageDirection(
    match: ConversationSearchMatch,
    oldestTimelineAt: ULong,
    oldestMessageId: String,
    newestTimelineAt: ULong,
    newestMessageId: String,
    hasMoreBefore: Boolean,
    hasMoreAfter: Boolean,
): ConversationSearchPageDirection? =
    when {
        hasMoreBefore &&
            compareConversationTimelinePosition(
                match.timelineAt,
                match.messageIdHex,
                oldestTimelineAt,
                oldestMessageId,
            ) < 0 -> ConversationSearchPageDirection.OLDER
        hasMoreAfter &&
            compareConversationTimelinePosition(
                match.timelineAt,
                match.messageIdHex,
                newestTimelineAt,
                newestMessageId,
            ) > 0 -> ConversationSearchPageDirection.NEWER
        else -> null
    }

// Cap on the live-projected timeline window (≈4 pages). Bounds memory for a
// long-open, busy conversation while leaving ample scroll headroom before
// loadOlder() must re-fetch.
internal const val LIVE_TIMELINE_WINDOW_CAP = 200
