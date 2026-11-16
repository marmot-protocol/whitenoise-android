package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TeamReportOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message report reaches the group at most once, however its White Noise copy fails, retries or is reopened. */
class MessageReportSendTest {
    private val surfaces = MessageBubbleSurfaceState().apply { reportSheetOpen = true }
    private var groupSends = 0
    private val teamAnswers = ArrayDeque<TeamReportOutcome>()
    private val presented = mutableListOf<Pair<Int?, Int?>>()
    private val sends =
        MessageReportSends(
            group = {
                groupSends++
                R.string.report_message_sent
            },
            team = { teamAnswers.removeFirst() },
            present = { group, team -> presented += group to team },
        )

    /** A locked retry after a failed team copy sends only the copy, and the report then finishes. */
    @Test
    fun aLockedRetrySendsOnlyTheTeamCopy() =
        runBlocking {
            teamAnswers += listOf(FAILED, ACCEPTED)

            send(ReportAudience.GroupAndTeam)
            assertTrue(surfaces.reportCopyPending)
            assertEquals(R.string.report_team_copy_failed, surfaces.reportError)
            send(ReportAudience.GroupAndTeam)

            assertEquals(1, groupSends)
            assertNull(surfaces.reportGroupResult)
            assertEquals(
                listOf(R.string.report_message_sent to null, R.string.report_message_sent to R.string.report_team_sent),
                presented,
            )
        }

    /** Dismissing after a failed copy, then failing again from the reopened sheet, never reports to the group twice. */
    @Test
    fun aReopenedSheetThatFailsAgainStaysTeamOnly() =
        runBlocking {
            teamAnswers += listOf(FAILED, FAILED, ACCEPTED)

            send(ReportAudience.GroupAndTeam)
            surfaces.closeReport()
            surfaces.reportSheetOpen = true
            assertEquals(ReportAudience.TeamMessage, surfaces.effectiveReportAudience(ReportAudience.GroupAndTeam))
            send(ReportAudience.GroupAndTeam)
            assertEquals(ReportAudience.TeamMessage, surfaces.effectiveReportAudience(ReportAudience.GroupAndTeam))
            assertFalse(surfaces.reportCopyPending)
            send(ReportAudience.GroupAndTeam)

            assertEquals(1, groupSends)
            assertNull(surfaces.reportGroupResult)
            assertEquals(null to R.string.report_team_sent, presented.last())
        }

    /** A report the group refused finishes without a team copy, and the next report starts fresh. */
    @Test
    fun aGroupRefusalSendsNoTeamCopy() =
        runBlocking {
            val refused =
                MessageReportSends(group = { null }, team = { error("no team copy") }, present = sends.present)

            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), refused)

            assertTrue(presented.isEmpty())
            assertEquals(
                ReportAudience.GroupAndTeam,
                surfaces.effectiveReportAudience(ReportAudience.GroupAndTeam),
            )
        }

    /** A remembered group report outlives this bubble's state, so a new bubble for the message sends only the copy. */
    @Test
    fun aGroupReportOutlivesTheBubbleThatSentIt() =
        runBlocking {
            val shared = mutableMapOf<String, Int>()
            val first = MessageBubbleSurfaceState(shared, "account|group|message")
            teamAnswers += listOf(FAILED, ACCEPTED)
            first.reportSheetOpen = true
            first.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), sends)
            first.closeReport()

            val recreated = MessageBubbleSurfaceState(shared, "account|group|message")
            val audience = recreated.effectiveReportAudience(ReportAudience.GroupAndTeam)
            recreated.sendReport(ReportTeamChoice(audience), sends)

            assertEquals(ReportAudience.TeamMessage, audience)
            assertEquals(1, groupSends)
            assertTrue(shared.isEmpty())
        }

    /** A team copy that fails after the reader dismissed the sheet is shown, not kept for a sheet nobody sees. */
    @Test
    fun aFailureAfterDismissalIsShown() =
        runBlocking {
            teamAnswers += FAILED
            surfaces.reportSheetOpen = false

            send(ReportAudience.GroupAndTeam)

            assertEquals(null to R.string.report_team_copy_failed, presented.last())
            assertFalse(surfaces.reportCopyPending)
            assertEquals(ReportAudience.TeamMessage, surfaces.effectiveReportAudience(ReportAudience.GroupAndTeam))
        }

    /** Sends one report from the sheet as it stands for [audience], with the team switch on. */
    private suspend fun send(audience: ReportAudience) {
        surfaces.sendReport(ReportTeamChoice(surfaces.effectiveReportAudience(audience)), sends)
    }

    private companion object {
        val FAILED = TeamReportOutcome.Failed(R.string.report_team_failed)
        val ACCEPTED = TeamReportOutcome.Accepted(R.string.report_team_sent)
    }
}
