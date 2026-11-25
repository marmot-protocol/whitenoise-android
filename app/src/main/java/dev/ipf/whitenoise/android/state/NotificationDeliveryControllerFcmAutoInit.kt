package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stops at the first account with native push on, and a failed read only counts when no account needs it. */
internal suspend fun NotificationDeliveryController.nativePushDemand(accountRefs: List<String>): NativePushDemand {
    val pendingDisables = withContext(pushWakeStorageDispatcher) { pushTokenStore.pendingDisables() }
    var unreadable = false
    for (account in accountRefs) {
        val settings = runCatchingCancellable { host.marmotIo { notificationSettings(account) } }.getOrNull()
        when {
            settings == null -> unreadable = true
            settings.nativePushEnabled && account !in pendingDisables -> return NativePushDemand.Required
        }
    }
    return if (unreadable) NativePushDemand.Unknown else NativePushDemand.None
}

/**
 * Acknowledges a sync in which no account needs native delivery, and turns Firebase auto-init off so
 * the SDK stops refreshing a token nobody registers. A Push selection in flight writes it on again under
 * the sync lock once its accounts are registered.
 */
internal suspend fun NotificationDeliveryController.completeSyncWithoutNativePush(isCurrent: () -> Boolean): Boolean {
    applyFcmAutoInit(enabled = false)
    return withContext(pushWakeStorageDispatcher) {
        pushTokenStore.completeRegistrationSyncIfCurrent(ownerIsCurrent = isCurrent)
    }
}

/** Applies Firebase auto-init off Main, a no-op in builds without Firebase. */
internal suspend fun NotificationDeliveryController.applyFcmAutoInit(enabled: Boolean) {
    withContext(Dispatchers.IO) { nativePushFallbackPlatform.setFcmAutoInitEnabled(enabled) }
}

/**
 * Turns Firebase auto-init off when the settings of [accountRefs] show that none uses native push. A
 * settings or storage read failure changes nothing, so it can never turn auto-init off for a Push account.
 */
internal suspend fun NotificationDeliveryController.disableFcmAutoInitWithoutNativeDemand(accountRefs: List<String>) {
    val demand = runCatchingCancellable { nativePushDemand(accountRefs) }.getOrNull()
    if (demand == NativePushDemand.None) applyFcmAutoInit(enabled = false)
}

/**
 * Turns Firebase auto-init off after a native-push selection that did not complete, unless an account
 * still uses native push. It reads the live account list, since accounts can change during the selection,
 * and the sync lock orders this write after any registration or rollback.
 */
internal suspend fun NotificationDeliveryController.settleFcmAutoInitAfterFailedSelection() {
    nativePushSyncMutex.withLock { disableFcmAutoInitWithoutLoadedNativeDemand() }
}

/**
 * Turns auto-init off when the loaded accounts show that none uses native push. An empty list may only
 * mean accounts have not loaded yet or are being republished, so it leaves auto-init alone.
 */
internal suspend fun NotificationDeliveryController.disableFcmAutoInitWithoutLoadedNativeDemand() {
    val accountRefs = host.nativePushAccountRefs()
    if (accountRefs.isNotEmpty()) disableFcmAutoInitWithoutNativeDemand(accountRefs)
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
