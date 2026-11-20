package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.WipeOutcomeFfi

/**
 * Removes a saved account that another of its devices unlinked, from the sign-in screen where no account is active.
 * It runs the same wipe as removing the account in view, so the drafts, decrypted media, launcher shortcuts and
 * preferences Android keeps for [label] go with MDK's data. True only once MDK reports its local cleanup complete.
 * The screen can also be open while another account is active, to add a profile. The wipe puts back whichever
 * account was in view when it fails, or none, so a failed removal never activates the account being removed.
 */
internal suspend fun WhiteNoiseAppState.removeUnlinkedAccount(label: String): Boolean =
    removeUnlinkedAccountForOutcome(label)?.localCleanup?.completed == true

/**
 * The same removal with MDK's whole outcome, for a caller that reports each cleanup stage. Null when the wipe could
 * not start, in which case nothing was removed.
 */
internal suspend fun WhiteNoiseAppState.removeUnlinkedAccountForOutcome(label: String): WipeOutcomeFfi? {
    // A cancelled setup child may need the account lifetime for its own cleanup before the wipe can take it.
    accountSetup.close()
    return withAccountLifetime { signOutAndWipeActiveAccountLocked(label) }
}
