package dev.ipf.whitenoise.android.updates

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateForegroundStateTest {
    @After
    fun resetForegroundState() {
        AppUpdateForegroundState.isForeground = false
    }

    @Test
    fun notificationsAreAllowedOnlyWhileBackgrounded() {
        AppUpdateForegroundState.isForeground = false
        assertTrue(AppUpdateForegroundState.shouldPostBackgroundNotification())

        AppUpdateForegroundState.isForeground = true
        assertFalse(AppUpdateForegroundState.shouldPostBackgroundNotification())
    }
}
