package dev.ipf.whitenoise.android.notifications

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun notificationPermissionIsImplicitBeforeAndroid13() {
        assertTrue(notificationPermissionGranted(Build.VERSION_CODES.R, runtimePermissionGranted = false))
        assertTrue(notificationPermissionGranted(Build.VERSION_CODES.S_V2, runtimePermissionGranted = false))
    }

    @Test
    fun notificationPermissionUsesRuntimeGrantFromAndroid13() {
        assertFalse(notificationPermissionGranted(Build.VERSION_CODES.TIRAMISU, runtimePermissionGranted = false))
        assertTrue(notificationPermissionGranted(Build.VERSION_CODES.TIRAMISU, runtimePermissionGranted = true))
    }
}
