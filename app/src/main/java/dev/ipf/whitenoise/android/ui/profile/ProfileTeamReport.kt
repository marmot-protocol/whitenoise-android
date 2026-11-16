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
import java.util.concurrent.atomic.AtomicBoolean

internal const val PROFILE_TEAM_REPORT_ACTION_TAG = "person_profile.team_report"

/**
 * Privately reports the person at [targetHex] to the White Noise team from [accountRef]. A failure keeps the sheet
 * open with its reason so the reader can retry, or is shown as feedback when the reader already dismissed it. Nothing
 * is posted in any chat, a second tap while sending does nothing, and a late answer after [canAct] turns false is
 * dropped.
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
    // Outlives the sheet, so an answer that lands after the reader dismissed it is still shown.
    val dismissed = remember { AtomicBoolean(false) }
    ReportMessageSheet(
        onDismissRequest = {
            dismissed.set(true)
            onClose()
        },
        onSubmit = submit@{ reason, explanation ->
            if (sending) return@submit
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
                            is TeamReportOutcome.Failed ->
                                if (dismissed.get()) {
                                    appState.present(outcome.message)
                                    onFeedback(appState.toast)
                                } else {
                                    error = outcome.message
                                }
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
