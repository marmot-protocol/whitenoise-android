package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the developer-mode push delivery debug integration (#1247): the binding
 * must be reached through [WhiteNoiseAppState.marmotIo] and surfaced on group
 * details with copyable fingerprints/hints — not left as dead FFI surface.
 */
class GroupPushDebugInfoCoverageTest {
    @Test
    fun conversationControllerLoadsGroupPushDebugInfoThroughMarmotIo() {
        val body = controllersSource().readText().functionBody("groupPushDebugInfo")

        assertTrue(
            "group push debug must load through marmotIo off the main thread",
            Regex(
                """runCatching\s*\{.*appState\.marmotIo\s*\{\s*groupPushDebugInfo\s*\(\s*account\s*,\s*group\.groupIdHex\s*\)\s*}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
    }

    @Test
    fun groupDetailsSurfacesPushDeliveryInDeveloperMode() {
        val source = groupDetailsScreenSource().readText()

        assertTrue(
            "group details must gate push delivery on developer mode",
            "appState.developerMode" in source && "R.string.push_delivery" in source,
        )
        assertTrue(
            "group details must render token fingerprints for diagnosis",
            "tokenFingerprint" in source && "DiagnosticRow" in source,
        )
        assertTrue(
            "group details must load push debug through the controller",
            "groupPushDebugInfo()" in source,
        )
    }

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).first { it.exists() }

    private fun groupDetailsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
        ).first { it.exists() }

    private fun String.functionBody(functionName: String): String {
        val start = indexOf("fun $functionName")
        check(start >= 0) { "function $functionName not found" }
        val braceStart = indexOf('{', start)
        var depth = 0
        for (index in braceStart until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(start, index + 1)
                }
            }
        }
        error("unterminated body for $functionName")
    }
}
