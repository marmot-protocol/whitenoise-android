package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Local-store reader seam for exhaustive in-conversation body search. */
interface ConversationMessageSearchTimelineReader {
    suspend fun timelineMessages(
        accountRef: String,
        query: TimelineMessageQueryFfi,
    ): TimelinePageFfi
}

/**
 * Exhaustive local-history search for one open conversation.
 *
 * The engine's `search` query narrows every page in SQLite. Only matching ids
 * are retained here; no full-history message cache or relay request is involved.
 */
object ConversationMessageSearch {
    data class Match(
        val messageIdHex: String,
        val timelineAt: ULong,
    )

    private const val DefaultPageSize = 50u

    suspend fun findMatches(
        timelineReader: ConversationMessageSearchTimelineReader,
        accountRef: String,
        groupIdHex: String,
        rawQuery: String,
        pageSize: UInt = DefaultPageSize,
    ): List<Match> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val normalizedQuery = MessageSearch.normalize(query)
        val matchesById = linkedMapOf<String, Match>()
        var before: ULong? = null
        var beforeMessageId: String? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val page =
                timelineReader.timelineMessages(
                    accountRef,
                    TimelineMessageQueryFfi(
                        groupIdHex = groupIdHex,
                        search = query,
                        before = before,
                        beforeMessageId = beforeMessageId,
                        after = null,
                        afterMessageId = null,
                        limit = pageSize,
                    ),
                )
            currentCoroutineContext().ensureActive()

            page.messages.forEach { record ->
                if (
                    MessageSearch.isSearchableBody(record.kind, record.deleted, record.plaintext) &&
                    MessageSearch.normalize(record.plaintext).contains(normalizedQuery)
                ) {
                    matchesById.putIfAbsent(
                        record.messageIdHex,
                        Match(record.messageIdHex, record.timelineAt),
                    )
                }
            }

            if (!page.hasMoreBefore) break
            val oldest =
                page.messages.minWithOrNull(compareBy({ it.timelineAt }, { it.messageIdHex }))
                    ?: error("timeline search pagination returned an empty non-terminal page")
            val nextBefore = oldest.timelineAt
            val nextBeforeMessageId = oldest.messageIdHex
            check(nextBefore != before || nextBeforeMessageId != beforeMessageId) {
                "timeline search pagination cursor did not advance"
            }
            before = nextBefore
            beforeMessageId = nextBeforeMessageId
        }

        return matchesById.values
            .sortedWith(compareBy({ it.timelineAt }, { it.messageIdHex }))
    }
}
