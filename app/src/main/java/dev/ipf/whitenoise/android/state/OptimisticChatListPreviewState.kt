package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi

/** One optimistic preview plus the acceptance order it must retain through reconciliation. */
internal data class OptimisticChatListPreviewEntry(
    val preview: ChatListMessagePreviewFfi,
    val activitySequence: ULong,
    val confirmedMessageIdHex: String? = null,
    val pendingAuthoritativeRow: ChatListRowFfi? = null,
)

/** Mutable per-row reconciliation state; its baseline remains authoritative engine data. */
internal data class OptimisticChatListPreviewState(
    var baselineRow: ChatListRowFfi,
    var baselineActivitySequence: ULong,
    val entries: LinkedHashMap<String, OptimisticChatListPreviewEntry> = linkedMapOf(),
    val reservedActivitySequenceById: LinkedHashMap<String, ULong> = linkedMapOf(),
    var failedFallbackEntry: OptimisticChatListPreviewEntry? = null,
    val confirmedActivitySequenceById: LinkedHashMap<String, ULong> = linkedMapOf(),
    val baselineActivitySequenceByLastMessage: LinkedHashMap<ChatListLastMessageActivity, ULong> = linkedMapOf(),
) {
    /** Deep-copies mutable maps so controller handoff never aliases the outgoing owner. */
    internal fun snapshot(): OptimisticChatListPreviewState =
        copy(
            entries = LinkedHashMap(entries),
            reservedActivitySequenceById = LinkedHashMap(reservedActivitySequenceById),
            confirmedActivitySequenceById = LinkedHashMap(confirmedActivitySequenceById),
            baselineActivitySequenceByLastMessage = LinkedHashMap(baselineActivitySequenceByLastMessage),
        )

    /** Returns true only after reservations, pending entries, failures, and confirmations are drained. */
    internal fun hasNoOptimisticPreviewWork(): Boolean =
        entries.isEmpty() &&
            reservedActivitySequenceById.isEmpty() &&
            failedFallbackEntry == null &&
            confirmedActivitySequenceById.isEmpty()
}

/** Same-process, account-bound state transferred during a live chat-controller replacement. */
internal data class OptimisticChatListPreviewHandoff(
    val accountRef: String,
    val rowsByGroup: Map<String, ChatListRowFfi>,
    val activitySequenceByGroup: Map<String, ULong>,
    val statesByGroup: Map<String, OptimisticChatListPreviewState>,
    val nextActivitySequence: ULong,
)

/** Stable activity identity used to preserve sequence tie-breakers across repeated projections. */
internal data class ChatListLastMessageActivity(
    val activitySortAt: ULong,
    val timelineAt: ULong?,
    val messageIdHex: String?,
)
