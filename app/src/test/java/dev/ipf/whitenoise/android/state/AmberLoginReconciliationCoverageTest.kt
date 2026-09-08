package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the runtime-reconciliation barrier required after a fresh Amber login. */
class AmberLoginReconciliationCoverageTest {
    /** Accepted interactive checkpoints return to setup before either legacy login or account activation. */
    @Test
    fun interactiveAmberSetupTakesOwnershipBeforeLegacyActivation() {
        val source = appStateSource().readText()
        val start = source.indexOf("suspend fun loginWithAmber()")
        val end = source.indexOf("private suspend fun reregisterExternalSigners()", start)
        val body = source.substring(start, end)
        val begin = body.indexOf("beginExternalSignerOnboarding(")
        val mount = body.indexOf("accountSetup.open(snapshot)", begin)
        val exit = body.indexOf("return", mount)
        val legacy = body.indexOf("loginExternalSigner(", exit)
        assertTrue("interactive setup must be attempted first", begin >= 0 && mount > begin)
        assertTrue("accepted setup must return before legacy login", exit > mount && legacy > exit)
        assertTrue(
            "only MDK's explicit legacy refusal allows fallback",
            body.substring(begin, mount).contains("catch (_: MarmotKitException.OnboardingActionUnavailable)"),
        )
    }

    @Test
    fun externalSignerIsReconciledBeforeTheAccountIsExposed() {
        val source = appStateSource().readText()
        val loginStart = source.indexOf("suspend fun loginWithAmber()")
        val nextFunction = source.indexOf("private suspend fun reregisterExternalSigners()", loginStart)
        require(loginStart >= 0 && nextFunction > loginStart) { "Missing Amber login function" }
        val body = source.substring(loginStart, nextFunction)

        val loginIndex = body.indexOf("loginExternalSigner(")
        val reconcileIndex = body.indexOf("registerExternalSigner(", loginIndex + 1)
        val refreshIndex = body.indexOf("refreshAccounts()", reconcileIndex + 1)
        val activateIndex = body.indexOf("setActiveAccount(summary.label)", refreshIndex + 1)

        assertTrue("Amber login must create the external-signer account", loginIndex >= 0)
        assertTrue(
            "the stable signer must be re-registered after login and before account refresh",
            reconcileIndex > loginIndex && refreshIndex > reconcileIndex,
        )
        assertTrue(
            "the reconciled account must not become active before refresh completes",
            activateIndex > refreshIndex,
        )
        assertTrue(
            "the reconciliation must target the new account with its canonical signer key",
            Regex(
                """registerExternalSigner\s*\(\s*summary\.label\s*,\s*amberSigner\.buildSigner\(pubkeyHex\)""",
            ).containsMatchIn(body),
        )
    }

    /** Production nsec imports must not turn arbitrary onboarding failures into legacy login attempts. */
    @Test
    fun productionNsecFallbackCatchesOnlyExplicitLegacyRefusal() {
        val source = appStateSource().readText()
        val start = source.indexOf("private suspend fun beginIdentitySetup(")
        val end = source.indexOf("private suspend fun engineLogin(", start)
        require(start >= 0 && end > start) { "Missing nsec setup boundary" }
        val body = source.substring(start, end)
        val production = body.substring(body.indexOf("return try {"))
        assertTrue(production.contains("accountSetup.begin(nsec)"))
        val caughtTypes =
            Regex("catch\\s*\\([^:]+:\\s*([^)]*)\\)")
                .findAll(production)
                .map { it.groupValues[1].trim() }
                .toList()
        assertEquals(listOf("MarmotKitException.OnboardingActionUnavailable"), caughtTypes)
        val fallback = production.substringAfter("catch (_: MarmotKitException.OnboardingActionUnavailable)")
        assertTrue("explicit legacy refusal must leave setup for the old login path", fallback.contains("null"))
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing AppState.kt")
}
