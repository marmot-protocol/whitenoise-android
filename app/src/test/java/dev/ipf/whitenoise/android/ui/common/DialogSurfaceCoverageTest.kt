package dev.ipf.whitenoise.android.ui.common

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every app dialog reaches its window through the shared dialog surface, which keeps off the window edges. */
class DialogSurfaceCoverageTest {
    /** The raw alert dialog has one caller, `WhiteNoiseAlertDialog`, so no feature dialog can drop its styling. */
    @Test
    fun onlyTheSharedBaseUsesTheRawAlertDialog() {
        assertEquals(
            "feature code must use WhiteNoiseAlertDialog (or ChoiceDialog, SpeechChoiceDialog, ConfirmDialog)",
            setOf(RAW_DIALOGS_SOURCE, SHARED_DIALOGS_SOURCE),
            mainSourcesNaming("AnimatedAlertDialog"),
        )
        assertEquals(
            "the raw dialog's own file must not call it",
            0,
            callCount(appModuleSource("src/main/java/$APP/$RAW_DIALOGS_SOURCE"), "AnimatedAlertDialog"),
        )
        assertEquals(
            "the shared dialog file must call the raw alert dialog exactly once",
            1,
            callCount(sharedDialogs(), "AnimatedAlertDialog"),
        )
        assertEquals(
            "that one call must be WhiteNoiseAlertDialog's",
            1,
            callCount(sharedDialogs().functionBody("WhiteNoiseAlertDialog"), "AnimatedAlertDialog"),
        )
    }

    /**
     * Custom-body dialog calls are pinned per file and can only go down: a new call anywhere fails, and a dialog that
     * moves onto the shared surface lowers its file's count here in the same change.
     */
    @Test
    fun customBodyDialogCallsOnlyShrink() {
        val unexpected = mainSourcesNaming("AnimatedBasicAlertDialog") - CUSTOM_BODY_DIALOG_CALLS.keys
        assertTrue(
            "new custom-body dialogs must sit on the shared dialog surface: $unexpected",
            unexpected.isEmpty(),
        )
        val actual =
            CUSTOM_BODY_DIALOG_CALLS.keys.associateWith { path ->
                callCount(appModuleSource("src/main/java/$APP/$path"), "AnimatedBasicAlertDialog")
            }
        assertEquals(
            "custom-body dialog calls per file changed; lower a count when a dialog moves, never raise one",
            CUSTOM_BODY_DIALOG_CALLS,
            actual,
        )
    }

    /** Both raw renderers cap the surface against the app window they open over, not the dialog window's own width. */
    @Test
    fun bothRenderersCapTheirWidthAgainstTheAppWindow() {
        val raw = appModuleSource("src/main/java/$APP/$RAW_DIALOGS_SOURCE")
        for (renderer in listOf("AnimatedAlertDialog", "AnimatedBasicAlertDialog")) {
            val body = raw.functionBody(renderer)
            assertTrue(
                "$renderer must read the app window's width where the dialog is composed",
                "val appWindowWidth = LocalWindowInfo.current.containerDpSize.width" in body,
            )
            assertTrue(
                "$renderer must cap its surface with that width",
                Regex("""Modifier\s*\.dialogWidthBounds\(appWindowWidth\)""").containsMatchIn(body),
            )
        }
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

    /** Calls of [function] in [source], qualified or not, leaving out its own declaration. */
    private fun callCount(
        source: String,
        function: String,
    ): Int = Regex("""(?<![\w])(?<!fun )${Regex.escape(function)}\s*\(""").findAll(source).count()

    /** The shared dialog components' source. */
    private fun sharedDialogs(): String = appModuleSource("src/main/java/$APP/$SHARED_DIALOGS_SOURCE")

    private companion object {
        const val APP = "dev/ipf/whitenoise/android"
        const val RAW_DIALOGS_SOURCE = "ui/common/AnimatedAlertDialog.kt"
        const val SHARED_DIALOGS_SOURCE = "ui/common/WhiteNoiseDialogs.kt"

        /** `AnimatedBasicAlertDialog` calls per file; lower a count once its dialog moves onto the shared surface. */
        val CUSTOM_BODY_DIALOG_CALLS =
            mapOf(
                RAW_DIALOGS_SOURCE to 0,
                SHARED_DIALOGS_SOURCE to 0,
                "ui/settings/TtsRatePicker.kt" to 1,
                "ui/settings/UsageDiagnosticsUi.kt" to 1,
            )
    }
}
