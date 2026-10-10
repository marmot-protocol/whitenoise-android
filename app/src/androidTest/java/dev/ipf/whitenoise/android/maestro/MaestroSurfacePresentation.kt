package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable

/** Additional production entry points stay separate from their native business-operation qualification. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroSurfacePresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("surface-onboarding-") ||
            fixture.scenario.startsWith("surface-addprofile-") ||
            fixture.scenario.startsWith("surface-profile-qr-") -> MaestroEntryPresentation(fixture)
        fixture.scenario.startsWith("surface-rejoin-") ||
            fixture.scenario.startsWith("surface-date-review-") -> MaestroRecoveryPresentation(fixture)
        else -> MaestroPickerPresentation(fixture)
    }
}
