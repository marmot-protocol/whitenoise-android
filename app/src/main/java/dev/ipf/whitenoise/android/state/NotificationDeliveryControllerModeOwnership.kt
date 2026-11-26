package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenStorageException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves the device mode from authoritative account settings and identifies any incomplete invariant. */
internal suspend fun NotificationDeliveryController.notificationDeliveryActivationPlan(
    owner: NotificationDeliveryModeOwner,
    includeBackgroundAccountModes: Boolean,
): NotificationDeliveryActivationPlan? {
    val settingsByAccount = readNotificationDeliverySettings(owner) ?: return null
    val anyNativeEnabled = settingsByAccount.values.any { it.nativePushEnabled }
    val mode =
        resolvedNotificationDeliveryMode(anyNativeEnabled, backgroundConnectionEnabled, nativePushCapability())
    val noTransportConfigured = !backgroundConnectionEnabled && !anyNativeEnabled
    return NotificationDeliveryActivationPlan(
        mode = mode,
        requiresDeviceWideReconciliation =
            includeBackgroundAccountModes ||
                noTransportConfigured ||
                !notificationDeliveryInvariantMatches(
                    mode,
                    settingsByAccount,
                    backgroundConnectionEnabled,
                    host.pushWakeServiceOwned,
                    perAccountSyncedFingerprints.keys,
                ),
    )
}

/** Reads every captured account without treating an unavailable setting as disabled. */
@Suppress("ReturnCount") // Missing settings, read failure, and owner invalidation abort the bounded scan.
private suspend fun NotificationDeliveryController.readNotificationDeliverySettings(
    owner: NotificationDeliveryModeOwner,
): Map<String, NotificationSettingsFfi>? {
    val settingsByAccount = linkedMapOf<String, NotificationSettingsFfi>()
    for (account in owner.accountRefs) {
        val settings =
            if (account == owner.activeAccountRef) {
                localNotificationSettings
            } else {
                runCatchingCancellable {
                    withContext(Dispatchers.IO) { owner.runtime.marmot.notificationSettings(account) }
                }.onFailure {
                    appStateDebug(it) { "notification activation settings read failed" }
                }.getOrNull()
            }
        if (!ownsNotificationDeliveryMode(owner)) return null
        settingsByAccount[account] = settings ?: return null
    }
    return settingsByAccount
}

/**
 * Reuses safe capability sync for restoration and reserves device-wide cutover for a real account switch.
 * Adopts the caller's open delivery-mode change and ends it after its finish. It starts atomically, so even a
 * job cancelled before it runs reaches that `finally`.
 */
internal fun NotificationDeliveryController.launchNotificationDeliveryModeReconciliation(
    owner: NotificationDeliveryModeOwner,
    mode: NotificationDeliveryMode,
    applyDeviceWideMode: Boolean,
) {
    notificationDeliveryModeBusy = true
    notificationScope.launch(start = CoroutineStart.ATOMIC) {
        try {
            notificationDeliveryModeMutex.withLock {
                if (!ownsNotificationDeliveryMode(owner)) return@withLock
                if (applyDeviceWideMode) {
                    if (mode == NotificationDeliveryMode.Local) nativePushFallback.invalidateAll()
                    when (mode) {
                        NotificationDeliveryMode.Fcm -> selectFcmDelivery(owner)
                        NotificationDeliveryMode.Local -> selectPersistentDelivery(owner)
                    }
                } else {
                    syncNativePushRegistrationIfEnabled()
                }
            }
        } finally {
            try {
                finishNotificationDeliveryModeTransaction(owner.intentGeneration)
            } finally {
                endDeliveryModeTransaction()
            }
        }
    }
}

/**
 * Opens a device-wide delivery-mode change: counts it in
 * [NotificationDeliveryController.activeDeliveryModeTransactions], then advances the delivery intent. It never
 * waits for a lock, so a newer choice supersedes an older change that holds the sync lock at once. Returns the
 * change's intent generation.
 */
internal fun NotificationDeliveryController.beginDeliveryModeTransaction(): Long {
    // Taken with an on write's admission check, so that write either lands first or sees this change.
    synchronized(fcmAutoInitAdmission) {
        activeDeliveryModeTransactions.incrementAndGet()
        return notificationDeliveryModeIntent.advance()
    }
}

/**
 * Ends a change opened by [beginDeliveryModeTransaction], after its finish has cleared the busy flag. The last
 * open change drains a registration sync left pending meanwhile, because a sync or enable that saw an open change
 * left auto-init to it. The count and the marker are read under the sync lock where those deferrals record it, so
 * a deferral is either seen here or happened after the count reached zero and was not deferred. A failed drain
 * keeps its durable marker for the next sync and never replaces the change's own result. Only that bookkeeping
 * ignores cancellation: the retry itself is cancellable and bounded (see [drainDeferredRegistrationSync]), and a
 * caller already cancelled, ending its change in a `finally`, schedules it instead.
 */
internal suspend fun NotificationDeliveryController.endDeliveryModeTransaction() {
    val drain =
        withContext(NonCancellable) {
            nativePushSyncMutex.withLock {
                activeDeliveryModeTransactions.decrementAndGet() == 0 &&
                    withContext(pushWakeStorageDispatcher) {
                        runCatching { pushTokenStore.nativePushRegistrationSyncPending() }.getOrDefault(false)
                    }
            }
        }
    when {
        !drain -> Unit
        currentCoroutineContext().isActive -> drainDeferredRegistrationSync()
        else -> notificationScope.launch { drainDeferredRegistrationSync() }
    }
}

