package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.audio.tts.TtsTrustWarningDialog
import dev.ipf.whitenoise.android.ui.group.LargeGroupInviteConfirmationDialog
import dev.ipf.whitenoise.android.ui.navigation.TtsReturnTransitionScreen
import dev.ipf.whitenoise.android.ui.settings.TtsCustomRateDialog

/** Boundary actions qualify visible dispatch/cancellation, not hardware or network completion. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroBoundaryPresentation(fixture: MaestroPresentationFixture) {
    when (fixture.scenario) {
        "tts-trust-cancel", "tts-trust-proceed" ->
            TtsTrustWarningDialog(
                onProceed = { fixture.finish("proceed") },
                onDismiss = { fixture.finish("dismiss") },
            )
        "tts-custom-cancel", "tts-custom-apply" ->
            TtsCustomRateDialog(
                initialRate = 1.1f,
                onDismiss = { fixture.finish("dismiss") },
                onRateSelected = {
                    check(it == 1.1f)
                    fixture.finish("rate-applied")
                },
            )
        "tts-return" -> TtsReturnTransitionScreen(requestId = 1)
        "group-large-cancel", "group-large-continue" ->
            LargeGroupInviteConfirmationDialog(
                onContinue = { fixture.finish("continue") },
                onDismiss = { fixture.finish("dismiss") },
            )
        else -> error("Unknown boundary presentation")
    }
}
