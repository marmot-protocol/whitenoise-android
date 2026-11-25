package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi

/** Pure projection of the current native grant, including the focused exact Remove choice. */
internal data class AccountSetupActionPlan(
    val primary: OnboardingActionFfi?,
    val secondary: OnboardingActionFfi?,
    val directRemove: Boolean,
)

/**
 * Orders the actions offered by the current native decision, including the exact relay removal path. Only actions that
 * can move the step forward are pinned: a discovery-relay change only changes where records are looked up, so it stays
 * under Details, and a failed check keeps Try again on screen.
 */
internal fun AccountSetupState.actionPlan(offered: List<OnboardingActionFfi>): AccountSetupActionPlan {
    val directRemove = directRemoveAvailable(offered)
    val pinnable = offered.filterNot { it == OnboardingActionFfi.EDIT_DISCOVERY_RELAYS }
    val primary = preferredAction(pinnable) ?: if (directRemove) null else fallbackAction(pinnable)
    val keepRetry =
        currentStep?.status == OnboardingStatusFfi.RETRYABLE_FAILURE &&
            snapshot?.cancellationPending != true &&
            OnboardingActionFfi.RETRY in offered &&
            primary != OnboardingActionFfi.RETRY &&
            primary != OnboardingActionFfi.RECONNECT_SIGNER
    val secondary =
        when {
            keepRetry -> OnboardingActionFfi.RETRY
            directRemove -> OnboardingActionFfi.EDIT_RELAYS
            else -> pinnable.firstOrNull { it != primary && it !in unpinnedSecondary && !duplicates(primary, it) }
        }
    return AccountSetupActionPlan(primary, secondary, directRemove)
}

internal val AccountSetupState.blocksSetupActions: Boolean
    get() = error || staleDecision || optionalMetadataPending

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

/** Removing unusable relays fixes the cause; a failed check is retried before anything is replaced. */
private fun AccountSetupState.fallbackAction(offered: List<OnboardingActionFfi>): OnboardingActionFfi? {
    val failedCheck = currentStep?.status == OnboardingStatusFfi.RETRYABLE_FAILURE
    return when {
        OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS in offered -> OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS
        failedCheck && OnboardingActionFfi.RETRY in offered -> OnboardingActionFfi.RETRY
        OnboardingActionFfi.USE_RECOMMENDED_RELAYS in offered -> OnboardingActionFfi.USE_RECOMMENDED_RELAYS
        OnboardingActionFfi.RETRY in offered -> OnboardingActionFfi.RETRY
        else -> offered.firstOrNull { it != OnboardingActionFfi.CANCEL_ONBOARDING }
    }
}

private val unpinnedSecondary = setOf(OnboardingActionFfi.CANCEL_ONBOARDING, OnboardingActionFfi.RETRY)

/** Both relay repair actions open the same native preview, so one button is enough. */
private fun duplicates(
    primary: OnboardingActionFfi?,
    candidate: OnboardingActionFfi,
): Boolean = primary == OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS && candidate == OnboardingActionFfi.USE_RECOMMENDED_RELAYS

private fun AccountSetupState.directRemoveAvailable(offered: List<OnboardingActionFfi>): Boolean =
    OnboardingActionFfi.EDIT_RELAYS in offered && directRemovalEndpoint() != null
