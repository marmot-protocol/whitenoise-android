package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Applies the transport and enables rendering only for an explicit selection. */
internal suspend fun NotificationDeliveryController.applyMode(
    mode: NotificationDeliveryMode,
    enableRendering: Boolean,
): Boolean {
    val intentGeneration = notificationDeliveryModeIntent.advance()
    if (mode == NotificationDeliveryMode.Local) nativePushFallback.invalidateAll()
    activeDeliveryModeTransactions.incrementAndGet()
    notificationDeliveryModeBusy = true
    return try {
        notificationDeliveryModeMutex.withLock {
            if (!notificationDeliveryModeIntent.isCurrent(intentGeneration)) return@withLock false
            refreshLocalNotificationPermission()
            if (!localNotificationPermissionGranted) {
                host.present(R.string.toast_notification_permission_needed)
                return@withLock false
            }
            val owner = captureNotificationDeliveryModeOwner(intentGeneration) ?: return@withLock false
            if (enableRendering && !ensureNotificationRenderingEnabled(owner)) return@withLock false
            when (mode) {
                NotificationDeliveryMode.Fcm -> selectFcmDelivery(owner, explicit = true)
                NotificationDeliveryMode.Local -> selectPersistentDelivery(owner)
            }
        }
    } finally {
        activeDeliveryModeTransactions.decrementAndGet()
        finishNotificationDeliveryModeTransaction(intentGeneration)
    }
}

/** Enables the shared rendering prerequisite while the delivery intent and account stay current. */
@Suppress("MaxLineLength")
private suspend fun NotificationDeliveryController.ensureNotificationRenderingEnabled(owner: NotificationDeliveryModeOwner): Boolean {
    val alreadyEnabled = localNotificationSettings?.localNotificationsEnabled == true
    return when {
        alreadyEnabled -> ownsNotificationDeliveryMode(owner)
        else -> {
            val settings =
                runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        owner.runtime.marmot.setLocalNotificationsEnabled(owner.activeAccountRef, true)
                    }
                }.onFailure {
                    host.presentFailure(R.string.toast_couldnt_enable_notifications, "NOTIFICATION_ENABLE", it)
                }.getOrNull()
            val stillOwned = settings != null && ownsNotificationDeliveryMode(owner)
            settings?.takeIf { stillOwned }?.let { localNotificationSettings = it }
            stillOwned && settings?.localNotificationsEnabled == true
        }
    }
}

/**
 * Selects native push for every notification-enabled account. Firebase auto-init is turned on only once the
 * whole cutover has committed, since turning it on starts the SDK's background token sync, which no later off
 * write can cancel. A selection that does not complete, including a cancelled one, settles auto-init instead.
 */
internal suspend fun NotificationDeliveryController.selectFcmDelivery(
    owner: NotificationDeliveryModeOwner,
    explicit: Boolean = false,
): Boolean {
    // runCatching also holds a cancellation, so auto-init is settled before getOrThrow rethrows it.
    uncommittedNativePushEnables.incrementAndGet()
    val selected =
        try {
            runCatching { selectFcmTransport(owner, explicit) }
        } finally {
            uncommittedNativePushEnables.decrementAndGet()
        }
    val settled =
        withContext(NonCancellable) {
            if (selected.getOrNull() == true) {
                commitFcmAutoInitAfterEnable { ownsNotificationDeliveryMode(owner) }
            } else {
                settleFcmAutoInitAfterFailedSelection()
                true
            }
        }
    // A committed cutover that could neither settle auto-init nor store its retry does not report success.
    return selected.getOrThrow() && settled
}

