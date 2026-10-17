package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.share.ShareImportProgress
import dev.ipf.whitenoise.android.ui.share.ShareImportStatus

/** Actual import progress chrome with controlled progress, without reading another app's content. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroImportStatusPresentation(fixture: MaestroPresentationFixture) {
    val progress =
        when (fixture.scenario.removePrefix("modal-import-")) {
            "known" -> ShareImportProgress(1, 3, 100L, 200L)
            "unknown" -> ShareImportProgress(1, 3, 100L, null)
            "none" -> null
            else -> error("Unknown import progress presentation")
        }
    ShareImportStatus(
        importing = true,
        progress = progress,
        request = null,
        onCancel = { fixture.finish("import-cancel") },
    ) { error("Importing cannot expose its share destination content") }
}
