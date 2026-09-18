package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi

/**
 * The stored key still reads `quick_profile_cycle`: the opt-in it records is the same one, so renaming it
 * would silently reset the choice for everyone who already made it.
 */
internal const val QUICK_ACCOUNT_SWITCHING_KEY = "quick_profile_cycle"

/** Signed-in, fully set-up accounts other than the active one, in stable native order. */
internal fun quickSwitchAccounts(
    accounts: List<AccountSummaryFfi>,
    activeAccountRef: String?,
    enabled: Boolean,
    eligible: (AccountSummaryFfi) -> Boolean = { true },
): List<AccountSummaryFfi> {
    if (!enabled || activeAccountRef == null) return emptyList()
    val signedIn = accounts.filter { it.isSignedInSigningAccount() && eligible(it) }
    return if (signedIn.none { it.label == activeAccountRef }) {
        emptyList()
    } else {
        signedIn.filterNot { it.label == activeAccountRef }
    }
}

/**
 * Whether the chat list shows the other accounts beside the active avatar.
 *
 * The app-wide opt-in never admits retained sign-out, incomplete setup or destructive-operation targets, so
 * a wipe or sign-out that transiently clears the active account hides the row rather than flashing a stale one.
 */
internal fun WhiteNoiseAppState.quickSwitchAvatarAccounts(): List<AccountSummaryFfi> =
    quickSwitchAccounts(
        accounts = accounts,
        activeAccountRef = activeAccountRef,
        enabled =
            quickAccountSwitching &&
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
 * Re-resolve the tapped account, then let the existing quick-switch/native activation owner decide whether to
 * commit. Completion copy is emitted only by the native local-ready callback and must still identify that
 * account and runtime, so a target removed between the tap and the callback confirms nothing.
 */
internal fun WhiteNoiseAppState.requestQuickAccountSwitchTo(
    targetLabel: String,
    requestSwitch: (String, () -> Unit) -> Unit,
    onSwitched: (String) -> Unit,
) {
    val target = quickSwitchAvatarAccounts().firstOrNull { it.label == targetLabel } ?: return
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
