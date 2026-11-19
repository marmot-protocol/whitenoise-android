package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Retry every `clearPushRegistration` that previously failed (sign-out
 * disconnected from the network, runtime transient error, etc.). On
 * success the entry leaves the persisted set; on failure it stays and
 * the next sync tick will try again. Without this drain a failed
 * sign-out-time deregistration would silently leave the push server
 * holding a stale token — there's no other code path that would notice
 * because [nativePushEnabled] is already false on the runtime side.
 */
internal suspend fun NotificationDeliveryController.drainPendingPushClears() {
    for (account in withContext(pushWakeStorageDispatcher) { pushTokenStore.pendingClears() }) {
        val cleared =
            runCatchingCancellable { host.marmotIo { clearPushRegistration(account) } }
                .onSuccess { outcome ->
                    logPushRegistrationShareOutcome("pending clear", account, outcome)
                }.onFailure {
                    appStateDebug { "pending clearPushRegistration retry failed: ${it.readableMessage()}" }
                }.isSuccess
        if (cleared) {
            withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPending(account) }
            appStateDebug { "pending clearPushRegistration drained account=${account.take(8)}" }
        }
    }
}

/** Retries sign-out push-disables that failed. On success the account leaves the pending set. */
internal suspend fun NotificationDeliveryController.drainPendingPushDisables() {
    val runtime = marmotRuntime ?: return
    for (account in withContext(pushWakeStorageDispatcher) { pushTokenStore.pendingDisables() }) {
        val settings =
            runCatchingCancellable {
                withContext(marmotIoDispatcher) { runtime.marmot.setNativePushEnabled(account, false) }
            }.onFailure {
                appStateDebug { "pending setNativePushEnabled(false) retry failed: ${it.readableMessage()}" }
            }.getOrNull()
        if (marmotRuntime !== runtime) return
        if (settings?.nativePushEnabled == false) {
            perAccountSyncedFingerprints.remove(account)
            if (account == activeAccountRef) localNotificationSettings = settings
            withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPendingDisable(account) }
            appStateDebug { "pending native-push disable drained account=${account.take(8)}" }
        }
    }
}

internal suspend fun NotificationDeliveryController.setNativePushEnabledLocked(
    account: String,
    enabled: Boolean,
): Boolean {
    if (enabled && !nativePushCapability().isAvailable) return false
    return runCatching {
        val settings =
            nativePushSyncMutex.withLock {
                if (
                    enabled &&
                    !withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPendingDisable(account) }
                ) {
                    return@runCatching false
                }
                host.marmotIo { setNativePushEnabled(account, enabled) }
            }
        localNotificationSettings = settings
        if (settings.nativePushEnabled != enabled) return@runCatching false
        if (enabled) {
            // Durable supersession was confirmed before the native positive write.
            val allAccountsReady = syncNativePushRegistrationIfEnabled()
            // The runtime flag alone is not a usable delivery path. Only
            // report enablement after upsertPushRegistration succeeded
            // for every enabled account and cached the active account's
            // exact token/server fingerprint.
            val confirmed =
                nativePushEnablementConfirmed(
                    allAccountsReady = allAccountsReady,
                    activeAccountRegistered = hasConfirmedNativePushRegistration(account),
                )
            if (!confirmed) {
                val rollbackSettings = host.marmotIo { setNativePushEnabled(account, false) }
                localNotificationSettings = rollbackSettings
                clearPushRegistrationForAccount(account)
            }
            confirmed
        } else {
            clearPushRegistrationForAccount(account)
            true
        }
    }.getOrElse {
        rethrowIfCancellation(it)
        host.presentFailure(R.string.toast_couldnt_update_notifications, "NOTIFICATION_SETTINGS_UPDATE", it)
        false
    }
}

/**
 * Runtime-side clear of an account's push registration plus the cached
 * fingerprint. If the FFI call fails (network hiccup, runtime mid-
 * teardown, sign-out racing with a transient error), persist the
 * account into the pending-clears set so [drainPendingPushClears]
 * retries it on the next sync — otherwise a server-side stale token
 * would stick indefinitely because `nativePushEnabled` is already
 * false locally and the sync loop would skip the account.
 */
private suspend fun NotificationDeliveryController.clearPushRegistrationForAccount(account: String) {
    nativePushSyncMutex.withLock {
        clearPushRegistrationForAccountLocked(account)
    }
}

/** Clears [account]'s push registration, queueing the clear for retry when the runtime call fails. */
internal suspend fun NotificationDeliveryController.clearPushRegistrationForAccountLocked(account: String) {
    perAccountSyncedFingerprints.remove(account)
    val result = runCatchingCancellable { host.marmotIo { clearPushRegistration(account) } }
    result.onSuccess { outcome ->
        // PENDING means MarmotKit durably retained the clear and will
        // retry group sharing; it is a successful local clear, not a
        // reason to restore or roll back the registration.
        logPushRegistrationShareOutcome("clear", account, outcome)
        withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPending(account) }
    }
    result.exceptionOrNull()?.let { failure ->
        val queued = withContext(Dispatchers.IO) { pushTokenStore.recordPendingClear(account) }
        appStateDebug {
            "clearPushRegistration failed cleanupQueued=$queued: ${failure.readableMessage()}"
        }
    }
}
