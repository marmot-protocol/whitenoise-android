package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.PendingAttachment
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

/** Formats the stable group-member subtitle, including the self-only case. */
internal fun conversationMemberCountLabel(
    count: Int,
    justYou: String,
    oneMember: String,
    membersFormat: String,
): String =
    when (count) {
        0 -> justYou
        1 -> oneMember
        else -> String.format(membersFormat, count)
    }

/** Freezes transition consumers immediately and through the retained terminal-frame hold. */
internal fun conversationRoutePresentationShouldFreeze(
    routeTransitionInProgress: Boolean,
    retainedPresentationFreeze: Boolean,
): Boolean = routeTransitionInProgress || retainedPresentationFreeze

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
    inlineTopErrorCount: Int = 0,
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
        1 + olderHeaderCount + inlineTopErrorCount + anchorIndex
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
    hasInlineTopError: Boolean = false,
): Boolean {
    if (!listState.canScrollForward) return true
    val leadingStructuralRowCount =
        conversationTimelineLeadingStructuralRowCount(hasOlderHeader, hasInlineTopError)
    val tailTimelineIndex =
        conversationTimelineTailListIndex(
            timelineSize = timelineSize,
            leadingStructuralRowCount = leadingStructuralRowCount,
        )
            ?: return true
    // Check the LAST visible item, not the first — keeps "near bottom"
    // truthful when the viewport shrinks (e.g. keyboard open) and fewer
    // items fit, which pushes firstVisibleItemIndex earlier even though
    // the bottom is still on-screen.
    val layoutInfo = listState.layoutInfo
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisible.index > tailTimelineIndex) return true
    if (lastVisible.index < tailTimelineIndex) return false

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
    hasInlineTopError: Boolean = false,
): Boolean {
    val nearBottom by remember(listState, renderedTimelineSize, hasOlderHeader, hasInlineTopError) {
        derivedStateOf {
            isNearBottom(listState, renderedTimelineSize, hasOlderHeader, hasInlineTopError)
        }
    }
    return nearBottom
}
