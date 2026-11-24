@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextDirection
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStatusFfi

/** The simplified timeout view is for an active relay check whose primary action is Retry. */
internal fun AccountSetupState.focusesRelayTimeout(): Boolean {
    val step = currentStep ?: return false
    val proposalStep = snapshot?.proposal?.step
    val activeDecision = !disconnected && snapshot?.cancellationPending == false && proposalStep != step.step
    val relayTimeout =
        step.step in relaySetupSteps && step.findings.any { it.issue == OnboardingIssueFfi.TIMED_OUT }
    val retryFirst =
        !blocksSetupActions && step.status != OnboardingStatusFfi.WAITING_FOR_SIGNER &&
            actionPlan(step.actions).primary == OnboardingActionFfi.RETRY
    return activeDecision && relayTimeout && retryFirst
}

/** Name the affected relays here and show their exact addresses in Details. */
@Composable
internal fun SetupRelayTimeoutHosts(findings: List<OnboardingFindingFfi>) {
    findings
        .filter { it.issue == OnboardingIssueFfi.TIMED_OUT }
        .mapNotNull { finding -> finding.endpoint?.let { Uri.parse(it).host }?.takeIf(String::isNotBlank) }
        .distinct()
        .forEach { host ->
            Text(
                host,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.Ltr,
                ),
            )
        }
}
