package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Reading disclosure or cancelling confirmation cannot grant logging or settle an undecided native receipt. */
internal suspend fun verifyMaestroConsent(
    native: Marmot,
    state: WhiteNoiseAppState,
    postcondition: String,
) {
    val declined = postcondition == "consent-declined"
    val expected =
        if (declined) UsageDiagnosticsDecisionFfi.DECLINED else UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED
    withTimeout(30_000L) {
        while (true) {
            check(!native.auditLogSettings().enabled) { "Disclosure/cancellation enabled audit logging" }
            val decision = native.usageDiagnosticsSnapshot().settings.decision
            check(decision != UsageDiagnosticsDecisionFfi.GRANTED) { "Disclosure/cancellation granted telemetry" }
            if (decision == expected && state.auditUploadConsentRequired != declined) return@withTimeout
            delay(100L)
        }
    }
}
