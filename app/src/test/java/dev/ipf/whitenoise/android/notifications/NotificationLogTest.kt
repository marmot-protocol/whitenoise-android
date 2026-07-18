package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationLogTest {
    @Test
    fun releaseWarningOmitsIdentifiersWithoutEvaluatingThem() {
        val message =
            notificationWarningMessage("mark-read failed", includeDebugDetails = false) {
                error("release logging must not evaluate identifier details")
            }

        assertEquals("mark-read failed", message)
    }

    @Test
    fun debugWarningIncludesIdentifierDetails() {
        val message =
            notificationWarningMessage("mark-read failed", includeDebugDetails = true) {
                "group=12345678 message=abcdef01"
            }

        assertEquals("mark-read failed group=12345678 message=abcdef01", message)
    }
}
