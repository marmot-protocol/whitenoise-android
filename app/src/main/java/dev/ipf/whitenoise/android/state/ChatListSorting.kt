package dev.ipf.whitenoise.android.state

/**
 * Chat-list ordering: the comparator the visible list is sorted by, and the
 * per-row keys it reads.
 *
 * Extracted from `Controllers.kt` so the sort is one readable unit and so new
 * work here lands outside the file `StateSourceSizeCeilingTest` is holding
 * down. Same package, so nothing that calls these changed.
 */

internal fun sortChatListItems(
    items: List<ChatListItem>,
    draftedAtSeconds: (ChatListItem) -> ULong? = { null },
): List<ChatListItem> {
    val draftedAtById = items.associate { item -> item.id to draftedAtSeconds(item) }
    val recencyTiesWithDraft =
        items
            .filter { item -> chatListItemRecencyComesFromDraft(item, draftedAtById[item.id]) }
            .mapTo(mutableSetOf()) { item -> chatListItemRecencyTie(item, draftedAtById[item.id]) }
    // Every key but the title is derived once per row rather than per
    // comparison. The recency key needs the draft timestamp, and the sequence
    // key needs a `ChatListItemRecencyTie` plus a set lookup to decide whether
    // arrival order still applies; computing those inside the comparator ran
    // them O(n log n) times and allocated a tie record on each one. The title
    // key stays lazy because it is memoized on the row and rows with distinct
    // recency never reach it.
    return items
        .map { item -> chatListSortEntry(item, draftedAtById[item.id], recencyTiesWithDraft) }
        .sortedWith(CHAT_LIST_SORT_ENTRY_COMPARATOR)
        .map(ChatListSortEntry::item)
}

/**
 * One row's precomputed sort keys. Field order matches the comparator so the
 * two stay readable together; `pinnedPosition` carries the same
 * `Long.MAX_VALUE` sentinel an unpinned row sorted by before.
 */
private class ChatListSortEntry(
    val item: ChatListItem,
    val pendingConfirmation: Boolean,
    val pinned: Boolean,
    val pinnedPosition: Long,
    val recency: ULong,
    val sequence: ULong,
)

private fun chatListSortEntry(
    item: ChatListItem,
    draftedAt: ULong?,
    recencyTiesWithDraft: Set<ChatListItemRecencyTie>,
): ChatListSortEntry {
    val recencyTie = chatListItemRecencyTie(item, draftedAt)
    return ChatListSortEntry(
        item = item,
        pendingConfirmation = item.group.pendingConfirmation,
        pinned = recencyTie.pinned,
        pinnedPosition = recencyTie.pinnedPosition?.toLong() ?: Long.MAX_VALUE,
        recency = recencyTie.recency,
        // A draft-supplied or absent recency drops arrival order, so the whole
        // tie falls through to the stable title key instead of mixing
        // unrelated message sequences.
        sequence =
            if (recencyTie.recency == 0uL || recencyTie in recencyTiesWithDraft) {
                0uL
            } else {
                item.activitySequence
            },
    )
}

/**
 * Pending confirmation first, then the pinned block in the engine's normalized
 * manual order, then draft-aware recency, then arrival order inside a
 * same-second tie, then the stable title key. Comparisons are on primitives,
 * so nothing is boxed per comparison.
 */
private val CHAT_LIST_SORT_ENTRY_COMPARATOR =
    Comparator<ChatListSortEntry> { left, right ->
        val byPending = right.pendingConfirmation.compareTo(left.pendingConfirmation)
        if (byPending != 0) return@Comparator byPending
        val byPinned = right.pinned.compareTo(left.pinned)
        if (byPinned != 0) return@Comparator byPinned
        val byPinnedPosition = left.pinnedPosition.compareTo(right.pinnedPosition)
        if (byPinnedPosition != 0) return@Comparator byPinnedPosition
        val byRecency = right.recency.compareTo(left.recency)
        if (byRecency != 0) return@Comparator byRecency
        val bySequence = right.sequence.compareTo(left.sequence)
        if (bySequence != 0) {
            bySequence
        } else {
            chatListItemSortKey(left.item).compareTo(chatListItemSortKey(right.item))
        }
    }

// A chat with an unsent draft rises to reflect when drafting began, but only
// when that is newer than the chat's last activity — a stale draft never
// outranks a fresher incoming message. Same unix-seconds unit as [latestAt].
internal fun chatListItemDraftSortAt(
    latestAt: ULong?,
    draftedAt: ULong?,
): ULong = maxOf(latestAt ?: 0uL, draftedAt ?: 0uL)

private fun chatListItemRecencyComesFromDraft(
    item: ChatListItem,
    draftedAt: ULong?,
): Boolean = draftedAt != null && draftedAt > (item.latestAt ?: 0uL)

private data class ChatListItemRecencyTie(
    val pendingConfirmation: Boolean,
    val pinned: Boolean,
    val pinnedPosition: UInt?,
    val recency: ULong,
)

private fun chatListItemRecencyTie(
    item: ChatListItem,
    draftedAt: ULong?,
): ChatListItemRecencyTie =
    ChatListItemRecencyTie(
        pendingConfirmation = item.group.pendingConfirmation,
        pinned = item.pinned(),
        pinnedPosition = item.pinnedPosition(),
        recency = chatListItemDraftSortAt(item.latestAt, draftedAt),
    )

/**
 * Sort tie-breaker key. Mirrors the gating that the UI uses to derive a
 * display title: named groups sort by the *sanitized* projected/raw name
 * ([ChatListItem.sanitizedNamedTitle], the same value the row renders, so
 * hostile bidi/zero-width names can't make the order drift from the visible
 * titles); unnamed groups — including names that sanitization strips entirely —
 * sort by the peer account (stable; co-locates the same DM-shaped
 * conversation across renders) or, lacking that, the member count. The
 * raw group hex must never leak into the sort key — that's what the UI
 * stopped showing, and the sort would otherwise drift away from it.
 *
 * The value is memoized on the projection ([ChatListItem.sortTitleKey]) so a
 * tied comparison reuses it instead of re-sanitizing and re-lowercasing.
 */
internal fun chatListItemSortKey(item: ChatListItem): String = item.sortTitleKey
