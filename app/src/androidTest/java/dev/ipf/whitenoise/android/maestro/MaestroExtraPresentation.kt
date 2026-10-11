package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable

/** Fault-state chrome and native-backed journeys retain separate acceptance boundaries. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroExtraPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("extra-modal-") -> MaestroModalPresentation(fixture)
        fixture.scenario.startsWith("extra-search-") -> MaestroSearchPresentation(fixture)
        fixture.scenario.startsWith("extra-message-") -> MaestroMessagePresentation(fixture)
        fixture.scenario.startsWith("extra-forward-") -> MaestroForwardPresentation(fixture)
        fixture.scenario.startsWith("extra-relay-") -> MaestroRelayPresentation(fixture)
        fixture.scenario.startsWith("extra-native-") -> MaestroNativePresentation(fixture)
        else -> MaestroStatePresentation(fixture)
    }
}
