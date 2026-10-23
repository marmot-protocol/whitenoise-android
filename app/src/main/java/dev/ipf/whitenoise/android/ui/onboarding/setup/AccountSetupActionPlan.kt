package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStepFfi

/** Pure projection of the current native grant, including the focused exact Remove choice. */
internal data class SetupActionPlan(
    val primary: OnboardingActionFfi?,
    val secondary: OnboardingActionFfi?,
    val directRemove: Boolean,
)

internal fun AccountSetupState.actionPlan(offered: List<OnboardingActionFfi>): SetupActionPlan {
    val directRemove = directRemoveAvailable(offered)
    val primary = preferredAction(offered) ?: if (directRemove) null else fallbackAction(offered)
    val secondary =
        if (directRemove) {
            OnboardingActionFfi.EDIT_RELAYS
        } else {
            offered.firstOrNull {
                it != primary && it != OnboardingActionFfi.CANCEL_ONBOARDING && it != OnboardingActionFfi.RETRY
            }
        }
    return SetupActionPlan(primary, secondary, directRemove)
}

private fun AccountSetupState.preferredAction(offered: List<OnboardingActionFfi>): OnboardingActionFfi? {
    val snapshot = checkNotNull(snapshot)
    val step = checkNotNull(currentStep)
    return when {
        snapshot.cancellationPending -> offered.firstOrNull()
        OnboardingActionFfi.APPROVE_REPAIR in offered -> OnboardingActionFfi.APPROVE_REPAIR
        step.step == OnboardingStepFfi.PROFILE && OnboardingActionFfi.CONTINUE_WITHOUT in offered ->
            OnboardingActionFfi.CONTINUE_WITHOUT
        step.step == OnboardingStepFfi.SINGLE_DEVICE && OnboardingActionFfi.CONTINUE_ANYWAY in offered ->
            OnboardingActionFfi.CONTINUE_ANYWAY
        OnboardingActionFfi.RECONNECT_SIGNER in offered -> OnboardingActionFfi.RECONNECT_SIGNER
        else -> null
    }
}

private fun fallbackAction(offered: List<OnboardingActionFfi>): OnboardingActionFfi? =
    when {
        OnboardingActionFfi.USE_RECOMMENDED_RELAYS in offered -> OnboardingActionFfi.USE_RECOMMENDED_RELAYS
        OnboardingActionFfi.RETRY in offered -> OnboardingActionFfi.RETRY
        else -> offered.firstOrNull()
    }

private fun AccountSetupState.directRemoveAvailable(offered: List<OnboardingActionFfi>): Boolean {
    val step = currentStep ?: return false
    return step.step in relaySetupSteps &&
        snapshot?.proposal == null &&
        OnboardingActionFfi.EDIT_RELAYS in offered &&
        step.findings
            .filter { it.issue == OnboardingIssueFfi.RETIRED_RELAY }
            .mapNotNull { it.endpoint?.takeIf(String::isNotBlank) }
            .distinct()
            .size == 1
}
