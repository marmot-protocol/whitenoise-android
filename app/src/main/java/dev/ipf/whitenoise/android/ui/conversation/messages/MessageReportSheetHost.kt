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
 * the sheet stays open and locked, so a retry sends only that copy and the group never gets the report twice.
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
    ReportMessageSheet(
        onDismissRequest = surfaces::closeReport,
        onSubmit = { reason, explanation ->
            val team = ReportTeamChoice(audience, surfaces.reportToTeam)
            surfaces.reportInFlight = true
            surfaces.reportError = null
            appState.launchMutation {
                try {
                    sendMessageReport(appState, controller, record, team, reason, explanation, surfaces)
                } finally {
                    surfaces.reportInFlight = false
                }
            }
        },
        audience = audience,
        sendToTeam = surfaces.reportToTeam,
        onSendToTeamChange = { surfaces.reportToTeam = it },
        form = ReportFormState(surfaces.reportInFlight, surfaces.reportGroupResult != null, surfaces.reportError),
    )
}

/** Sends the group report unless the group already has it, then the team copy, and says what each achieved. */
@Suppress("LongParameterList")
private suspend fun sendMessageReport(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    record: AppMessageRecordFfi,
    team: ReportTeamChoice,
    reason: ReportReasonFfi,
    explanation: String,
    surfaces: MessageBubbleSurfaceState,
) {
    val groupResult =
        if (team.reachesGroup && surfaces.reportGroupResult == null) {
            sendGroupReport(appState, controller, record.messageIdHex, reason, explanation)
        } else {
            surfaces.reportGroupResult
        }
    if (team.reachesGroup && groupResult == null || !team.reachesTeam) {
        surfaces.closeReport()
        groupResult?.let { appState.present(it) }
        return
    }
    val outcome =
        controller.boundAccountRef?.let { appState.reportToTeam(it, record.sender, reason, explanation) }
            ?: TeamReportOutcome.Failed(R.string.report_team_failed)
    when (outcome) {
        is TeamReportOutcome.Accepted -> {
            surfaces.closeReport()
            if (groupResult == null) {
                appState.present(outcome.message)
            } else {
                appState.present(groupResult, outcome.message)
            }
        }
        is TeamReportOutcome.Failed -> keepTeamCopyForRetry(appState, surfaces, groupResult, outcome.message)
    }
}

/** Sends the group's report, presenting a refusal itself, and returns what the group's answer tells the reader. */
private suspend fun sendGroupReport(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    messageIdHex: String,
    reason: ReportReasonFfi,
    explanation: String,
): Int? {
    val outcome = controller.reportMessage(messageIdHex, reason, explanation)
    // The controller already presented a failure with its report.
    if (outcome == ReportOutcome.NotAllowed) appState.present(R.string.report_message_not_allowed)
    return groupReportResult(outcome)
}

/**
 * Keeps the sheet open after the team copy failed, locked to the group's answer so a retry never reports to the
 * group twice. The group's answer is shown once, when it first arrives.
 */
private fun keepTeamCopyForRetry(
    appState: WhiteNoiseAppState,
    surfaces: MessageBubbleSurfaceState,
    @StringRes groupResult: Int?,
    @StringRes failure: Int,
) {
    if (surfaces.reportGroupResult == null) groupResult?.let { appState.present(it) }
    surfaces.reportGroupResult = groupResult
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
