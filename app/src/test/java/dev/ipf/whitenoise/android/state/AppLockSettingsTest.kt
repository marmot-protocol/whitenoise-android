package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSettingsTest {
    @Test
    fun appLockDelayFallsBackToImmediateForUnknownPreferences() {
        assertEquals(AppLockDelay.Immediately, AppLockDelay.fromPreference(null))
        assertEquals(AppLockDelay.Immediately, AppLockDelay.fromPreference("unknown"))
        assertEquals(AppLockDelay.FiveMinutes, AppLockDelay.fromPreference("5m"))
    }

    @Test
    fun appLockDelayRoundTripsEveryPreferenceValue() {
        AppLockDelay.entries.forEach { delay ->
            assertEquals(delay, AppLockDelay.fromPreference(delay.preferenceValue))
        }
    }

    @Test
    fun appLockRequiresEnabledSettingAndCredential() {
        assertFalse(
            shouldShowAppLock(
                requireUnlock = false,
                credentialAvailable = true,
                lastUnlockedAtMillis = 0L,
                nowMillis = 1_000_000L,
                delay = AppLockDelay.Immediately,
            ),
        )
        assertFalse(
            shouldShowAppLock(
                requireUnlock = true,
                credentialAvailable = false,
                lastUnlockedAtMillis = 0L,
                nowMillis = 1_000_000L,
                delay = AppLockDelay.Immediately,
            ),
        )
    }

    @Test
    fun appLockHonorsDelayThreshold() {
        assertFalse(
            shouldShowAppLock(
                requireUnlock = true,
                credentialAvailable = true,
                lastUnlockedAtMillis = 1_000L,
                nowMillis = 60_999L,
                delay = AppLockDelay.OneMinute,
            ),
        )
        assertTrue(
            shouldShowAppLock(
                requireUnlock = true,
                credentialAvailable = true,
                lastUnlockedAtMillis = 1_000L,
                nowMillis = 61_000L,
                delay = AppLockDelay.OneMinute,
            ),
        )
    }

    @Test
    fun appLockTreatsBackwardsClockAsStillUnlockedUntilDelayPasses() {
        assertFalse(
            shouldShowAppLock(
                requireUnlock = true,
                credentialAvailable = true,
                lastUnlockedAtMillis = 5_000L,
                nowMillis = 4_000L,
                delay = AppLockDelay.OneMinute,
            ),
        )
    }

    @Test
    fun backgroundingRefreshesDelayBaselineOnlyWhenUnlockedAndCredentialed() {
        assertTrue(
            shouldRefreshAppLockDelayBaselineOnBackground(
                requireUnlock = true,
                credentialAvailable = true,
                lockScreenVisible = false,
            ),
        )
        assertFalse(
            shouldRefreshAppLockDelayBaselineOnBackground(
                requireUnlock = false,
                credentialAvailable = true,
                lockScreenVisible = false,
            ),
        )
        assertFalse(
            shouldRefreshAppLockDelayBaselineOnBackground(
                requireUnlock = true,
                credentialAvailable = false,
                lockScreenVisible = false,
            ),
        )
        assertFalse(
            shouldRefreshAppLockDelayBaselineOnBackground(
                requireUnlock = true,
                credentialAvailable = true,
                lockScreenVisible = true,
            ),
        )
    }

    @Test
    fun backgroundWindowSecureFollowsStoredAppLockIntent() {
        assertTrue(shouldSecureAppLockWindowWhileBackgrounded(requireUnlock = true))
        assertFalse(shouldSecureAppLockWindowWhileBackgrounded(requireUnlock = false))
    }
}
