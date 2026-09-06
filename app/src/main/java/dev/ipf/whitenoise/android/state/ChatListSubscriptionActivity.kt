package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi

/**
 * Returns whether an ordered subscription event introduces a different last
 * message without moving to an earlier timestamp. Message identifiers are
 * opaque, so their lexical order cannot establish causality within one second.
 */
internal fun observesDistinctNewLastMessage(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
    trigger: ChatListUpdateTriggerFfi,
): Boolean {
    val incomingLast = incoming.lastMessage
    val currentLast = current.lastMessage
    return trigger == ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE &&
        incomingLast != null &&
        (
            currentLast == null ||
                (
                    incomingLast.messageIdHex != currentLast.messageIdHex &&
                        incomingLast.timelineAt >= currentLast.timelineAt
                )
        )
}

/** Determines whether this fold carries causally ordered message activity. */
internal fun observesSubscriptionActivity(
    current: ChatListRowFfi?,
    folded: ChatListRowFfi,
    trigger: ChatListUpdateTriggerFfi?,
): Boolean =
    current != null &&
        trigger != null &&
        observesDistinctNewLastMessage(current, folded, trigger)
