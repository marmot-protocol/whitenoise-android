package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WarmResumeAppLockPrivacyTest {
    @Test
    fun lockedBranchComposesNoProtectedShell() {
        val source = whiteNoiseAppSource()
        val lockedBranch =
            source
                .substringAfter("if (appState.appLockScreenVisible) {")
                .substringBefore("                    } else {")

        assertTrue(lockedBranch.contains("AppLockScreen("))
        assertFalse(lockedBranch.contains("MainShell("))
        assertFalse(lockedBranch.contains("ForwardOperationStatusHost("))
        assertFalse(lockedBranch.contains("WipeOutcomeSheet("))
    }

    private fun whiteNoiseAppSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing WhiteNoiseApp.kt")
}
