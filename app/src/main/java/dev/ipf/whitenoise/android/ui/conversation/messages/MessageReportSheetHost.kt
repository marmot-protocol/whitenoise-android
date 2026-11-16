package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ReportOutcome
import dev.ipf.whitenoise.android.state.TeamReportOutcome
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.canReportMessage
import dev.ipf.whitenoise.android.state.reportMessage
import dev.ipf.whitenoise.android.state.reportToTeam

/**
 * Who a report on one message reaches, or null when nobody can receive it. The group's admins get group reports, and
 * the White Noise team, when [teamAvailable], gets any other person's live message, including in a direct chat.
 */
internal fun messageReportAudience(
    mine: Boolean,
    deleted: Boolean,
    directConversation: Boolean,
    teamAvailable: Boolean,
): ReportAudience? {
    val toGroup = canReportMessage(mine, deleted, directConversation)
    val toTeam = teamAvailable && !mine && !deleted
    return when {
        toGroup && toTeam -> ReportAudience.GroupAndTeam
        toGroup -> ReportAudience.Group
        toTeam -> ReportAudience.TeamMessage
        else -> null
    }
}

/**
 * The report sheet for [record], sent to [audience]. A group report keeps its existing flow. When the team copy fails,
 * the sheet stays open and locked, so a retry sends only that copy. If the reader dismisses it instead, reopening
 * offers only the team copy, so the group never gets the report twice.
 */
@Suppress("FunctionNaming")
@Composable
internal fun MessageReportSheetHost(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    record: AppMessageRecordFfi,
    audience: ReportAudience,
    surfaces: MessageBubbleSurfaceState,
) {
    val effectiveAudience = surfaces.effectiveReportAudience(audience)
    ReportMessageSheet(
        onDismissRequest = surfaces::closeReport,
        onSubmit = { reason, explanation ->
            val team = ReportTeamChoice(effectiveAudience, surfaces.reportToTeam)
            val generation = appState.runtimeGeneration
            val account = appState.activeAccountRef
            val owns = { appState.runtimeGeneration == generation && appState.activeAccountRef == account }
            val sends =
                MessageReportSends(
                    group = {
                        val outcome = controller.reportMessage(record.messageIdHex, reason, explanation)
                        // The controller already presented a failure with its report.
                        if (outcome == ReportOutcome.NotAllowed && owns()) {
                            appState.present(R.string.report_message_not_allowed)
                        }
                        groupReportResult(outcome)
                    },
                    team = {
                        val reporter = controller.boundAccountRef
                        reporter?.let { appState.reportToTeam(it, record.sender, reason, explanation) }
                            ?: TeamReportOutcome.Failed(R.string.report_team_failed)
                    },
                    present = { group, team -> if (owns()) presentReportOutcome(appState, group, team) },
                )
            surfaces.reportInFlight = true
            surfaces.reportError = null
            appState.launchMutation {
                try {
                    surfaces.sendReport(team, sends)
                } finally {
                    surfaces.reportInFlight = false
                }
            }
        },
        audience = effectiveAudience,
        sendToTeam = surfaces.reportToTeam,
        onSendToTeamChange = { surfaces.reportToTeam = it },
        form = ReportFormState(surfaces.reportInFlight, surfaces.reportCopyPending, surfaces.reportError),
    )
}

/** What a reopened sheet offers: only the team copy once the group already has this report. */
internal fun MessageBubbleSurfaceState.effectiveReportAudience(audience: ReportAudience): ReportAudience =
    if (reportGroupResult != null && !reportCopyPending) ReportAudience.TeamMessage else audience

/**
 * The two sends one report makes and how their answers reach the reader. [group] returns what the group's answer
 * tells the reader, or null when the group did not take the report. [present] takes the group's and the team's
 * messages, either of which may be absent.
 */
internal class MessageReportSends(
    val group: suspend () -> Int?,
    val team: suspend () -> TeamReportOutcome,
    val present: (group: Int?, team: Int?) -> Unit,
)

/**
 * Sends the group report unless the group already has it, then the team copy, and says what each achieved. Once the
 * group has the report it stays recorded until the report finishes, so neither a locked retry nor a reopened,
 * team-only sheet sends it to the group again.
 */
internal suspend fun MessageBubbleSurfaceState.sendReport(
    team: ReportTeamChoice,
    sends: MessageReportSends,
) {
    val groupResult = if (team.reachesGroup) reportGroupResult ?: sends.group() else null
    if (team.reachesGroup && groupResult == null || !team.reachesTeam) {
        finishReport()
        groupResult?.let { sends.present(it, null) }
        return
    }
    when (val outcome = sends.team()) {
        is TeamReportOutcome.Accepted -> {
            finishReport()
            sends.present(groupResult, outcome.message)
        }
        is TeamReportOutcome.Failed -> keepTeamCopyForRetry(groupResult, outcome.message, sends.present)
    }
}

/**
 * Keeps the sheet open after the team copy failed. A group answer that arrived with this send locks the sheet to
 * that report, and one recorded by an earlier send is never forgotten, so the group never gets the report twice. The
 * group's answer is shown once, when it first arrives.
 */
private fun MessageBubbleSurfaceState.keepTeamCopyForRetry(
    @StringRes groupResult: Int?,
    @StringRes failure: Int,
    present: (Int?, Int?) -> Unit,
) {
    if (reportGroupResult == null && groupResult != null) present(groupResult, null)
    reportGroupResult = groupResult ?: reportGroupResult
    reportCopyPending = groupResult != null
    reportError =
        if (reportGroupResult != null && failure == R.string.report_team_failed) {
            R.string.report_team_copy_failed
        } else {
            failure
        }
}

/** Says what each audience took: the group's answer when it got the report, then the team's. */
private fun presentReportOutcome(
    appState: WhiteNoiseAppState,
    @StringRes group: Int?,
    @StringRes team: Int?,
) {
    when {
        group != null && team != null -> appState.present(group, team)
        group != null -> appState.present(group)
        team != null -> appState.present(team)
    }
}

/** What the group's answer tells the reader, or null when the report did not reach the group. */
@StringRes
private fun groupReportResult(outcome: ReportOutcome): Int? =
    when (outcome) {
        ReportOutcome.Sent -> R.string.report_message_sent
        ReportOutcome.Queued -> R.string.report_message_queued
        ReportOutcome.NotAllowed, ReportOutcome.Failed -> null
    }
