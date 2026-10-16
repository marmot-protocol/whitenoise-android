package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.PinnedConversationNavigation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup
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

/**
 * Confirmed local removal invalidates pending launcher requests as well as already-visible pins. The notification
 * presenter's snapshot of the removed conversation shortcut is dropped in the same publication-lock section as the
 * platform removal, off the main thread, so no card can publish between the two and a new conversation under the
 * same group id publishes its shortcut again before its first card.
 */
internal suspend fun WhiteNoiseAppState.removePinnedConversationShortcuts(
    accountRef: String,
    groupIdHex: String,
) {
    withContext(Dispatchers.IO) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            try {
                PinnedConversationShortcuts(appContext).removeGroup(accountRef, groupIdHex)
            } finally {
                forgetNotificationConversationShortcut(accountRef, groupIdHex)
            }
        }
    }
}

/**
 * Platform failure cannot undo native removal after durable revocation; later refresh retries presentation cleanup.
 * The notification presenter's snapshot of the removed conversation shortcut is dropped either way, in the same
 * publication-lock section as the platform removal and off the main thread.
 */
internal suspend fun WhiteNoiseAppState.removeRevokedPinnedConversationShortcuts(
    accountRef: String,
    groupIdHex: String,
) {
    withContext(Dispatchers.IO) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            runCatching { PinnedConversationShortcuts(appContext).removeRevokedGroup(accountRef, groupIdHex) }
                .onFailure { appStateDebug(it) { "revoked launcher presentation cleanup deferred" } }
            forgetNotificationConversationShortcut(accountRef, groupIdHex)
        }
    }
}

/** Persist credential revocation before a native deletion, reset or account sign-out can become durable. */
internal suspend fun <T> WhiteNoiseAppState.withRevokedPinnedTarget(
    accountRef: String,
    groupIdHex: String? = null,
    remove: suspend () -> T,
): T = PinnedConversationTokens.create(appContext).withRemovalRevoked(accountRef, groupIdHex, remove)
