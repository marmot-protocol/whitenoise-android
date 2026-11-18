package dev.ipf.whitenoise.android.state

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenStorageException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** A callback token is ephemeral authority for this rotation only, never a persisted-cache fallback. */
internal suspend fun NotificationDeliveryController.syncNativePushRegistration(
    freshCallbackToken: String? = null,
    callbackOwnerIsCurrent: () -> Boolean = { true },
): Boolean =
    try {
        nativePushSyncMutex.withLock {
            syncNativePushRegistrationIfEnabledLocked(freshCallbackToken, callbackOwnerIsCurrent)
        }
    } catch (_: PushTokenStorageException) {
        android.util.Log.w("PushTokenStorage", "registration_deferred")
        false
    }

/** Retains required sign-out cleanup while permission loss pauses registration and fallback mutation. */
internal suspend fun NotificationDeliveryController.pauseNotificationDeliveryReconciliation(): Boolean {
    nativePushFallback.invalidateAll()
    return nativePushSyncMutex.withLock {
        drainPendingPushClears()
        drainPendingPushDisables()
        false
    }
}

internal suspend fun NotificationDeliveryController.hasConfirmedNativePushRegistration(account: String): Boolean =
    nativePushSyncMutex.withLock { perAccountSyncedFingerprints.containsKey(account) }

/** Replays pending cleanup before registration and gates token acquisition on capability. */
@Suppress("ReturnCount")
private suspend fun NotificationDeliveryController.syncNativePushRegistrationIfEnabledLocked(
    freshCallbackToken: String? = null,
    callbackOwnerIsCurrent: () -> Boolean = { true },
): Boolean {
    val rotation = pushTokenRotationSequence.get()
    val operationIsCurrent = { pushTokenRotationSequence.get() == rotation && callbackOwnerIsCurrent() }

    // Drain before resolving the push-server config so a clear that
    // failed earlier still retries even if the config is later blanked
    // or GMS is uninstalled — otherwise a stale server-side registration
    // would keep wrapping wake events for a device that can no longer
    // receive them. Only the upsert path is gated on config + GMS.
    drainPendingPushClears()
    drainPendingPushDisables()
    refreshLocalNotificationPermission()
    if (!localNotificationPermissionGranted) {
        nativePushFallback.invalidateAll()
        return false
    }
    var accountRefs = host.nativePushAccountRefs()
    if (accountRefs.isEmpty()) {
        // Only clear the durable #755 retry flag when the account list is
        // authoritative. setAppInForeground() can trigger this before
        // bootstrap/refreshAccounts() has loaded accounts; clearing then
        // would strand a signed-in device on a stale push token.
        if (marmotRuntime == null) return false
        host.refreshAccounts()
        accountRefs = host.nativePushAccountRefs()
        if (accountRefs.isEmpty()) {
            return withContext(pushWakeStorageDispatcher) {
                pushTokenStore.completeRegistrationSyncIfCurrent(ownerIsCurrent = operationIsCurrent)
            }
        }
    }
    val config = pushServerConfigProvider()
    val capability = nativePushCapability(config)
    if (!capability.isAvailable) return reconcileUnavailableNativePushFallback(capability, accountRefs)
    config ?: return false
    if (!withContext(pushWakeStorageDispatcher) { pushTokenStore.registrationAllowed() }) return false
    if (!operationIsCurrent()) return false
    return syncNativePushRegistrationForAccountsLocked(accountRefs, config, freshCallbackToken, operationIsCurrent)
}

/** The caller holds the sync mutex through every account and the atomic pending-marker acknowledgement. */
private suspend fun NotificationDeliveryController.syncNativePushRegistrationForAccountsLocked(
    accountRefs: List<String>,
    config: PushServerConfig,
    freshCallbackToken: String?,
    operationIsCurrent: () -> Boolean,
): Boolean {
    val token = tokenForNativePushSync(freshCallbackToken, operationIsCurrent) ?: return false
    val tokenIsCurrent: suspend () -> Boolean = {
        operationIsCurrent() &&
            (
                freshCallbackToken == null ||
                    withContext(pushWakeStorageDispatcher) { pushTokenStore.lastToken() == token }
            ) &&
            operationIsCurrent()
    }
    return tokenIsCurrent() &&
        accountRefs.all { account -> syncPushForAccount(account, config, token, tokenIsCurrent) } &&
        tokenIsCurrent() &&
        withContext(pushWakeStorageDispatcher) {
            pushTokenStore.completeRegistrationSyncIfCurrent(token, operationIsCurrent)
        }
}

/** Only this invocation's fresh callback can replace a failed SDK acquisition. */
private suspend fun NotificationDeliveryController.tokenForNativePushSync(
    freshCallbackToken: String?,
    operationIsCurrent: () -> Boolean,
): String? =
    fetchFcmTokenOrNull(freshCallbackToken, operationIsCurrent)
        ?: freshCallbackToken?.takeIf {
            operationIsCurrent() &&
                withContext(pushWakeStorageDispatcher) { pushTokenStore.lastToken() == it }
        }

/**
 * Registers [token] for [account] when its native push is enabled, caching the fingerprint only after a
 * settings re-read confirms it, and rolling back when a concurrent disable won. Returns whether the
 * account needs no retry.
 */
