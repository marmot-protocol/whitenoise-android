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

    /** Ordinary imports and setup recovery share the same receipt-fenced completion path. */
    @Test
    fun recoverySuccessActivatesTheAccountExactlyLikeAnOrdinaryImport() {
        val source = appStateSource()

        assertTrue(
            source
                .functionBody("recoverIncompleteIdentitySetup")
                .contains("activateImportedIdentity(summary, originRuntime, originEpoch, originSelection)"),
        )
        assertTrue(
            source
                .functionBody("importIdentity")
                .contains("activateImportedIdentity(summary, originRuntime, originEpoch, originSelection)"),
        )

        val activation = source.functionBody("activateImportedIdentity")
        listOf(
            "refreshAccounts()",
            "AccountSessionActivationRequest(summary.label, shouldActivate = ownsRuntime)",
            "phase = AppPhase.Ready",
            "warmProfile(summary.accountIdHex)",
        ).forEach { assertTrue("activation must keep $it", activation.contains(it)) }
    }

    /** Amber's legacy fallback logs in through the external-signer API and never reaches setup recovery. */
    @Test
    fun externalSignerLoginIsUntouchedByRecovery() {
        val source = appStateSource()
        val login = source.functionBody("loginWithAmber")
        val fallback = source.functionBody("loginAmberFallback")

        assertTrue(login.contains("loginAmberFallback("))
        assertTrue(fallback.contains("loginExternalSigner("))
        listOf(login, fallback).forEach { body ->
            assertFalse(body.contains("loginRecoveringIncompleteSetup"))
            assertFalse(body.contains("acknowledgePossibleKeyPackageOrphan"))
        }
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

    // The pinned engine bindings declare the reset API, so only first-party
    // sources are inspected for call sites.
    private fun appSources(): List<File> =
        listOf(File("src/main/java/dev/ipf/whitenoise/android"), File("app/src/main/java/dev/ipf/whitenoise/android"))
            .first { it.exists() }
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
}
