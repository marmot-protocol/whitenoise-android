package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Privately reports the person at [targetHex] to the White Noise team from [accountRef]. A failure keeps the sheet
 * open with its reason so the reader can retry, nothing is posted in any chat, and a late answer after [canAct] turns
 * false is dropped.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ProfileTeamReportSheet(
    appState: WhiteNoiseAppState,
    accountRef: String,
    targetHex: String,
    canAct: () -> Boolean,
    onClose: () -> Unit,
    onFeedback: (ToastMessage?) -> Unit,
) {
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    ReportMessageSheet(
        onDismissRequest = onClose,
        onSubmit = { reason, explanation ->
            sending = true
            error = null
            appState.launchMutation {
                try {
                    val outcome = appState.reportToTeam(accountRef, targetHex, reason, explanation)
                    if (canAct()) {
                        when (outcome) {
                            is TeamReportOutcome.Accepted -> {
                                onClose()
                                appState.present(outcome.message)
                                onFeedback(appState.toast)
                            }
                            is TeamReportOutcome.Failed -> error = outcome.message
                        }
                    }
                } finally {
                    sending = false
                }
            }
        },
        audience = ReportAudience.TeamProfile,
        form = ReportFormState(sending, error = error),
    )
}
