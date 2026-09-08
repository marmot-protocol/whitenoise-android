package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.whitenoise.android.core.IdentityEntryInput

/** Whether [WhiteNoiseAppState.importIdentity] may call the engine — direct import is nsec-only. */
internal fun permitsDirectIdentityImport(trimmed: String): Boolean {
    val kind = IdentityEntryInput.classify(trimmed)
    return kind == IdentityEntryInput.Kind.SecretKey
}

/**
 * How a direct nsec sign-in ended. The engine's account-setup states are kept
 * apart because each one calls for a different thing from the user: two are
 * resumable by signing in again, one says the account was never in the state
 * recovery applies to, and one needs explicit consent before anything rotates.
 */
internal sealed interface IdentityImportOutcome {
    /** The identity reached ordinary account activation. */
    data object Success : IdentityImportOutcome

    /** Identity accepted; explicit setup routing replaces immediate activation. */
    data object SetupStarted : IdentityImportOutcome

    /** Input the engine was never asked about, or a failure with no typed meaning. */
    data object Failed : IdentityImportOutcome

    /** Durable account setup can be resumed by retrying the same sign-in. */
    data object SetupRetryRequired : IdentityImportOutcome

    /** A recoverable KeyPackage setup state exists, so retry rather than reset. */
    data object SetupKeyPackageRecoveryAvailable : IdentityImportOutcome

    /** The account was not in the incomplete-setup state the reset applies to. */
    data object SetupResetNotApplicable : IdentityImportOutcome

    /**
     * Local evidence cannot prove a previously signed KeyPackage was never
     * exposed, so the engine forbids rotation until the host passes an explicit
     * acknowledgement.
     */
    data object SetupRecoveryRequired : IdentityImportOutcome
}

/**
 * The two engine login entry points behind a direct nsec sign-in. Injectable so
 * a test can count them: which binding a sign-in reaches is the consent
 * guarantee, and a source-text guard cannot see a bypass routed through some
 * other wrapper.
 */
internal interface IdentityLoginCalls {
    /** Tests may supply the identity-only result before exercising legacy recovery. */
    suspend fun beginOnboarding(nsec: String): OnboardingSnapshotFfi? = null

    /** Runs legacy login only after staged setup declines ownership of the account. */
    suspend fun login(
        nsec: String,
        relays: List<String>,
        keyPackageRelays: List<String>,
    ): AccountSummaryFfi

    /** Retries legacy setup recovery with the caller’s explicit orphan acknowledgment. */
    suspend fun loginRecoveringIncompleteSetup(
        nsec: String,
        relays: List<String>,
        keyPackageRelays: List<String>,
        acknowledgePossibleKeyPackageOrphan: Boolean,
    ): AccountSummaryFfi
}

/** Keeps recovery-requiring engine failures distinct from ordinary sign-in errors. */
internal fun identityImportOutcome(error: Throwable): IdentityImportOutcome =
    when (error) {
        is MarmotKitException.AccountSetupRetryRequired -> IdentityImportOutcome.SetupRetryRequired
        is MarmotKitException.AccountSetupKeyPackageRecoveryAvailable ->
            IdentityImportOutcome.SetupKeyPackageRecoveryAvailable
        is MarmotKitException.AccountSetupResetNotApplicable -> IdentityImportOutcome.SetupResetNotApplicable
        is MarmotKitException.AccountSetupRecoveryRequired -> IdentityImportOutcome.SetupRecoveryRequired
        else -> IdentityImportOutcome.Failed
    }
