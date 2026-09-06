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

/** Detects native-enabled background accounts that the active-account UI cannot safely mutate. */
internal fun Map<String, NotificationSettingsFfi>.hasUnreconciledNativePushOutside(activeAccountRef: String): Boolean =
    any { (accountRef, settings) ->
        accountRef != activeAccountRef && settings.localNotificationsEnabled && settings.nativePushEnabled
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
