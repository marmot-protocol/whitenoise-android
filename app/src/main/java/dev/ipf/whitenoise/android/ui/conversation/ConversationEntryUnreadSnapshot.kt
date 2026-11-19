package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.firstUnreadReceivedIndex
import dev.ipf.whitenoise.android.state.isUnreadEligible
import dev.ipf.whitenoise.android.state.reconciledConversationEntryUnreadCount

internal data class ConversationEntryUnreadSnapshot(
    val count: Int,
    val firstUnreadMessageId: String?,
    val projectionCaptured: Boolean = true,
    val readAnchorMessageId: String? = null,
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

/**
 * The projected first-unread id, unless it is the read anchor or the loaded rows place it before the
 * anchor. A row folded before newer messages arrived can keep an old first-unread id after the cursor
 * moved past it. Its count is just as old, so the divider goes on the first unread message after a
 * loaded anchor. An id equal to an unloaded anchor is kept so paging can load the anchor and move it.
 */
internal fun firstUnreadAfterReadAnchor(
    firstUnreadId: String?,
    readAnchorMessageId: String?,
    timeline: List<TimelineMessage>,
): String? {
    if (firstUnreadId == null) return null

    fun indexOf(id: String?) = if (id == null) -1 else timeline.indexOfFirst { it.record.messageIdHex == id }
    val anchorIndex = indexOf(readAnchorMessageId)
    val stale = firstUnreadId == readAnchorMessageId || (anchorIndex >= 0 && indexOf(firstUnreadId) in 0..anchorIndex)
    return when {
        !stale -> firstUnreadId
        anchorIndex < 0 -> firstUnreadId
        else ->
            timeline
                .drop(anchorIndex + 1)
                .firstOrNull { it.record.isUnreadEligible() }
                ?.record
                ?.messageIdHex
                ?.takeIf { it.isNotBlank() }
    }
}

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
    val projectionCaptured = projectionHolder.frozen != null
    val entryProjection = projectionHolder.frozen ?: currentProjection
    val snapshot =
        remember(controllerIdentity, entryProjection, timeline.isNotEmpty(), projectionCaptured) {
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
                projectionCaptured = projectionCaptured,
                readAnchorMessageId = entryProjection.readAnchorMessageId,
                firstUnreadMessageId =
                    if (count <= 0) {
                        null
                    } else {
                        firstUnreadAfterReadAnchor(
                            entryProjection.firstUnreadMessageId,
                            entryProjection.readAnchorMessageId,
                            timeline,
                        )
                            ?: timeline
                                .getOrNull(firstUnreadIndex)
                                ?.record
                                ?.messageIdHex
                                ?.takeIf { it.isNotBlank() }
                    },
            )
        }
    return rememberAnchorCheckedSnapshot(snapshot, timeline)
}

/** Paging can load both the anchor and the frozen id, proving it stale. Move the line once and keep it there. */
@Composable
private fun rememberAnchorCheckedSnapshot(
    snapshot: ConversationEntryUnreadSnapshot,
    timeline: List<TimelineMessage>,
): ConversationEntryUnreadSnapshot {
    val anchorCheck = remember(snapshot) { ConversationEntryAnchorCheck() }
    if (!anchorCheck.done && canCheckAgainstReadAnchor(snapshot, timeline)) {
        anchorCheck.done = true
        anchorCheck.firstUnreadMessageId =
            firstUnreadAfterReadAnchor(snapshot.firstUnreadMessageId, snapshot.readAnchorMessageId, timeline)
    }
    return if (anchorCheck.done) snapshot.copy(firstUnreadMessageId = anchorCheck.firstUnreadMessageId) else snapshot
}

private fun canCheckAgainstReadAnchor(
    snapshot: ConversationEntryUnreadSnapshot,
    timeline: List<TimelineMessage>,
): Boolean {
    fun loaded(id: String?) = id != null && timeline.any { it.record.messageIdHex == id }
    val anchorId = snapshot.readAnchorMessageId
    val firstUnreadId = snapshot.firstUnreadMessageId
    return loaded(anchorId) && (firstUnreadId == anchorId || loaded(firstUnreadId))
}

private class ConversationEntryAnchorCheck(
    var done: Boolean = false,
    var firstUnreadMessageId: String? = null,
)

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
        // Paging back to an older stale id also loads the newer read anchor, so check the order again.
        loadedAuthoritativeId?.let { firstUnreadAfterReadAnchor(it, snapshot.readAnchorMessageId, timeline()) }
            ?: run {
                val currentTimeline = timeline()
                // Once paging loads the anchor, only messages after it can still be unread.
                val count =
                    reconciledConversationEntryUnreadCount(
                        projectionUnread = snapshot.count,
                        timeline = currentTimeline,
                        readAnchorMessageId = snapshot.readAnchorMessageId,
                    )
                currentTimeline
                    .getOrNull(firstUnreadReceivedIndex(currentTimeline, count))
                    ?.record
                    ?.messageIdHex
                    ?.takeIf { it.isNotBlank() }
            }
    }
}
