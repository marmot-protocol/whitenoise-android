package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageReportRegistry
import dev.ipf.whitenoise.android.state.TeamReportOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message report reaches the group at most once, however its White Noise copy fails, retries or is reopened. */
class MessageReportSendTest {
    private val registry = MessageReportRegistry()
    private val surfaces = MessageBubbleSurfaceState(registry, KEY).apply { reportSheetOpen = true }
    private var groupSends = 0
    private val teamAnswers = ArrayDeque<TeamReportOutcome>()
    private val presented = mutableListOf<Pair<Int?, Int?>>()
    private val sends =
        MessageReportSends(
            group = {
                groupSends++
                GroupAnswer(R.string.report_message_sent, took = true)
            },
            team = { teamAnswers.removeFirst() },
            present = { group, team -> presented += group to team },
        )

    /** A completed copy stays mounted for the caller's exit; a failed copy never starts that exit. */
    @Test
    fun onlyACompletedReportRequestsAnimatedExit() =
        runBlocking {
            teamAnswers += listOf(FAILED, ACCEPTED)
            var exits = 0
            val finish = {
                surfaces.finishReport(closeSheet = false)
                exits++
                Unit
            }

            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), sends, finish)
            assertEquals(0, exits)
            assertTrue(surfaces.reportCopyPending)
            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), sends, finish)

            assertEquals(1, exits)
            assertEquals(1, groupSends)
            assertTrue(surfaces.reportSheetOpen)
            assertNull(surfaces.reportGroupResult)
            surfaces.closeReport()
            assertFalse(surfaces.reportSheetOpen)
            assertFalse(surfaces.reportCopyPending)
        }

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

    /** When the group refuses the report, the team copy still goes, and one notice says what each audience got. */
    @Test
    fun aGroupRefusalStillSendsTheTeamCopy() =
        runBlocking {
            teamAnswers += ACCEPTED
            val refused =
                MessageReportSends(
                    group = { GroupAnswer(R.string.report_message_not_allowed, took = false) },
                    team = sends.team,
                    present = sends.present,
                )

            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), refused)

            assertEquals(listOf(R.string.report_message_not_allowed to R.string.report_team_sent), presented)
            assertTrue(teamAnswers.isEmpty())
            assertNull(surfaces.reportGroupResult)
        }

    /** A failed group report with an accepted team copy gives one notice saying both, replacing the controller's. */
    @Test
    fun aGroupFailureWithAnAcceptedTeamCopySaysBoth() =
        runBlocking {
            teamAnswers += ACCEPTED
            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), failingGroup())

            assertEquals(listOf(R.string.report_message_failed to R.string.report_team_sent), presented)
        }

    /** A failed group report and a failed team copy keep the controller's own notice, with the copy's error inline. */
    @Test
    fun aGroupFailureWithAFailedTeamCopyKeepsTheControllersNotice() =
        runBlocking {
            teamAnswers += FAILED
            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), failingGroup())

            assertTrue(presented.isEmpty())
            assertEquals(R.string.report_team_failed, surfaces.reportError)
        }

    /** After the group refuses for good and the copy fails, the retry goes to the White Noise team alone. */
    @Test
    fun aRefusedGroupIsNotAskedAgainOnRetry() =
        runBlocking {
            var groupAsks = 0
            val refusing =
                MessageReportSends(
                    group = {
                        groupAsks++
                        GroupAnswer(R.string.report_message_not_allowed, took = false, refuse = true)
                    },
                    team = sends.team,
                    present = sends.present,
                )
            teamAnswers += listOf(FAILED, ACCEPTED)

            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), refusing)
            val retry = surfaces.effectiveReportAudience(ReportAudience.GroupAndTeam)
            surfaces.sendReport(ReportTeamChoice(retry), refusing)

            assertEquals(ReportAudience.TeamMessage, retry)
            assertEquals(1, groupAsks)
            assertFalse(surfaces.reportGroupRefused)
        }

    /** After dismissal, a late team failure is shown alone when the controller already showed the group failure. */
    @Test
    fun aLateTeamFailureAfterAShownGroupFailureIsShownAlone() =
        runBlocking {
            teamAnswers += FAILED
            val closing =
                MessageReportSends(
                    group = failingGroup().group,
                    team = {
                        surfaces.closeReport()
                        teamAnswers.removeFirst()
                    },
                    present = sends.present,
                )

            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), closing)

            assertEquals(listOf(null to R.string.report_team_failed), presented)
        }

    /** A group-only report the controller already reported as failed adds no second notice. */
    @Test
    fun aGroupOnlyFailureIsNotShownTwice() =
        runBlocking {
            val failed =
                MessageReportSends(
                    group = { GroupAnswer(R.string.report_message_failed, took = false, alreadyShown = true) },
                    team = { error("no team copy") },
                    present = sends.present,
                )

            surfaces.sendReport(ReportTeamChoice(ReportAudience.Group), failed)

            assertTrue(presented.isEmpty())
        }

    /** A remembered group report outlives this bubble's state, so a new bubble for the message sends only the copy. */
    @Test
    fun aGroupReportOutlivesTheBubbleThatSentIt() =
        runBlocking {
            teamAnswers += listOf(FAILED, ACCEPTED)
            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), sends)
            surfaces.closeReport()

            val recreated = MessageBubbleSurfaceState(registry, KEY)
            val audience = recreated.effectiveReportAudience(ReportAudience.GroupAndTeam)
            recreated.sendReport(ReportTeamChoice(audience), sends)

            assertEquals(ReportAudience.TeamMessage, audience)
            assertEquals(1, groupSends)
            assertTrue(registry.groupResults.isEmpty())
        }

    /** While the team copy is still sending, a recreated bubble sees the report in flight and the group's answer. */
    @Test
    fun aBubbleRecreatedMidSendCannotReportToTheGroupAgain() =
        runBlocking {
            val teamAnswer = CompletableDeferred<TeamReportOutcome>()
            val slowTeam =
                MessageReportSends(group = sends.group, team = { teamAnswer.await() }, present = sends.present)
            surfaces.reportInFlight = true
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), slowTeam)
                }

            val recreated = MessageBubbleSurfaceState(registry, KEY)

            assertTrue(recreated.reportInFlight)
            assertEquals(ReportAudience.TeamMessage, recreated.effectiveReportAudience(ReportAudience.GroupAndTeam))
            teamAnswer.complete(ACCEPTED)
            first.await()
            assertEquals(1, groupSends)
        }

    /** A team copy that fails after the reader dismissed the sheet is shown in one notice with the group's answer. */
    @Test
    fun aFailureAfterDismissalIsShownWithTheGroupAnswer() =
        runBlocking {
            teamAnswers += FAILED
            val slowTeam =
                MessageReportSends(
                    group = sends.group,
                    team = {
                        surfaces.closeReport()
                        teamAnswers.removeFirst()
                    },
                    present = sends.present,
                )

            surfaces.sendReport(ReportTeamChoice(ReportAudience.GroupAndTeam), slowTeam)

            assertEquals(listOf(R.string.report_message_sent to R.string.report_team_copy_failed), presented)
            assertFalse(surfaces.reportCopyPending)
            assertEquals(ReportAudience.TeamMessage, surfaces.effectiveReportAudience(ReportAudience.GroupAndTeam))
        }

    /** Sends whose group report fails in a way the controller already showed with its diagnostics. */
    private fun failingGroup() =
        MessageReportSends(
            group = { GroupAnswer(R.string.report_message_failed, took = false, alreadyShown = true) },
            team = sends.team,
            present = sends.present,
        )

    /** Sends one report from the sheet as it stands for [audience], with the team switch on. */
    private suspend fun send(audience: ReportAudience) {
        surfaces.sendReport(ReportTeamChoice(surfaces.effectiveReportAudience(audience)), sends)
    }

    private companion object {
        const val KEY = "account|group|message"
        val FAILED = TeamReportOutcome.Failed(R.string.report_team_failed)
        val ACCEPTED = TeamReportOutcome.Accepted(R.string.report_team_sent)
    }
}
