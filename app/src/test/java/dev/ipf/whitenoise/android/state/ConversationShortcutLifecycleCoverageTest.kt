package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins shortcut privacy cleanup at account lifecycle boundaries that require the real FFI to execute. */
class ConversationShortcutLifecycleCoverageTest {
    @Test
    fun accountSwitchHidesDirectShareBeforeChangingActiveAccount() {
        val body = appStateSection("suspend fun setActiveAccount", "private fun clearInMemoryMediaCaches")
        val clearIndex = body.indexOf("hideConversationShortcutsFromDirectShare()")
        val switchIndex = body.indexOf("activeAccountRef = label")

        assertTrue(
            "account switches must hide prior-account Direct Share targets before changing the active account",
            clearIndex >= 0 && switchIndex > clearIndex,
        )
    }

    @Test
    fun nonDestructiveSignOutClearsOnlyTheSignedOutAccountsShortcutSurfaces() {
        val body = appStateSection("suspend fun signOutActiveAccount", "suspend fun exportActiveAccountNsec")

        assertTrue(
            "sign-out must clear dynamic and long-lived shortcuts for the signed-out account",
            "accountRef = account" in body && "includeUnscopedLegacy = accounts.none" in body,
        )
    }

    @Test
    fun destructiveLastAccountWipeClearsOnlyTheWipedAccountsShortcutSurfaces() {
        val body =
            appStateSection(
                "suspend fun signOutAndWipeActiveAccount",
                "suspend fun exportEncryptedSecretKeyBackup",
            )
        val wipeIndex = body.indexOf("signOutAndWipe(wipedRef)")
        val completedCleanupIndex = body.indexOf("if (!outcome.localCleanup.completed)")
        val clearIndex = body.indexOf("accountRef = wipedRef")

        assertTrue(
            "destructive wipe must clear only the wiped account's shortcuts after cleanup completes",
            wipeIndex >= 0 && completedCleanupIndex > wipeIndex && clearIndex > completedCleanupIndex,
        )
    }

    /** Causal facade/coordinator tests execute these entrypoints; the registered gate prevents bypassing them. */
    @Test
    fun lifetimeAndNativeFalseRemainAtTheActualOwnershipMutationBoundaries() {
        val source = appStateSource().readText()
        val bootstrap =
            appStateSection(
                "private suspend fun startBootstrapRuntime()",
                "private suspend fun startBootstrapRuntimeLocked",
            )
        assertTrue("bootstrap open/start/failure close must share lifetime", "withAccountLifetime" in bootstrap)
        val signOut =
            appStateSection(
                "private suspend fun admittedNativeSignOutLocked",
                "private suspend fun finishOwnedSignOutLocked",
            )
        val falseCall = signOut.indexOf("setNativePushEnabled(account, false)")
        val nativeCall = signOut.indexOf("runtime.marmot.signOut(account")
        assertTrue("native false must precede native signout", falseCall >= 0 && nativeCall > falseCall)
        val route = appStateSection("private suspend fun routePendingAccountSetup", "suspend fun setActiveAccount")
        assertTrue(
            "pending route null publication must retain original selection identity",
            "accountSwitchHandoff.isCurrent(selectionGeneration)" in route,
        )
        assertTrue(
            "explicit settings must durably supersede cleanup before positive write",
            "pushTokenStore.clearPendingDisable(account)" in source && "setNativePushEnabledLocked" in source,
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
