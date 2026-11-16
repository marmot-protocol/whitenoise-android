package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/** Reuses safe capability sync for restoration and reserves device-wide cutover for a real account switch. */
internal fun NotificationDeliveryController.launchNotificationDeliveryModeReconciliation(
    owner: NotificationDeliveryModeOwner,
    mode: NotificationDeliveryMode,
    applyDeviceWideMode: Boolean,
) {
    notificationDeliveryModeBusy = true
    notificationScope.launch {
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
            finishNotificationDeliveryModeTransaction(owner.intentGeneration)
        }
    }
}

/** Clears the latest cutover and drains token work that arrived while it owned the mode lock. */
internal suspend fun NotificationDeliveryController.finishNotificationDeliveryModeTransaction(epoch: Long) =
    withContext(NonCancellable) {
        if (!notificationDeliveryModeIntent.isCurrent(epoch)) return@withContext
        notificationDeliveryModeMutex.withLock {
            val latest = notificationDeliveryModeIntent.runIfCurrent(epoch) { notificationDeliveryModeBusy = false }
            val pending =
                latest && runCatching { pushTokenStore.nativePushRegistrationSyncPending() }.getOrDefault(false)
            if (pending) syncNativePushRegistrationIfEnabled()
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
