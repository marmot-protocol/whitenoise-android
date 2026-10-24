package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins consent boundaries before Android runtime and WorkManager integration can safely adopt them. */
class BackgroundSyncPolicyTest {
    /** All four user choices select one owner when Android permits alerts. */
    @Test
    fun independentControlsSelectExactlyOneOwner() {
        val expected =
            mapOf(
                (false to false) to BackgroundSyncOwner.ForegroundOnly,
                (false to true) to BackgroundSyncOwner.ForegroundOnly,
                (true to false) to BackgroundSyncOwner.QuietPeriodic,
                (true to true) to BackgroundSyncOwner.NotificationDelivery,
            )
        expected.forEach { (controls, owner) ->
            assertEquals(
                "sync=${controls.first}, alerts=${controls.second}",
                owner,
                backgroundSyncOwner(controls.first, true, controls.second, true),
            )
        }
    }

    /** OS denial cannot authorize work when sync is off or prevent independently consented quiet work. */
    @Test
    fun deniedPermissionUsesQuietWorkOnlyWithSyncConsent() {
        for (alerts in listOf(false, true, null)) {
            assertEquals(BackgroundSyncOwner.QuietPeriodic, backgroundSyncOwner(true, true, alerts, false))
            assertEquals(BackgroundSyncOwner.ForegroundOnly, backgroundSyncOwner(false, true, alerts, false))
        }
    }

    /** Account-local alert opt-out must not replace another account's notification-delivery owner. */
    @Test
    fun anotherAccountsAlertsRetainNotificationDelivery() {
        val signedInAccountAlerts = listOf(false, true)
        assertEquals(
            BackgroundSyncOwner.NotificationDelivery,
            backgroundSyncOwner(true, true, signedInAccountAlerts.any { it }, true),
        )
    }

    /** No identity means no periodic native bootstrap, even when a retained device preference is enabled. */
    @Test
    fun signingOutLastAccountStopsAutomaticWork() {
        for (permission in listOf(false, true)) {
            for (alerts in listOf(false, true, null)) {
                assertEquals(BackgroundSyncOwner.ForegroundOnly, backgroundSyncOwner(true, false, alerts, permission))
            }
        }
    }

    /** A failed account-settings read cannot silently switch an existing delivery transport into polling. */
    @Test
    fun incompleteSettingsDeferTransportReconciliation() {
        assertNull(backgroundSyncOwner(true, true, null, true))
        assertEquals(BackgroundSyncOwner.ForegroundOnly, backgroundSyncOwner(false, true, null, true))
    }

    /** FCM deliberately disables the persistent connection; migration must retain its sync consent. */
    @Test
    fun migratesFcmInstallWithoutRequiringPersistentConnection() {
        assertEquals(true, migrateBackgroundSyncConsent(null, false, true))
    }

    /** A working local transport retains intent even if native-push settings are unavailable. */
    @Test
    fun migratesPersistentDeliveryWithoutInventingNativeSettings() {
        assertEquals(true, migrateBackgroundSyncConsent(null, true, null))
        assertEquals(true, migrateBackgroundSyncConsent(null, true, false))
    }

    /** Legacy users who disabled both delivery transports are never opted into a new periodic schedule. */
    @Test
    fun preservesLegacyOptOut() {
        assertEquals(false, migrateBackgroundSyncConsent(null, false, false))
    }

    /** Migration waits for authoritative evidence instead of persisting false after a transient MDK failure. */
    @Test
    fun missingNativeSettingsDoNotBecomeAStoredOptOut() {
        assertNull(migrateBackgroundSyncConsent(null, false, null))
    }

    /** Reconnect, process recreation and retained transport settings cannot overwrite explicit consent. */
    @Test
    fun explicitConsentAlwaysWinsOverLegacyTransportState() {
        for (persistent in listOf(false, true)) {
            for (native in listOf(false, true, null)) {
                assertEquals(false, migrateBackgroundSyncConsent(false, persistent, native))
                assertEquals(true, migrateBackgroundSyncConsent(true, persistent, native))
            }
        }
    }
}
