package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationChannelWriteGuard
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.PinnedConversationNavigation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup
import dev.ipf.whitenoise.android.notifications.deleteConversationNotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The live App Lock decision for a launcher tap, read from the same state the lock screen renders. */
internal fun WhiteNoiseAppState.pinnedShortcutLockDecision(): PinnedShortcutLockDecision =
    pinnedShortcutLockDecision(appLock.screenVisible, appLock.evaluationPending)

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
 * Confirmed local removal invalidates pending launcher requests, already-visible pins, and the
 * conversation's `:conv:` notification channels. All three run together with dropping the
 * presenter's shortcut snapshot inside the publication lock, off the main thread, so no card can
 * publish between them and a recreated conversation under the same group id publishes cleanly.
 * Incomplete channel cleanup throws so [finishLocalGroupDeleteCleanup] retains its durable retry.
 */
internal suspend fun WhiteNoiseAppState.removePinnedConversationShortcuts(
    accountRef: String,
    groupIdHex: String,
) {
    withContext(Dispatchers.IO) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            // Channels go first so shortcut removal cannot soft-delete one with its private name intact. If they
            // fail, shortcuts stay until the journal retry, which redoes channels before shortcuts.
            val channelsResult =
                runCatching {
                    check(deleteConversationNotificationChannels(appContext, accountRef, groupIdHex)) {
                        "Conversation notification channel cleanup incomplete"
                    }
                }
            val shortcutsResult =
                if (channelsResult.isSuccess) {
                    runCatching { PinnedConversationShortcuts(appContext).removeGroup(accountRef, groupIdHex) }
                } else {
                    Result.success(Unit)
                }
            forgetNotificationConversationShortcut(accountRef, groupIdHex)
            shortcutsResult.getOrThrow()
            channelsResult.getOrThrow()
        }
    }
}

/**
 * Platform failure cannot undo native removal after durable revocation; later refresh retries presentation cleanup.
 * The notification presenter's snapshot and `:conv:` notification channels are cleared in the same
 * publication-lock section as the platform removal, off the main thread. Incomplete channel cleanup stages
 * a media-free durable delete intent, so the journal retries it like any other local delete.
 */
internal suspend fun WhiteNoiseAppState.removeRevokedPinnedConversationShortcuts(
    accountRef: String,
    groupIdHex: String,
) {
    val channelsCleared =
        withContext(Dispatchers.IO) {
            synchronized(UserEventNotificationGroup.mutationLock) {
                val cleared =
                    runCatching { deleteConversationNotificationChannels(appContext, accountRef, groupIdHex) }
                        .getOrDefault(false)
                // A failed channel cleanup keeps shortcuts for the journal retry so removal cannot hide one.
                if (cleared) {
                    runCatching { PinnedConversationShortcuts(appContext).removeRevokedGroup(accountRef, groupIdHex) }
                        .onFailure { appStateDebug(it) { "revoked launcher presentation cleanup deferred" } }
                }
                forgetNotificationConversationShortcut(accountRef, groupIdHex)
                cleared
            }
        }
    if (!channelsCleared) {
        appStateDebug { "conversation notification channel cleanup deferred to the local delete journal" }
        val pending = PendingLocalGroupDeleteCleanup(accountRef, groupIdHex, emptyList(), emptySet())
        runCatchingCancellable {
            localGroupDeleteCleanupMutex.withLock {
                withContext(Dispatchers.IO) {
                    // An earlier Delete chat may have staged media keys; keep that entry, which also retries channels.
                    if (localGroupDeleteCleanupJournal.find(accountRef, groupIdHex) == null) {
                        localGroupDeleteCleanupJournal.stage(pending)
                    }
                }
            }
        }.onFailure { appStateDebug(it) { "conversation notification channel retry could not be staged" } }
        schedulePendingLocalGroupDeleteCleanup(retryTransport = true)
    }
}

/**
 * Captures the departing account's known group ids before native sign-out or wipe revokes them,
 * so upgrade-era `:conv:` channels without a shortcut or ownership record are still deleted when
 * another account remains signed in. Pending local-delete journal groups are included, since journal
 * replay no longer runs for an account once it has signed out.
 */
internal suspend fun WhiteNoiseAppState.captureAccountKnownGroupIds(accountRef: String): Set<String> =
    buildSet {
        runCatchingCancellable {
            marmotIo { chatList(accountRef, includeArchived = true) }
        }.getOrDefault(emptyList()).forEach { row -> add(row.groupIdHex) }
        runCatchingCancellable {
            marmotIo { quarantinedGroups(accountRef) }
        }.getOrDefault(emptyList()).forEach { group -> add(group.groupIdHex) }
        runCatching { conversationVibrationPreferences.groupIdsForAccount(accountRef) }
            .getOrDefault(emptySet())
            .forEach(::add)
        runCatchingCancellable {
            withContext(Dispatchers.IO) { localGroupDeleteCleanupJournal.groupIdsForAccount(accountRef) }
        }.getOrDefault(emptySet()).forEach(::add)
    }

/**
 * Captures live account ownership, credential revocation, and channel-cleanup epochs before a
 * conversation channel write suspends or waits on [UserEventNotificationGroup.mutationLock].
 */
internal fun WhiteNoiseAppState.captureConversationChannelWriteGuard(
    accountRef: String,
    groupIdHex: String,
    shortcutId: String,
): () -> Boolean {
    val runtime = runtimeGeneration
    val writeEpoch = NotificationChannelWriteGuard.captureEpoch()
    val revocationGeneration = PinnedConversationTokens.captureRequest()
    return {
        val accountActive = runtimeGeneration == runtime && activeAccountRef == accountRef
        val accountSignedIn =
            !signOutInProgress &&
                !wipeInProgress &&
                accounts.any { it.label == accountRef && it.isSignedInSigningAccount() }
        val cleanupCurrent =
            NotificationChannelWriteGuard.isCurrent(writeEpoch, accountRef, shortcutId) &&
                PinnedConversationTokens.isTargetCurrent(revocationGeneration, accountRef, groupIdHex)
        accountActive && accountSignedIn && cleanupCurrent
    }
}

/** Persist credential revocation before a native deletion, reset or account sign-out can become durable. */
internal suspend fun <T> WhiteNoiseAppState.withRevokedPinnedTarget(
    accountRef: String,
    groupIdHex: String? = null,
    remove: suspend () -> T,
): T = PinnedConversationTokens.create(appContext).withRemovalRevoked(accountRef, groupIdHex, remove)
