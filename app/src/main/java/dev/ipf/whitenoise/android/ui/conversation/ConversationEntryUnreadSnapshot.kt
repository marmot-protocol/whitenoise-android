package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.firstUnreadReceivedIndex
import dev.ipf.whitenoise.android.state.reconciledConversationEntryUnreadCount

internal data class ConversationEntryUnreadSnapshot(
    val count: Int,
    val firstUnreadMessageId: String?,
)

/** Keep the entry marker fixed for the lifetime of this conversation open. */
internal fun shouldShowConversationEntryUnreadDivider(
    entryUnreadCount: Int,
    dividerRetired: Boolean,
    messageId: String,
    firstUnreadMessageId: String?,
): Boolean =
    entryUnreadCount > 0 &&
        !dividerRetired &&
        messageId == firstUnreadMessageId

/** A reply newer than the frozen entry boundary makes an unread marker misleading. */
internal fun hasSentMessageAfterUnreadBoundary(
    timeline: List<TimelineMessage>,
    firstUnreadMessageId: String?,
): Boolean {
    val boundaryIndex =
        firstUnreadMessageId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> timeline.indexOfFirst { it.record.messageIdHex == id } }
            ?.takeIf { it >= 0 }
            ?: return false
    return timeline.drop(boundaryIndex + 1).any { it.record.direction == "sent" }
}

internal fun conversationReadAnchorCandidateIndex(
    initialTimelineAnchored: Boolean,
    highestVisibleTimelineIndex: Int,
): Int = if (initialTimelineAnchored) highestVisibleTimelineIndex else -1

internal fun shouldCommitConversationInitialAnchor(
    hasRenderedTimeline: Boolean,
    projectionAvailable: Boolean,
    initialTimelineAnchored: Boolean,
    hasScrollRestore: Boolean,
): Boolean =
    hasRenderedTimeline &&
        projectionAvailable &&
        !initialTimelineAnchored &&
        !hasScrollRestore

private data class ConversationEntryUnreadProjection(
    val count: Int,
    val firstUnreadMessageId: String?,
    val readAnchorMessageId: String?,
)

private class ConversationEntryUnreadProjectionHolder(
    var frozen: ConversationEntryUnreadProjection?,
)

/**
 * Freezes the unread boundary on the first non-empty timeline for one
 * controller. Controller identity is part of the key because the same group id
 * can be open under multiple local accounts with different read watermarks.
 */
@Composable
internal fun rememberConversationEntryUnreadSnapshot(
    controllerIdentity: Any,
    projectionUnread: Int,
    projectionFirstUnreadMessageId: String?,
    projectionAvailable: Boolean = true,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): ConversationEntryUnreadSnapshot {
    val currentProjection =
        ConversationEntryUnreadProjection(
            count = projectionUnread.coerceAtLeast(0),
            firstUnreadMessageId = projectionFirstUnreadMessageId?.takeIf { it.isNotBlank() },
            readAnchorMessageId = readAnchorMessageId,
        )
    val hasAuthoritativeUnread =
        currentProjection.count > 0 || currentProjection.firstUnreadMessageId != null
    val canFreezeProjection = projectionAvailable || hasAuthoritativeUnread
    val projectionHolder =
        remember(controllerIdentity) {
            ConversationEntryUnreadProjectionHolder(
                frozen = currentProjection.takeIf { canFreezeProjection },
            )
        }
    if (projectionHolder.frozen == null && canFreezeProjection) {
        // A notification/deep-link open can compose before the chat-list
        // projection arrives. Freeze the first authoritative unread state, not
        // that transient zero; once frozen, later mark-read updates cannot move
        // the entry divider while this conversation remains open.
        projectionHolder.frozen = currentProjection
    }
    val entryProjection = projectionHolder.frozen ?: currentProjection
    return remember(controllerIdentity, entryProjection, timeline.isNotEmpty()) {
        val count =
            if (timeline.isEmpty()) {
                entryProjection.count
            } else {
                reconciledConversationEntryUnreadCount(
                    projectionUnread = entryProjection.count,
                    timeline = timeline,
                    readAnchorMessageId = entryProjection.readAnchorMessageId,
                )
            }
        val firstUnreadIndex = firstUnreadReceivedIndex(timeline, count)
        ConversationEntryUnreadSnapshot(
            count = count,
            firstUnreadMessageId =
                if (count <= 0) {
                    null
                } else {
                    entryProjection.firstUnreadMessageId
                        ?: timeline
                            .getOrNull(firstUnreadIndex)
                            ?.record
                            ?.messageIdHex
                            ?.takeIf { it.isNotBlank() }
                },
        )
    }
}

/** Resolve and, when necessary, page in the frozen first-unread row. */
internal suspend fun resolveConversationEntryUnreadMessageId(
    snapshot: ConversationEntryUnreadSnapshot,
    timeline: () -> List<TimelineMessage>,
    loadUntilMessageAvailable: suspend (String) -> Boolean,
): String? {
    fun loadedMessageId(target: String): String? =
        timeline()
            .firstOrNull { it.record.messageIdHex == target }
            ?.record
            ?.messageIdHex
            ?.takeIf { it.isNotBlank() }

    return if (snapshot.count <= 0) {
        null
    } else {
        val authoritativeId = snapshot.firstUnreadMessageId?.takeIf { it.isNotBlank() }
        val loadedAuthoritativeId =
            authoritativeId?.let { target ->
                loadedMessageId(target)
                    ?: if (loadUntilMessageAvailable(target)) loadedMessageId(target) else null
            }
        loadedAuthoritativeId
            ?: run {
                val currentTimeline = timeline()
                currentTimeline
                    .getOrNull(firstUnreadReceivedIndex(currentTimeline, snapshot.count))
                    ?.record
                    ?.messageIdHex
                    ?.takeIf { it.isNotBlank() }
            }
    }
}
