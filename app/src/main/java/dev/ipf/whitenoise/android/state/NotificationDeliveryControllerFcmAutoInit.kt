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
 * Turns Firebase auto-init off when the settings of [accountRefs] show that none uses native push. A
 * settings or storage read failure changes nothing, so it can never turn auto-init off for a Push account.
 */
internal suspend fun NotificationDeliveryController.disableFcmAutoInitWithoutNativeDemand(accountRefs: List<String>) {
    val demand = runCatchingCancellable { nativePushDemand(accountRefs) }.getOrNull()
    if (demand == NativePushDemand.None) disableFcmAutoInitIfUnchanged(accountRefs)
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

/**
 * Runs [block], which holds the native sync lock, and settles auto-init from the loaded accounts' demand if
 * it is cancelled. The block may already have committed a native-push disable, such as a drained sign-out
 * disable, so the settle runs before the cancellation propagates.
 */
internal suspend inline fun <T> NotificationDeliveryController.settlingFcmAutoInitOnCancel(block: () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { disableFcmAutoInitWithoutLoadedNativeDemand() }
        throw cancelled
    }
