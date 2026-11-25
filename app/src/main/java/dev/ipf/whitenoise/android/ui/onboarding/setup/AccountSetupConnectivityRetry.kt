package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi

/**
 * The offered Retry for an idle failed checkpoint. A proposal represents either a preview or a saved approval,
 * so neither can be retried automatically.
 */
internal fun AccountSetupState.retryableCheckRequest(): SetupRequest? {
    val snapshot = snapshot ?: return null
    val step = currentStep ?: return null
    val idle = !busy && !showsSetupFailure && editor == null
    val failedCheck =
        step.status == OnboardingStatusFfi.RETRYABLE_FAILURE && OnboardingActionFfi.RETRY in step.actions
    val nothingPending = !snapshot.ready && !snapshot.cancellationPending && snapshot.proposal == null
    return if (idle && failedCheck && nothingPending) {
        SetupRequest(snapshot.revision, step.step, OnboardingActionFfi.RETRY, recoveryEpoch = snapshot.recoveryEpoch)
    } else {
        null
    }
}

/** Matches the exact durable failure under the same account lifetime used to dispatch Retry. */
internal fun OnboardingSnapshotFfi.allowsAutomaticRetry(
    account: String,
    request: SetupRequest,
): Boolean = accountIdHex == account && AccountSetupState(snapshot = this).retryableCheckRequest() == request
