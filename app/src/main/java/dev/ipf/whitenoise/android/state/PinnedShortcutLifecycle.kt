package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.PinnedConversationNavigation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The live App Lock decision for a launcher tap, read from the same state the lock screen renders. */
internal fun WhiteNoiseAppState.pinnedShortcutLockDecision(): PinnedShortcutLockDecision =
    pinnedShortcutLockDecision(appLockScreenVisible, appUnlockEvaluationPending)

/**
 * Launcher authority is checked again after native reads and account activation, immediately before routing.
 * A still-pending lock evaluation counts as not current here: callers that can wait do so before asking.
 */
internal fun WhiteNoiseAppState.pinnedShortcutTargetIsCurrent(target: NotificationTarget): Boolean {
    if (target.shortcutCapability == null) return true
    return PinnedConversationNavigation.isCurrent(
        appContext,
        target,
        accounts.filter { it.isSignedInSigningAccount() }.mapTo(hashSetOf()) { it.label },
        pinnedShortcutLockDecision() != PinnedShortcutLockDecision.OPEN,
    )
}

/** Confirmed local removal invalidates pending launcher requests as well as already-visible pins. */
internal suspend fun WhiteNoiseAppState.removePinnedConversationShortcuts(
    accountRef: String,
    groupIdHex: String,
) {
    withContext(Dispatchers.IO) {
        PinnedConversationShortcuts(appContext).removeGroup(accountRef, groupIdHex)
    }
}

/** Platform failure cannot undo native removal after durable revocation; later refresh retries presentation cleanup. */
internal suspend fun WhiteNoiseAppState.removeRevokedPinnedConversationShortcuts(
    accountRef: String,
    groupIdHex: String,
) {
    withContext(Dispatchers.IO) {
        runCatching { PinnedConversationShortcuts(appContext).removeRevokedGroup(accountRef, groupIdHex) }
            .onFailure { appStateDebug(it) { "revoked launcher presentation cleanup deferred" } }
    }
}

/** Persist credential revocation before a native deletion, reset or account sign-out can become durable. */
internal suspend fun <T> WhiteNoiseAppState.withRevokedPinnedTarget(
    accountRef: String,
    groupIdHex: String? = null,
    remove: suspend () -> T,
): T = PinnedConversationTokens.create(appContext).withRemovalRevoked(accountRef, groupIdHex, remove)
