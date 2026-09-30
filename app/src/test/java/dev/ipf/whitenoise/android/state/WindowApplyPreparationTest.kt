package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.TimelineEditSummaryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class WindowApplyPreparationTest {
    /** A deferred media bridge owns an unprojected row until its exact send handoff completes. */
    @Test
    fun commitRecheckPreservesPendingProjectionBridge() {
        val id = "aa".repeat(32)
        val held = timelineRecord(id, 1uL, "held")
        val snapshot = WindowApplySnapshot(listOf(held), emptySet(), heldOrder = mapOf(id to 0uL))
        val prepared =
            prepareWindowApply(
                page = TimelinePageFfi(listOf(held), hasMoreBefore = false, hasMoreAfter = false),
                snapshot = snapshot,
                replaceWindow = false,
            )

        val plan =
            prepared.planCommit(
                snapshot = snapshot,
                liveRecords = mapOf(id to held),
                projectedItemIds = emptySet(),
                pendingProjectionIds = setOf(id),
            )
        assertTrue(plan.projectIds.isEmpty())
        assertTrue(plan.departedIds.isEmpty())
    }

    /** EXTEND keeps hydrated Markdown for retained rows and projects changed rows only. */
    @Test
    fun extendPreparationCarriesMarkdownAndPlansOnlyChangedRows() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("held")))),
                blankLinesBefore = ByteArray(0),
            )
        val unchangedId = "aa".repeat(32)
        val changedId = "bb".repeat(32)
        val heldUnchanged = timelineRecord(unchangedId, 1uL, "held").copy(contentTokens = document)
        val heldChanged = timelineRecord(changedId, 2uL, "before")
        val page =
            TimelinePageFfi(
                messages =
                    listOf(
                        timelineRecord(unchangedId, 1uL, "held"),
                        timelineRecord(changedId, 2uL, "after"),
                    ),
                hasMoreBefore = false,
                hasMoreAfter = false,
            )

        val prepared =
            prepareWindowApply(
                page = page,
                snapshot =
                    WindowApplySnapshot(
                        listOf(heldUnchanged, heldChanged),
                        emptySet(),
                        heldOrder = mapOf(unchangedId to 7uL, changedId to 8uL),
                    ),
                replaceWindow = false,
                reconcileNewExtendedRecords = true,
            )

        assertEquals(WindowApplyMode.EXTEND, prepared.mode)
        assertEquals(mapOf(unchangedId to 7uL, changedId to 8uL), prepared.authoritativeOrder)
        assertFalse(prepared.rows[0].needsProjection)
        assertEquals(document, prepared.rows[0].record.contentTokens)
        assertTrue(prepared.rows[1].needsProjection)
        val projected = prepared.rows.filter(PreparedWindowRow::needsProjection).map { it.record.messageIdHex }
        assertEquals(listOf(changedId), projected)
    }

    /** A non-forced window sharing no ordered row with the held ones prepares as a replacement. */
    @Test
    fun extendPreparationWithoutASharedRowFallsBackToReplace() {
        val heldId = "aa".repeat(32)
        val newId = "bb".repeat(32)
        val page =
            TimelinePageFfi(
                messages = listOf(timelineRecord(newId, 5uL, "elsewhere")),
                hasMoreBefore = true,
                hasMoreAfter = true,
            )

        val prepared =
            prepareWindowApply(
                page = page,
                snapshot =
                    WindowApplySnapshot(
                        listOf(timelineRecord(heldId, 1uL, "held")),
                        emptySet(),
                        heldOrder = mapOf(heldId to 0uL),
                    ),
                replaceWindow = false,
            )

        assertEquals(WindowApplyMode.REPLACE, prepared.mode)
        assertTrue(prepared.rows.single().needsProjection)
        assertEquals(mapOf(newId to AUTHORITATIVE_ORDER_BASE), prepared.authoritativeOrder)
    }

    /** Rows beyond a final edge depart; rows beyond an edge with more history stay; an interior gap replaces. */
    @Test
    fun extendPreparationDepartsOnlyRowsThePageProvesGone() {
        val ids = listOf("aa", "bb", "cc", "dd").map { it.repeat(32) }
        val held = ids.mapIndexed { index, id -> timelineRecord(id, index.toULong(), "row") }
        val heldOrder = ids.withIndex().associate { (index, id) -> id to index.toULong() }
        val snapshot = WindowApplySnapshot(held, emptySet(), heldOrder)
        val newerHalf = listOf(held[2], held[3])

        val finalOlderEdge =
            prepareWindowApply(
                page = TimelinePageFfi(newerHalf, hasMoreBefore = false, hasMoreAfter = true),
                snapshot = snapshot,
                replaceWindow = false,
            )
        val moreHistoryBefore =
            prepareWindowApply(
                page = TimelinePageFfi(newerHalf, hasMoreBefore = true, hasMoreAfter = true),
                snapshot = snapshot,
                replaceWindow = false,
            )
        val interiorGap =
            prepareWindowApply(
                page = TimelinePageFfi(listOf(held[1], held[3]), hasMoreBefore = true, hasMoreAfter = true),
                snapshot = snapshot,
                replaceWindow = false,
            )

        assertEquals(WindowApplyMode.EXTEND, finalOlderEdge.mode)
        assertEquals(setOf(ids[0], ids[1]), finalOlderEdge.departedIds)
        assertEquals(emptySet<String>(), moreHistoryBefore.departedIds)
        // The row missing between b and d moved d's ordinal, so the shared rows disagree and the page replaces.
        assertEquals(WindowApplyMode.REPLACE, interiorGap.mode)
        assertEquals(emptySet<String>(), interiorGap.departedIds)
    }

    /** A row inserted inside the span moves the rows after it, so the page replaces, not misplaces retained rows. */
    @Test
    fun extendPreparationReplacesWhenARowWasInsertedInsideTheSpan() {
        val ids = listOf("aa", "bb", "cc", "dd", "ee", "ff", "gg").map { it.repeat(32) }
        val held = ids.mapIndexed { index, id -> timelineRecord(id, index.toULong(), "row") }
        val heldOrder = ids.withIndex().associate { (index, id) -> id to index.toULong() }
        val inserted = timelineRecord("99".repeat(32), 1uL, "late arrival")
        // The window holds A..E, F and G are retained past its newer edge, and X lands between A and B.
        val rows = listOf(held[0], inserted, held[1], held[2], held[3], held[4])

        val prepared =
            prepareWindowApply(
                page = TimelinePageFfi(rows, hasMoreBefore = true, hasMoreAfter = true),
                snapshot = WindowApplySnapshot(held, emptySet(), heldOrder),
                replaceWindow = false,
            )

        assertEquals(WindowApplyMode.REPLACE, prepared.mode)
        assertTrue(prepared.departedIds.isEmpty())
        assertEquals(
            rows.size,
            prepared.authoritativeOrder.values
                .toSet()
                .size,
        )
        assertTrue(prepared.authoritativeOrder.values.all { it >= AUTHORITATIVE_ORDER_BASE })
    }

    /** A new edge row that would take a retained row's ordinal, or shared rows that disagree, means no shift. */
    @Test
    fun windowOrderShiftRefusesCollisionsAndDisagreement() {
        val retained = "aa".repeat(32)
        val first = "bb".repeat(32)
        val second = "cc".repeat(32)
        val fresh = "dd".repeat(32)
        val heldOrder = mapOf(retained to 4uL, first to 5uL, second to 6uL)

        fun page(vararg ids: String) =
            TimelinePageFfi(
                messages = ids.mapIndexed { index, id -> timelineRecord(id, index.toULong(), "row") },
                hasMoreBefore = true,
                hasMoreAfter = true,
            )

        assertEquals(5L, windowOrderShift(page(first, second), heldOrder))
        assertEquals(4L, windowOrderShift(page(fresh, first, second), mapOf(first to 5uL, second to 6uL)))
        assertEquals(null, windowOrderShift(page(fresh, first, second), heldOrder))
        assertEquals(null, windowOrderShift(page(first, fresh, second), heldOrder))
    }

    /** The first page row the timeline already orders decides the shift; a page sharing none has no shift. */
    @Test
    fun windowOrderShiftAlignsThePageToTheFirstSharedRow() {
        val older = "aa".repeat(32)
        val shared = "bb".repeat(32)
        val page =
            TimelinePageFfi(
                messages = listOf(timelineRecord(older, 1uL, "older"), timelineRecord(shared, 2uL, "shared")),
                hasMoreBefore = true,
                hasMoreAfter = false,
            )

        val shift = windowOrderShift(page, mapOf(shared to 40uL))

        assertEquals(39L, shift)
        assertEquals(39uL, shiftedOrder(0, shift))
        assertEquals(40uL, shiftedOrder(1, shift))
        assertEquals(null, windowOrderShift(page, mapOf("cc".repeat(32) to 3uL)))
        assertEquals(AUTHORITATIVE_ORDER_BASE + 2uL, shiftedOrder(2, null))
    }

    /** REPLACE prepares its immutable rows on the worker without touching controller state. */
    @Test
    @Suppress("LongMethod") // One 200-row fixture must cover every projection shape in the same pure snapshot.
    fun replacementPreparationIsPureAndRunsOffTheCallingThread() =
        runBlocking {
            val callingThread = Thread.currentThread().name
            val heldRecords =
                (0 until 200).map { index ->
                    timelineRecord(
                        messageId = index.toString(16).padStart(64, '0'),
                        timelineAt = index.toULong(),
                        plaintext = "held-$index",
                    )
                }
            val replacement =
                heldRecords.mapIndexed { index, record ->
                    record.copy(
                        plaintext = if (index % 5 == 0) "updated-$index" else record.plaintext,
                        deleted = index % 17 == 0,
                        clientToken = if (index % 19 == 0) "optimistic-$index" else null,
                        direction = if (index % 19 == 0) "sent" else record.direction,
                        replyToMessageIdHex = if (index % 7 == 0) heldRecords.first().messageIdHex else null,
                        replyPreview = if (index % 7 == 0) replyPreview(index) else null,
                        reactions = if (index % 11 == 0) reactions(index, record.messageIdHex) else record.reactions,
                        edit =
                            if (index % 13 == 0) {
                                TimelineEditSummaryFfi(
                                    editCount = 1uL,
                                    latestEditMessageIdHex = "edit-$index",
                                    editedAt = index.toULong(),
                                )
                            } else {
                                null
                            },
                        kind = if (index % 23 == 0) 1200uL else record.kind,
                        tags =
                            if (index % 23 == 0) {
                                listOf(MessageProjector.streamTag("stream-$index"))
                            } else {
                                record.tags
                            },
                        agentTextStreamJson = if (index % 23 == 0) "{\"status\":\"started\"}" else null,
                    )
                }
            val snapshot =
                WindowApplySnapshot(
                    heldRecords = heldRecords,
                    pendingProjectionIds = emptySet(),
                )
            val originalSnapshot = snapshot.heldRecords.toList()
            val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "window-prepare-worker") }
            val dispatcher = worker.asCoroutineDispatcher()

            try {
                var preparationThread = ""
                val prepared =
                    withContext(dispatcher) {
                        preparationThread = Thread.currentThread().name
                        prepareWindowApply(
                            page = TimelinePageFfi(replacement, hasMoreBefore = true, hasMoreAfter = false),
                            snapshot = snapshot,
                            replaceWindow = true,
                            reconcileNewExtendedRecords = false,
                        )
                    }

                assertFalse(preparationThread == callingThread)
                assertTrue(preparationThread.startsWith("window-prepare-worker"))
                assertEquals(originalSnapshot, snapshot.heldRecords)
                assertEquals(200, prepared.rows.size)
                assertEquals(WindowApplyMode.REPLACE, prepared.mode)
                assertEquals(emptySet<String>(), prepared.departedIds)
                assertEquals(
                    (0 until 200).map { AUTHORITATIVE_ORDER_BASE + it.toULong() },
                    prepared.authoritativeOrder.values.toList(),
                )
                assertTrue(prepared.rows.all(PreparedWindowRow::needsProjection))
                assertTrue(prepared.rows.any { it.record.replyPreview != null })
                assertTrue(
                    prepared.rows.any {
                        it.record.reactions.userReactions
                            .isNotEmpty()
                    },
                )
                assertTrue(prepared.rows.any { it.record.edit != null })
                assertTrue(prepared.rows.any { it.record.deleted })
                assertTrue(prepared.rows.any { it.record.clientToken != null })
                assertTrue(prepared.rows.any { it.record.agentTextStreamJson != null })
                assertEquals(replacement.map { it.messageIdHex }, prepared.rows.map { it.actionRecord.messageIdHex })
            } finally {
                dispatcher.close()
                worker.shutdownNow()
            }
        }

    private fun replyPreview(index: Int) =
        TimelineReplyPreviewFfi(
            messageIdHex = "reply-$index",
            sender = "reply-sender",
            plaintext = "quoted-$index",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            deleted = false,
            deletionSource = DeletionSourceFfi.UNKNOWN,
            invalidationStatus = null,
        )

    private fun reactions(
        index: Int,
        target: String,
    ) = TimelineReactionSummaryFfi(
        byEmoji = emptyList(),
        userReactions =
            listOf(
                TimelineUserReactionFfi(
                    reactionMessageIdHex = "reaction-$index",
                    targetMessageIdHex = target,
                    sender = "reaction-sender",
                    emoji = "👍",
                    reactedAt = index.toULong(),
                ),
            ),
    )
}
