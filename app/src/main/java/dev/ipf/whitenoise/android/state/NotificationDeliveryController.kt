package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.messaging.FirebaseMessaging
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationBatteryPolicy
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenStorageException
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import dev.ipf.whitenoise.android.notifications.readNotificationBatteryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val FCM_REGISTRATION_BATCH_SIZE = 3

/**
 * Host surface the notification controller needs from [WhiteNoiseAppState]: account and runtime
 * identity for staleness checks, Marmot access, and user-facing failure presentation.
 */
internal interface NotificationDeliveryHost {
    val activeAccountRef: String?
    val marmotRuntime: AppMarmotRuntime?
    val runtimeGeneration: Int
    val accountSwitchHandoff: AccountSwitchLocalSnapshotHandoff
    val signOutInProgress: Boolean
    val wipeInProgress: Boolean
    val pushWakeServiceOwned: Boolean

    /** Signed-in signing accounts whose setup is eligible for native push registration. */
    fun nativePushAccountRefs(): List<String>

    suspend fun refreshAccounts()

    suspend fun <T> marmotIo(block: suspend MarmotInterface.() -> T): T

    suspend fun <T> withAccountLifetime(block: suspend () -> T): T

    fun present(
        @StringRes titleRes: Int,
    )

    fun present(
        @StringRes titleRes: Int,
        @StringRes detailRes: Int,
        copyable: Boolean,
    )

    fun presentTransient(
        @StringRes titleRes: Int,
    )

    fun presentFailure(
        @StringRes titleRes: Int,
        operationCode: String,
        throwable: Throwable,
    )
}

/**
 * Owns local notification settings, the notification delivery mode (rendering, foreground
 * connection, native push), native push registration sync and the native-push fallback runtime.
 *
 * Lock order is [notificationDeliveryModeMutex] before [nativePushSyncMutex]. Every async
 * continuation re-checks account, runtime and switch identity through [host] before committing.
 */
