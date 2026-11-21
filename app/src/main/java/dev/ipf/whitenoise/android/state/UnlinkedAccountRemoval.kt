package dev.ipf.whitenoise.android.state

/**
 * Removes the unlinked copy before a fresh sign-in. A completed native deletion allows the user to sign in again
 * even when another cleanup stage needs attention; the app-root report preserves those details across navigation.
 */
internal suspend fun WhiteNoiseAppState.removeUnlinkedAccount(label: String): Boolean {
    val completion = removeUnlinkedAccountForOutcome(label) ?: return false
    if (!completion.report.clean) pendingWipeReport = completion.report
    return completion.native.localCleanup.completed
}

/** Removes exactly the rendered unlinked identity, after rechecking its native status under the account lifetime. */
internal suspend fun WhiteNoiseAppState.removeUnlinkedAccountForOutcome(
    label: String,
    expectedRuntime: Int = runtimeGeneration,
    requireInView: Boolean = false,
): AccountWipeCompletion? {
    fun ownsRequest() = runtimeGeneration == expectedRuntime && (!requireInView || activeAccountRef == label)
    if (!ownsRequest()) return null
    // An unrelated setup route keeps its signer and subscriptions. A matching route must drain before lifetime.
    if (accountSetup.controller?.account == label) accountSetup.close()
    return withAccountLifetime {
        if (!ownsRequest()) return@withAccountLifetime null
        val unlinked = runCatchingCancellable { marmotIo { listAccounts().find { it.label == label && it.unlinked } } }
        val target = unlinked.getOrNull() ?: return@withAccountLifetime null
        if (ownsRequest()) signOutAndWipeActiveAccountLocked(label, target.accountIdHex) else null
    }
}
