package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi

/** Advances routine choices through the published API; MDK remains the sole owner of durable progress. */
internal class AccountSetupDefaults(
    private val marmot: MarmotInterface,
    private val account: String,
) {
    /** Skips optional metadata and creates only confirmed-missing relay lists, stopping at genuine decisions. */
    suspend fun advance(initial: OnboardingSnapshotFfi): OnboardingSnapshotFfi {
        var current = initial
        val attempted = mutableSetOf<OnboardingStepFfi>()
        while (!current.ready && !current.cancellationPending && current.proposal == null) {
            current = advanceStep(current, attempted) ?: break
        }
        return current
    }

    /** Attempts each checkpoint at most once per explicit run, so failures never create retry loops. */
    private suspend fun advanceStep(
        current: OnboardingSnapshotFfi,
        attempted: MutableSet<OnboardingStepFfi>,
    ): OnboardingSnapshotFfi? {
        check(current.accountIdHex == account)
        val step = AccountSetupState(snapshot = current).currentStep
        return if (step != null && attempted.add(step.step)) {
            when {
                step.canSkipMetadata() -> marmot.continueOnboardingWithout(account, step.step)
                step.confirmedMissingRelays() -> createMissingRelays(step.step)
                else -> null
            }
        } else {
            null
        }
    }

    /** A newly discovered existing record stops automatic approval; native approval also rechecks discovery. */
    private suspend fun createMissingRelays(step: OnboardingStepFfi): OnboardingSnapshotFfi {
        val proposed = marmot.proposeOnboardingRecommendedRelays(account, step)
        val proposal = proposed.proposal
        val createsNewList = proposal?.step == step && proposal.previousEventId == null
        val canApprove =
            proposed.steps
                .firstOrNull { it.step == step }
                ?.actions
                .orEmpty()
        return if (createsNewList && OnboardingActionFfi.APPROVE_REPAIR in canApprove) {
            check(proposed.accountIdHex == account && !proposed.cancellationPending)
            marmot.approveOnboardingRepair(account, requireNotNull(proposal).revision)
        } else {
            proposed
        }
    }
}

/** Optional records have no onboarding publication or completion requirement. */
private fun OnboardingStepStateFfi.canSkipMetadata() =
    step in setOf(OnboardingStepFfi.PROFILE, OnboardingStepFfi.FOLLOWS) &&
        OnboardingActionFfi.CONTINUE_WITHOUT in actions

/** Missing is affirmative discovery evidence; unknown, unhealthy, and existing lists require review. */
private fun OnboardingStepStateFfi.confirmedMissingRelays(): Boolean {
    val canPropose =
        step in setOf(OnboardingStepFfi.RELAYS, OnboardingStepFfi.INBOX_RELAYS) &&
            status == OnboardingStatusFfi.NEEDS_INPUT &&
            OnboardingActionFfi.USE_RECOMMENDED_RELAYS in actions
    return canPropose && findings.map { it.issue }.toSet() == setOf(OnboardingIssueFfi.MISSING)
}
