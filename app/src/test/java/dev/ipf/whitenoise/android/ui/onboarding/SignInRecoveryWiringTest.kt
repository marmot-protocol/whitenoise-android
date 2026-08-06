package dev.ipf.whitenoise.android.ui.onboarding

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SignInRecoveryWiringTest {
    @Test
    fun recoveryIsReachableOnlyFromTheConfirmCallback() {
        val body = onboardingScreenSource().functionBody("OnboardingScreen")
        val confirm = body.indexOf("onRecoveryConsentConfirm = {")
        val dismiss = body.indexOf("onRecoveryConsentDismiss = {")
        val call = body.indexOf("recoverIncompleteIdentitySetup(")

        assertEquals(
            "recovery must have exactly one call site",
            1,
            Regex("recoverIncompleteIdentitySetup\\(").findAll(body).count(),
        )
        assertTrue("the confirm and dismiss callbacks must both be wired", confirm >= 0 && dismiss > confirm)
        assertTrue("recovery must live inside the confirm callback", call in (confirm + 1) until dismiss)
    }

    @Test
    fun theSignInAttemptItselfNeverRecovers() {
        val body = onboardingScreenSource().functionBody("OnboardingScreen")
        val import = body.indexOf("appState.importIdentity(")
        val confirm = body.indexOf("onRecoveryConsentConfirm = {")

        assertTrue(import >= 0 && confirm > import)
        assertTrue(
            "the import branch must only ask for consent",
            body.contains("SignInStep.AskRecoveryConsent -> recoveryConsentVisible = true"),
        )
    }

    private fun onboardingScreenSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/onboarding/OnboardingScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/onboarding/OnboardingScreen.kt"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing OnboardingScreen.kt source file")
}
