package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.state.isRetentionExpiredForSearch
import dev.ipf.whitenoise.android.state.messageSearchSnippet
import dev.ipf.whitenoise.android.state.searchPage
import dev.ipf.whitenoise.android.state.searchableTimelineRecord
import java.util.Locale

private const val MILLIS_PER_SECOND = 1_000L

/** MDK's attachment-type codes on `TimelineMessageQueryFfi.attachmentType`. */
private const val MDK_ATTACHMENT_ANY = 0u
private const val MDK_ATTACHMENT_AUDIO = 3u
private const val MDK_ATTACHMENT_FILE = 4u
private const val MDK_ATTACHMENT_ATTACHMENT = 5u

/**
 * What the global message results list (#1944). MDK filters and pages: the needle as a case-insensitive literal
 * substring, one sender, the time range and an attachment class all go into the typed query. Where Android's
 * filters are wider than one MDK value — several chats, several senders, text or link content — the query asks
 * MDK for the narrowest superset and [admits] keeps the exact rows. [groups] is null for every chat and holds the
 * scoped chats' original ids otherwise; an empty set matches nothing.
 */
internal data class GlobalSearchResultsQuery(
    val needle: String,
    val groups: Set<String>? = null,
    val constraints: MessageSearchConstraints? = null,
) {
    private val canonicalGroups: Set<String>? = groups?.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    private val ciNeedle: String = needle.lowercase(Locale.ROOT)

    /** True when no message can match, so the list shows its empty state without a native read. */
    val matchesNothing: Boolean get() = groups?.isEmpty() == true

    /** The typed MDK page from [cursor], at most [limit] rows. */
    fun toFfi(
        cursor: GlobalSearchCursor?,
        limit: UInt,
    ): TimelineMessageQueryFfi {
        val bounds = constraints?.dateBounds
        return TimelineMessageQueryFfi.searchPage(
            // One scoped chat is searched directly; several are searched account-wide and narrowed by [admits].
            groupIdHex = groups?.singleOrNull(),
            search = needle.ifEmpty { null },
            sender = constraints?.senderIds?.singleOrNull(),
            since = bounds?.let { ceilSeconds(it.startEpochMillisInclusive) },
            until = bounds?.let { ceilSeconds(it.endEpochMillisExclusive) },
            attachmentType = mdkAttachmentType(constraints?.contentKinds.orEmpty()),
            cursor = cursor?.let { Triple(it.timelineAt, it.messageIdHex, it.groupIdHex) },
            limit = limit,
        )
    }

    /**
     * The result row for one MDK record, or null when it must not show: outside the scoped chats, hidden on this
     * device, past its disappearing deadline, not user-authored text (reactions, deletions, system JSON), deleted,
     * or failing a filter MDK could only approximate.
     */
    fun admit(
        record: TimelineMessageRecordFfi,
        hiddenIds: (groupIdHex: String) -> Set<String>,
        nowMillis: Long,
    ): GlobalSearchResult? {
        val inScope = canonicalGroups == null || record.groupIdHex.lowercase(Locale.ROOT) in canonicalGroups
        val searchable = searchableTimelineRecord(record)
        val eligible = ChatListMessageSearch.isEligibleMatch(searchable, ciNeedle, constraints, Int.MAX_VALUE)
        val hidden = hiddenIds(record.groupIdHex).any { it.equals(record.messageIdHex, ignoreCase = true) }
        val visible = inScope && eligible && !hidden && !isRetentionExpiredForSearch(record, nowMillis)
        val snippet =
            if (visible) {
                // A hit past the snippet window still shows its leading text rather than vanishing.
                messageSearchSnippet(searchable, needle)
                    ?: ChatListMessageSearch.buildFilteredSnippet(searchable.plaintext, searchable.mediaLabels)
            } else {
                null
            }
        return snippet?.let {
            GlobalSearchResult(
                groupIdHex = record.groupIdHex,
                messageIdHex = record.messageIdHex,
                senderIdHex = record.sender,
                timelineAt = record.timelineAt,
                snippet = it,
                attachment = globalSearchResultAttachment(searchable.mediaTypes, searchable.mediaLabels),
            )
        }
    }
}

