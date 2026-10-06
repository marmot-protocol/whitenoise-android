package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.PinnedConversationNavigation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Launcher authority is checked again after native reads and account activation, immediately before routing. */
internal fun WhiteNoiseAppState.pinnedShortcutTargetIsCurrent(target: NotificationTarget): Boolean {
    if (target.shortcutCapability == null) return true
    return PinnedConversationNavigation.isCurrent(
        appContext,
        target,
        accounts.filter { it.isSignedInSigningAccount() }.mapTo(hashSetOf()) { it.label },
        appLockScreenVisible || appUnlockEvaluationPending,
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

/** Persist credential revocation before a native deletion, reset or account sign-out can become durable. */
internal suspend fun <T> WhiteNoiseAppState.withRevokedPinnedTarget(
    accountRef: String,
    groupIdHex: String? = null,
    remove: suspend () -> T,
): T = PinnedConversationTokens.create(appContext).withRemovalRevoked(accountRef, groupIdHex, remove)
