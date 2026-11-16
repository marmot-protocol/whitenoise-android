package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NativePushCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Migrates the active account while reporting unknown or background-owned state as incomplete. */
internal suspend fun NotificationDeliveryController.reconcileUnavailableNativePushFallback(
    capability: NativePushCapability,
    accountRefs: List<String>,
): Boolean {
    val reconciled =
        nativePushFallback.reconcile(
            capability = capability,
            owner = captureNativePushFallbackOwner() ?: return false,
            accountRefs = accountRefs,
            bindings =
                NativePushFallbackBindings(
                    ownerIsCurrent = { owner ->
                        localNotificationPermissionGranted &&
                            localNotificationPresenter.canPostNotifications() &&
                            ownsNativePushFallback(owner)
                    },
                    readSettings = { request, account -> request.runtime.marmot.notificationSettings(account) },
                    publishActiveSettings = { localNotificationSettings = it },
                    publishPersistentConnectionEnabled = { backgroundConnectionEnabled = true },
                    publishComplete = pushTokenStore::clearPendingNativePushRegistrationSync,
                    removeFingerprint = { perAccountSyncedFingerprints.remove(it.accountRef) },
                    setNativePushDisabled = { it.runtime.marmot.setNativePushEnabled(it.accountRef, false) },
                    clearRegistration = this::clearPushRegistrationForOwnerLocked,
                    onFailure = { message -> appStateDebug { message } },
                ),
        )
    return reconciled
}

/** Captures the exact active account/runtime lifetime used by service readiness callbacks. */
internal fun NotificationDeliveryController.captureNativePushFallbackOwner(): NativePushFallbackOwner? =
    nativePushFallback.owner(
        activeAccountRef,
        marmotRuntime,
        runtimeGeneration,
        accountSwitchHandoff.capture(),
    )

/** Rejects suspended fallback work after an account switch or runtime replacement. */
@Suppress("MaxLineLength")
internal fun NotificationDeliveryController.ownsNativePushFallback(owner: NativePushFallbackOwner): Boolean =
    captureNativePushFallbackOwner()?.matches(owner) == true

/** Clears only [owner]'s registration on its captured runtime and retains failed cleanup for retry. */
@Suppress("MaxLineLength")
internal suspend fun NotificationDeliveryController.clearPushRegistrationForOwnerLocked(owner: NativePushFallbackOwner) {
    if (!ownsNativePushFallback(owner)) return
    currentCoroutineContext().ensureActive()
    runCatchingCancellable {
        withContext(Dispatchers.IO) { owner.runtime.marmot.clearPushRegistration(owner.accountRef) }
    }.onSuccess { outcome ->
        logPushRegistrationShareOutcome("capability fallback clear", owner.accountRef, outcome)
        if (ownsNativePushFallback(owner)) pushTokenStore.clearPending(owner.accountRef)
    }.onFailure { failure ->
        appStateDebug { "capability fallback clear failed (queued for retry): ${failure.readableMessage()}" }
    }
}
