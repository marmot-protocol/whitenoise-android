package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.whitenoise.android.state.runCatchingCancellable

/**
 * The offered Retry for an idle failed checkpoint. A proposal represents either a preview or a saved approval,
 * so neither can be retried automatically.
 */
internal fun AccountSetupState.retryableCheckRequest(): SetupRequest? {
    val checkpoint = snapshot?.takeUnless { it.ready || it.cancellationPending || it.proposal != null }
    val step =
        currentStep?.takeIf {
            it.status == OnboardingStatusFfi.RETRYABLE_FAILURE && OnboardingActionFfi.RETRY in it.actions
        }
    val idle = !busy && !showsSetupFailure && editor == null
    return if (idle && step != null && checkpoint != null) {
        SetupRequest(
            checkpoint.revision,
            step.step,
            OnboardingActionFfi.RETRY,
            recoveryEpoch = checkpoint.recoveryEpoch,
        )
    } else {
        null
    }
}

/** Matches the exact durable failure under the same account lifetime used to dispatch Retry. */
internal fun OnboardingSnapshotFfi.allowsAutomaticRetry(
    account: String,
    request: SetupRequest,
): Boolean = accountIdHex == account && AccountSetupState(snapshot = this).retryableCheckRequest() == request

/** Only a confirmed local signer can retry unattended: relay authentication and later steps may sign. */
internal fun MarmotInterface.signsLocallyForSetup(account: String): Boolean =
    runCatchingCancellable {
        // Native can resolve an account reference by label before reading it as a public key.
        val summary = listAccounts().singleOrNull { it.accountIdHex == account || it.label == account }
        summary?.let { it.accountIdHex == account && it.localSigning && !it.externalSigning } == true
    }.getOrDefault(false)
