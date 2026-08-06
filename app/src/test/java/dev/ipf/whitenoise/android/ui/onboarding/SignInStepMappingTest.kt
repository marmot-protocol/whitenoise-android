package dev.ipf.whitenoise.android.ui.onboarding

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.IdentityImportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SignInStepMappingTest {
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
            recoveryStepFor(IdentityImportOutcome.SetupRecoveryRequired, nsec),
        )
    }

    @Test
    fun recoveryKeepsTheOtherStatesAsTheyAre() {
        assertEquals(SignInStep.SignedIn, recoveryStepFor(IdentityImportOutcome.Success, nsec))
        assertEquals(
            SignInStep.InlineError(R.string.sign_in_error_setup_retry),
            recoveryStepFor(IdentityImportOutcome.SetupRetryRequired, nsec),
        )
    }

    private fun inlineErrorRes(outcome: IdentityImportOutcome): Int {
        val step = signInStepFor(outcome, nsec)
        return (step as? SignInStep.InlineError)?.messageRes
            ?: error("expected an inline message for $outcome, got $step")
    }
}
