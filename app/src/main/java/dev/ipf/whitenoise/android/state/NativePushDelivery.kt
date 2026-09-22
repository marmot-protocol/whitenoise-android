package dev.ipf.whitenoise.android.state

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.marmotkit.PushRegistrationShareOutcomeFfi
import dev.ipf.marmotkit.PushRegistrationShareStatusFfi
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import dev.ipf.whitenoise.android.notifications.nativePushCapability as resolveNativePushCapability

/** Structural cache key for one account's confirmed push registration. */
internal data class PushFingerprint(
    val platform: PushPlatformFfi,
    val token: String,
    val serverPubkeyHex: String,
    val relayHint: String?,
)

/** Account and runtime generation that own one capability-loss reconciliation. */
internal data class NativePushFallbackOwner(
    val accountRef: String,
    val runtime: AppMarmotRuntime,
    val runtimeGeneration: Int,
    val accountSwitchGeneration: Long,
    val intentGeneration: Long,
)

/** Exact device-wide owner of one notification-delivery transaction. */
internal data class NotificationDeliveryModeOwner(
    val activeAccountRef: String,
    val accountRefs: List<String>,
    val runtime: AppMarmotRuntime,
    val runtimeGeneration: Int,
    val accountSwitchGeneration: Long,
    val intentGeneration: Long,
)

/** Activation result that carries both the selected mode and whether the device-wide invariant needs repair. */
internal data class NotificationDeliveryActivationPlan(
    val mode: NotificationDeliveryMode,
    val requiresDeviceWideReconciliation: Boolean,
)

/** The only two user-facing notification delivery choices. */
internal enum class NotificationDeliveryMode {
    Fcm,
    Local,
}

/** Resolves a device mode from retained runtime ownership and native account settings. */
internal fun resolvedNotificationDeliveryMode(
    anyNativeEnabled: Boolean,
    persistentConnectionEnabled: Boolean,
    nativePushCapability: NativePushCapability,
): NotificationDeliveryMode =
    if (persistentConnectionEnabled || !anyNativeEnabled || !nativePushCapability.isAvailable) {
        NotificationDeliveryMode.Local
    } else {
        NotificationDeliveryMode.Fcm
    }

/** Checks account preferences and the runtime owner before treating a device mode as settled. */
internal fun notificationDeliveryInvariantMatches(
    mode: NotificationDeliveryMode,
    settingsByAccount: Map<String, NotificationSettingsFfi>,
    persistentConnectionEnabled: Boolean,
    persistentServiceOwned: Boolean,
    syncedAccounts: Set<String>,
): Boolean {
    val accountsMatchMode =
        when (mode) {
            NotificationDeliveryMode.Local -> settingsByAccount.values.none { it.nativePushEnabled }
            NotificationDeliveryMode.Fcm ->
                settingsByAccount.values.all { it.nativePushEnabled == it.localNotificationsEnabled }
        }
    val runtimeMatchesMode =
        when (mode) {
            NotificationDeliveryMode.Local -> persistentConnectionEnabled && persistentServiceOwned
            NotificationDeliveryMode.Fcm ->
                !persistentConnectionEnabled &&
                    settingsByAccount.filterValues { it.nativePushEnabled }.keys.all(syncedAccounts::contains)
        }
    return accountsMatchMode && runtimeMatchesMode
}

/** Projects one truthful choice from native settings and Android persistent-delivery ownership. */
internal fun notificationDeliveryMode(
    settings: dev.ipf.marmotkit.NotificationSettingsFfi?,
    persistentConnectionEnabled: Boolean,
    nativePushCapability: NativePushCapability,
): NotificationDeliveryMode =
    if (
        nativePushCapability.isAvailable &&
        settings?.nativePushEnabled == true &&
        !persistentConnectionEnabled
    ) {
        NotificationDeliveryMode.Fcm
    } else {
        NotificationDeliveryMode.Local
    }

