package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

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
            File("src/main/java/dev/ipf/whitenoise/android/notifications/NotificationBatteryPolicy.kt")
                .readText()
        assertFalse(source.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(source.contains("REQUEST_IGNORE_BATTERY"))
    }
}
