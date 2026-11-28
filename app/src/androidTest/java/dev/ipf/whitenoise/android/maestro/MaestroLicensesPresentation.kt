package dev.ipf.whitenoise.android.maestro

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.ui.settings.OpenSourceLicensesScreen
import dev.ipf.whitenoise.android.ui.settings.OpenSourceNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * Only the resource loader is controlled; production owns Retry and the title. In the app the list and a notice are
 * two Settings pages (#4095), so this fixture stands in for that route: a tapped notice opens its page, and Back from
 * it returns to the list.
 */
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
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { if (selectedId != null) selectedId = null else fixture.finish("dismiss") }
    BackHandler(onBack = back)
    OpenSourceLicensesScreen(
        onBack = back,
        selectedId = selectedId,
        onSelect = { selectedId = it.id },
        noticesLoader = loader,
    )
}
