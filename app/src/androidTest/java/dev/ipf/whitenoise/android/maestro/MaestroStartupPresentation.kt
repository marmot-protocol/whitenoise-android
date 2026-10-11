package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.StartupFailureScreen
import dev.ipf.whitenoise.android.ui.common.StartupLoadingScreen
import dev.ipf.whitenoise.android.ui.common.StartupLocalProjectionScreen

/** Startup recovery controls remain testable without inducing a corrupt real account or storage fault. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroStartupPresentation(fixture: MaestroPresentationFixture) {
    when (fixture.scenario) {
        "startup-projection-loading", "startup-projection-retryable", "startup-projection-terminal" ->
            StartupLocalProjectionScreen(
                error =
                    if (fixture.scenario == "startup-projection-loading") {
                        null
                    } else {
                        ErrorPresentation(
                            AppText.Resource(R.string.error_try_again),
                            "Disposable diagnostic report",
                            retryable = fixture.scenario == "startup-projection-retryable",
                        )
                    },
                onRetry = { fixture.finish("retry") },
            )
        "startup-loading" -> StartupLoadingScreen()
        "startup-destination-loading" -> LoadingScreen("Waiting for disposable fixture")
        "startup-retryable", "startup-terminal" ->
            StartupFailureScreen(
                title = "Disposable startup failure",
                error =
                    ErrorPresentation(
                        AppText.Resource(R.string.error_try_again),
                        "Disposable diagnostic report",
                        retryable = fixture.scenario == "startup-retryable",
                    ),
                onRetry = { fixture.finish("retry") },
            )
        else -> error("Unknown startup presentation")
    }
}
