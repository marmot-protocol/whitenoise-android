package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * Runs while sign-out holds the native sync lock, once [account]'s native-push disable has committed: drops
 * its pending-disable marker and settles auto-init from the loaded accounts' demand. A sign-out that then
 * fails keeps the account signed in but off Push, so auto-init must not wait for the sign-out to finish.
 */
internal suspend fun NotificationDeliveryController.settleFcmAutoInitAfterSignOutNativeDisable(account: String) {
    withContext(pushWakeStorageDispatcher) { runCatching { pushTokenStore.clearPendingDisable(account) } }
    disableFcmAutoInitWithoutLoadedNativeDemand()
}

/**
 * Runs an account [wipe] while the caller holds the native sync lock, then settles Firebase auto-init from the
 * native account list unless the wipe reports that its cleanup did not finish, which keeps the account. A wipe
 * can commit just before a cancellation surfaces, so the settle reads what committed rather than trusting the
 * result, and it also covers a wipe that leaves only accounts off native push.
 */
@Suppress("MaxLineLength")
internal suspend fun NotificationDeliveryController.wipeSettlingFcmAutoInit(wipe: suspend () -> WipeOutcomeFfi): Result<WipeOutcomeFfi> {
    val result = runCatching { wipe() }
    if (result.getOrNull()?.localCleanup?.completed != false) {
        withContext(NonCancellable) { disableFcmAutoInitWithoutNativeAccountDemand() }
    }
    return result
}

/**
 * Turns auto-init off when the native account list holds no signed-in signer on native push. Unlike the
 * loaded list, the native list is authoritative, so an empty one means no signer is left. When the list or a
 * signer's settings cannot be read, it records the durable pending registration sync instead, so the next
 * sync settles auto-init.
 */
private suspend fun NotificationDeliveryController.disableFcmAutoInitWithoutNativeAccountDemand() {
    val listed = runCatchingCancellable { host.marmotIo { listAccounts() } }.getOrNull()
    val signerRefs = listed?.filter { it.isSignedInSigningAccount() }?.map { it.label }
    val demand =
        when {
            signerRefs == null -> null
            signerRefs.isEmpty() -> NativePushDemand.None
            else -> runCatchingCancellable { nativePushDemand(signerRefs) }.getOrNull()
        }
    when (demand) {
        NativePushDemand.None -> applyFcmAutoInit(enabled = false)
        NativePushDemand.Required -> Unit
        NativePushDemand.Unknown, null -> recordPendingRegistrationSync()
    }
}

/**
 * Records the durable pending registration sync, which the next sync, at the latest on the next app start,
 * acknowledges once it has settled registration and auto-init. A storage failure is ignored.
 */
internal suspend fun NotificationDeliveryController.recordPendingRegistrationSync() {
    withContext(NonCancellable + pushWakeStorageDispatcher) {
        runCatching { pushTokenStore.recordPendingNativePushRegistrationSync() }
    }
}

/**
 * Turns native push on or off for one account, confirming an enablement only once its registration is in
 * place and rolling it back otherwise. Until it is confirmed the enable counts as uncommitted, so no sync
 * turns Firebase auto-init on for it, and only the confirmed enable does. Once a disable or a rollback has
 * been attempted, auto-init is settled from the accounts' live demand on every exit, cancellation included,
 * so the account's change never leaves auto-init on for nobody. A cancelled enable records a pending
 * registration sync, so an account left on native push is registered, and auto-init turned on, by the next
 * sync.
 */
internal suspend fun NotificationDeliveryController.setNativePushEnabledSettlingAutoInit(
    account: String,
    enabled: Boolean,
): Boolean {
    if (enabled && !nativePushCapability().isAvailable) return false
    // A native disable can commit before a cancellation surfaces, so the settle runs in finally.
    val nativeDisableAttempted = AtomicBoolean(!enabled)
    try {
        if (enabled) uncommittedNativePushEnables.incrementAndGet()
        val changed =
            try {
                setNativePushEnabledLocked(account, enabled, nativeDisableAttempted)
            } finally {
                if (enabled) uncommittedNativePushEnables.decrementAndGet()
            }
        if (enabled && changed) commitFcmAutoInitAfterEnable { true }
        return changed
    } catch (cancelled: CancellationException) {
        // A cancelled enable may have left native push on without a registration, before its rollback or
        // during it. The durable pending-sync marker makes the next sync register it, turning auto-init on
        // first. After a rollback that did commit, that sync clears it when no account needs Push.
        if (enabled) recordPendingRegistrationSync()
        throw cancelled
    } finally {
        if (nativeDisableAttempted.get()) withContext(NonCancellable) { settleFcmAutoInitAfterFailedSelection() }
    }
}

/** Writes [account]'s native-push flag and confirms or rolls back an enablement, marking any disable attempt. */
private suspend fun NotificationDeliveryController.setNativePushEnabledLocked(
    account: String,
    enabled: Boolean,
    nativeDisableAttempted: AtomicBoolean,
): Boolean =
    runCatching {
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
                // A rollback is a native disable, so auto-init is settled from live demand on exit.
                nativeDisableAttempted.set(true)
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
