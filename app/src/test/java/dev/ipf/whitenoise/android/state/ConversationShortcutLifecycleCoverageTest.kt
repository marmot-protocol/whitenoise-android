package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins shortcut privacy cleanup at account lifecycle boundaries that require the real FFI to execute. */
class ConversationShortcutLifecycleCoverageTest {
    @Test
    fun accountSwitchClearsShortcutSurfacesBeforeChangingActiveAccount() {
        val body = appStateSection("suspend fun setActiveAccount", "private fun clearInMemoryMediaCaches")
        val clearIndex = body.indexOf("clearConversationShortcutSurfaces()")
        val switchIndex = body.indexOf("activeAccountRef = label")

        assertTrue(
            "account switches must clear prior-account conversation shortcuts before changing the active account",
            clearIndex >= 0 && switchIndex > clearIndex,
        )
    }

    @Test
    fun nonDestructiveSignOutClearsShortcutSurfaces() {
        val body = appStateSection("suspend fun signOutActiveAccount", "suspend fun exportActiveAccountNsec")

        assertTrue(
            "sign-out must clear dynamic and long-lived conversation shortcuts",
            "clearConversationShortcutSurfaces()" in body,
        )
    }

    @Test
    fun destructiveLastAccountWipeClearsShortcutSurfaces() {
        val body =
            appStateSection(
                "suspend fun signOutAndWipeActiveAccount",
                "suspend fun exportEncryptedSecretKeyBackup",
            )
        val clearIndex = body.indexOf("clearConversationShortcutSurfaces()")
        val wipeIndex = body.indexOf("signOutAndWipe(wipedRef)")

        assertTrue(
            "destructive wipe must clear shortcuts before removing the account, including the last account",
            clearIndex >= 0 && wipeIndex > clearIndex,
        )
    }

    private fun appStateSection(
        startMarker: String,
        endMarker: String,
    ): String {
        val source = appStateSource().readText()
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, startIndex = start + startMarker.length)
        check(start >= 0 && end > start) { "Missing AppState section $startMarker .. $endMarker" }
        return source.substring(start, end)
    }

    private fun appStateSource(): File {
        val candidates =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            )
        return candidates.firstOrNull(File::isFile) ?: error("Missing AppState.kt source file")
    }
}
