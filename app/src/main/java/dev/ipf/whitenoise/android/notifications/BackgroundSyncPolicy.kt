package dev.ipf.whitenoise.android.notifications

/** Exclusive Android work owner; protocol state and account attention remain in MDK. */
internal enum class BackgroundSyncOwner {
    ForegroundOnly,
    QuietPeriodic,
    NotificationDelivery,
}

/**
 * Resolves device-wide background ownership independently of the chosen FCM/persistent transport.
 * [anyAccountAlertsEnabled] must aggregate all signed-in accounts; null means the native settings read
 * was incomplete. A null result defers reconciliation instead of inventing quiet consent from a failed
 * read. This policy is preparatory: runtime ownership must be integrated before scheduling its result.
 */
internal fun backgroundSyncOwner(
    syncEnabled: Boolean,
    hasSignedInAccount: Boolean,
    anyAccountAlertsEnabled: Boolean?,
    notificationPermissionGranted: Boolean,
): BackgroundSyncOwner? =
    when {
        !syncEnabled || !hasSignedInAccount -> BackgroundSyncOwner.ForegroundOnly
        !notificationPermissionGranted -> BackgroundSyncOwner.QuietPeriodic
        anyAccountAlertsEnabled == null -> null
        anyAccountAlertsEnabled -> BackgroundSyncOwner.NotificationDelivery
        else -> BackgroundSyncOwner.QuietPeriodic
    }

/**
 * Migrates existing transport consent without conflating FCM with opt-out. A false persistent setting
 * alone is not enough: it is also normal on FCM installations. Explicit new consent always wins; null
 * native-push evidence defers migration if neither consent nor the persistent setting resolves it.
 */
internal fun migrateBackgroundSyncConsent(
    explicitConsent: Boolean?,
    persistentConnectionEnabled: Boolean,
    anyNativePushEnabled: Boolean?,
): Boolean? = explicitConsent ?: if (persistentConnectionEnabled) true else anyNativePushEnabled
