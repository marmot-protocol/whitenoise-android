package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the runtime-reconciliation barrier required after a fresh Amber login. */
class AmberLoginReconciliationCoverageTest {
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

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing AppState.kt")
}
