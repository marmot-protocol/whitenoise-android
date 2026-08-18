package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.firstUnreadReceivedIndex
import dev.ipf.whitenoise.android.state.reconciledConversationEntryUnreadCount
import kotlin.math.abs

/**
 * Whether the conversation top bar should render a members-count subtitle.
 *
 * The top bar must not show group copy until the initial roster has loaded, and
 * a just-created DM gets one extra grace state: while its nameless roster is
 * still 0/1 members, keep the DM presentation instead of flashing "Just you" or
 * "1 member" before the peer row hydrates. If that one-member DM state stalls,
 * keep it quiet for this open session and let a later reopen re-evaluate from
 * live roster state (#998).
 */
internal fun shouldShowConversationMembersSubtitle(
    membersLoaded: Boolean,
    openedAsDmHint: Boolean,
    groupName: String,
    memberCount: Int,
): Boolean =
    membersLoaded &&
        !GroupProjector.isDm(memberCount, groupName) &&
        !(openedAsDmHint && GroupProjector.isUnnamed(groupName) && memberCount < 2)

/** UI-only scroll anchor for a conversation the user left while reading history. */
internal data class ConversationScrollSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val anchorItemId: String? = null,
    val anchorMessageIdHex: String? = null,
)

/** Whether saved history may own this open instead of the first-unread anchor. */
internal fun shouldRestoreConversationScrollSnapshot(
    focusMessageId: String?,
    justCreated: Boolean,
    notificationOpenRequestId: Long,
    entryUnreadCount: Int,
): Boolean =
    focusMessageId == null &&
        !justCreated &&
        notificationOpenRequestId == 0L &&
        entryUnreadCount <= 0

internal fun conversationScrollKey(
    accountRef: String?,
    groupIdHex: String,
): String = "${accountRef.orEmpty()}\u0000$groupIdHex"

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
    if (snapshot.count <= 0) return null

    fun loadedMessageId(target: String): String? =
        timeline()
            .firstOrNull { it.record.messageIdHex == target }
            ?.record
            ?.messageIdHex
            ?.takeIf { it.isNotBlank() }

    snapshot.firstUnreadMessageId?.takeIf { it.isNotBlank() }?.let { authoritativeId ->
        loadedMessageId(authoritativeId)?.let { return it }
        if (loadUntilMessageAvailable(authoritativeId)) {
            loadedMessageId(authoritativeId)?.let { return it }
        }
    }

    val currentTimeline = timeline()
    return currentTimeline
        .getOrNull(firstUnreadReceivedIndex(currentTimeline, snapshot.count))
        ?.record
        ?.messageIdHex
        ?.takeIf { it.isNotBlank() }
}

/**
 * Snapshot to persist when leaving a conversation. Returns null when the reader
 * was at/near the bottom so the normal unread/newest anchor runs on re-entry.
 */
internal fun conversationScrollSnapshotOnLeave(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    nearBottom: Boolean,
    anchorItemId: String? = null,
    anchorMessageIdHex: String? = null,
): ConversationScrollSnapshot? =
    if (nearBottom) {
        null
    } else {
        ConversationScrollSnapshot(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            anchorItemId = anchorItemId,
            anchorMessageIdHex = anchorMessageIdHex,
        )
    }

internal fun conversationScrollRestoreListIndex(
    snapshot: ConversationScrollSnapshot,
    renderedItemIds: List<String>,
    renderedMessageIds: List<String> = emptyList(),
    olderHeaderCount: Int,
): Int {
    val anchorIndex =
        snapshot.anchorMessageIdHex
            ?.takeIf { it.isNotBlank() }
            ?.let(renderedMessageIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: snapshot.anchorItemId
                ?.let(renderedItemIds::indexOf)
                ?.takeIf { it >= 0 }
            ?: -1
    return if (anchorIndex >= 0) {
        1 + olderHeaderCount + anchorIndex
    } else {
        snapshot.firstVisibleItemIndex
    }
}

internal data class ImageAttachmentReadOutcome(
    val attachment: PendingAttachment?,
    val overflowed: Boolean = false,
)

// How many rows from the top to begin prefetching the next older page.
internal const val OLDER_PAGE_PREFETCH_ROWS = 4

// Symmetric edge threshold after an unread-anchor load has shifted the bounded window.
internal const val NEWER_PAGE_PREFETCH_ROWS = 4

/**
 * Walk a bounded timeline back to its physical newest edge before a jump-to-bottom.
 * Each page loader reports whether its bounded window actually advanced, so a
 * failed or corrupt pager stops on the first no-progress result without imposing
 * an arbitrary history-size limit on legitimate conversations.
 */
internal suspend fun loadConversationTimelineToNewest(
    hasMoreAfter: () -> Boolean,
    loadNewer: suspend () -> Boolean,
): Boolean {
    while (hasMoreAfter()) {
        if (!loadNewer()) break
    }
    return !hasMoreAfter()
}

/**
 * Shared definition of "user is at (or near) the newest message". Used both
 * by the auto-scroll LaunchedEffect (issue #59) and the jump-to-newest FAB
 * so they can't disagree on the threshold.
 */
internal fun isNearBottom(
    listState: LazyListState,
    timelineSize: Int,
    hasOlderHeader: Boolean,
): Boolean {
    if (!listState.canScrollForward) return true
    val olderHeaderCount = if (hasOlderHeader) 1 else 0
    val bottomTimelineIndex = timelineSize + 1 + olderHeaderCount
    // Check the LAST visible item, not the first — keeps "near bottom"
    // truthful when the viewport shrinks (e.g. keyboard open) and fewer
    // items fit, which pushes firstVisibleItemIndex earlier even though
    // the bottom is still on-screen.
    val layoutInfo = listState.layoutInfo
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisible.index >= bottomTimelineIndex) return true
    if (lastVisible.index < bottomTimelineIndex - 1) return false

    // A normal tail row counts as near-bottom as soon as any part is visible,
    // preserving the existing one-row threshold. For an oversized row, keep
    // only a small no-flicker zone near the real tail; a full viewport delays
    // the FAB until too much of an expanded message has already scrolled away.
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    if (lastVisible.size <= viewportHeight) return true
    val tailDistanceFromViewport =
        lastVisible.offset + lastVisible.size - layoutInfo.viewportEndOffset
    val oversizedTailThreshold = viewportHeight / 4
    return tailDistanceFromViewport <= oversizedTailThreshold
}

/** True when the target row has settled at the usable transcript top. */
internal fun isConversationItemTopAligned(
    listState: LazyListState,
    targetIndex: Int,
    tolerancePx: Int = 1,
): Boolean {
    val layoutInfo = listState.layoutInfo
    val target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return false
    return abs(target.offset - layoutInfo.viewportStartOffset) <= tolerancePx.coerceAtLeast(0)
}

/**
 * Scroll-backed near-bottom flag for the conversation timeline. Keyed on the
 * rendered timeline size so async hydration cannot capture an initial zero in
 * the derived-state closure (issue #1253).
 */
@Composable
internal fun rememberConversationNearBottom(
    listState: LazyListState,
    renderedTimelineSize: Int,
    hasOlderHeader: Boolean,
): Boolean {
    val nearBottom by remember(listState, renderedTimelineSize, hasOlderHeader) {
        derivedStateOf {
            isNearBottom(listState, renderedTimelineSize, hasOlderHeader)
        }
    }
    return nearBottom
}
