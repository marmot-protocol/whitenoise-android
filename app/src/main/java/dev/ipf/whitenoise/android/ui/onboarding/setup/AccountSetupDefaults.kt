package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi

/** Advances optional follow-list setup; relay publication always requires an explicit decision. */
internal class AccountSetupDefaults(
    private val marmot: MarmotInterface,
    private val account: String,
) {
    /** An empty lookup on the checked relays does not prove that an imported identity has no list. */
    suspend fun advance(initial: OnboardingSnapshotFfi): OnboardingSnapshotFfi {
        if (initial.ready || initial.cancellationPending || initial.proposal != null) {
            return initial
        }
        check(initial.accountIdHex == account)
        val step = AccountSetupState(snapshot = initial).currentStep
        if (step?.step != OnboardingStepFfi.FOLLOWS || OnboardingActionFfi.CONTINUE_WITHOUT !in step.actions) {
            return initial
        }
        return marmot.continueOnboardingWithout(account, step.step)
    }
}
