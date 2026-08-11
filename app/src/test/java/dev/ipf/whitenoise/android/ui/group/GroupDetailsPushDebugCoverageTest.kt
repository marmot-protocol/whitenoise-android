package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GroupDetailsPushDebugCoverageTest {
    @Test
    fun developerModeLoadsGroupPushDiagnosticsThroughController() {
        val screen = groupDetailsSource().readText()
        val controller = controllersSource().readText()

        assertTrue(
            "group details must load push diagnostics only from the developer-mode refresh path",
            "suspend fun refreshPushDebugInfo()" in screen &&
                "if (!appState.developerMode) return" in screen &&
                "pushDebugInfo = controller.groupPushDebugInfo()" in screen,
        )
        assertTrue(
            "the controller must request per-group push diagnostics from marmot using the active conversation account",
            Regex(
                """suspend\s+fun\s+groupPushDebugInfo\s*\(\s*\).*""" +
                    """val\s+account\s*=\s*conversationAccountRef\s*\?:\s*return\s+null.*""" +
                    """appState\.marmotIo\s*\{\s*groupPushDebugInfo\s*\(\s*account\s*,\s*group\.groupIdHex\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(controller),
        )
        assertTrue(
            "push debug load must structurally rethrow cancellation like the neighboring MLS loader",
            "runCatchingCancellable {" in controller.functionBody("groupPushDebugInfo"),
        )
        assertTrue(
            "push debug load failures should surface a copyable diagnostic toast",
            "R.string.toast_couldnt_load_push_debug_info" in controller.functionBody("groupPushDebugInfo") &&
                "copyable = true" in controller.functionBody("groupPushDebugInfo"),
        )
    }

    @Test
    fun pushDebugSectionRendersOnlyInsideDeveloperModeBlock() {
        val screen = groupDetailsSource().readText()
        val pushSectionCall = screen.indexOf("PushDeliveryDebugSection(")
        check(pushSectionCall >= 0) { "Missing PushDeliveryDebugSection call" }
        val developerModeStart = screen.lastIndexOf("if (appState.developerMode) {", pushSectionCall)
        check(developerModeStart >= 0) { "Missing developer-mode block around PushDeliveryDebugSection" }
        val developerModeBrace = screen.indexOf('{', developerModeStart)
        val developerModeBody =
            screen.kotlinBlockFrom(
                developerModeBrace,
                "GroupDetailsScreen developer-mode block",
            )

        assertTrue(
            "push delivery diagnostics must only render from inside developer mode",
            "PushDeliveryDebugSection(" in developerModeBody &&
                "R.string.push_delivery" in screen.functionBody("PushDeliveryDebugSection"),
        )
    }

    @Test
    fun pushDebugSectionSurfacesTokenHealthAndCopyableIdentifiers() {
        val screen = groupDetailsSource().readText()
        val sectionBody = screen.functionBody("PushDeliveryDebugSection")
        val tokenRowsBody = screen.functionBody("PushTokenDebugRows")

        assertTrue(
            "developer diagnostics should include token freshness and relay-hint counters",
            "R.string.push_debug_missing_relay_hints" in sectionBody &&
                "R.string.push_debug_stale_tokens" in sectionBody &&
                "R.string.push_debug_last_token_list_update" in sectionBody,
        )
        assertTrue(
            "token rows should expose copyable token fingerprints",
            "copyValue = token.tokenFingerprint" in tokenRowsBody,
        )
        assertTrue(
            "token rows should show local registration and per-token delivery state",
            "R.string.push_debug_local_notifications_enabled" in sectionBody &&
                "R.string.native_push" in sectionBody &&
                "R.string.push_debug_relay_hint" in tokenRowsBody &&
                "R.string.push_debug_member_matches_active_leaf" in tokenRowsBody,
        )
        assertTrue(
            "member token fingerprints must display abbreviated values while copying the full fingerprint",
            Regex(
                """R\.string\.push_debug_token_fingerprint.*IdentityFormatter\.short\(token\.tokenFingerprint\).*copyValue\s*=\s*token\.tokenFingerprint""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(tokenRowsBody),
        )
        assertTrue(
            "member diagnostics must render relay-hint presence",
            Regex(
                """R\.string\.push_debug_relay_hint.*token\.hasRelayHint""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(tokenRowsBody),
        )
    }

    private fun groupDetailsSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing GroupDetailsScreen.kt source file")

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")
}
