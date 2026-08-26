package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi

internal data class SignOutOutcome(
    val nextActiveRef: String?,
    val phase: AppPhase,
)

/**
 * The active-account ref and app phase after signing [activeRef] out. Sign-out
 * is a non-destructive session switch, so if another account remains we switch
 * to it and stay [AppPhase.Ready]; if the signed-out account was the last
 * active one, drop to [AppPhase.Onboarding] rather than leaving a MainShell
 * rendered with no active account.
 */
internal fun signOutOutcome(
    accountLabels: List<String>,
    activeRef: String?,
): SignOutOutcome {
    val next = accountLabels.firstOrNull { it != activeRef }
    return SignOutOutcome(next, if (next == null) AppPhase.Onboarding else AppPhase.Ready)
}

/**
 * Persisted account entries that can sign through either local key material or
 * an external signer such as Amber. This is identity/signing-method inventory,
 * not a liveness or signer-reachability check: a non-running external signer is
 * still a signed-in signing account for account switchers, background sweeps,
 * and notification/account-count projections.
 */
internal fun AccountSummaryFfi.isSignedInSigningAccount(): Boolean =
    !signedOut &&
        label.isNotBlank() &&
        (localSigning || externalSigning)
