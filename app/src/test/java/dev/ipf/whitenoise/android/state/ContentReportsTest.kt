package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MarmotKit 0.10.1 content reports are published to the whole group. These pin who may file one, how the
 * engine's send summary becomes a user-facing outcome, and how the admin list is ordered.
 */
class ContentReportsTest {
    /** Another member's live message in a group can be reported. */
    @Test
    fun anotherMembersGroupMessageIsReportable() {
        assertTrue(canReportMessage(mine = false, deleted = false, directConversation = false))
    }

    /** Own messages, tombstones and direct chats never offer Report: a DM report would only reach the reported peer. */
    @Test
    fun ownDeletedAndDirectMessagesAreNotReportable() {
        assertFalse(canReportMessage(mine = true, deleted = false, directConversation = false))
        assertFalse(canReportMessage(mine = false, deleted = true, directConversation = false))
        assertFalse(canReportMessage(mine = false, deleted = false, directConversation = true))
    }

    /** A published report reads as sent; an accepted-but-pending one reads as queued rather than lying. */
    @Test
    fun outcomeFollowsTheAcceptDisposition() {
        assertEquals(ReportOutcome.Sent, reportOutcome(summary(SendAcceptDispositionFfi.PUBLISHED)))
        assertEquals(ReportOutcome.Queued, reportOutcome(summary(SendAcceptDispositionFfi.ACCEPTED_PENDING)))
        assertEquals(ReportOutcome.Queued, reportOutcome(summary(SendAcceptDispositionFfi.COMPLETION_UNKNOWN)))
    }

    /** The explanation is trimmed and capped at the shared limit. */
    @Test
    fun explanationIsTrimmedAndBounded() {
        assertEquals("why", boundedExplanation("  why \n"))
        assertEquals(REPORT_EXPLANATION_LIMIT, boundedExplanation("x".repeat(REPORT_EXPLANATION_LIMIT + 40)).length)
    }

    /** Every engine reason is offered exactly once, with the catch-all last. */
    @Test
    fun everyReasonIsOfferedOnceWithOtherLast() {
        assertEquals(ReportReasonFfi.entries.toSet(), REPORT_REASONS.toSet())
        assertEquals(ReportReasonFfi.entries.size, REPORT_REASONS.size)
        assertEquals(ReportReasonFfi.OTHER, REPORT_REASONS.last())
    }

    /** Open reports come first, newest at the top; dismissed ones trail so an admin sees what still needs them. */
    @Test
    fun openReportsLeadNewestFirstAndDismissedTrail() {
        val ordered =
            listOf(
                report("old-dismissed", reportedAt = 10uL, dismissed = true),
                report("older", reportedAt = 20uL),
                report("newest", reportedAt = 40uL),
                report("new-dismissed", reportedAt = 50uL, dismissed = true),
            ).newestFirst()

        assertEquals(listOf("newest", "older", "new-dismissed", "old-dismissed"), ordered.map { it.reportIdHex })
    }

    private fun summary(disposition: SendAcceptDispositionFfi) =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf("aa".repeat(32)),
            acceptDisposition = disposition,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    private fun report(
        id: String,
        reportedAt: ULong,
        dismissed: Boolean = false,
    ) = ContentReportFfi(
        reportIdHex = id,
        messageIdHex = "bb".repeat(32),
        messageAuthor = "cc".repeat(32),
        reporter = "dd".repeat(32),
        reason = ReportReasonFfi.SPAM,
        explanation = "",
        reportedAt = reportedAt,
        dismissed = dismissed,
    )
}
