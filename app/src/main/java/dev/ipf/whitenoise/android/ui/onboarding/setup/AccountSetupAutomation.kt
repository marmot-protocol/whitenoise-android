package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi

/** Navigates once after native setup finishes; durable progress remains in MDK. */
internal class AccountSetupAutomation {
    private var opened = false

    /** Failed activation retains a manual retry, while routine completion needs no extra tap. */
    fun advance(
        state: AccountSetupState,
        openChats: () -> Unit,
    ) {
        val ready = state.snapshot?.let { it.ready && !it.cancellationPending } == true
        val interrupted = state.busy || state.error || state.staleDecision
        if (ready && !interrupted && !opened) {
            opened = true
            openChats()
        }
    }
}

/** Follow-list checks advance in the background; a missing profile keeps its edit-or-skip choice. */
internal val AccountSetupState.optionalMetadataPending: Boolean
    get() {
        val optional = currentStep?.step == OnboardingStepFfi.FOLLOWS
        val interrupted = snapshot?.cancellationPending == true
        return optional && !interrupted && snapshot?.proposal == null
    }

/** Routine device consent needs one action; interrupted discovery and native recovery retain their controls. */
internal val AccountSetupState.routineDeviceNotice: Boolean
    get() {
        val step = currentStep ?: return false
        val interrupted = busy || error || disconnected || staleDecision || snapshot?.cancellationPending == true
        return !interrupted &&
            snapshot?.proposal == null &&
            step.step == OnboardingStepFfi.SINGLE_DEVICE &&
            step.status == OnboardingStatusFfi.NEEDS_INPUT &&
            OnboardingActionFfi.CONTINUE_ANYWAY in step.actions &&
            step.findings.all { it.issue in routineDeviceFindings }
    }

private val routineDeviceFindings =
    setOf(OnboardingIssueFfi.MULTI_DEVICE_UNSUPPORTED, OnboardingIssueFfi.OTHER_INSTALLATION_POSSIBLE)

/** Device warnings already have dedicated copy; genuine failed checks still explain their recovery action. */
internal val AccountSetupState.decisionFinding: OnboardingFindingFfi?
    get() {
        val step = currentStep ?: return null
        return step.findings.firstOrNull {
            step.step != OnboardingStepFfi.SINGLE_DEVICE || it.issue !in routineDeviceFindings
        }
    }
