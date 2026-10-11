package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingScreen
import dev.ipf.whitenoise.android.ui.profile.AddIdentitySheet
import dev.ipf.whitenoise.android.ui.profile.ProfileQrSheet

/** Connectivity is explicitly unavailable, so entry-screen actions cannot create or recover a native account. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroEntryPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("surface-onboarding-") ->
            OnboardingScreen(fixture.appState, hasValidatedInternet = { false })
        fixture.scenario.startsWith("surface-addprofile-") ->
            AddIdentitySheet(fixture.appState, onDismiss = { fixture.finish("dismiss") })
        else ->
            ProfileQrSheet(
                appState = fixture.appState,
                accountIdHex = checkNotNull(fixture.appState.activeAccount).accountIdHex,
                onDismiss = { fixture.finish("dismiss") },
                showScan = false,
            )
    }
}
