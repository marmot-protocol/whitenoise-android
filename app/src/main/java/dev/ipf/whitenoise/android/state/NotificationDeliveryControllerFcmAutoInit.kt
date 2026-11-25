package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stops at the first account with native push on, and a failed read only counts when no account needs it. */
internal suspend fun NotificationDeliveryController.nativePushDemand(accountRefs: List<String>): NativePushDemand {
    val pendingDisables = withContext(pushWakeStorageDispatcher) { pushTokenStore.pendingDisables() }
    val unreadAccounts = mutableListOf<String>()
    for (account in accountRefs) {
        val settings = runCatchingCancellable { host.marmotIo { notificationSettings(account) } }.getOrNull()
        when {
            settings == null -> unreadAccounts += account
            settings.nativePushEnabled && account !in pendingDisables -> return NativePushDemand.Required
        }
    }
    return if (unreadAccounts.isEmpty()) NativePushDemand.None else NativePushDemand.Unknown
}

/**
 * Acknowledges a sync in which none of [accountRefs] needs native delivery, and turns Firebase auto-init off
 * so the SDK stops refreshing a token nobody registers. A Push selection in flight writes it on again under
 * the sync lock once its accounts are registered.
 */
internal suspend fun NotificationDeliveryController.completeSyncWithoutNativePush(
    accountRefs: List<String>,
    isCurrent: () -> Boolean,
): Boolean {
    disableFcmAutoInitIfUnchanged(accountRefs)
    return withContext(pushWakeStorageDispatcher) {
        pushTokenStore.completeRegistrationSyncIfCurrent(ownerIsCurrent = isCurrent)
    }
}

/** Applies Firebase auto-init off Main, a no-op in builds without Firebase. */
internal suspend fun NotificationDeliveryController.applyFcmAutoInit(enabled: Boolean) {
    withContext(Dispatchers.IO) { nativePushFallbackPlatform.setFcmAutoInitEnabled(enabled) }
}

/**
 * Turns Firebase auto-init off when the settings of [accountRefs] show that none uses native push, and returns
 * the demand it read, null when unreadable. A settings or storage read failure changes nothing, so it can
 * never turn auto-init off for a Push account.
 */
@Suppress("MaxLineLength")
internal suspend fun NotificationDeliveryController.disableFcmAutoInitWithoutNativeDemand(accountRefs: List<String>): NativePushDemand? {
    val demand = runCatchingCancellable { nativePushDemand(accountRefs) }.getOrNull()
    if (demand == NativePushDemand.None) disableFcmAutoInitIfUnchanged(accountRefs)
    return demand
}

/**
 * Turns auto-init off only while the account list still equals [readAccountRefs]. Accounts are republished
 * without the sync lock, so a list that changed during the settings reads may hold a Push account they
 * never saw. That account's own sync decides auto-init instead.
 */
private suspend fun NotificationDeliveryController.disableFcmAutoInitIfUnchanged(readAccountRefs: List<String>) {
    if (host.nativePushAccountRefs() == readAccountRefs) applyFcmAutoInit(enabled = false)
}

/**
 * Settles Firebase auto-init after a delivery change that did not complete. It turns auto-init off when no
 * account uses native push. Otherwise an account may be left on native push without its registration or
 * auto-init, for example after a failed rollback, or the demand could not be read, so it records the durable
 * pending registration sync for the next sync to settle both. It reads the live account list, since accounts
 * can change during the change, and the sync lock orders it after any registration or rollback.
 */
internal suspend fun NotificationDeliveryController.settleFcmAutoInitAfterFailedSelection() {
    nativePushSyncMutex.withLock {
        if (disableFcmAutoInitWithoutLoadedNativeDemand() != NativePushDemand.None) recordPendingRegistrationSync()
    }
}

/**
 * Turns auto-init off when the loaded accounts show that none uses native push, and returns the demand it
 * read. An empty list may only mean accounts have not loaded yet or are being republished, so it leaves
 * auto-init alone and returns null.
 */
internal suspend fun NotificationDeliveryController.disableFcmAutoInitWithoutLoadedNativeDemand(): NativePushDemand? {
    val accountRefs = host.nativePushAccountRefs()
    return if (accountRefs.isEmpty()) null else disableFcmAutoInitWithoutNativeDemand(accountRefs)
}

/** Drops the cached FCM token and turns Firebase auto-init off when a wipe leaves no signed-in signer. */
internal suspend fun NotificationDeliveryController.forgetFcmTokenAfterLastAccount() {
    runCatching { pushTokenStore.clear() }
    applyFcmAutoInit(enabled = false)
}

/**
 * After a sign-out, turns Firebase auto-init off when no signed-in signing account remains, since only those
 * can use native push. A signed-out entry can stay in the list, so the cached token is still dropped only
 * when no account entry remains at all.
 */
internal suspend fun NotificationDeliveryController.settleFcmAfterSignOut(
    nextActiveRef: String?,
    remaining: List<AccountSummaryFfi>,
) {
    if (nextActiveRef != null || remaining.any { it.isSignedInSigningAccount() }) return
    if (remaining.isEmpty()) runCatching { pushTokenStore.clear() }
    applyFcmAutoInit(enabled = false)
}

/**
 * Runs [block], which holds the native sync lock, and settles auto-init from the loaded accounts' demand if
 * it is cancelled. The block may already have committed a native-push disable, such as a drained sign-out
 * disable, so the settle runs before the cancellation propagates.
 */
internal suspend inline fun NotificationDeliveryController.settlingFcmAutoInitOnCancel(block: () -> Boolean): Boolean {
    try {
        return block()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { disableFcmAutoInitWithoutLoadedNativeDemand() }
        throw cancelled
    }
}

/**
 * Reads the selection's token explicitly, with Firebase auto-init still off, while [owner] still owns
 * delivery. The ownership checks and the token store run under the native sync lock, and the Firebase read
 * does not. A wipe ends ownership before it takes this lock, so a selection resumed after a wipe or an
 * account change keeps no token, and a stalled read never holds up the wipe. Auto-init is turned on only
 * once the owned registration commits in [enableNativeDeliveryForAccounts].
 */
@Suppress("MaxLineLength")
internal suspend fun NotificationDeliveryController.fetchFcmTokenForOwner(owner: NotificationDeliveryModeOwner): String? =
    fetchFcmTokenOrNull(
        tokenOwnerIsCurrent = { ownsNotificationDeliveryMode(owner) },
        ownerFence = nativePushSyncMutex,
        enableAutoInit = false,
    )
