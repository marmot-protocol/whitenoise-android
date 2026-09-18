package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An older page slides the bounded window by one page. Rows the window still holds must keep their
 * projected items and their place, and must not be re-parsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationTimelineExtendApplyTest {
    /**
     * Rows the extended window kept are not re-projected: their held record survives by identity,
     * which is what makes a page cost the rows that changed rather than the whole window.
     *
     * Their rendered item is still re-stamped, because sliding the window does move them — see
     * [extendRestampsKeptRowsSoTheSlidWindowKeepsItsOrder].
     */
    @Test
    fun extendKeepsUnchangedRecordsByIdentity() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller ->
                val before = controller.timelineRecords.toMap()

                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND), row(THIRD))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertSame(before.getValue(SECOND), controller.timelineRecords.getValue(SECOND))
                assertSame(before.getValue(THIRD), controller.timelineRecords.getValue(THIRD))
                assertNotNull(controller.timelineRecords[FIRST])
            }
        }

    /**
     * The regression this mode could most easily introduce: a kept row skips re-projection, so its
     * ordinal must be re-stamped or the slid window would reorder history.
     */
    @Test
    fun extendRestampsKeptRowsSoTheSlidWindowKeepsItsOrder() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller ->
                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND), row(THIRD))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf(FIRST, SECOND, THIRD), timelineMessageIds(controller))
            }
        }

    /** Rows the window dropped leave the timeline and every index keyed by their id. */
    @Test
    fun extendRemovesRowsTheWindowNoLongerHolds() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller ->
                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf(FIRST, SECOND), timelineMessageIds(controller))
                assertTrue(THIRD !in controller.timelineRecords)
            }
        }

    /** Markdown already parsed for a row carries across the page, so nothing re-parses unchanged text. */
    @Test
    fun extendCarriesMarkdownTokensForUnchangedText() =
        runBlocking {
            withController(seed = listOf(hydratedRow(SECOND))) { controller ->
                val parsedBefore = controller.timelineRecords.getValue(SECOND).contentTokens
                assertTrue(parsedBefore.blocks.isNotEmpty())

                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(parsedBefore, controller.timelineRecords.getValue(SECOND).contentTokens)
            }
        }

    /** Edited text must still be re-parsed, so carried tokens are never applied across a change. */
    @Test
    fun extendRehydratesWhenTextChanged() =
        runBlocking {
            withController(seed = listOf(hydratedRow(SECOND))) { controller ->
                controller.applyTimelinePage(
                    page(listOf(row(SECOND, plaintext = "edited"))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertTrue(
                    controller.timelineRecords
                        .getValue(SECOND)
                        .contentTokens.blocks
                        .isEmpty(),
                )
            }
        }

    /** A replacement still rebuilds everything, so open, jump and reconnect are unaffected. */
    @Test
    fun replaceStillRebuildsTheWholeWindow() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller ->
                val before = controller.timeline.first { it.record.messageIdHex == SECOND }

                controller.applyTimelinePage(
                    page(listOf(row(SECOND), row(THIRD))),
                    replaceWindow = true,
                    updatePagination = true,
                )

                val after = controller.timeline.first { it.record.messageIdHex == SECOND }
                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
                assertTrue(before !== after)
            }
        }

    /** A page ordered oldest-first keeps that order after an extend. */
    private fun page(messages: List<TimelineMessageRecordFfi>): TimelinePageFfi {
        val older = true
        return TimelinePageFfi(messages = messages, hasMoreBefore = older, hasMoreAfter = false)
    }

    /** A plain authoritative row whose position follows its id's rank. */
    private fun row(
        messageId: String,
        plaintext: String = "body",
    ) = timelineRecord(
        messageId = messageId,
        timelineAt = RANK.getValue(messageId),
        plaintext = plaintext,
    )

    /** The same row with Markdown already parsed, as hydration would leave it. */
    private fun hydratedRow(messageId: String) = row(messageId).withMarkdownTokens(parsedDocument())

    /** A minimal non-empty Markdown document, as the parser would leave one. */
    private fun parsedDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("body")))),
            blankLinesBefore = ByteArray(0),
        )

    /** Drains the main looper so controller startup and Compose state settle. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Owns a controller seeded with [seed] as its opening window. */
    private suspend fun withController(
        seed: List<TimelineMessageRecordFfi>,
        block: suspend (ConversationController) -> Unit,
    ) {
        val subscription = ScriptedConversationTimelineSubscription(snapshotPage = page(seed))
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(subscription),
                group = conversationTimelineTestGroup(),
            )
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        try {
            // Apply the opening window here rather than waiting on subscription startup: under the
            // full suite that race leaves the timeline empty and every assertion below is vacuous.
            settle()
            controller.applyTimelinePage(page(seed), replaceWindow = true, updatePagination = true)
            check(timelineMessageIds(controller).isNotEmpty()) { "the seeded window must be applied" }
            block(controller)
            settle()
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    private companion object {
        val FIRST = "11".repeat(32)
        val SECOND = "22".repeat(32)
        val THIRD = "33".repeat(32)
        val RANK = mapOf(FIRST to 100uL, SECOND to 200uL, THIRD to 300uL)
    }
}