/** Registers every notification-enabled account before the device-wide persistent path is stopped. */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
private suspend fun NotificationDeliveryController.selectFcmTransport(
    owner: NotificationDeliveryModeOwner,
    explicit: Boolean,
): Boolean {
    val config = pushServerConfigProvider() ?: return false
    if (!nativePushCapability(config).isAvailable) return false
    if (explicit && !clearExplicitPendingDisable(owner)) return false
    val previousPersistent = backgroundConnectionEnabled
    val previous = Collections.synchronizedMap(linkedMapOf<String, NotificationSettingsFfi>())
    val persistentStopAttempted = AtomicBoolean(false)
    return try {
        if (!withContext(pushWakeStorageDispatcher) { pushTokenStore.registrationAllowed() }) return false
        val token = fetchFcmTokenForOwner(owner) ?: return false
        val registered = enableNativeDeliveryForAccounts(owner, config, token, previous)
        if (!registered) {
            rollbackNativePushMode(owner, previous)
            return false
        }
        val preferenceStopped =
            withContext(Dispatchers.IO) {
                nativePushFallbackPlatform.persistBackgroundConnectionEnabled(false) {
                    ownsNotificationDeliveryMode(owner)
                }
            }
        if (!preferenceStopped || !ownsNotificationDeliveryMode(owner)) {
            settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
            return false
        }
        backgroundConnectionEnabled = false
        nativePushFallback.invalidateAll()
        val persistentStopped =
            withContext(Dispatchers.IO) {
                persistentStopAttempted.set(true)
                nativePushFallbackPlatform.stopBackgroundConnection()
            }
        if (!persistentStopped || !ownsNotificationDeliveryMode(owner)) {
            settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
            false
        } else {
            true
        }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
        }
        throw cancelled
    } catch (failure: Throwable) {
        appStateDebug(failure) { "native notification selection failed" }
        withContext(NonCancellable) {
            settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
        }
        false
    }
}

/** Drops [owner]'s queued native-push disable, reporting whether it was cleared while the owner stays current. */
@Suppress("MaxLineLength")
private suspend fun NotificationDeliveryController.clearExplicitPendingDisable(owner: NotificationDeliveryModeOwner): Boolean {
    val cleared =
        nativePushSyncMutex.withLock {
            withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPendingDisable(owner.activeAccountRef) }
        }
    return cleared && ownsNotificationDeliveryMode(owner)
}

/**
 * Registers at most three accounts together while the native sync lock fences other mutations. It leaves
 * Firebase auto-init alone: the selection can still roll back after this, so only the committed cutover
 * turns it on.
 */
private suspend fun NotificationDeliveryController.enableNativeDeliveryForAccounts(
    owner: NotificationDeliveryModeOwner,
    config: PushServerConfig,
    token: String,
    previous: MutableMap<String, NotificationSettingsFfi>,
): Boolean =
    nativePushSyncMutex.withLock {
        val orderedAccounts =
            listOf(owner.activeAccountRef) + owner.accountRefs.filterNot { it == owner.activeAccountRef }
        val configure: suspend (String) -> Boolean = { account ->
            configureNativeDeliveryForAccount(owner, account, config, token, previous)
        }
        orderedAccounts.chunked(FCM_REGISTRATION_BATCH_SIZE).all { batch ->
            val ready =
                coroutineScope {
                    batch
                        .map { async { configure(it) } }
                        .awaitAll()
                        .all { it }
                }
            ready && ownsNotificationDeliveryMode(owner)
        }
    }

/** Applies one account's authoritative notification preference and confirms its registration. */
@Suppress("ReturnCount")
private suspend fun NotificationDeliveryController.configureNativeDeliveryForAccount(
    owner: NotificationDeliveryModeOwner,
    account: String,
    config: PushServerConfig,
    token: String,
    previous: MutableMap<String, NotificationSettingsFfi>,
): Boolean {
    if (!ownsNotificationDeliveryMode(owner)) return false
    val settings = withContext(Dispatchers.IO) { owner.runtime.marmot.notificationSettings(account) }
    if (!ownsNotificationDeliveryMode(owner)) return false
    previous[account] = settings
    val desiredNative = settings.localNotificationsEnabled
    if (desiredNative && account in pushTokenStore.pendingDisables()) return false
    val updated =
        if (settings.nativePushEnabled == desiredNative) {
            settings
        } else {
            withContext(Dispatchers.IO) {
                owner.runtime.marmot.setNativePushEnabled(account, desiredNative)
            }
        }
    if (!ownsNotificationDeliveryMode(owner) || updated.nativePushEnabled != desiredNative) return false
    if (account == owner.activeAccountRef) localNotificationSettings = updated
    if (!desiredNative) return true
    return syncPushForNotificationDeliveryOwner(owner, account, config, token)
}

/**
 * Selects persistent delivery for every signed-in account. If it does not complete after a native-push
 * disable committed, including on cancellation, Firebase auto-init is settled from the accounts' live
 * demand, since the off write at the end of the cutover may never run.
 */
