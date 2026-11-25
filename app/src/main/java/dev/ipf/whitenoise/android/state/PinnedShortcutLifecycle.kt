package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationChannelWriteGuard
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.PinnedConversationNavigation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup
import dev.ipf.whitenoise.android.notifications.deleteConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.recordDepartingConversationChannelOwners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val SIGN_OUT_GROUP_READ_ATTEMPTS = 3

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
            // fail, revoke credentials and relabel shortcuts until the journal retry deletes channels first.
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
                    val revocationResult =
                        runCatching { PinnedConversationTokens.create(appContext).revokeGroup(accountRef, groupIdHex) }
                    runCatching { PinnedConversationShortcuts(appContext).relabelGroup(accountRef, groupIdHex) }
                        .onFailure { appStateDebug(it) { "conversation shortcut relabel failed" } }
                    revocationResult
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
                // Failed channel cleanup keeps shortcuts, relabeled, for the journal retry so removal cannot hide one.
                val shortcuts = PinnedConversationShortcuts(appContext)
                runCatching {
                    if (cleared) {
                        shortcuts.removeRevokedGroup(accountRef, groupIdHex)
                    } else {
                        shortcuts.relabelGroup(accountRef, groupIdHex)
                    }
                }.onFailure { appStateDebug(it) { "revoked launcher presentation cleanup deferred" } }
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
 * replay no longer runs for an account once it has signed out. The capture is saved to the channel owner
 * store before returning, so a process death before Android cleanup cannot lose it.
 */
internal suspend fun WhiteNoiseAppState.captureAccountKnownGroupIds(accountRef: String): Set<String> {
    val groupIds = readAccountKnownGroupIds(accountRef)
    val recorded =
        withContext(Dispatchers.IO) { recordDepartingConversationChannelOwners(appContext, accountRef, groupIds) }
    if (!recorded) appStateDebug { "sign-out channel capture could not be saved for a restart" }
    return groupIds
}

/** Reads every group id the account's channels may be named after, retrying transient native reads before fallback. */
private suspend fun WhiteNoiseAppState.readAccountKnownGroupIds(accountRef: String): Set<String> =
    buildSet {
        readGroupInventoryWithRetry("chats") { marmotIo { chatList(accountRef, includeArchived = true) } }
            .forEach { row -> add(row.groupIdHex) }
        readGroupInventoryWithRetry("quarantined groups") { marmotIo { quarantinedGroups(accountRef) } }
            .forEach { group -> add(group.groupIdHex) }
        // Retain already-mounted active/archived chats if native listing fails on a degraded store.
        boundChats(accountRef)?.let { controller ->
            controller.items.forEach { item -> add(item.group.groupIdHex) }
            controller.archivedItems.forEach { item -> add(item.group.groupIdHex) }
        }
        runCatching { conversationVibrationPreferences.groupIdsForAccount(accountRef) }
            .getOrDefault(emptySet())
            .forEach(::add)
        runCatchingCancellable {
            withContext(Dispatchers.IO) { localGroupDeleteCleanupJournal.groupIdsForAccount(accountRef) }
        }.getOrDefault(emptySet()).forEach(::add)
    }

/** Retries transient native inventory reads before sign-out revocation without trapping a broken account. */
private suspend fun <T> readGroupInventoryWithRetry(
    label: String,
    read: suspend () -> List<T>,
): List<T> {
    var lastFailure: Throwable? = null
    repeat(SIGN_OUT_GROUP_READ_ATTEMPTS) { attempt ->
        val result = runCatchingCancellable { read() }
        if (result.isSuccess) return result.getOrDefault(emptyList())
        lastFailure = result.exceptionOrNull()
        if (attempt + 1 < SIGN_OUT_GROUP_READ_ATTEMPTS) yield()
    }
    lastFailure?.let { appStateDebug(it) { "sign-out channel capture could not list $label" } }
    return emptyList()
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