/** Resolves the first unavailable build or device prerequisite without reaching later SDKs. */
internal fun nativePushCapabilityForContext(
    context: Context,
    config: PushServerConfig?,
): NativePushCapability {
    val pushServerConfigured = config != null
    val googlePlayServicesAvailable =
        pushServerConfigured &&
            GoogleApiAvailability
                .getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    val firebaseInitialized = googlePlayServicesAvailable && FirebaseApp.getApps(context).isNotEmpty()
    return resolveNativePushCapability(
        pushServerConfigured = pushServerConfigured,
        googlePlayServicesAvailable = googlePlayServicesAvailable,
        firebaseInitialized = firebaseInitialized,
    )
}

/**
 * Prefer native push for first-run delivery when it is usable, otherwise retain
 * the persistent relay connection. Partial native enablement is rolled back
 * before the persistent fallback is restored.
 */
internal suspend fun configureDefaultNotificationDelivery(
    nativePushCapability: NativePushCapability,
    enableNativePush: suspend () -> Boolean,
    disableNativePush: suspend () -> Boolean,
    setBackgroundConnectionEnabled: suspend (Boolean) -> Boolean,
): Boolean {
    val configured =
        if (!nativePushCapability.isAvailable) {
            setBackgroundConnectionEnabled(true)
        } else {
            val nativePushReady = enableNativePush()
            if (nativePushReady && setBackgroundConnectionEnabled(false)) {
                true
            } else {
                val nativePushDisabled = disableNativePush()
                val backgroundConnectionEnabled = setBackgroundConnectionEnabled(true)
                nativePushDisabled && backgroundConnectionEnabled
            }
        }
    return configured
}

/** Requires global registration success and an active-account fingerprint. */
internal fun nativePushEnablementConfirmed(
    allAccountsReady: Boolean,
    activeAccountRegistered: Boolean,
): Boolean = allAccountsReady && activeAccountRegistered

/**
 * Migrates an enabled native-push preference to persistent delivery after a
 * prerequisite disappears. Persistent delivery is established first: if that
 * fails or ownership changes, native push remains enabled so a later sync can
 * retry instead of leaving both delivery paths off.
 */
internal suspend fun reconcileUnavailableNativePushDelivery(
    capability: NativePushCapability,
    nativePushEnabled: Boolean,
    ownerIsCurrent: () -> Boolean,
    enablePersistentConnection: suspend () -> Boolean,
    disableNativePush: suspend () -> Boolean,
): Boolean =
    when {
        capability.isAvailable || !nativePushEnabled -> true
        !ownerIsCurrent() -> false
        else -> {
            val persistentConnectionEnabled = enablePersistentConnection()
            coroutineContext.ensureActive()
            persistentConnectionEnabled && ownerIsCurrent() && disableNativePush() && ownerIsCurrent()
        }
    }

/** Whether native registration sharing completed now or entered Marmot's durable retry queue. */
internal enum class PushRegistrationSharingState {
    Complete,
    PendingDurableRetry,
}

/** Maps native registration sharing to immediate or durable-retry completion. */
internal fun pushRegistrationSharingState(outcome: PushRegistrationShareOutcomeFfi): PushRegistrationSharingState =
    when (outcome.status) {
        PushRegistrationShareStatusFfi.COMPLETE -> PushRegistrationSharingState.Complete
        PushRegistrationShareStatusFfi.PENDING -> PushRegistrationSharingState.PendingDurableRetry
    }

/** Formats privacy-bounded registration sharing counts for debug-only diagnostics. */
internal fun pushRegistrationShareLogMessage(
    operation: String,
    account: String,
    outcome: PushRegistrationShareOutcomeFfi,
): String =
    "push registration $operation sharing=${pushRegistrationSharingState(outcome)} " +
        "account=${account.take(ACCOUNT_LOG_PREFIX_LENGTH)} " +
        "attempted=${outcome.attemptedGroups} succeeded=${outcome.succeededGroups} " +
        "failed=${outcome.failedGroups} pending=${outcome.pendingGroups}"

/** Emits one debug-only, privacy-bounded registration sharing result. */
internal fun logPushRegistrationShareOutcome(
    operation: String,
    account: String,
    outcome: PushRegistrationShareOutcomeFfi,
) {
    appStateDebug { pushRegistrationShareLogMessage(operation, account, outcome) }
}

private const val ACCOUNT_LOG_PREFIX_LENGTH = 8
