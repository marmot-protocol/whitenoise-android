package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SignOutOutcome(
    val nextActiveRef: String?,
    val phase: AppPhase,
)

/** Rethrows cancellation after Android cleanup, preserving the native cancellation as the primary cause. */
internal suspend fun rethrowSignOutCancellation(
    native: CancellationException?,
    boundary: CancellationException?,
) {
    val cancellation = native ?: boundary
    if (cancellation != null) throw cancellation
    currentCoroutineContext().ensureActive()
}

/**
 * The active-account ref and app phase after signing [activeRef] out. Sign-out
 * is a non-destructive session switch, so if another signed-in signing account
 * remains we switch to it and stay [AppPhase.Ready]; otherwise, drop to
 * [AppPhase.Onboarding] rather than leaving a MainShell rendered with no active
 * account or reactivating a retained signed-out identity.
 */
internal fun signOutOutcome(
    accounts: List<AccountSummaryFfi>,
    activeRef: String?,
): SignOutOutcome {
    val next = accounts.firstOrNull { it.label != activeRef && it.isSignedInSigningAccount() }?.label
    return SignOutOutcome(next, if (next == null) AppPhase.Onboarding else AppPhase.Ready)
}

/**
 * The profile in view after [wipedRef] was wiped. Removing a profile that was not in view leaves the one that was
 * ([inView]) where it is, as long as it is still signed in. Wiping the profile in view follows [signOutOutcome].
 */
internal fun nextActiveAfterWipe(
    accounts: List<AccountSummaryFfi>,
    wipedRef: String,
    inView: String?,
): String? =
    accounts.firstOrNull { it.label == inView && it.label != wipedRef && it.isSignedInSigningAccount() }?.label
        ?: signOutOutcome(accounts, wipedRef).nextActiveRef

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

/** Returns the stable identities of every locally signed-in signing account. */
internal fun List<AccountSummaryFfi>.signedInSigningAccountIds(): Set<String> =
    mapNotNullTo(mutableSetOf()) { account ->
        account.accountIdHex.takeIf { account.isSignedInSigningAccount() }
    }

/** Mirror a completed engine sign-out when its follow-up account refresh fails. */
internal fun reconcileCachedAccountsAfterSignOut(
    accounts: List<AccountSummaryFfi>,
    signedOutRef: String,
): List<AccountSummaryFfi> =
    accounts.map { account ->
        if (account.label == signedOutRef) {
            account.copy(signedOut = true, running = false)
        } else {
            account
        }
    }
