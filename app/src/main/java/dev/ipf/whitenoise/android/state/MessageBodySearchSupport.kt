package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.SnippetHighlight

/** Adapts an engine timeline row to the client-side search predicate, media types and labels included. */
internal fun searchableTimelineRecord(record: TimelineMessageRecordFfi): ChatListMessageSearch.SearchableRecord =
    object : ChatListMessageSearch.SearchableRecord {
        override val kind = record.kind
        override val deleted = record.deleted
        override val plaintext = record.plaintext
        override val messageIdHex = record.messageIdHex
        override val timelineAt = record.timelineAt
        override val sender = record.sender
        override val mediaTypes = record.media.map { it.mediaType }
        override val mediaLabels = record.media.map { it.fileName.ifBlank { it.mediaType } }
    }

/** Needle searches highlight the hit; filter-only searches show the leading body or attachment label. */
internal fun messageSearchSnippet(
    match: ChatListMessageSearch.SearchableRecord,
    needle: String,
): SnippetHighlight? =
    if (needle.isEmpty()) {
        ChatListMessageSearch.buildFilteredSnippet(match.plaintext, match.mediaLabels)
    } else {
        ChatListMessageSearch.buildSnippet(match.plaintext, needle)
    }
