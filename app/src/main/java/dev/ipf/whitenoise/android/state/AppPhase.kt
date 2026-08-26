package dev.ipf.whitenoise.android.state

sealed interface AppPhase {
    data object Bootstrapping : AppPhase

    data object Onboarding : AppPhase

    data object Ready : AppPhase

    data class Failed(
        val error: ErrorPresentation,
    ) : AppPhase
}
