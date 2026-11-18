package dev.ipf.whitenoise.android.state

/**
 * Removes a saved account that another of its devices unlinked, from the sign-in screen where no account is active.
 * It runs the same wipe as removing the account in view, so the drafts, decrypted media, launcher shortcuts and
 * preferences Android keeps for [label] go with MDK's data. True only once MDK reports its local cleanup complete.
 */
internal suspend fun WhiteNoiseAppState.removeUnlinkedAccount(label: String): Boolean {
    // A cancelled setup child may need the account lifetime for its own cleanup before the wipe can take it.
    accountSetup.close()
    val outcome = withAccountLifetime { signOutAndWipeActiveAccountLocked(label) }
    return outcome?.localCleanup?.completed == true
}
