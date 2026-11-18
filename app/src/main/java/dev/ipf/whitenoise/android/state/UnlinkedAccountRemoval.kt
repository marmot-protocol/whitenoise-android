package dev.ipf.whitenoise.android.state

/**
 * Removes [label] from this device after another of the account's devices unlinked it. MDK refuses to sign such an
 * account in again, so its sign-out and wipe is the only way forward: it clears the account's local data and skips
 * leaving chats and deleting key packages, which the other devices already did. True once MDK no longer lists it.
 */
internal suspend fun WhiteNoiseAppState.removeUnlinkedAccount(label: String): Boolean {
    val wiped = runCatchingCancellable { marmotIo { signOutAndWipe(label) } }
    runCatchingCancellable { refreshAccounts() }
    return wiped.isSuccess && accounts.none { it.label == label }
}
