package dev.ipf.whitenoise.android.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationBatteryPolicyTest {
    /** Android background restriction takes precedence over optimization exemption state. */
    @Test
    fun restrictedPolicyWins() {
        assertEquals(NotificationBatteryPolicy.Restricted, notificationBatteryPolicy(true, true))
        assertEquals(NotificationBatteryPolicy.Restricted, notificationBatteryPolicy(true, false))
    }

    /** Exemption and ordinary optimization are distinct when background restriction is absent. */
    @Test
    fun availablePolicyStatesRemainDistinct() {
        assertEquals(NotificationBatteryPolicy.Unrestricted, notificationBatteryPolicy(false, true))
        assertEquals(NotificationBatteryPolicy.Optimized, notificationBatteryPolicy(false, false))
        assertEquals(NotificationBatteryPolicy.Unknown, notificationBatteryPolicy(false, null))
    }

    /** The settings action never requests battery-optimization exemption. */
    @Test
    fun settingsSourceContainsNoExemptionRequest() {
        val source =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/notifications/NotificationBatteryPolicy.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/notifications/NotificationBatteryPolicy.kt"),
            ).firstOrNull(File::isFile)
                ?.readText()
                ?: error("Missing NotificationBatteryPolicy.kt source file")
        assertFalse(source.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(source.contains("REQUEST_IGNORE_BATTERY"))
    }

    /** A rejected dedicated battery surface falls back to app details. */
    @Test
    fun rejectedBatterySurfaceFallsBackToAppDetails() {
        val context = RecordingSettingsContext(mutableListOf(SecurityException(), null))

        assertTrue(openNotificationBatterySettings(context))
        assertEquals(2, context.started.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, context.started.last().action)
    }

    /** A device that rejects both settings intents reports failure without throwing. */
    @Test
    fun rejectedBatteryAndAppDetailsSurfacesReportFailure() {
        val context =
            RecordingSettingsContext(
                mutableListOf(
                    ActivityNotFoundException(),
                    SecurityException(),
                ),
            )

        assertFalse(openNotificationBatterySettings(context))
        assertEquals(2, context.started.size)
    }

    /** Controllable context for settings-resolution failures without platform UI. */
    private class RecordingSettingsContext(
        private val failures: MutableList<Throwable?>,
    ) : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val started = mutableListOf<Intent>()

        /** Records every attempted surface and throws the configured platform failure. */
        override fun startActivity(intent: Intent) {
            started += intent
            failures.removeFirstOrNull()?.let { throw it }
        }
    }
}
