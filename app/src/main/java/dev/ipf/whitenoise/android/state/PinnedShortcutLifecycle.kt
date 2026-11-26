package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationChannelWriteGuard
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.PinnedConversationNavigation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup
import dev.ipf.whitenoise.android.notifications.deleteConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.departedChatConversationShortcutIds
import dev.ipf.whitenoise.android.notifications.groupChannelShortcutIds
import dev.ipf.whitenoise.android.notifications.hasUnaccountedConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.recordDepartingConversationChannelOwners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val SIGN_OUT_GROUP_READ_ATTEMPTS = 3
private const val SIGN_OUT_OWNER_SAVE_ATTEMPTS = 3

/** Captured departing-account group inventory together with whether every native listing succeeded. */
private data class AccountGroupInventory(
    val groupIds: Set<String>,
    val nativeInventoryComplete: Boolean,
)

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
 * Captures the departing account's known group ids before native sign-out or wipe revokes them,
 * so upgrade-era `:conv:` channels without a shortcut or ownership record are still deleted when
 * another account remains signed in. Pending local-delete journal groups are included, since journal
 * replay no longer runs for an account once it has signed out. While another account remains, channels
 * of chats no account still lists are recorded too (see [departedChatChannelShortcutIds]).
 *
 * Returns null before native revocation when native inventory remained incomplete while
 * [includeUnscopedLegacy] is false and an unaccounted `:conv:` channel exists on Android, for the departing
 * account or any account that remains, or when the departing ownership record and any last-signer sweep
 * could not be durably saved after retries.
 */
internal suspend fun WhiteNoiseAppState.captureAccountKnownGroupIds(
    accountRef: String,
    includeUnscopedLegacy: Boolean = accounts.none { it.label != accountRef && it.isSignedInSigningAccount() },
): Set<String>? {
    val inventory = readAccountKnownGroupIds(accountRef)
    val groupIds = inventory.groupIds
    val unaccountedChannelRisk =
        !inventory.nativeInventoryComplete &&
            !includeUnscopedLegacy &&
            withContext(Dispatchers.IO) {
                hasUnaccountedConversationNotificationChannels(appContext, accountRef, groupIds)
            }
    // The last signer's sweep already deletes every ownerless channel, so only a shared device needs this.
    val departed =
        if (includeUnscopedLegacy || unaccountedChannelRisk) {
            emptySet()
        } else {
            departedChatChannelShortcutIds(accountRef, groupIds)
        }
    // An unlisted chat's channel could be another account's or a departed chat's. Neither may be guessed, and
    // once native revocation commits nothing would sweep a departed one while a signer remains, so stop first.
    val departedUnknown =
        departed == null &&
            withContext(Dispatchers.IO) {
                hasUnaccountedConversationNotificationChannels(appContext, accountRef, groupIds)
            }
    return when {
        unaccountedChannelRisk || departedUnknown -> {
            appStateDebug { "sign-out channel capture incomplete with unaccounted conversation channels" }
            null
        }
        !retrySignOutOwnerSave {
            withContext(Dispatchers.IO) {
                recordDepartingConversationChannelOwners(
                    appContext,
                    accountRef,
                    groupIds,
                    includeUnscopedLegacy,
                    departedChatShortcutIds = departed.orEmpty(),
                )
            }
        } -> {
            appStateDebug { "sign-out channel capture could not be saved before native revocation" }
            null
        }
        else -> groupIds
    }
}

/**
 * Shortcut ids of `:conv:` channels whose chat no account lists any more, such as a chat an older build deleted
 * after its vibration was reset to Default. Every other account's chats are read first, so none of their channels
 * is ever counted. Null when any of them cannot be listed, or Android's channels cannot be read, since such a
 * channel could still be theirs.
 */
private suspend fun WhiteNoiseAppState.departedChatChannelShortcutIds(
    accountRef: String,
    groupIds: Set<String>,
): Set<String>? {
    val kept = HashSet<String>()
    for (other in accounts.map { it.label }.filter { it != accountRef }.distinct()) {
        val inventory = readAccountKnownGroupIds(other)
        if (!inventory.nativeInventoryComplete) {
            appStateDebug { "sign-out could not list another account's chats for unlisted chat channels" }
            return null
        }
        kept += groupChannelShortcutIds(other, inventory.groupIds)
    }
    return withContext(Dispatchers.IO) {
        departedChatConversationShortcutIds(appContext, accountRef, groupIds, kept)
    }
}

/** Retries durable owner-record persistence before native revocation commits. */
private suspend fun retrySignOutOwnerSave(save: suspend () -> Boolean): Boolean {
    repeat(SIGN_OUT_OWNER_SAVE_ATTEMPTS) { attempt ->
        if (save()) return true
        if (attempt + 1 < SIGN_OUT_OWNER_SAVE_ATTEMPTS) yield()
    }
    return false
}

/** Reads every group id the account's channels may be named after, retrying transient native reads before fallback. */
private suspend fun WhiteNoiseAppState.readAccountKnownGroupIds(accountRef: String): AccountGroupInventory {
    val chats = readGroupInventoryWithRetry("chats") { marmotIo { chatList(accountRef, includeArchived = true) } }
    val quarantined =
        readGroupInventoryWithRetry("quarantined groups") { marmotIo { quarantinedGroups(accountRef) } }
    val groupIds =
        buildSet {
            chats?.forEach { row -> add(row.groupIdHex) }
            quarantined?.forEach { group -> add(group.groupIdHex) }
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
    return AccountGroupInventory(
        groupIds = groupIds,
        nativeInventoryComplete = chats != null && quarantined != null,
    )
}

/** Retries transient native inventory reads before sign-out revocation, returning null if every attempt fails. */
private suspend fun <T> readGroupInventoryWithRetry(
    label: String,
    read: suspend () -> List<T>,
): List<T>? {
    var lastFailure: Throwable? = null
    repeat(SIGN_OUT_GROUP_READ_ATTEMPTS) { attempt ->
        val result = runCatchingCancellable { read() }
        if (result.isSuccess) return result.getOrDefault(emptyList())
        lastFailure = result.exceptionOrNull()
        if (attempt + 1 < SIGN_OUT_GROUP_READ_ATTEMPTS) yield()
    }
    lastFailure?.let { appStateDebug(it) { "sign-out channel capture could not list $label" } }
    return null
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