/**
 * Runs the registration sync a delivery-mode change deferred, cancellably and for at most
 * [NotificationDeliveryController.deferredRegistrationDrainTimeoutMs], so a stalled Firebase token task cannot keep
 * the native sync lock from sign-out or wipe. A failed, cancelled or timed-out retry keeps its durable marker.
 */
private suspend fun NotificationDeliveryController.drainDeferredRegistrationSync() {
    withTimeoutOrNull(deferredRegistrationDrainTimeoutMs) {
        runCatchingCancellable { syncNativePushRegistrationIfEnabled() }
    }
}

/**
 * Clears the busy flag of the latest cutover. Token work that arrived while it owned the mode lock left its durable
 * marker, which the last change's [endDeliveryModeTransaction] drains, cancellably and bounded, so a stalled Firebase
 * task can never hold the mode and native sync locks here.
 */
internal suspend fun NotificationDeliveryController.finishNotificationDeliveryModeTransaction(epoch: Long) =
    withContext(NonCancellable) {
        if (!notificationDeliveryModeIntent.isCurrent(epoch)) return@withContext
        notificationDeliveryModeMutex.withLock {
            notificationDeliveryModeIntent.runIfCurrent(epoch) { notificationDeliveryModeBusy = false }
        }
    }

/** Captures every lifetime that can invalidate a device-wide delivery cutover. */
@Suppress("MaxLineLength", "ReturnCount")
internal fun NotificationDeliveryController.captureNotificationDeliveryModeOwner(intentGeneration: Long): NotificationDeliveryModeOwner? {
    val account = activeAccountRef ?: return null
    val runtime = marmotRuntime ?: return null
    val accountRefs = host.nativePushAccountRefs()
    if (account !in accountRefs) return null
    return NotificationDeliveryModeOwner(
        activeAccountRef = account,
        accountRefs = accountRefs,
        runtime = runtime,
        runtimeGeneration = runtimeGeneration,
        accountSwitchGeneration = accountSwitchHandoff.capture(),
        intentGeneration = intentGeneration,
    )
}

/** Rejects account switches (including A-B-A), sign-in changes, runtime replacement, and newer intent. */
@Suppress("MaxLineLength")
internal fun NotificationDeliveryController.ownsNotificationDeliveryMode(owner: NotificationDeliveryModeOwner): Boolean =
    notificationDeliveryModeIntent.isCurrent(owner.intentGeneration) &&
        activeAccountRef == owner.activeAccountRef &&
        marmotRuntime === owner.runtime &&
        runtimeGeneration == owner.runtimeGeneration &&
        accountSwitchHandoff.isCurrent(owner.accountSwitchGeneration) &&
        host.nativePushAccountRefs() == owner.accountRefs

/** Registers one account using the runtime captured by the surrounding mode transaction. */
@Suppress("ReturnCount")
internal suspend fun NotificationDeliveryController.syncPushForNotificationDeliveryOwner(
    owner: NotificationDeliveryModeOwner,
    account: String,
    config: PushServerConfig,
    token: String,
): Boolean {
    val fingerprint =
        PushFingerprint(
            platform = PushPlatformFfi.FCM,
            token = token,
            serverPubkeyHex = config.serverPubkeyHex,
            relayHint = config.relayHint,
        )
    if (perAccountSyncedFingerprints[account] == fingerprint) return ownsNotificationDeliveryMode(owner)
    val outcome =
        withContext(Dispatchers.IO) {
            if (!pushTokenStore.registrationAllowed()) throw PushTokenStorageException()
            owner.runtime.marmot.upsertPushRegistration(
                accountRef = account,
                platform = PushPlatformFfi.FCM,
                rawToken = token,
                serverPubkeyHex = config.serverPubkeyHex,
                relayHint = config.relayHint,
            )
        }
    logPushRegistrationShareOutcome("delivery mode upsert", account, outcome.share)
    if (!ownsNotificationDeliveryMode(owner)) return false
    val settingsAfter =
        withContext(Dispatchers.IO) {
            owner.runtime.marmot.notificationSettings(account)
        }
    if (!ownsNotificationDeliveryMode(owner) || !settingsAfter.nativePushEnabled) return false
    perAccountSyncedFingerprints[account] = fingerprint
    return true
}

/** Clears one registration while the caller holds [nativePushSyncMutex]. */
internal suspend fun NotificationDeliveryController.clearPushRegistrationForNotificationDeliveryOwnerLocked(
    owner: NotificationDeliveryModeOwner,
    account: String,
) {
    val result =
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                owner.runtime.marmot.clearPushRegistration(account)
            }
        }
    result.onSuccess { outcome ->
        logPushRegistrationShareOutcome("delivery mode clear", account, outcome)
        if (ownsNotificationDeliveryMode(owner)) {
            withContext(Dispatchers.IO) { pushTokenStore.clearPending(account) }
        }
    }
    result.exceptionOrNull()?.let { failure ->
        appStateDebug(failure) { "delivery mode registration clear remains pending" }
    }
}
