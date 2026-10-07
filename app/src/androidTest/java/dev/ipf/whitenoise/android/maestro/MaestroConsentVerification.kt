package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Reading disclosure or cancelling confirmation cannot grant logging or settle an undecided native receipt. */
internal suspend fun verifyMaestroConsent(
    native: Marmot,
    state: WhiteNoiseAppState,
    postcondition: String,
) {
    require(postcondition == "consent-pending" || postcondition == "consent-declined")
    val declined = postcondition == "consent-declined"
    val expected =
        if (declined) UsageDiagnosticsDecisionFfi.DECLINED else UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED
    withTimeout(30_000L) {
        while (true) {
            check(!native.auditLogSettings().enabled) { "Disclosure/cancellation enabled audit logging" }
            val decision = native.usageDiagnosticsSettings().decision
            check(decision != UsageDiagnosticsDecisionFfi.GRANTED) { "Disclosure/cancellation granted telemetry" }
            val requiresUsageChoice = withContext(Dispatchers.Main.immediate) { state.diagnostics.requiresChoice }
            if (decision == expected && requiresUsageChoice != declined) return@withTimeout
            delay(100L)
        }
    }
}
