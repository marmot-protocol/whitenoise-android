package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate: every external store listing launch (dictation provider install, voice-message transcript
 * provider install, Offline Translator acquisition, and app-update Zapstore listing) routes through the shared
 * `ExternalStoreLauncher` helper. `ExternalStoreLauncherTest` proves the helper attaches
 * `Intent.FLAG_ACTIVITY_NEW_TASK` to every intent it builds.
 */
class ExternalStoreLaunchCoverageTest {
    /** Every candidate and launch in the shared helper goes through the one `ACTION_VIEW` intent builder. */
    @Test
    fun sharedStoreLauncherBuildsEveryCandidateThroughOneViewIntentBuilder() {
        val launcher = appModuleSource("src/main/java/dev/ipf/whitenoise/android/core/ExternalStoreLauncher.kt")

        val viewIntentBody = launcher.expressionFunctionBody("externalStoreViewIntent")
        assertTrue(viewIntentBody.contains("Intent.ACTION_VIEW"))

        val candidatesBody = launcher.functionBody("externalStoreListingIntents")
        assertTrue(candidatesBody.contains("externalStoreViewIntent("))

        val openBody = launcher.functionBody("openExternalStoreListing")
        assertTrue(openBody.contains("externalStoreListingIntents("))
    }

    /** Dictation, voice-message transcript, Offline Translator, and app-update routes share the launcher. */
    @Test
    fun dictationTranslatorAndAppUpdateRoutesShareExternalStoreLauncher() {
        val dictation =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/settings/DictationSettingsScreen.kt")
        val dictationListingBody = dictation.expressionFunctionBody("openOfflineSpeechToTextListing")
        assertTrue(dictationListingBody.contains("openExternalStoreListing("))
        assertFalse(dictationListingBody.contains("Intent.ACTION_VIEW"))
        assertFalse(dictationListingBody.contains("startActivity("))
        assertTrue(dictation.functionBody("DictationSettingsScreen").contains("openOfflineSpeechToTextListing("))

        val transcription =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/AudioTranscription.kt")
        assertTrue(
            transcription.functionBody("ReceivedAudioTranscription").contains("openOfflineSpeechToTextListing("),
        )

        val translator =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/translation/OfflineTranslatorAcquisition.kt")
        val translatorBody = translator.functionBody("openOfflineTranslator")
        assertTrue(translatorBody.contains("openExternalStoreListing("))
        assertTrue(translatorBody.contains("Intent.FLAG_ACTIVITY_NEW_TASK"))
        assertFalse(translatorBody.contains("Intent.ACTION_VIEW"))

        val appUpdates =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppStateAppUpdates.kt")
        val updateListingBody = appUpdates.expressionFunctionBody("openAppUpdateZapstoreListing")
        assertTrue(updateListingBody.contains("openExternalStoreListing("))
        assertFalse(updateListingBody.contains("Intent.ACTION_VIEW"))
        assertTrue(appUpdates.functionBody("openZapstoreListing").contains("openAppUpdateZapstoreListing("))
        assertTrue(appUpdates.functionBody("handleAppUpdateAction").contains("openZapstoreListing("))
    }

    /** No production source outside `core/ExternalStoreLauncher.kt` builds its own store-listing intents. */
    @Test
    fun noProductionSourceOutsideExternalStoreLauncherBuildsStoreListingIntents() {
        val violations = mutableListOf<String>()
        for (sourceRootPath in PRODUCTION_SOURCE_ROOTS) {
            val root = appModuleFile(sourceRootPath)
            for (file in kotlinFilesUnder(root)) {
                val relative = file.toRelativeString(root).replace('\\', '/')
                if (sourceRootPath == MAIN_SOURCE_ROOT && relative == OWNER_RELATIVE_PATH) continue
                val text = file.readText()
                if ("ZAPSTORE_PACKAGE" in text || "\"dev.zapstore.app\"" in text) {
                    violations += "$sourceRootPath/$relative references Zapstore package directly"
                }
                val mentionsStoreUrl = STORE_URL_MARKERS.any { marker -> marker in text }
                if (mentionsStoreUrl && "ACTION_VIEW" in text) {
                    violations += "$sourceRootPath/$relative builds an ACTION_VIEW intent for a store listing"
                }
            }
        }
        assertEquals("external store launches outside ExternalStoreLauncher", emptyList<String>(), violations)
    }

    /**
     * Returns the source text of an expression-bodied function from `=` through its balanced call or `.apply`
     * block, failing loudly if the declaration is missing or changes shape.
     */
    private fun String.expressionFunctionBody(functionName: String): String {
        val start =
            Regex("""\bfun\s+(?:\w+\.)?${Regex.escape(functionName)}\s*\(""")
                .find(this)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val paramsStart = indexOf('(', start)
        val params =
            kotlinBlockFrom(
                openDelimiter = paramsStart,
                description = "parameters for $functionName",
                opening = '(',
                closing = ')',
            )
        val headerEnd = paramsStart + params.length
        val equalsIndex = indexOf('=', headerEnd)
        require(equalsIndex >= 0 && '{' !in substring(headerEnd, equalsIndex)) {
            "Missing '=' body for $functionName"
        }
        val callStart = indexOf('(', equalsIndex)
        require(callStart >= 0) { "Missing call in $functionName" }
        val callArgs =
            kotlinBlockFrom(
                openDelimiter = callStart,
                description = "call arguments for $functionName",
                opening = '(',
                closing = ')',
            )
        val callEnd = callStart + callArgs.length
        val applyMatch = Regex("""^\s*\.apply\s*\{""").find(substring(callEnd))
        val bodyEnd =
            if (applyMatch != null) {
                val blockStart = callEnd + applyMatch.range.last
                val block = kotlinBlockFrom(openDelimiter = blockStart, description = "apply block for $functionName")
                blockStart + block.length
            } else {
                callEnd
            }
        return substring(equalsIndex, bodyEnd)
    }

    /** Returns all Kotlin source files under [root] in deterministic path order. */
    private fun kotlinFilesUnder(root: File): List<File> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

    private companion object {
        const val MAIN_SOURCE_ROOT = "src/main/java/dev/ipf/whitenoise/android"
        const val OWNER_RELATIVE_PATH = "core/ExternalStoreLauncher.kt"
        val PRODUCTION_SOURCE_ROOTS =
            listOf(
                MAIN_SOURCE_ROOT,
                "src/play/java/dev/ipf/whitenoise/android",
                "src/zapstore/java/dev/ipf/whitenoise/android",
            )
        val STORE_URL_MARKERS =
            listOf(
                "https://zapstore.dev/",
                "market://",
                "ZAPSTORE_LISTING_URL",
                "OSTT_ZAPSTORE_URL",
                "OFFLINE_TRANSLATOR_ZAPSTORE_URL",
            )
    }
}
