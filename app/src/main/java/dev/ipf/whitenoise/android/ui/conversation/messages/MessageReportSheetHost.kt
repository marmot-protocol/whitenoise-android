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
        onSubmit = submit@{ reason, explanation, finishExit ->
            if (surfaces.reportInFlight) return@submit
            val team = ReportTeamChoice(effectiveAudience, surfaces.reportToTeam)
            val generation = appState.runtimeGeneration
            val account = appState.activeAccountRef
            val owns = { appState.runtimeGeneration == generation && appState.activeAccountRef == account }
            val sends =
                MessageReportSends(
                    group = { groupAnswer(controller.reportMessage(record.messageIdHex, reason, explanation)) },
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
                    surfaces.sendReport(team, sends) {
                        surfaces.finishReport(closeSheet = false)
                        finishExit()
                    }
                } finally {
                    surfaces.reportInFlight = false
                }
            }
        },
        ownerKey = Triple(controller, record.messageIdHex, appState.activeAccountRef to appState.runtimeGeneration),
        audience = effectiveAudience,
        sendToTeam = surfaces.reportToTeam,
        onSendToTeamChange = { surfaces.reportToTeam = it },
        form = ReportFormState(surfaces.reportInFlight, surfaces.reportCopyPending, surfaces.reportError),
    )
}

/** What a reopened sheet offers: only the team copy once the group already has this report. */
internal fun MessageBubbleSurfaceState.effectiveReportAudience(audience: ReportAudience): ReportAudience =
    if (reportGroupResult != null && !reportCopyPending || reportGroupRefused) ReportAudience.TeamMessage else audience

/**
 * The group's answer to a report: the [notice] it gives the reader, whether the group took the report, whether the
 * controller [alreadyShown] it on its own, as it does for a failure with its diagnostics, and whether the group will
 * [refuse] any retry, as when this account is no longer allowed to report there.
 */
internal data class GroupAnswer(
    @StringRes val notice: Int,
    val took: Boolean,
    val alreadyShown: Boolean = false,
    val refuse: Boolean = false,
)

/**
 * The two sends one report makes and how their answers reach the reader. [group] returns the group's answer.
 * [present] takes the group's and the team's messages, either of which may be absent.
 */
internal class MessageReportSends(
    val group: suspend () -> GroupAnswer,
    val team: suspend () -> TeamReportOutcome,
    val present: (group: Int?, team: Int?) -> Unit,
)

/**
 * Sends the group report unless the group already has it, then the team copy, and says what each achieved. The
 * team copy is sent even when the group refuses or fails the report, since the team is a separate channel. The
 * group's answer is recorded as soon as the group takes the report, before the team copy, and stays recorded until
 * the report finishes, so neither a locked retry, a reopened team-only sheet, nor a recreated bubble sends it to the
 * group again.
 */
internal suspend fun MessageBubbleSurfaceState.sendReport(
    team: ReportTeamChoice,
    sends: MessageReportSends,
    onFinished: () -> Unit = { finishReport() },
) {
    val recorded = reportGroupResult
    val sheetGeneration = reportSheetGeneration
    val answer = if (team.reachesGroup && recorded == null) sends.group() else null
    val groupResult = if (team.reachesGroup) recorded ?: answer?.notice?.takeIf { answer.took } else null
    if (!team.reachesTeam) {
        onFinished()
        answer?.takeUnless { it.alreadyShown }?.let { sends.present(it.notice, null) }
        return
    }
    if (groupResult != null) reportGroupResult = groupResult
    when (val outcome = sends.team()) {
        is TeamReportOutcome.Accepted -> {
            onFinished()
            // One notice for both, which replaces a group failure the controller showed but still names it.
            sends.present(answer?.notice ?: groupResult, outcome.message)
        }
        is TeamReportOutcome.Failed -> {
            val sameSheet = reportSheetOpen && reportSheetGeneration == sheetGeneration
            if (sameSheet && answer?.refuse == true) reportGroupRefused = true
            keepTeamCopyForRetry(groupResult, answer, outcome.message, sameSheet, sends.present)
        }
    }
}

/**
 * Keeps the report open after the team copy failed. On the sheet that sent it, a recorded group answer locks the
 * form to that report so a retry sends only the copy. When the reader dismissed that sheet meanwhile, even if they
 * reopened one, a single notice says what the group took and that the copy failed, and a reopened sheet stays an
 * unlocked team-only report. The group's answer, taken or refused, is shown once, when it first arrives, unless the
 * controller already showed that failure. A group that will refuse any retry leaves the sheet a team-only report.
 */
private fun MessageBubbleSurfaceState.keepTeamCopyForRetry(
    @StringRes groupResult: Int?,
    firstGroupAnswer: GroupAnswer?,
    @StringRes failure: Int,
    sameSheet: Boolean,
    present: (Int?, Int?) -> Unit,
) {
    val error =
        if (reportGroupResult != null && failure == R.string.report_team_failed) {
            R.string.report_team_copy_failed
        } else {
            failure
        }
    if (sameSheet) {
        // A failure the controller already showed keeps its own notice and diagnostics.
        firstGroupAnswer?.takeUnless { it.alreadyShown }?.let { present(it.notice, null) }
        reportCopyPending = groupResult != null
        reportError = error
    } else {
        present(firstGroupAnswer?.takeUnless { it.alreadyShown }?.notice, error)
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

/** The group's answer to [outcome], including why it did not take the report. */
private fun groupAnswer(outcome: ReportOutcome): GroupAnswer =
    when (outcome) {
        ReportOutcome.Sent -> GroupAnswer(R.string.report_message_sent, took = true)
        ReportOutcome.Queued -> GroupAnswer(R.string.report_message_queued, took = true)
        ReportOutcome.NotAllowed -> GroupAnswer(R.string.report_message_not_allowed, took = false, refuse = true)
        ReportOutcome.Failed -> GroupAnswer(R.string.report_message_failed, took = false, alreadyShown = true)
    }
