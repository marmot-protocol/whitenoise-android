package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationSettingsFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Restores only mutations made by this transaction while no newer user choice owns delivery. */
internal suspend fun NotificationDeliveryController.rollbackNativePushMode(
    owner: NotificationDeliveryModeOwner,
    previous: Map<String, NotificationSettingsFfi>,
) {
    nativePushSyncMutex.withLock {
        if (!ownsNotificationDeliveryMode(owner)) return@withLock
        previous.forEach { (account, settings) ->
            if (!ownsNotificationDeliveryMode(owner)) return@withLock
            if (settings.nativePushEnabled && account in pushTokenStore.pendingDisables()) return@forEach
            val restored =
                runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        owner.runtime.marmot.setNativePushEnabled(account, settings.nativePushEnabled)
                    }
                }.getOrNull() ?: return@forEach
            if (account == owner.activeAccountRef && ownsNotificationDeliveryMode(owner)) {
                localNotificationSettings = restored
            }
            if (!settings.nativePushEnabled) {
                perAccountSyncedFingerprints.remove(account)
                withContext(Dispatchers.IO) { pushTokenStore.recordPendingClear(account) }
                clearPushRegistrationForNotificationDeliveryOwnerLocked(owner, account)
            }
        }
    }
}

/** Restores the prior global preference while the same account/runtime/intent still owns settlement. */
internal suspend fun NotificationDeliveryController.restorePersistentPreference(
    owner: NotificationDeliveryModeOwner,
    enabled: Boolean,
): Boolean {
    val persisted =
        withContext(NonCancellable + Dispatchers.IO) {
            nativePushFallbackPlatform.persistBackgroundConnectionEnabled(enabled) {
                ownsNotificationDeliveryMode(owner)
            }
        }
    if (!persisted || !ownsNotificationDeliveryMode(owner)) return false
    backgroundConnectionEnabled = enabled
    if (!enabled) {
        nativePushFallback.invalidateAll()
        nativePushFallbackPlatform.stopBackgroundConnection()
    }
    return true
}

/** Keeps the acknowledged Local owner once any native disable may have become durable. */
internal suspend fun NotificationDeliveryController.settleFailedPersistentDelivery(
    owner: NotificationDeliveryModeOwner,
    previousPersistent: Boolean,
    nativeDisableCommitted: Boolean,
) {
    if (!ownsNotificationDeliveryMode(owner)) return
    restorePersistentPreference(owner, enabled = previousPersistent || nativeDisableCommitted)
}

/** Restores the previous persistent side before native registration can be rolled back. */
internal suspend fun NotificationDeliveryController.settleFailedFcmDelivery(
    owner: NotificationDeliveryModeOwner,
    previousPersistent: Boolean,
    previousNative: Map<String, NotificationSettingsFfi>,
    persistentStopAttempted: Boolean,
) {
    if (!ownsNotificationDeliveryMode(owner)) return
    val persistentSettled =
        when {
            !previousPersistent -> true
            persistentStopAttempted -> establishPersistentMode(owner) != null
            else -> restorePersistentPreference(owner, enabled = true)
        }
    if (persistentSettled && ownsNotificationDeliveryMode(owner)) {
        rollbackNativePushMode(owner, previousNative)
    }
}
