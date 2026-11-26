package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationSettingsFfi
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val FCM_REGISTRATION_BATCH_SIZE = 3

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
    internal val marmotIoDispatcher: CoroutineDispatcher,
    internal val pushWakeStorageDispatcher: CoroutineDispatcher,
    internal val pushServerConfigProvider: () -> PushServerConfig?,
    internal val fcmTokenReaderOverride: (suspend () -> String?)?,
    private val nativePushCapabilityResolver: (PushServerConfig?) -> NativePushCapability,
    internal val nativePushFallbackPlatform: NativePushFallbackPlatform,
    internal val pushTokenStore: PushTokenStore,
    internal val localNotificationPresenter: LocalNotificationPresenter,
    internal val notificationScope: CoroutineScope,
    internal val host: NotificationDeliveryHost,
) {
    internal val nativePushSyncMutex = Mutex()
    internal val pushTokenRotationSequence = AtomicLong()

    // The token of the latest onNewToken callback, published before its sequence increment so a token
    // read that sees the new sequence also sees which token caused it.
    internal val lastRotatedFcmToken = AtomicReference<String?>()

    // Native-push enables that can still roll back: a Push selection before its cutover commits, or a
    // per-account enable before it is confirmed. While any is open a sync leaves Firebase auto-init alone,
    // since turning it on starts an SDK token sync that no later off write cancels. Each sets it on commit.
    internal val uncommittedNativePushEnables = AtomicInteger()

    // Whether the latest Firebase auto-init write failed. A failed write records the pending registration
    // sync, and no sync acknowledges it until a later write succeeds, so the next sync retries the write.
    internal val fcmAutoInitWriteFailed = AtomicBoolean()
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
        internal set

    var notificationDeliveryModeBusy by mutableStateOf(false)
        internal set

    var notificationBatteryPolicy by mutableStateOf(NotificationBatteryPolicy.Unknown)
        internal set

    internal val activeAccountRef get() = host.activeAccountRef
    internal val marmotRuntime get() = host.marmotRuntime
    internal val runtimeGeneration get() = host.runtimeGeneration
    internal val accountSwitchHandoff get() = host.accountSwitchHandoff
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
        lastRotatedFcmToken.set(token)
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
                setNativePushEnabledSettlingAutoInit(requested, enabled)
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
