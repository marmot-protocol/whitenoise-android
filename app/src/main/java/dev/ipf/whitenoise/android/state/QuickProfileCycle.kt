package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi

internal const val QUICK_PROFILE_CYCLE_KEY = "quick_profile_cycle"

/** Stable native account order, independent of active-first selector presentation and signer liveness. */
internal fun nextQuickProfileCycleAccount(
    accounts: List<AccountSummaryFfi>,
    activeAccountRef: String?,
    enabled: Boolean,
    eligible: (AccountSummaryFfi) -> Boolean = { true },
): AccountSummaryFfi? {
    val signedIn = accounts.filter { it.isSignedInSigningAccount() && eligible(it) }
    val index = signedIn.indexOfFirst { it.label == activeAccountRef }
    return if (!enabled || signedIn.size < 2 || index < 0) null else signedIn[(index + 1) % signedIn.size]
}

/** The app-wide opt-in never admits retained sign-out, incomplete setup or destructive-operation targets. */
internal fun WhiteNoiseAppState.quickProfileCycleTarget(): AccountSummaryFfi? =
    nextQuickProfileCycleAccount(
        accounts = accounts,
        activeAccountRef = activeAccountRef,
        enabled =
            quickProfileCycling &&
                !signOutInProgress &&
                !wipeInProgress &&
                retainedAccountReactivationRef == null,
        eligible = accountSetup::eligible,
    )

/** Whether the avatar should open the native selector instead of the single-account Settings destination. */
internal fun WhiteNoiseAppState.chatsAvatarOpensSelector(): Boolean {
    val eligibleCount = accounts.count { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
    return eligibleCount > 1
}

/**
 * Recompute at the tap, then let the existing quick-switch/native activation owner decide whether to commit.
 * Completion copy is emitted only by the native local-ready callback and must still identify that account/runtime.
 */
internal fun WhiteNoiseAppState.requestQuickProfileCycle(
    requestSwitch: (String, () -> Unit) -> Unit,
    onSwitched: (String) -> Unit,
) {
    val target = quickProfileCycleTarget() ?: return
    val runtime = runtimeGeneration
    requestSwitch(target.label) {
        val actual = activeAccount
        val runtimeAvailable = runtimeGeneration == runtime && !signOutInProgress && !wipeInProgress
        val sameAccount =
            actual != null &&
                actual.label == target.label &&
                actual.accountIdHex.equals(target.accountIdHex, ignoreCase = true)
        if (runtimeAvailable && sameAccount && actual != null) {
            if (actual.isSignedInSigningAccount() && accountSetup.eligible(actual)) {
                onSwitched(accountDisplayNameCached(actual.accountIdHex))
            }
        }
    }
}
