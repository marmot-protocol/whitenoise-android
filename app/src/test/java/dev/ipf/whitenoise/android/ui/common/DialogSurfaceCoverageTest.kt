package dev.ipf.whitenoise.android.ui.common

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every app dialog reaches its window through the shared dialog surface, which keeps off the window edges. */
class DialogSurfaceCoverageTest {
    /** Only the shared base names the raw alert dialog, so no feature dialog can drop its colours, outline or width. */
    @Test
    fun onlyTheSharedBaseUsesTheRawAlertDialog() {
        assertEquals(
            "feature code must use WhiteNoiseAlertDialog (or ChoiceDialog, SpeechChoiceDialog, ConfirmDialog)",
            setOf(RAW_DIALOGS_SOURCE, SHARED_DIALOGS_SOURCE),
            mainSourcesNaming("AnimatedAlertDialog"),
        )
        assertTrue(
            "WhiteNoiseAlertDialog must be the one shared caller of the raw alert dialog",
            "AnimatedAlertDialog(" in sharedDialogs().functionBody("WhiteNoiseAlertDialog"),
        )
    }

    /** Custom-body dialogs may only leave the list below as they move onto the shared surface; none may join it. */
    @Test
    fun customBodyDialogsOnlyShrink() {
        val unexpected = mainSourcesNaming("AnimatedBasicAlertDialog") - CUSTOM_BODY_DIALOG_SOURCES
        assertTrue(
            "new custom-body dialogs must sit on the shared dialog surface: $unexpected",
            unexpected.isEmpty(),
        )
    }

    /** Both raw renderers bound the width first, while the incoming width is still the dialog window's. */
    @Test
    fun bothRenderersBoundTheirWidthAgainstTheWindow() {
        val raw = appModuleSource("src/main/java/$APP/$RAW_DIALOGS_SOURCE")
        assertTrue(
            "the alert dialog's modifier chain must start with the window width bound",
            Regex("""modifier\s*=\s*Modifier\s*\.dialogWindowWidthBounds\(\)""")
                .containsMatchIn(raw.functionBody("AnimatedAlertDialog")),
        )
        assertTrue(
            "the custom-body dialog's modifier chain must start with the window width bound",
            "modifier = Modifier.dialogWindowWidthBounds()" in raw.functionBody("AnimatedBasicAlertDialog"),
        )
    }

    /** Main sources, relative to the app package, that mention [identifier] as a whole word. */
    private fun mainSourcesNaming(identifier: String): Set<String> {
        val root = appModuleFile("src/main/java/$APP")
        val word = Regex("""\b${Regex.escape(identifier)}\b""")
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && word.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
    }

    /** The shared dialog components' source. */
    private fun sharedDialogs(): String = appModuleSource("src/main/java/$APP/$SHARED_DIALOGS_SOURCE")

    private companion object {
        const val APP = "dev/ipf/whitenoise/android"
        const val RAW_DIALOGS_SOURCE = "ui/common/AnimatedAlertDialog.kt"
        const val SHARED_DIALOGS_SOURCE = "ui/common/WhiteNoiseDialogs.kt"

        /** Remove a source here once its dialog moves onto the shared surface. */
        val CUSTOM_BODY_DIALOG_SOURCES =
            setOf(
                RAW_DIALOGS_SOURCE,
                SHARED_DIALOGS_SOURCE,
                "ui/conversation/messages/MessageDeleteDialog.kt",
                "ui/settings/TtsRatePicker.kt",
                "ui/settings/UsageDiagnosticsUi.kt",
            )
    }
}
