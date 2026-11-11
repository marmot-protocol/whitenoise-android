package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** Shared fakes and records for the global message-search controller tests (#1944). */
internal object GlobalSearchTestFixtures {
    /** One projection update of [group] carrying [changes]. */
    fun update(
        group: String,
        vararg changes: TimelineMessageChangeFfi,
    ) = TimelineProjectionUpdateFfi(
        groupIdHex = group,
        messages = emptyList(),
        changes = changes.toList(),
        chatListRow = null,
        chatListTrigger = ChatListUpdateTriggerFfi.entries.first(),
    )

    /** A reader that holds its first read after taking the page from [store], with the gate that releases it. */
    fun holdingFirstRead(store: StoreReader): Pair<GlobalSearchTimelineReader, CompletableDeferred<Unit>> {
        val gate = CompletableDeferred<Unit>()
        var held = true
        val reader =
            GlobalSearchTimelineReader { query ->
                val page = store.page(query)
                if (held) {
                    held = false
                    gate.await()
                }
                page
            }
        return reader to gate
    }

    /** One of the two scoped chats, alternating by index. */
    fun chosen(index: Int) = if (index % 2 == 0) "chosen-a" else "chosen-b"

    /** The keys of the loaded results in display order. */
    fun keys(controller: GlobalSearchResultsController) = controller.state.results.map { it.key }

    /**
     * Delivers [update] for the session's account the way the session's observer does: removed and deleted keys are
     * withdrawn first, then the answer says whether a recheck is still needed.
     */
    fun GlobalSearchResultsController.deliver(update: TimelineProjectionUpdateFfi): Boolean =
        touchesLoadedRows(
            MarmotEventFfi.ProjectionUpdated(RuntimeProjectionUpdateFfi("ff".repeat(32), ACCOUNT, update)),
            ACCOUNT,
            this,
        ) { revoke(it) }

    private const val ACCOUNT = "personal"

    /** A controller whose batches run as children of the test, with the given local hides, owner check and clock. */
    fun TestScope.controller(
        reader: GlobalSearchTimelineReader,
        hidden: (String) -> Set<String> = { emptySet() },
        isCurrent: () -> Boolean = { true },
        nowMillis: () -> Long = { NOW_MILLIS },
    ) = GlobalSearchResultsController(
        reader = reader,
        scope = this,
        environment =
            GlobalSearchResultsEnvironment(
                hiddenMessageIds = hidden,
                isCurrent = isCurrent,
                nowMillis = nowMillis,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ),
    )

    /**
     * An in-memory MDK store: filters by chat, text and sender, orders account-wide by (time, message id, group)
     * or within one chat by (time, message id), applies the strict cursor, and returns pages oldest first.
     */
    class StoreReader(
        records: List<TimelineMessageRecordFfi>,
    ) : GlobalSearchTimelineReader {
        private val rows = records.toMutableList()
        val queries = mutableListOf<TimelineMessageQueryFfi>()
        var rowsReturned = 0

        /** Changes the stored row with [id]. */
        fun replace(
            id: String,
            change: (TimelineMessageRecordFfi) -> TimelineMessageRecordFfi,
        ) {
            val index = rows.indexOfFirst { it.messageIdHex == id }
            rows[index] = change(rows[index])
        }

        /** The stored row with [id]. */
        fun row(id: String): TimelineMessageRecordFfi = rows.first { it.messageIdHex == id }

        /** Stores a new row. */
        fun add(record: TimelineMessageRecordFfi) {
            rows += record
        }

        /** Answers one typed query the way MDK's account-wide or single-chat wall-clock search does. */
        override suspend fun page(query: TimelineMessageQueryFfi): TimelinePageFfi {
            queries += query
            val needle = query.search?.trim()?.lowercase()
            val cursor =
                query.before?.let { at ->
                    GlobalSearchCursor(at, requireNotNull(query.beforeMessageId), query.cursorGroupId.orEmpty())
                }
            val matching =
                rows
                    .filter { query.groupIdHex == null || it.groupIdHex == query.groupIdHex }
                    .filter { needle == null || it.plaintext.lowercase().contains(needle) }
                    .filter { query.sender == null || it.sender == query.sender }
                    .filter { cursor == null || it.isOlderThan(cursor, sameChat = query.groupIdHex != null) }
                    .sortedWith(ACCOUNT_ORDER)
            val limit = query.limit!!.toInt()
            val page = matching.take(limit)
            rowsReturned += page.size
            return TimelinePageFfi(page.reversed(), hasMoreBefore = matching.size > limit, hasMoreAfter = false)
        }

        /** MDK's strict cursor predicate: by time and id in one chat, by time, id and group account-wide. */
        private fun TimelineMessageRecordFfi.isOlderThan(
            cursor: GlobalSearchCursor,
            sameChat: Boolean,
        ): Boolean {
            val own = if (sameChat) GlobalSearchCursor(timelineAt, messageIdHex, "") else globalSearchCursor()
            val bound = if (sameChat) cursor.copy(groupIdHex = "") else cursor
            return own < bound
        }
    }

    /** One final page holding [records] in the order given. */
    fun page(vararg records: TimelineMessageRecordFfi) =
        TimelinePageFfi(records.toList().sortedBy { it.timelineAt }, hasMoreBefore = false, hasMoreAfter = false)

    /** Records each typed query and answers with the next scripted page. */
    class FakeReader(
        vararg pages: suspend () -> TimelinePageFfi,
    ) : GlobalSearchTimelineReader {
        private val scripted = ArrayDeque(pages.toList())
        val queries = mutableListOf<TimelineMessageQueryFfi>()

        /** Answers with the next scripted page. */
        override suspend fun page(query: TimelineMessageQueryFfi): TimelinePageFfi {
            queries += query
            return scripted.removeFirst().invoke()
        }
    }

    const val NOW_MILLIS = 1_000_000L

    /** A poll response, which search never lists. */
    const val POLL_RESPONSE_KIND = 1018uL
    val SENDER = "aa".repeat(32)
    val OTHER_SENDER = "bb".repeat(32)

    /** MDK's account-search order, newest first. */
    val ACCOUNT_ORDER: Comparator<TimelineMessageRecordFfi> =
        compareByDescending<TimelineMessageRecordFfi> { it.globalSearchCursor() }

    val EMPTY_TOKENS = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))

    /** A timeline record with the fields search reads; [invalidation] marks a losing-branch row. */
    @Suppress("LongParameterList")
    fun record(
        group: String,
        id: String,
        at: ULong,
        text: String = "hello there",
        sender: String = SENDER,
        kind: ULong = 9uL,
        deleted: Boolean = false,
        expiresAt: ULong? = null,
        invalidation: String? = null,
    ) = TimelineMessageRecordFfi(
        clientToken = null,
        hasReports = false,
        poll = null,
        messageIdHex = id,
        sourceMessageIdHex = null,
        sourceEpoch = null,
        retentionSeconds = expiresAt?.let { 1uL },
        retentionExpiresAt = expiresAt,
        direction = "received",
        groupIdHex = group,
        sender = sender,
        plaintext = text,
        contentTokens = EMPTY_TOKENS,
        kind = kind,
        tags = emptyList(),
        sticker = null,
        timelineAt = at,
        receivedAt = at,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        edit = null,
        deleted = deleted,
        deletionSource = DeletionSourceFfi.UNKNOWN,
        deletedByMessageIdHex = null,
        invalidationStatus = invalidation,
        recipientConfirmed = false,
        importedFrom = null,
        historyImportProvenance = null,
    )
}
