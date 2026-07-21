package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.firstUnreadReceivedIndex
import dev.ipf.whitenoise.android.state.reconciledConversationEntryUnreadCount

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

/** Keep the entry marker fixed while unread messages remain, then retire it. */
internal fun shouldShowConversationEntryUnreadDivider(
    entryUnreadCount: Int,
    liveUnreadCount: Int,
    dividerRetired: Boolean,
    messageId: String,
    firstUnreadMessageId: String?,
): Boolean =
    entryUnreadCount > 0 &&
        liveUnreadCount > 0 &&
        !dividerRetired &&
        messageId == firstUnreadMessageId

/**
 * Freezes the unread boundary on the first non-empty timeline for one
 * controller. Controller identity is part of the key because the same group id
 * can be open under multiple local accounts with different read watermarks.
 */
@Composable
internal fun rememberConversationEntryUnreadSnapshot(
    controllerIdentity: Any,
    projectionUnread: Int,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): ConversationEntryUnreadSnapshot =
    remember(controllerIdentity, timeline.isNotEmpty()) {
        val count =
            if (timeline.isEmpty()) {
                projectionUnread.coerceAtLeast(0)
            } else {
                reconciledConversationEntryUnreadCount(
                    projectionUnread = projectionUnread,
                    timeline = timeline,
                    readAnchorMessageId = readAnchorMessageId,
                )
            }
        val firstUnreadIndex = firstUnreadReceivedIndex(timeline, count)
        ConversationEntryUnreadSnapshot(
            count = count,
            firstUnreadMessageId =
                timeline
                    .getOrNull(firstUnreadIndex)
                    ?.record
                    ?.messageIdHex
                    ?.takeIf { it.isNotBlank() },
        )
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

/**
 * Target for the jump-to-newest affordance.
 *
 * With unread messages, the first tap should preserve reading context by
 * landing on the last row the reader had already reached. Once that row is
 * visible, the next tap proceeds to the physical bottom. If the read anchor is
 * unavailable (trimmed/not loaded), fall back to the bottom rather than guessing.
 */
internal fun conversationJumpToNewestTargetListIndex(
    unreadIncomingCount: Int,
    readAnchorMessageId: String?,
    renderedMessageIds: List<String>,
    visibleListIndices: Collection<Int>,
    olderHeaderCount: Int,
    bottomTimelineIndex: Int,
): Int {
    if (unreadIncomingCount > 0) {
        val anchorTimelineIndex =
            readAnchorMessageId
                ?.takeIf { it.isNotBlank() }
                ?.let(renderedMessageIds::indexOf)
                ?.takeIf { it >= 0 }
        if (anchorTimelineIndex != null) {
            val anchorListIndex = 1 + olderHeaderCount + anchorTimelineIndex
            if (anchorListIndex !in visibleListIndices) return anchorListIndex
        }
    }
    return bottomTimelineIndex
}

internal data class ImageAttachmentReadOutcome(
    val attachment: PendingAttachment?,
    val overflowed: Boolean = false,
)

// How many rows from the top to begin prefetching the next older page.
internal const val OLDER_PAGE_PREFETCH_ROWS = 4

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
    val lastVisible =
        listState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index ?: return false
    return lastVisible >= bottomTimelineIndex - 1
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

/**
 * Near-bottom gate for IME-open bottom chase. Until this conversation has
 * observed the IME closed, follows live [nearBottom] so a chat opened with the
 * keyboard already up can still chase once initial anchoring settles. After the
 * first composer-focus edge, holds that value while the IME is open so the
 * pre-inset layout transient cannot treat a history reader as "at bottom"
 * (#1375, #1574).
 */
@Composable
internal fun rememberImeOpenReanchorNearBottom(
    chatId: String,
    imeIsOpen: Boolean,
    composerFocused: Boolean,
    nearBottom: Boolean,
): Boolean {
    var nearBottomAtFocusEdge by remember(chatId) { mutableStateOf<Boolean?>(null) }
    var wasComposerFocused by remember(chatId) { mutableStateOf(false) }
    SideEffect {
        if (composerFocused && !wasComposerFocused && !imeIsOpen) {
            nearBottomAtFocusEdge = nearBottom
        }
        if (!composerFocused && !imeIsOpen) {
            nearBottomAtFocusEdge = null
        }
        wasComposerFocused = composerFocused
    }
    return if (imeIsOpen) nearBottomAtFocusEdge ?: nearBottom else nearBottom
}
