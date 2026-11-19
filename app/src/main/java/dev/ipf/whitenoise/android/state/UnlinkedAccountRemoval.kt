package dev.ipf.whitenoise.android.state

/**
 * Removes a saved account that another of its devices unlinked, from the sign-in screen where no account is active.
 * It runs the same wipe as removing the account in view, so the drafts, decrypted media, launcher shortcuts and
 * preferences Android keeps for [label] go with MDK's data. True only once MDK reports its local cleanup complete.
 * The screen can also be open while another account is active, to add a profile. A removal that fails there puts
 * that account back in view, never the one being removed.
 */
internal suspend fun WhiteNoiseAppState.removeUnlinkedAccount(label: String): Boolean {
    val inView = activeAccountRef?.takeIf { it != label }
    // A cancelled setup child may need the account lifetime for its own cleanup before the wipe can take it.
    accountSetup.close()
    val outcome = withAccountLifetime { signOutAndWipeActiveAccountLocked(label) }
    val removed = outcome?.localCleanup?.completed == true
    if (!removed && shouldRestoreAccountInView(inView, activeAccountRef, accounts.map { it.label })) {
        setActiveAccount(checkNotNull(inView))
    }
    return removed
}

/**
 * Whether a failed removal left the app on a different account than the one that was [inView] before it, while that
 * account is still [saved] on this device.
 */
internal fun shouldRestoreAccountInView(
    inView: String?,
    activeNow: String?,
    saved: List<String>,
): Boolean = inView != null && activeNow != inView && inView in saved
