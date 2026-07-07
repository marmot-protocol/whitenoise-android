package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.PendingAttachment

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

internal fun conversationScrollKey(
    accountRef: String?,
    groupIdHex: String,
): String = "${accountRef.orEmpty()}\u0000$groupIdHex"

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

/** Within this many items of the trailing edge counts as "at bottom". */
private const val ConversationNearBottomItemSlack = 3

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
    listState: androidx.compose.foundation.lazy.LazyListState,
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
