package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
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
        val refreshBody = source.functionBody("refreshMlsDetails")
        val developerModeStart = source.indexOf("if (appState.developerMode) {")
        check(developerModeStart >= 0) { "Missing GroupDetailsScreen developer-mode block" }
        val developerModeBrace = source.indexOf('{', developerModeStart)
        check(developerModeBrace >= 0) { "Missing body for GroupDetailsScreen developer-mode block" }
        val developerModeBody =
            source.kotlinBlockFrom(
                developerModeBrace,
                "GroupDetailsScreen developer-mode block",
            )
        val tokenDetailsBody = source.functionBody("GroupPushTokenDebugDetails")

        assertTrue(
            "group details must gate push delivery on developer mode",
            "R.string.push_delivery" in developerModeBody,
        )
        assertTrue(
            "push debug refresh must remain developer-only",
            "if (!appState.developerMode) return" in refreshBody,
        )
        assertTrue(
            "group details must load push debug through the controller",
            "groupPushDebugInfo()" in refreshBody,
        )
        assertTrue(
            "member token fingerprints must display abbreviated values while copying the full fingerprint",
            Regex(
                """R\.string\.push_debug_token_fingerprint.*IdentityFormatter\.short\(token\.tokenFingerprint\).*copyValue\s*=\s*token\.tokenFingerprint""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(tokenDetailsBody),
        )
        assertTrue(
            "member diagnostics must render relay-hint presence",
            Regex(
                """R\.string\.push_debug_relay_hint.*token\.hasRelayHint""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(tokenDetailsBody),
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
}