private suspend fun NotificationDeliveryController.syncPushForAccount(
    account: String,
    config: PushServerConfig,
    token: String,
    tokenIsCurrent: suspend () -> Boolean = { true },
): Boolean {
    val settings =
        runCatchingCancellable { host.marmotIo { notificationSettings(account) } }.getOrNull()
            ?: return false
    if (account == activeAccountRef) localNotificationSettings = settings
    // Skip accounts with a queued sign-out disable so a stale enabled flag can't re-register them.
    if (account in withContext(pushWakeStorageDispatcher) { pushTokenStore.pendingDisables() }) return true
    if (!settings.nativePushEnabled) return true
    val fingerprint =
        PushFingerprint(
            platform = PushPlatformFfi.FCM,
            token = token,
            serverPubkeyHex = config.serverPubkeyHex,
            relayHint = config.relayHint,
        )
    if (perAccountSyncedFingerprints[account] == fingerprint) return true
    return runCatching {
        val syncResult =
            host.marmotIo {
                if (!pushTokenStore.registrationAllowed() || !tokenIsCurrent()) throw PushTokenStorageException()
                upsertPushRegistration(
                    accountRef = account,
                    platform = PushPlatformFfi.FCM,
                    rawToken = token,
                    serverPubkeyHex = config.serverPubkeyHex,
                    relayHint = config.relayHint,
                )
            }
        logPushRegistrationShareOutcome("upsert", account, syncResult.share)
        // Re-read settings: a concurrent `setNativePushEnabled(false)` or
        // sign-out could have flipped the flag (and cleared the cache)
        // while upsertPushRegistration was suspended. If push is no
        // longer enabled, do NOT write the fingerprint back — otherwise
        // the cache restores a stale entry and the next enable
        // short-circuits without re-registering. Roll back instead.
        val settingsAfter = runCatchingCancellable { host.marmotIo { notificationSettings(account) } }.getOrNull()
        when {
            // Re-read failed: that's a transient error, not a disable —
            // the upsert itself succeeded, so keep the registration. Skip
            // the fingerprint write so the next sync re-verifies instead
            // of trusting a state we couldn't confirm.
            settingsAfter == null ->
                false.also {
                    appStateDebug {
                        "push settings re-read failed; keeping registration account=${account.take(8)}"
                    }
                }
            settingsAfter.nativePushEnabled && tokenIsCurrent() -> {
                perAccountSyncedFingerprints[account] = fingerprint
                appStateDebug { "push registration synced account=${account.take(8)}" }
                true
            }
            settingsAfter.nativePushEnabled -> false
            else -> {
                appStateDebug { "push registration raced disable; rolling back account=${account.take(8)}" }
                // Shares the pending-clear bookkeeping: a failed rollback
                // is queued for retry instead of stranding the account
                // registered server-side.
                clearPushRegistrationForAccountLocked(account)
                true
            }
        }
    }.getOrElse {
        rethrowIfCancellation(it)
        // Drop the fingerprint on failure so the next sync retries
        // rather than assuming the registration is fresh.
        perAccountSyncedFingerprints.remove(account)
        appStateDebug { "push registration sync failed: ${it.readableMessage()}" }
        false
    }
}

/** Fetches and retains a non-blank FCM token only when every push prerequisite is available. */
internal suspend fun NotificationDeliveryController.fetchFcmTokenOrNull(
    expectedCallbackToken: String? = null,
    tokenOwnerIsCurrent: () -> Boolean = { true },
): String? {
    val rotation = pushTokenRotationSequence.get()
    if (!nativePushCapability().isAvailable) return null
    val token =
        runCatchingCancellable {
            val reader: suspend () -> String? = fcmTokenReaderOverride ?: this::readFcmTokenFromSdk
            reader()
        }.onFailure {
            appStateDebug { "FCM token fetch failed: ${it.readableMessage()}" }
        }.getOrNull()
    return if (token.isNullOrBlank()) {
        null
    } else {
        val retained =
            withContext(pushWakeStorageDispatcher) {
                pushTokenStore.setTokenIfCurrent(token, expectedCallbackToken) {
                    pushTokenRotationSequence.get() == rotation && tokenOwnerIsCurrent()
                }
            }
        token.takeIf { retained }
    }
}

/** Reads the current FCM token from the Firebase SDK, or null when the task fails. */
private suspend fun NotificationDeliveryController.readFcmTokenFromSdk(): String? =
    suspendCancellableCoroutine<String?> { continuation ->
        // The Firebase Task API has no cancel surface, so the
        // completion listener can fire after this coroutine is
        // cancelled. Guard the resume on isActive so a stale
        // callback doesn't try to push a value onto a closed
        // continuation; the task completes in the background and
        // its result is dropped. The outer runCatching is a
        // belt — `getInstance()` itself can throw
        // IllegalStateException if FirebaseApp isn't initialized,
        // and we'd rather drop the token fetch than crash.
        FirebaseMessaging
            .getInstance()
            .token
            .addOnCompleteListener { task ->
                if (continuation.isActive) {
                    continuation.resume(if (task.isSuccessful) task.result else null)
                }
            }
    }
