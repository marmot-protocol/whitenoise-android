package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccountSetupRecoveryTest {
    @Test
    fun eachAccountSetupStateGetsItsOwnOutcome() {
        val mapped =
            listOf(
                MarmotKitException.AccountSetupRetryRequired(),
                MarmotKitException.AccountSetupKeyPackageRecoveryAvailable(),
                MarmotKitException.AccountSetupResetNotApplicable(),
                MarmotKitException.AccountSetupRecoveryRequired(),
            ).map(::identityImportOutcome)

        assertEquals(
            listOf(
                IdentityImportOutcome.SetupRetryRequired,
                IdentityImportOutcome.SetupKeyPackageRecoveryAvailable,
                IdentityImportOutcome.SetupResetNotApplicable,
                IdentityImportOutcome.SetupRecoveryRequired,
            ),
            mapped,
        )
        assertEquals("account-setup states must not collapse onto one outcome", 4, mapped.toSet().size)
    }

    @Test
    fun untypedEngineFailuresStayGeneric() {
        assertEquals(
            IdentityImportOutcome.Failed,
            identityImportOutcome(MarmotKitException.Runtime("boom")),
        )
        assertEquals(IdentityImportOutcome.Failed, identityImportOutcome(IllegalStateException("boom")))
    }

    @Test
    fun recoverySuccessActivatesTheAccountExactlyLikeAnOrdinaryImport() {
        val source = appStateSource()

        assertTrue(source.functionBody("recoverIncompleteIdentitySetup").contains("activateImportedIdentity(summary)"))
        assertTrue(source.functionBody("importIdentity").contains("activateImportedIdentity(summary)"))

        val activation = source.functionBody("activateImportedIdentity")
        listOf(
            "refreshAccounts()",
            "setActiveAccount(summary.label)",
            "phase = AppPhase.Ready",
            "warmProfile(summary.accountIdHex)",
        ).forEach { assertTrue("activation must keep $it", activation.contains(it)) }
    }

    @Test
    fun externalSignerLoginIsUntouchedByRecovery() {
        val body = appStateSource().functionBody("loginWithAmber")

        assertTrue(body.contains("loginExternalSigner("))
        assertFalse(body.contains("loginRecoveringIncompleteSetup"))
        assertFalse(body.contains("acknowledgePossibleKeyPackageOrphan"))
    }

    @Test
    fun standaloneResetApiIsNotWired() {
        val offenders =
            appSources()
                .filter { it.readText().contains("resetIncompleteAccountSetup") }
                .map { it.path }

        assertTrue(
            "the one-call recovery covers this flow, so the reset API stays unwired: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun appStateSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing AppState.kt source file")

    // The vendored engine bindings declare the reset API, so only first-party
    // sources are inspected for call sites.
    private fun appSources(): List<File> =
        listOf(File("src/main/java/dev/ipf/whitenoise/android"), File("app/src/main/java/dev/ipf/whitenoise/android"))
            .first { it.exists() }
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
}
