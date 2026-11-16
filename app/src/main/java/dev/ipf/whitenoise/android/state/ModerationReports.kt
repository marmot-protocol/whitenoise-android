package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ModerationReportOriginFfi
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.R

/** How a private report to the White Noise team ended. */
internal sealed interface TeamReportOutcome {
    /** MDK took the report, and [message] says whether a relay has it yet. A queued report is never resubmitted. */
    data class Accepted(
        @StringRes val message: Int,
    ) : TeamReportOutcome

    /** Nothing was sent, and [message] says why and that the reader can try again. */
    data class Failed(
        @StringRes val message: Int,
    ) : TeamReportOutcome
}

/** What the reader is told about an accepted team report: only a relay's acceptance counts as sent. */
@StringRes
internal fun teamReportAcceptedMessage(disposition: SendAcceptDispositionFfi): Int =
    when (disposition) {
        SendAcceptDispositionFfi.PUBLISHED -> R.string.report_team_sent
        SendAcceptDispositionFfi.ACCEPTED_PENDING -> R.string.report_team_queued
        SendAcceptDispositionFfi.COMPLETION_UNKNOWN -> R.string.report_team_pending
    }

/**
 * MDK's answer to whether this build can reach the White Noise team, null until it has been read. The destination
 * comes from the build, so one successful read holds for the whole process.
 */
internal class TeamReportingMirror {
    var available: Boolean? by mutableStateOf(null)
}

/**
 * Message reports that outlive the bubble that sent them, keyed by account, group and message: whether one is still
 * sending, and the group's answer once the group took it, until the report finishes.
 */
internal class MessageReportRegistry {
    val groupResults: MutableMap<String, Int> = mutableStateMapOf()
    val inFlight: MutableMap<String, Boolean> = mutableStateMapOf()
}

/** Whether report actions may offer the White Noise team: false until MDK has confirmed the destination. */
internal val WhiteNoiseAppState.teamReportingAvailable: Boolean
    get() = runtimeMirrors.teamReporting.available == true

/** Asks MDK once whether the White Noise team can receive reports, keeping only an answer that MDK gave. */
internal suspend fun WhiteNoiseAppState.loadTeamReporting() {
    val mirror = runtimeMirrors.teamReporting
    if (mirror.available != null) return
    runCatchingCancellable { marmotIo { moderationReportingAvailable() } }.onSuccess { mirror.available = it }
}

/**
 * Privately reports [reportedPubkey] from [accountRef] to the White Noise team. MDK gift-wraps only the reported key,
 * [reason] and the bounded [explanation], so no message content or group detail leaves the device.
 */
internal suspend fun WhiteNoiseAppState.reportToTeam(
    accountRef: String,
    reportedPubkey: String,
    reason: ReportReasonFfi,
    explanation: String,
): TeamReportOutcome =
    runCatchingCancellable {
        marmotIo {
            val origin = ModerationReportOriginFfi.REPORT
            submitModerationReport(accountRef, reportedPubkey, reason, boundedExplanation(explanation), origin)
        }
    }.fold(
        onSuccess = { TeamReportOutcome.Accepted(teamReportAcceptedMessage(it.acceptDisposition)) },
        onFailure = { failure ->
            TeamReportOutcome.Failed(
                if (failure is MarmotKitException.ModerationReportRateLimited) {
                    R.string.report_team_rate_limited
                } else {
                    R.string.report_team_failed
                },
            )
        },
    )
