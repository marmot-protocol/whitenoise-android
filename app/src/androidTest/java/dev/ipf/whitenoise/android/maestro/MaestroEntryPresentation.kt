package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingScreen
import dev.ipf.whitenoise.android.ui.profile.AddIdentitySheet
import dev.ipf.whitenoise.android.ui.profile.ProfileQrSheet
import dev.ipf.whitenoise.android.ui.settings.RemoteSignerAccountAction
import dev.ipf.whitenoise.android.ui.settings.RemoteSignerAccountDialogs

/** Connectivity is explicitly unavailable, so entry-screen actions cannot create or recover a native account. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroEntryPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario == "surface-remote-signer-disconnect" ->
            RemoteSignerAccountDialogs(
                appState = fixture.appState,
                account = fixture.peerAccountIdHex,
                dialog = RemoteSignerAccountAction.Disconnect,
                review = null,
                onDismiss = { fixture.finish("dismiss") },
            )
        fixture.scenario.startsWith("surface-onboarding-") ->
            OnboardingScreen(fixture.appState, hasActiveNetwork = { false })
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
