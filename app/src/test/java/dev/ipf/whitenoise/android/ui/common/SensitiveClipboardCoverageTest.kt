package dev.ipf.whitenoise.android.ui.common

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant gate for #3805: a secret copied to the clipboard is removed again on a deadline. Only
 * [copySensitiveToClipboard] may put a clip marked sensitive on the clipboard, and it schedules the clear.
 */
class SensitiveClipboardCoverageTest {
    /** No other production function builds a sensitive clip and writes it to the clipboard itself. */
    @Test
    fun onlyTheTimedHelperWritesSensitiveClips() {
        val root = appModuleFile("src/main/java")
        val writers =
            root
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { file -> file.readText().let { "EXTRA_IS_SENSITIVE" in it && "setPrimaryClip" in it } }
                .flatMap { file ->
                    val source = file.readText()
                    blockFunctions(source)
                        .filter { (_, body) -> "EXTRA_IS_SENSITIVE" in body && "setPrimaryClip" in body }
                        .map { (name, _) -> "${file.relativeTo(root).invariantSeparatorsPath}#$name" }
                }.toList()
        assertEquals(listOf(HELPER), writers)
    }

    /** The helper replaces any pending clear with durable work on the shared lifetime. */
    @Test
    fun helperSchedulesTheClear() {
        val body = appModuleSource(CLIPBOARD).functionBody("copySensitiveToClipboard")
        assertTrue("EXTRA_IS_SENSITIVE" in body)
        assertTrue("setInitialDelay(SENSITIVE_CLIP_LIFETIME_MS" in body)
        assertTrue("enqueueUniqueWork(SENSITIVE_CLIP_CLEAR_WORK, ExistingWorkPolicy.REPLACE" in body)
    }

    /** Both Profile Keys secrets go through the helper; the public key stays an ordinary copy. */
    @Test
    fun profileKeysSecretsUseTheTimedHelper() {
        val screen = appModuleSource(ACCOUNT_KEYS)
        assertTrue("copySensitiveToClipboard(context, privateKeyLabel, " in screen)
        assertTrue("copySensitiveToClipboard(context, encryptedBackupLabel, " in screen)
        assertTrue("copyToClipboard(context, publicKeyLabel, " in screen)
        assertTrue("EXTRA_IS_SENSITIVE" !in screen.functionBody("copyToClipboard"))
    }

    /** Pairs each block-bodied function name with its body; expression bodies cannot hold two statements. */
    private fun blockFunctions(source: String): Sequence<Pair<String, String>> =
        FUNCTION.findAll(source).mapNotNull { match ->
            val parametersStart = match.range.last
            val parameters =
                source.kotlinBlockFrom(parametersStart, "parameters of ${match.groupValues[1]}", '(', ')')
            val afterParameters = parametersStart + parameters.length
            val bodyStart = source.indexOf('{', afterParameters)
            if (bodyStart < 0 || '=' in source.substring(afterParameters, bodyStart)) return@mapNotNull null
            match.groupValues[1] to source.kotlinBlockFrom(bodyStart, "function ${match.groupValues[1]}")
        }

    private companion object {
        val FUNCTION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w<>?,*. ]+\.)?(\w+)\s*\(""")
        const val CLIPBOARD = "src/main/java/dev/ipf/whitenoise/android/ui/common/Clipboard.kt"
        const val ACCOUNT_KEYS = "src/main/java/dev/ipf/whitenoise/android/ui/settings/AccountKeysScreen.kt"
        const val HELPER = "dev/ipf/whitenoise/android/ui/common/Clipboard.kt#copySensitiveToClipboard"
    }
}