internal class NotificationDeliveryController(
    private val appContext: Context,
    private val marmotIoDispatcher: CoroutineDispatcher,
    private val pushWakeStorageDispatcher: CoroutineDispatcher,
    private val pushServerConfigProvider: () -> PushServerConfig?,
    private val fcmTokenReaderOverride: (suspend () -> String?)?,
    private val nativePushCapabilityResolver: (PushServerConfig?) -> NativePushCapability,
    private val nativePushFallbackPlatform: NativePushFallbackPlatform,
    private val pushTokenStore: PushTokenStore,
    private val localNotificationPresenter: LocalNotificationPresenter,
    private val notificationScope: CoroutineScope,
    private val host: NotificationDeliveryHost,
) {
    internal val nativePushSyncMutex = Mutex()
    private val pushTokenRotationSequence = AtomicLong()
    internal val nativePushFallback = NativePushFallbackCoordinator(nativePushFallbackPlatform)
    internal val notificationDeliveryModeMutex = Mutex()
    internal val notificationDeliveryModeIntent = StalenessGuard()

    // Per-account (platform, token, server-pubkey, relay-hint) fingerprint
    // of the most recent successful `upsertPushRegistration`. Skip redundant
    // FFI calls when nothing has changed across foreground/token-rotation/
    // account-bind events. Keyed per account so multi-account devices keep a
    // working registration on every enabled account, not just the active
    // one. An entry is removed when the corresponding account disables
    // native push, signs out, or hits a sync failure that may indicate the
    // registration is stale.
    internal val perAccountSyncedFingerprints = mutableMapOf<String, PushFingerprint>()

    var localNotificationSettings by mutableStateOf<NotificationSettingsFfi?>(null)
        internal set

    var localNotificationPermissionGranted by mutableStateOf(localNotificationPresenter.canPostNotifications())
        private set

    var backgroundConnectionEnabled by mutableStateOf(BackgroundConnectionPreferences.isEnabled(appContext))
        private set

    var notificationDeliveryModeBusy by mutableStateOf(false)
        private set

    var notificationBatteryPolicy by mutableStateOf(NotificationBatteryPolicy.Unknown)
        internal set

    private val activeAccountRef get() = host.activeAccountRef
    private val marmotRuntime get() = host.marmotRuntime
    private val runtimeGeneration get() = host.runtimeGeneration
    private val accountSwitchHandoff get() = host.accountSwitchHandoff
    private val signOutInProgress get() = host.signOutInProgress
    private val wipeInProgress get() = host.wipeInProgress

    suspend fun setLocalNotificationsEnabled(enabled: Boolean): Boolean {
        val account = activeAccountRef ?: return false
        if (!enabled) nativePushFallback.invalidateAll()
        refreshLocalNotificationPermission()
        if (enabled && !localNotificationPermissionGranted) {
            host.present(R.string.toast_notification_permission_needed)
            return false
        }
        return runCatching {
            val settings = host.marmotIo { setLocalNotificationsEnabled(account, enabled) }
            localNotificationSettings = settings
            if (!enabled) {
                updateBackgroundConnectionPreference(false)
                NotificationStreamForegroundService.stop(appContext)
            }
            appStateDebug {
                "local notifications account=${account.take(8)} enabled=${settings.localNotificationsEnabled} permission=$localNotificationPermissionGranted"
            }
            host.presentTransient(
                if (enabled) {
                    R.string.toast_local_notifications_enabled
                } else {
                    R.string.toast_local_notifications_disabled
                },
            )
            true
        }.getOrElse {
            rethrowIfCancellation(it)
            host.presentFailure(R.string.toast_couldnt_update_notifications, "NOTIFICATION_SETTINGS_UPDATE", it)
            false
        }
    }

    /** Projects the single delivery choice from the existing native and global runtime truths. */
    internal fun notificationDeliveryMode(): NotificationDeliveryMode =
        notificationDeliveryMode(
            settings = localNotificationSettings,
            persistentConnectionEnabled = backgroundConnectionEnabled,
            nativePushCapability = nativePushCapability(),
        )

    /** Refreshes Android's live battery policy only on an explicit lifecycle or UI edge. */
    fun refreshNotificationBatteryPolicy() {
        notificationBatteryPolicy = readNotificationBatteryPolicy(appContext)
    }

    /**
     * Applies one latest-wins delivery choice. Replacement delivery is confirmed before the prior
     * path is disabled, and the existing native/global settings remain the only durable truth.
     */
    internal suspend fun setNotificationDeliveryMode(mode: NotificationDeliveryMode): Boolean = applyMode(mode, true)

    /** Applies the transport and enables rendering only for an explicit selection. */
    private suspend fun applyMode(
        mode: NotificationDeliveryMode,
        enableRendering: Boolean,
    ): Boolean {
        val intentGeneration = notificationDeliveryModeIntent.advance()
        if (mode == NotificationDeliveryMode.Local) nativePushFallback.invalidateAll()
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
            finishNotificationDeliveryModeTransaction(intentGeneration)
        }
    }

    /** Enables the shared rendering prerequisite while the delivery intent and account stay current. */
    private suspend fun ensureNotificationRenderingEnabled(owner: NotificationDeliveryModeOwner): Boolean {
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

    /** Registers every notification-enabled account before the device-wide persistent path is stopped. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun selectFcmDelivery(
        owner: NotificationDeliveryModeOwner,
        explicit: Boolean = false,
    ): Boolean {
        val config = pushServerConfigProvider() ?: return false
        if (!nativePushCapability(config).isAvailable) return false
        if (explicit && !clearExplicitPendingDisable(owner)) return false
        val previousPersistent = backgroundConnectionEnabled
        val previous = Collections.synchronizedMap(linkedMapOf<String, NotificationSettingsFfi>())
        val persistentStopAttempted = AtomicBoolean(false)
        return try {
            if (!withContext(pushWakeStorageDispatcher) { pushTokenStore.registrationAllowed() }) return false
            val token = fetchFcmTokenOrNull() ?: return false
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

    private suspend fun clearExplicitPendingDisable(owner: NotificationDeliveryModeOwner): Boolean {
        val cleared =
            nativePushSyncMutex.withLock {
                withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPendingDisable(owner.activeAccountRef) }
            }
        return cleared && ownsNotificationDeliveryMode(owner)
    }

    /** Registers at most three accounts together while the native sync lock fences other mutations. */
    private suspend fun enableNativeDeliveryForAccounts(
        owner: NotificationDeliveryModeOwner,
        config: PushServerConfig,
        token: String,
        previous: MutableMap<String, NotificationSettingsFfi>,
    ): Boolean =
        nativePushSyncMutex.withLock {
            val orderedAccounts =
                listOf(owner.activeAccountRef) + owner.accountRefs.filterNot { it == owner.activeAccountRef }
            orderedAccounts.chunked(FCM_REGISTRATION_BATCH_SIZE).all { batch ->
                val ready =
                    coroutineScope {
                        batch
                            .map { async { configureNativeDeliveryForAccount(owner, it, config, token, previous) } }
                            .awaitAll()
                            .all { it }
                    }
                ready && ownsNotificationDeliveryMode(owner)
            }
        }

    /** Applies one account's authoritative notification preference and confirms its registration. */
    @Suppress("ReturnCount")
    private suspend fun configureNativeDeliveryForAccount(
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
        return !desiredNative || syncPushForNotificationDeliveryOwner(owner, account, config, token)
    }

    /** Waits for the exact foreground-service acknowledgement before disabling native delivery. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun selectPersistentDelivery(owner: NotificationDeliveryModeOwner): Boolean {
        val previousPersistent = backgroundConnectionEnabled
        val nativeDisableCommitted = AtomicBoolean(false)
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
    private suspend fun establishPersistentMode(modeOwner: NotificationDeliveryModeOwner): NativePushFallbackOwner? {
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

    /** Disables native delivery for every captured signed-in account behind the acknowledged service. */
    @Suppress("ReturnCount")
    private suspend fun disableNativePushForPersistentDelivery(
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
            true
        }
    }

    /**
     * Repairs legacy dual-on and capability-loss states without changing rendering preferences.
     * Capability restoration never changes a settled persistent-delivery choice.
     */
    internal suspend fun reconcileNotificationDeliveryMode(): Boolean {
        refreshLocalNotificationPermission()
        val settings = localNotificationSettings
        if (!localNotificationPermissionGranted) return pauseNotificationDeliveryReconciliation()
        return if (settings == null) {
            syncNativePushRegistrationIfEnabled()
        } else {
            val desired =
                if (settings.nativePushEnabled && !backgroundConnectionEnabled && nativePushCapability().isAvailable) {
                    NotificationDeliveryMode.Fcm
                } else {
                    NotificationDeliveryMode.Local
                }
            applyMode(desired, enableRendering = false)
        }
    }

    /** Reconciles the captured owner without changing its rendering preference or blocking activation. */
    @Suppress("ReturnCount") // Permission and owner-invalidated paths terminate before transport reconciliation.
    internal suspend fun reconcileNotificationDeliveryModeAfterActivation(
        includeBackgroundAccountModes: Boolean,
    ): Boolean {
        refreshLocalNotificationPermission()
        val settings = localNotificationSettings
        if (!localNotificationPermissionGranted) return pauseNotificationDeliveryReconciliation()
        if (settings == null) return syncNativePushRegistrationIfEnabled()
        val intentGeneration = notificationDeliveryModeIntent.advance()
        var handedOff = false
        return try {
            notificationDeliveryModeMutex.withLock {
                if (!notificationDeliveryModeIntent.isCurrent(intentGeneration)) return@withLock false
                val owner = captureNotificationDeliveryModeOwner(intentGeneration) ?: return@withLock false
                val plan = notificationDeliveryActivationPlan(owner, includeBackgroundAccountModes)
                if (!ownsNotificationDeliveryMode(owner)) return@withLock false
                if (plan == null) return@withLock false
                if (!plan.requiresDeviceWideReconciliation) return@withLock true
                launchNotificationDeliveryModeReconciliation(
                    owner = owner,
                    mode = plan.mode,
                    applyDeviceWideMode = true,
                )
                handedOff = true
                true
            }
        } finally {
            if (!handedOff) finishNotificationDeliveryModeTransaction(intentGeneration)
        }
    }

    /** Resolves the device mode from authoritative account settings and identifies any incomplete invariant. */
    private suspend fun notificationDeliveryActivationPlan(
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
    private suspend fun readNotificationDeliverySettings(
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
    private fun launchNotificationDeliveryModeReconciliation(
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
    private suspend fun finishNotificationDeliveryModeTransaction(epoch: Long) =
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
    @Suppress("ReturnCount")
    private fun captureNotificationDeliveryModeOwner(intentGeneration: Long): NotificationDeliveryModeOwner? {
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
    private fun ownsNotificationDeliveryMode(owner: NotificationDeliveryModeOwner): Boolean =
        notificationDeliveryModeIntent.isCurrent(owner.intentGeneration) &&
            activeAccountRef == owner.activeAccountRef &&
            marmotRuntime === owner.runtime &&
            runtimeGeneration == owner.runtimeGeneration &&
            accountSwitchHandoff.isCurrent(owner.accountSwitchGeneration) &&
            host.nativePushAccountRefs() == owner.accountRefs

    /** Registers one account using the runtime captured by the surrounding mode transaction. */
    @Suppress("ReturnCount")
    private suspend fun syncPushForNotificationDeliveryOwner(
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
    private suspend fun clearPushRegistrationForNotificationDeliveryOwnerLocked(
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

    /** Restores only mutations made by this transaction while no newer user choice owns delivery. */
    private suspend fun rollbackNativePushMode(
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
    private suspend fun restorePersistentPreference(
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
    private suspend fun settleFailedPersistentDelivery(
        owner: NotificationDeliveryModeOwner,
        previousPersistent: Boolean,
        nativeDisableCommitted: Boolean,
    ) {
        if (!ownsNotificationDeliveryMode(owner)) return
        restorePersistentPreference(owner, enabled = previousPersistent || nativeDisableCommitted)
    }

    /** Restores the previous persistent side before native registration can be rolled back. */
    private suspend fun settleFailedFcmDelivery(
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

    suspend fun setBackgroundConnectionEnabled(enabled: Boolean): Boolean {
        val account =
            activeAccountRef ?: run {
                host.present(R.string.toast_no_active_account)
                return false
            }
        refreshLocalNotificationPermission()
        if (enabled && !localNotificationPermissionGranted) {
            host.present(R.string.toast_notification_permission_needed)
            return false
        }
        if (enabled && localNotificationSettings?.localNotificationsEnabled != true) {
            val settings =
                runCatching {
                    host.marmotIo { setLocalNotificationsEnabled(account, true) }
                }.getOrElse {
                    rethrowIfCancellation(it)
                    host.presentFailure(R.string.toast_couldnt_enable_notifications, "NOTIFICATION_ENABLE", it)
                    return false
                }
            localNotificationSettings = settings
        }

        updateBackgroundConnectionPreference(enabled)
        val serviceUpdated =
            if (enabled) {
                startBackgroundConnectionService()
            } else {
                NotificationStreamForegroundService.stop(appContext)
            }
        if (enabled && !serviceUpdated) {
            updateBackgroundConnectionPreference(false)
            host.present(
                R.string.toast_couldnt_keep_connected,
                R.string.toast_android_blocked_foreground_service,
                copyable = true,
            )
            return false
        }
        host.presentTransient(
            if (enabled) {
                R.string.toast_background_connection_enabled
            } else {
                R.string.toast_background_connection_disabled
            },
        )
        return true
    }

    /**
     * Reconcile the background-connection preference when the foreground
     * service fails to come up. `start()` returns true the moment the start
     * intent is *queued*, before `onStartCommand` runs — so an Android 14+
     * foreground-start rejection inside the service can't be seen by the
     * enable path and would otherwise leave the toggle stuck "on" while no
     * service runs. The service calls this from its failure branch so the
     * UI reflects reality and surfaces the same toast as the synchronous
     * rejection path, for every entry point (toggle, app-foreground, resume).
     * Main-thread only (invoked from `onStartCommand`). See #164.
     */
    fun onBackgroundConnectionStartRejected() {
        assertMainThread { "onBackgroundConnectionStartRejected" }
        if (!backgroundConnectionEnabled) return
        updateBackgroundConnectionPreference(false)
        host.present(
            R.string.toast_couldnt_keep_connected,
            R.string.toast_android_blocked_foreground_service,
            copyable = true,
        )
    }

    /** Reconcile a user-owned foreground connection after supervised runtime retries exhaust. */
    fun onBackgroundConnectionRuntimeExhausted() {
        assertMainThread { "onBackgroundConnectionRuntimeExhausted" }
        if (!backgroundConnectionEnabled) return
        updateBackgroundConnectionPreference(false)
        appStateDebug { "background connection runtime retries exhausted" }
    }

    /** Returns the first unmet native-push prerequisite without reaching later SDKs. */
    @Suppress("MaxLineLength")
    internal fun nativePushCapability(config: PushServerConfig? = pushServerConfigProvider()): NativePushCapability = nativePushCapabilityResolver(config)

    /**
     * Persist the FCM token and trigger a re-sync against the runtime. Called
     * by [dev.ipf.whitenoise.android.notifications.MarmotFirebaseMessagingService] on
     * every Firebase token rotation. The sync coroutine no-ops if any
     * precondition is missing, so the call is safe at any point in the app
     * lifecycle. Returns the re-sync job, or null if no rotation was queued.
     */
    fun onPushTokenRotated(token: String): Job? {
        if (token.isBlank()) return null
        val rotation = pushTokenRotationSequence.incrementAndGet()
        val runtime = marmotRuntime
        val generation = runtimeGeneration
        val account = activeAccountRef
        val accountSwitch = accountSwitchHandoff.capture()
        val ownerIsCurrent = {
            pushTokenRotationSequence.get() == rotation &&
                marmotRuntime === runtime &&
                runtimeGeneration == generation &&
                activeAccountRef == account &&
                accountSwitchHandoff.isCurrent(accountSwitch) &&
                !signOutInProgress &&
                !wipeInProgress
        }
        val retained =
            try {
                pushTokenStore.setTokenIfCurrent(token) { pushTokenRotationSequence.get() == rotation }
            } catch (_: PushTokenStorageException) {
                android.util.Log.w("PushTokenStorage", "token_rotation_deferred")
                false
            }
        return if (retained) {
            notificationScope.launch {
                val recorded =
                    withContext(pushWakeStorageDispatcher) {
                        observePushState {
                            pushTokenStore.recordPendingNativePushRegistrationSync()
                            true
                        }
                    }
                if (recorded != true) return@launch
                notificationDeliveryModeMutex.withLock {
                    if (!notificationDeliveryModeBusy) {
                        if (ownerIsCurrent()) syncNativePushRegistration(token, ownerIsCurrent)
                    }
                }
            }
        } else {
            null
        }
    }

    /**
     * Push the current FCM token to the runtime for every signed-in account
     * that requires native delivery, including background accounts so their
     * pushes can wake the device while another account is active.
     * Idempotent per account: a successful sync caches the
     * (token, server, relay) fingerprint and skips on the next call until
     * something changes. Returns true only when every account that currently
     * requires native push is registered (or does not require registration).
     */
    suspend fun syncNativePushRegistrationIfEnabled(): Boolean = syncNativePushRegistration()

    /** A callback token is ephemeral authority for this rotation only, never a persisted-cache fallback. */
    private suspend fun syncNativePushRegistration(
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
    private suspend fun pauseNotificationDeliveryReconciliation(): Boolean {
        nativePushFallback.invalidateAll()
        return nativePushSyncMutex.withLock {
            drainPendingPushClears()
            drainPendingPushDisables()
            false
        }
    }

    private suspend fun hasConfirmedNativePushRegistration(account: String): Boolean =
        nativePushSyncMutex.withLock { perAccountSyncedFingerprints.containsKey(account) }

    /** Replays pending cleanup before registration and gates token acquisition on capability. */
    @Suppress("ReturnCount")
    private suspend fun syncNativePushRegistrationIfEnabledLocked(
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
    private suspend fun syncNativePushRegistrationForAccountsLocked(
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
    private suspend fun tokenForNativePushSync(
        freshCallbackToken: String?,
        operationIsCurrent: () -> Boolean,
    ): String? =
        fetchFcmTokenOrNull(freshCallbackToken, operationIsCurrent)
            ?: freshCallbackToken?.takeIf {
                operationIsCurrent() &&
                    withContext(pushWakeStorageDispatcher) { pushTokenStore.lastToken() == it }
            }

    /** Migrates the active account while reporting unknown or background-owned state as incomplete. */
    private suspend fun reconcileUnavailableNativePushFallback(
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
                        clearRegistration = ::clearPushRegistrationForOwnerLocked,
                        onFailure = { message -> appStateDebug { message } },
                    ),
            )
        return reconciled
    }

    /** Captures the exact active account/runtime lifetime used by service readiness callbacks. */
    private fun captureNativePushFallbackOwner(): NativePushFallbackOwner? =
        nativePushFallback.owner(
            activeAccountRef,
            marmotRuntime,
            runtimeGeneration,
            accountSwitchHandoff.capture(),
        )

    /** Rejects suspended fallback work after an account switch or runtime replacement. */
    @Suppress("MaxLineLength")
    private fun ownsNativePushFallback(owner: NativePushFallbackOwner): Boolean = captureNativePushFallbackOwner()?.matches(owner) == true

    /** Accepts only the exact service generation that reached a successful supervised runtime boundary. */
    internal fun onNativePushFallbackRuntimeStarted(generation: Long): Job? {
        assertMainThread { "onNativePushFallbackRuntimeStarted" }
        refreshLocalNotificationPermission()
        val owner =
            nativePushFallback.acknowledge(generation) { candidate ->
                localNotificationPermissionGranted && ownsNativePushFallback(candidate)
            } ?: return null
        return notificationScope.launch {
            if (ownsNativePushFallback(owner) && !notificationDeliveryModeBusy) {
                syncNativePushRegistrationIfEnabled()
            }
        }
    }

    /** Drops a rejected, exhausted, recovery-fenced, or destroyed service generation. */
    internal fun onNativePushFallbackRuntimeUnavailable(generation: Long) {
        assertMainThread { "onNativePushFallbackRuntimeUnavailable" }
        nativePushFallback.invalidate(generation)
    }

    /** Clears only [owner]'s registration on its captured runtime and retains failed cleanup for retry. */
    private suspend fun clearPushRegistrationForOwnerLocked(owner: NativePushFallbackOwner) {
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

    /**
     * Retry every `clearPushRegistration` that previously failed (sign-out
     * disconnected from the network, runtime transient error, etc.). On
     * success the entry leaves the persisted set; on failure it stays and
     * the next sync tick will try again. Without this drain a failed
     * sign-out-time deregistration would silently leave the push server
     * holding a stale token — there's no other code path that would notice
     * because [nativePushEnabled] is already false on the runtime side.
     */
    private suspend fun drainPendingPushClears() {
        for (account in pushTokenStore.pendingClears()) {
            val cleared =
                runCatchingCancellable { host.marmotIo { clearPushRegistration(account) } }
                    .onSuccess { outcome ->
                        logPushRegistrationShareOutcome("pending clear", account, outcome)
                    }.onFailure {
                        appStateDebug { "pending clearPushRegistration retry failed: ${it.readableMessage()}" }
                    }.isSuccess
            if (cleared) {
                pushTokenStore.clearPending(account)
                appStateDebug { "pending clearPushRegistration drained account=${account.take(8)}" }
            }
        }
    }

    // Retry sign-out push-disables that failed; on success the account leaves the pending set.
    private suspend fun drainPendingPushDisables() {
        val runtime = marmotRuntime ?: return
        for (account in pushTokenStore.pendingDisables()) {
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
                pushTokenStore.clearPendingDisable(account)
                appStateDebug { "pending native-push disable drained account=${account.take(8)}" }
            }
        }
    }

    private suspend fun syncPushForAccount(
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
        if (account in pushTokenStore.pendingDisables()) return true
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

    /**
     * Enable or disable real push on the active account. When enabling, also
     * triggers the registration sync; when disabling, clears the runtime
     * registration so the MIP-05 server stops trying to deliver to a token
     * the device no longer wants.
     */
    suspend fun setNativePushEnabled(enabled: Boolean): Boolean {
        val requested = activeAccountRef ?: return false
        val runtime = marmotRuntime
        val epoch = runtimeGeneration
        val intent = notificationDeliveryModeIntent.capture()
        return host.withAccountLifetime {
            notificationDeliveryModeMutex.withLock {
                val ownerCurrent =
                    activeAccountRef == requested && marmotRuntime === runtime && runtimeGeneration == epoch
                if (!ownerCurrent ||
                    !notificationDeliveryModeIntent.isCurrent(intent)
                ) {
                    return@withLock false
                }
                setNativePushEnabledLocked(requested, enabled)
            }
        }
    }

    private suspend fun setNativePushEnabledLocked(
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
    private suspend fun clearPushRegistrationForAccount(account: String) {
        nativePushSyncMutex.withLock {
            clearPushRegistrationForAccountLocked(account)
        }
    }

    private suspend fun clearPushRegistrationForAccountLocked(account: String) {
        perAccountSyncedFingerprints.remove(account)
        val result = runCatchingCancellable { host.marmotIo { clearPushRegistration(account) } }
        result.onSuccess { outcome ->
            // PENDING means MarmotKit durably retained the clear and will
            // retry group sharing; it is a successful local clear, not a
            // reason to restore or roll back the registration.
            logPushRegistrationShareOutcome("clear", account, outcome)
            pushTokenStore.clearPending(account)
        }
        result.exceptionOrNull()?.let { failure ->
            val queued = withContext(Dispatchers.IO) { pushTokenStore.recordPendingClear(account) }
            appStateDebug {
                "clearPushRegistration failed cleanupQueued=$queued: ${failure.readableMessage()}"
            }
        }
    }

    /** Fetches and retains a non-blank FCM token only when every push prerequisite is available. */
    private suspend fun fetchFcmTokenOrNull(
        expectedCallbackToken: String? = null,
        tokenOwnerIsCurrent: () -> Boolean = { true },
    ): String? {
        val rotation = pushTokenRotationSequence.get()
        if (!nativePushCapability().isAvailable) return null
        val token =
            runCatchingCancellable {
                val reader: suspend () -> String? = fcmTokenReaderOverride ?: ::readFcmTokenFromSdk
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

    private suspend fun readFcmTokenFromSdk(): String? =
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

    fun refreshLocalNotificationPermission() {
        localNotificationPermissionGranted = localNotificationPresenter.canPostNotifications()
    }

    private fun updateBackgroundConnectionPreference(enabled: Boolean) {
        if (!enabled) nativePushFallback.invalidateAll()
        backgroundConnectionEnabled = enabled
        BackgroundConnectionPreferences.setEnabled(appContext, enabled)
        appStateDebug { "background connection enabled=$enabled" }
    }

    internal fun startBackgroundConnectionService(): Boolean {
        val started =
            NotificationStreamForegroundService.start(
                appContext,
                ForegroundStartTrigger.UserToggle,
            )
        appStateDebug { "background connection service start=$started" }
        return started
    }
}
