package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.TeamReportOutcome
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.reportToTeam
import dev.ipf.whitenoise.android.ui.conversation.messages.ReportAudience
import dev.ipf.whitenoise.android.ui.conversation.messages.ReportFormState
import dev.ipf.whitenoise.android.ui.conversation.messages.ReportMessageSheet

internal const val PROFILE_TEAM_REPORT_ACTION_TAG = "person_profile.team_report"

/**
 * The profile's report to the White Noise team, held by the profile rather than the sheet, so dismissing and reopening
 * the sheet cannot start a second report while one is sending, and an answer after dismissal is still shown.
 */
internal class ProfileTeamReportState {
    var open by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var error by mutableStateOf<Int?>(null)

    /** Closes the sheet and forgets its last error. */
    fun close() {
        open = false
        error = null
    }
}

/**
 * Privately reports the person at [targetHex] to the White Noise team from [accountRef]. A failure keeps the sheet
 * open with its reason so the reader can retry, or is shown as feedback when the reader already dismissed it. Nothing
 * is posted in any chat, a second submit while one is sending does nothing, and a late answer after [canAct] turns
 * false is dropped.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ProfileTeamReportSheet(
    appState: WhiteNoiseAppState,
    accountRef: String,
    targetHex: String,
    canAct: () -> Boolean,
    state: ProfileTeamReportState,
    onFeedback: (ToastMessage?) -> Unit,
) {
    ReportMessageSheet(
        onDismissRequest = state::close,
        onSubmit = submit@{ reason, explanation ->
            if (state.sending) return@submit
            state.sending = true
            state.error = null
            appState.launchMutation {
                try {
                    val outcome = appState.reportToTeam(accountRef, targetHex, reason, explanation)
                    if (canAct()) presentProfileReportOutcome(appState, state, outcome, onFeedback)
                } finally {
                    state.sending = false
                }
            }
        },
        audience = ReportAudience.TeamProfile,
        form = ReportFormState(state.sending, error = state.error),
    )
}

/** Closes the sheet on success, keeps a failure on an open sheet, and shows it as feedback once dismissed. */
private fun presentProfileReportOutcome(
    appState: WhiteNoiseAppState,
    state: ProfileTeamReportState,
    outcome: TeamReportOutcome,
    onFeedback: (ToastMessage?) -> Unit,
) {
    val message =
        when (outcome) {
            is TeamReportOutcome.Accepted -> outcome.message
            is TeamReportOutcome.Failed -> outcome.message
        }
    if (outcome is TeamReportOutcome.Failed && state.open) {
        state.error = message
    } else {
        state.close()
        appState.present(message)
        onFeedback(appState.toast)
    }
}
