package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ReportReasonFfi
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
    val groupHasReport = surfaces.reportGroupResult != null
    val effectiveAudience =
        if (groupHasReport && !surfaces.reportCopyPending) ReportAudience.TeamMessage else audience
    ReportMessageSheet(
        onDismissRequest = surfaces::closeReport,
        onSubmit = { reason, explanation ->
            val team = ReportTeamChoice(effectiveAudience, surfaces.reportToTeam)
            val generation = appState.runtimeGeneration
            val account = appState.activeAccountRef
            val owns = { appState.runtimeGeneration == generation && appState.activeAccountRef == account }
            surfaces.reportInFlight = true
            surfaces.reportError = null
            appState.launchMutation {
                try {
                    val report = MessageReport(record, team, reason, explanation)
                    sendMessageReport(appState, controller, report, surfaces, owns)
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

/** One submitted report: the message, who receives it, and what the reader chose. */
private data class MessageReport(
    val record: AppMessageRecordFfi,
    val team: ReportTeamChoice,
    val reason: ReportReasonFfi,
    val explanation: String,
)

/**
 * Sends the group report unless the group already has it, then the team copy, and says what each achieved while
 * [owns] still holds, so an answer that lands after an account or runtime change is not presented.
 */
private suspend fun sendMessageReport(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    report: MessageReport,
    surfaces: MessageBubbleSurfaceState,
    owns: () -> Boolean,
) {
    val team = report.team
    val groupResult =
        if (team.reachesGroup) {
            surfaces.reportGroupResult
                ?: sendGroupReport(appState, controller, report, owns)
        } else {
            null
        }
    if (team.reachesGroup && groupResult == null || !team.reachesTeam) {
        surfaces.finishReport()
        if (owns()) groupResult?.let { appState.present(it) }
        return
    }
    val outcome =
        controller.boundAccountRef?.let {
            appState.reportToTeam(it, report.record.sender, report.reason, report.explanation)
        } ?: TeamReportOutcome.Failed(R.string.report_team_failed)
    when (outcome) {
        is TeamReportOutcome.Accepted -> {
            surfaces.finishReport()
            if (owns()) presentReportOutcome(appState, groupResult, outcome.message)
        }
        is TeamReportOutcome.Failed -> keepTeamCopyForRetry(appState, surfaces, groupResult, outcome.message, owns)
    }
}

/** Says what each audience took: the group's answer when it got the report, then the team's. */
private fun presentReportOutcome(
    appState: WhiteNoiseAppState,
    @StringRes groupResult: Int?,
    @StringRes teamResult: Int,
) {
    if (groupResult == null) appState.present(teamResult) else appState.present(groupResult, teamResult)
}

/** Sends the group's report, presenting a refusal itself, and returns what the group's answer tells the reader. */
private suspend fun sendGroupReport(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    report: MessageReport,
    owns: () -> Boolean,
): Int? {
    val outcome = controller.reportMessage(report.record.messageIdHex, report.reason, report.explanation)
    // The controller already presented a failure with its report.
    if (outcome == ReportOutcome.NotAllowed && owns()) appState.present(R.string.report_message_not_allowed)
    return groupReportResult(outcome)
}

/**
 * Keeps the sheet open after the team copy failed. With the group's answer recorded, it is locked to that report so
 * a retry, or a reopened sheet, never reports to the group twice. The group's answer is shown once, when it arrives.
 */
private fun keepTeamCopyForRetry(
    appState: WhiteNoiseAppState,
    surfaces: MessageBubbleSurfaceState,
    @StringRes groupResult: Int?,
    @StringRes failure: Int,
    owns: () -> Boolean,
) {
    if (surfaces.reportGroupResult == null && owns()) groupResult?.let { appState.present(it) }
    surfaces.reportGroupResult = groupResult
    surfaces.reportCopyPending = groupResult != null
    surfaces.reportError =
        if (groupResult != null && failure == R.string.report_team_failed) {
            R.string.report_team_copy_failed
        } else {
            failure
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
