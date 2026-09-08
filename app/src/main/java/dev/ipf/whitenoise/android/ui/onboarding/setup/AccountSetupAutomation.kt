package dev.ipf.whitenoise.android.ui.onboarding.setup

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

/** Optional missing metadata is background work, never a separate confirmation screen. */
internal val AccountSetupState.optionalMetadataPending: Boolean
    get() {
        val optional =
            currentStep?.step in
                setOf(
                    dev.ipf.marmotkit.OnboardingStepFfi.PROFILE,
                    dev.ipf.marmotkit.OnboardingStepFfi.FOLLOWS,
                )
        val interrupted = snapshot?.cancellationPending == true
        return optional && !interrupted && snapshot?.proposal == null
    }
