package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.SnippetHighlight

/**
 * Whether a stored row has outlived its disappearing-message deadline and must stay out of search.
 *
 * The transcript hides an expired message as soon as its deadline passes, but the engine's prune is
 * periodic, so the row survives in the store for up to an hour afterwards and longer when a read
 * anchor defers it. Every search surface reads that store directly, so without this check a message
 * the user watched disappear still answers queries, with its body and its attachment names.
 *
 * This is deliberately stricter than the transcript: it ignores the read-anchor deferral that keeps
 * an unread message visible. Search has no reading position to anchor to, and for a disappearing
 * message the safe direction is to stop surfacing it rather than to keep it findable.
 */
internal fun isRetentionExpiredForSearch(
    record: TimelineMessageRecordFfi,
    nowMillis: Long,
): Boolean =
    DisappearingMessageSweep.isLocallyExpired(
        nowMillis = nowMillis,
        row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = record.timelineAt,
                // Zero is not a real deadline: the engine emits it when retention is off, and
                // reading it as epoch would hide every row.
                expiresAtLocalSeconds = record.retentionExpiresAt?.takeIf { it > 0uL },
                retentionAtSendSeconds = record.retentionSeconds?.takeIf { it > 0uL },
            ),
    )

/** Adapts an engine timeline row to the client-side search predicate, media types and labels included. */
internal fun searchableTimelineRecord(record: TimelineMessageRecordFfi): ChatListMessageSearch.SearchableRecord =
    object : ChatListMessageSearch.SearchableRecord {
        override val kind = record.kind
        override val deleted = record.deleted
        override val plaintext = record.plaintext
        override val messageIdHex = record.messageIdHex
        override val timelineAt = record.timelineAt
        override val sender = record.sender
        private val accepted = MessageAttachments.acceptedReferences(record.media)
        override val mediaTypes = accepted.map { it.mediaType }
        override val mediaLabels = accepted.map { it.fileName.ifBlank { it.mediaType } }
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