@Suppress("MaxLineLength")
internal suspend fun NotificationDeliveryController.selectPersistentDelivery(owner: NotificationDeliveryModeOwner): Boolean {
    val nativeDisableCommitted = AtomicBoolean(false)
    // runCatching also holds a cancellation, so the settle runs before getOrThrow rethrows it.
    val selected = runCatching { selectPersistentTransport(owner, nativeDisableCommitted) }
    if (selected.getOrNull() != true && nativeDisableCommitted.get()) {
        withContext(NonCancellable) { settleFcmAutoInitAfterFailedSelection() }
    }
    return selected.getOrThrow()
}

/** Waits for the exact foreground-service acknowledgement before disabling native delivery. */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
private suspend fun NotificationDeliveryController.selectPersistentTransport(
    owner: NotificationDeliveryModeOwner,
    nativeDisableCommitted: AtomicBoolean,
): Boolean {
    val previousPersistent = backgroundConnectionEnabled
    return try {
        val fallbackOwner =
            establishPersistentMode(owner) ?: run {
                restorePersistentPreference(owner, previousPersistent)
                return false
            }
        val disabled = disableNativePushForPersistentDelivery(owner, fallbackOwner, nativeDisableCommitted)
        if (!disabled && ownsNotificationDeliveryMode(owner)) {
            // Persistent delivery remains established when a per-account mutation fails; the
            // unresolved account can be retried without creating a no-transport window.
            return false
        }
        disabled
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            settleFailedPersistentDelivery(owner, previousPersistent, nativeDisableCommitted.get())
        }
        throw cancelled
    } catch (failure: Throwable) {
        appStateDebug(failure) { "persistent notification selection failed" }
        settleFailedPersistentDelivery(owner, previousPersistent, nativeDisableCommitted.get())
        false
    }
}

/** Returns the exact acknowledged service owner or leaves both transports unchanged on rejection. */
@Suppress("MaxLineLength")
internal suspend fun NotificationDeliveryController.establishPersistentMode(modeOwner: NotificationDeliveryModeOwner): NativePushFallbackOwner? {
    val owner = captureNativePushFallbackOwner() ?: return null
    val ready =
        nativePushFallback.establishPersistentDelivery(
            owner = owner,
            ownerIsCurrent = {
                ownsNotificationDeliveryMode(modeOwner) && ownsNativePushFallback(owner)
            },
            publishEnabled = {
                if (ownsNotificationDeliveryMode(modeOwner) && ownsNativePushFallback(owner)) {
                    backgroundConnectionEnabled = true
                }
            },
            onFailure = { failure ->
                appStateDebug(failure) { "persistent notification delivery failed" }
            },
        )
    return owner.takeIf {
        ready &&
            ownsNotificationDeliveryMode(modeOwner) &&
            ownsNativePushFallback(owner)
    }
}

/** Disables native delivery for every captured signed-in account, then Firebase auto-init, behind the service. */
@Suppress("ReturnCount")
private suspend fun NotificationDeliveryController.disableNativePushForPersistentDelivery(
    modeOwner: NotificationDeliveryModeOwner,
    fallbackOwner: NativePushFallbackOwner,
    nativeDisableCommitted: AtomicBoolean,
): Boolean {
    return nativePushSyncMutex.withLock {
        for (account in modeOwner.accountRefs) {
            if (!ownsNotificationDeliveryMode(modeOwner) || !nativePushFallback.isReady(fallbackOwner)) {
                return@withLock false
            }
            val cleanupQueued =
                withContext(Dispatchers.IO) {
                    nativePushFallbackPlatform.recordPendingRegistrationClear(account)
                }
            if (!cleanupQueued || !ownsNotificationDeliveryMode(modeOwner)) return@withLock false
            perAccountSyncedFingerprints.remove(account)
            val settings =
                withContext(Dispatchers.IO) {
                    modeOwner.runtime.marmot.setNativePushEnabled(account, false).also { updated ->
                        if (!updated.nativePushEnabled) nativeDisableCommitted.set(true)
                    }
                }
            if (!ownsNotificationDeliveryMode(modeOwner) || settings.nativePushEnabled) return@withLock false
            if (account == modeOwner.activeAccountRef) localNotificationSettings = settings
            clearPushRegistrationForNotificationDeliveryOwnerLocked(modeOwner, account)
            if (!ownsNotificationDeliveryMode(modeOwner)) return@withLock false
        }
        // Every signed-in account left native push, so Firebase must stop refreshing its token.
        applyFcmAutoInit(enabled = false)
        true
    }
}
