package dev.ipf.whitenoise.android.maestro

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.ui.settings.OpenSourceLicensesScreen
import dev.ipf.whitenoise.android.ui.settings.OpenSourceNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** Only the resource loader is controlled; production owns selection, Retry, title and both Back routes. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroLicensesPresentation(fixture: MaestroPresentationFixture) {
    val loader: suspend (Resources) -> List<OpenSourceNotice> =
        remember(fixture) {
            var reads = 0
            val controlled: suspend (Resources) -> List<OpenSourceNotice> = { _ ->
                if (fixture.scenario == "extra-licenses-loading") awaitCancellation()
                withContext(Dispatchers.Main.immediate) {
                    if (reads++ == 0) error("Controlled first resource failure")
                    fixture.record("retry")
                    listOf(OpenSourceNotice("fixture", "Maestro local notice", "Complete local notice text"))
                }
            }
            controlled
        }
    OpenSourceLicensesScreen(onBack = { fixture.finish("dismiss") }, noticesLoader = loader)
}