/**
 * Builds the results query from the search screen's state. [scopedChatIds] is null when no chat, folder or
 * chat-type filter is set, the same scope rule the attachment library uses.
 */
internal fun globalSearchResultsQuery(
    rawQuery: String,
    constraints: MessageSearchConstraints?,
    scopedChatIds: Collection<String>?,
): GlobalSearchResultsQuery =
    GlobalSearchResultsQuery(
        needle = rawQuery.trim(),
        groups = scopedChatIds?.toSet(),
        constraints = constraints?.takeIf { it.isActive },
    )

/**
 * The one mode rule of global search (#1944). Any nonblank text query, and any sender, date or content filter,
 * shows paginated message results under the chat-name matches. Attachment-only content filters show the Files &
 * media library instead, a pasted npub or NIP-05 address keeps its identifier result, and folder, chat-type or
 * chat filters alone keep the compact chat list.
 */
internal fun globalSearchShowsMessageResults(
    searchActive: Boolean,
    browsingAttachments: Boolean,
    query: String,
    messageFiltersActive: Boolean,
): Boolean {
    val textQuery = query.isNotBlank() && ChatListIdentifierSearch.classify(query.trim()) == null
    return searchActive && !browsingAttachments && (messageFiltersActive || textQuery)
}

/** The cursor of one MDK record, in MDK's account-search order. */
internal fun TimelineMessageRecordFfi.globalSearchCursor(): GlobalSearchCursor =
    GlobalSearchCursor(timelineAt = timelineAt, messageIdHex = messageIdHex, groupIdHex = groupIdHex)

/** The first unix second at or after [millis], so `seconds * 1000 >= millis` exactly matches the bound. */
private fun ceilSeconds(millis: Long): ULong {
    val seconds = Math.floorDiv(millis + MILLIS_PER_SECOND - 1, MILLIS_PER_SECOND)
    return seconds.coerceAtLeast(0L).toULong()
}

/**
 * MDK's attachment class for the selected content kinds. Text and link kinds need no attachment, so MDK is not
 * narrowed then; a single audio or file kind maps exactly, and any other attachment selection asks for any
 * attachment and leaves the exact kind to [GlobalSearchResultsQuery.admit].
 */
private fun mdkAttachmentType(kinds: Set<GlobalSearchContentKind>): UInt =
    when {
        kinds.isEmpty() || GlobalSearchContentKind.TEXT in kinds || GlobalSearchContentKind.LINKS in kinds ->
            MDK_ATTACHMENT_ANY
        kinds == setOf(GlobalSearchContentKind.VOICE_AUDIO) -> MDK_ATTACHMENT_AUDIO
        kinds == setOf(GlobalSearchContentKind.FILES_DOCUMENTS) -> MDK_ATTACHMENT_FILE
        else -> MDK_ATTACHMENT_ATTACHMENT
    }

/** The first accepted attachment's kind and label, or null for a message without one. */
private fun globalSearchResultAttachment(
    mediaTypes: List<String>,
    labels: List<String>,
): GlobalSearchResultAttachment? {
    val type = mediaTypes.firstOrNull() ?: return null
    val kind =
        when {
            type.startsWith("image/", ignoreCase = true) -> GlobalSearchResultAttachmentKind.IMAGE
            type.startsWith("video/", ignoreCase = true) -> GlobalSearchResultAttachmentKind.VIDEO
            type.startsWith("audio/", ignoreCase = true) -> GlobalSearchResultAttachmentKind.AUDIO
            else -> GlobalSearchResultAttachmentKind.FILE
        }
    return GlobalSearchResultAttachment(kind, labels.firstOrNull().orEmpty(), mediaTypes.size)
}
