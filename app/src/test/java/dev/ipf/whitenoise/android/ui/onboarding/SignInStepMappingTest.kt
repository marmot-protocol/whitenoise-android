package dev.ipf.whitenoise.android.ui.onboarding

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.IdentityImportOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SignInStepMappingTest {
    @Test
    fun acceptedIdentityHasADistinctSetupRoute() {
        assertEquals(SignInStep.SetupStarted, signInStepFor(IdentityImportOutcome.SetupStarted, ""))
    }

    private val nsec = "nsec1" + "q".repeat(58)
    private val npub = "npub1" + "a".repeat(58)

    @Test
    fun eachAccountSetupStateGetsItsOwnMessage() {
        val messages =
            listOf(
                IdentityImportOutcome.SetupRetryRequired,
                IdentityImportOutcome.SetupKeyPackageRecoveryAvailable,
                IdentityImportOutcome.SetupResetNotApplicable,
                IdentityImportOutcome.Failed,
            ).map { inlineErrorRes(it) }

        assertEquals(
            listOf(
                R.string.sign_in_error_setup_retry,
                R.string.sign_in_error_setup_key_package_retry,
                R.string.sign_in_error_setup_unexpected_state,
                R.string.identity_entry_error_import_failed,
            ),
            messages,
        )
        assertEquals("account-setup states must not share a message", 4, messages.toSet().size)
    }

    /** A correct key for a profile this device was unlinked from says so, on sign-in and on recovery alike. */
    @Test
    fun anUnlinkedProfileIsNotReportedAsAWrongKey() {
        val unlinked = IdentityImportOutcome.Unlinked("work")
        assertEquals(SignInStep.AskRemovalConsent("work"), signInStepFor(unlinked, "entered-key"))
        assertEquals(
            SignInStep.InlineError(R.string.error_account_unlinked),
            recoveryStepFor(unlinked),
        )
    }

    @Test
    fun resumableStatesReadAsRetryableAndNotAsTheGuardMessage() {
        val retry = inlineErrorRes(IdentityImportOutcome.SetupRetryRequired)
        val keyPackage = inlineErrorRes(IdentityImportOutcome.SetupKeyPackageRecoveryAvailable)

        assertNotEquals(R.string.sign_in_error_setup_unexpected_state, retry)
        assertNotEquals(R.string.sign_in_error_setup_unexpected_state, keyPackage)
        assertNotEquals(retry, keyPackage)
    }

    @Test
    fun guardStateReadsAsUnexpectedStateNotAsSetupFailure() {
        val guard = inlineErrorRes(IdentityImportOutcome.SetupResetNotApplicable)

        assertEquals(R.string.sign_in_error_setup_unexpected_state, guard)
        assertNotEquals(R.string.identity_entry_error_import_failed, guard)
        assertNotEquals(R.string.sign_in_error_setup_retry, guard)
    }

    @Test
    fun recoveryRequiredAsksForConsentInsteadOfShowingAMessage() {
        assertEquals(
            SignInStep.AskRecoveryConsent,
            signInStepFor(IdentityImportOutcome.SetupRecoveryRequired, nsec),
        )
    }

    @Test
    fun successSignsIn() {
        assertEquals(SignInStep.SignedIn, signInStepFor(IdentityImportOutcome.Success, nsec))
    }

    @Test
    fun ordinaryFailuresKeepTheirExistingMessages() {
        assertEquals(
            SignInStep.InlineError(R.string.identity_entry_error_import_failed),
            signInStepFor(IdentityImportOutcome.Failed, nsec),
        )
        assertEquals(
            SignInStep.InlineError(R.string.sign_in_error_public_key),
            signInStepFor(IdentityImportOutcome.Failed, npub),
        )
        assertEquals(
            SignInStep.InlineError(R.string.identity_entry_error_invalid_key),
            signInStepFor(IdentityImportOutcome.Failed, "not-a-key"),
        )
    }

    @Test
    fun anAcknowledgedRecoveryNeverAsksForConsentAgain() {
        assertEquals(
            SignInStep.InlineError(R.string.sign_in_error_setup_recovery_failed),
            recoveryStepFor(IdentityImportOutcome.SetupRecoveryRequired),
        )
    }

    @Test
    fun aSignInAfterAFailedRecoveryForTheSameKeyReportsItInsteadOfRePrompting() {
        assertEquals(
            SignInStep.InlineError(R.string.sign_in_error_setup_recovery_failed),
            signInStepFor(IdentityImportOutcome.SetupRecoveryRequired, nsec, recoveryConsentedFor = nsec),
        )
    }

    @Test
    fun anotherKeyStillGetsItsOwnConsentPrompt() {
        val otherNsec = "nsec1" + "p".repeat(58)

        assertEquals(
            SignInStep.AskRecoveryConsent,
            signInStepFor(IdentityImportOutcome.SetupRecoveryRequired, otherNsec, recoveryConsentedFor = nsec),
        )
    }

    @Test
    fun aFailedRecoveryReadsAsRecoveryFailureNotAsABadKey() {
        assertEquals(
            SignInStep.InlineError(R.string.sign_in_error_setup_recovery_failed),
            recoveryStepFor(IdentityImportOutcome.Failed),
        )
    }

    @Test
    fun anUnexpectedStateAfterRecoveryDoesNotReuseTheUntouchedAccountMessage() {
        val afterRecovery = recoveryStepFor(IdentityImportOutcome.SetupResetNotApplicable)

        assertEquals(SignInStep.InlineError(R.string.sign_in_error_setup_recovery_unexpected_state), afterRecovery)
        assertNotEquals(
            signInStepFor(IdentityImportOutcome.SetupResetNotApplicable, nsec),
            afterRecovery,
        )
    }

    @Test
    fun recoveryKeepsTheOtherStatesAsTheyAre() {
        assertEquals(SignInStep.SignedIn, recoveryStepFor(IdentityImportOutcome.Success))
        assertEquals(
            SignInStep.InlineError(R.string.sign_in_error_setup_retry),
            recoveryStepFor(IdentityImportOutcome.SetupRetryRequired),
        )
        assertEquals(
            SignInStep.InlineError(R.string.sign_in_error_setup_key_package_retry),
            recoveryStepFor(IdentityImportOutcome.SetupKeyPackageRecoveryAvailable),
        )
    }

    /** A failed removal never starts sign-in; completing it retries the original key exactly once. */
    @Test
    fun removingTheConfirmedProfileControlsTheRetry() =
        runTest {
            val calls = mutableListOf<String>()
            val retry: suspend (String) -> IdentityImportOutcome = {
                assertEquals(nsec, it)
                calls += "import"
                IdentityImportOutcome.SetupStarted
            }
            val refused =
                signInAfterRemovalConsent("old", nsec, {
                    calls += it
                    false
                }, retry)
            assertEquals(SignInStep.InlineError(R.string.unlinked_account_remove_failed), refused)
            assertEquals(listOf("old"), calls)
            val accepted =
                signInAfterRemovalConsent("old", nsec, {
                    calls += it
                    true
                }, retry)
            assertEquals(SignInStep.SetupStarted, accepted)
            assertEquals(listOf("old", "old", "import"), calls)
        }

    private fun inlineErrorRes(outcome: IdentityImportOutcome): Int {
        val step = signInStepFor(outcome, nsec)
        return (step as? SignInStep.InlineError)?.messageRes
            ?: error("expected an inline message for $outcome, got $step")
    }
}
