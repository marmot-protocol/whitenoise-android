package dev.ipf.darkmatter.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundConnectionPolicyTest {
    @Test
    fun startsOnBootWhenBackgroundConnectionIsEnabled() {
        assertTrue(
            BackgroundConnectionPolicy.shouldStartFromSystemWake(
                action = BackgroundConnectionPolicy.ACTION_BOOT_COMPLETED,
                backgroundConnectionEnabled = true,
            ),
        )
    }

    @Test
    fun startsAfterAppUpdateWhenBackgroundConnectionIsEnabled() {
        assertTrue(
            BackgroundConnectionPolicy.shouldStartFromSystemWake(
                action = BackgroundConnectionPolicy.ACTION_MY_PACKAGE_REPLACED,
                backgroundConnectionEnabled = true,
            ),
        )
    }

    @Test
    fun doesNotStartOnBootWhenBackgroundConnectionIsDisabled() {
        assertFalse(
            BackgroundConnectionPolicy.shouldStartFromSystemWake(
                action = BackgroundConnectionPolicy.ACTION_BOOT_COMPLETED,
                backgroundConnectionEnabled = false,
            ),
        )
    }

    @Test
    fun ignoresUnrelatedSystemWakeActions() {
        assertFalse(
            BackgroundConnectionPolicy.shouldStartFromSystemWake(
                action = "android.intent.action.TIMEZONE_CHANGED",
                backgroundConnectionEnabled = true,
            ),
        )
    }
}
