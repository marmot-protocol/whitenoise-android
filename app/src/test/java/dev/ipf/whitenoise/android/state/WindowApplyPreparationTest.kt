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
        val snapshot = WindowApplySnapshot(listOf(held), emptySet())
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
                snapshot = WindowApplySnapshot(listOf(heldUnchanged, heldChanged), emptySet()),
                replaceWindow = false,
                reconcileNewExtendedRecords = true,
            )

        assertFalse(prepared.rows[0].needsProjection)
        assertEquals(document, prepared.rows[0].record.contentTokens)
        assertTrue(prepared.rows[1].needsProjection)
        assertEquals(setOf(changedId), prepared.touchedIds)
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
                assertEquals((0 until 200).map { it.toULong() }, prepared.authoritativeOrder.values.toList())
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
