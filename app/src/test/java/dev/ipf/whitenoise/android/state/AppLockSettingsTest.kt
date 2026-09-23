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

    @Test
    fun unlockSessionBeginsExactlyOnceUntilItsTerminalResult() {
        val first = attachedSession()

        assertEquals(1L, first.activeSessionId)
        assertEquals(first, first.begin())
        assertEquals(null, first.terminate(sessionId = 99L, hostId = 10))

        val terminated = requireNotNull(first.terminate(sessionId = 1L, hostId = 10))
        assertEquals(null, terminated.activeSessionId)
        assertEquals(2L, terminated.begin().activeSessionId)
    }

    @Test
    fun unlockSessionAcceptsCurrentSuccessOnlyOnceAndIgnoresStaleCallbacks() {
        val first = attachedSession()
        val completed =
            requireNotNull(
                first.complete(
                    sessionId = 1L,
                    hostId = 10,
                    foregroundReturnExpiresAtElapsedRealtime = null,
                ),
            )

        assertEquals(null, completed.activeSessionId)
        assertEquals(
            null,
            completed.complete(sessionId = 1L, hostId = 10, foregroundReturnExpiresAtElapsedRealtime = null),
        )

        val second =
            requireNotNull(
                completed.begin().attachHost(2L, hostId = 20, replaceExistingHost = false),
            )
        assertEquals(
            null,
            second.complete(sessionId = 1L, hostId = 10, foregroundReturnExpiresAtElapsedRealtime = null),
        )
        assertTrue(second.owns(2L, hostId = 20))
    }

    @Test
    fun promptLifecycleReturnIsBoundedAndClearedWithoutStartingAnotherSession() {
        val active = attachedSession()
        val completed =
            requireNotNull(
                active.complete(
                    sessionId = 1L,
                    hostId = 10,
                    foregroundReturnExpiresAtElapsedRealtime = 5_000L,
                ),
            )

        assertTrue(completed.foregroundReturnIsValid(nowElapsedRealtime = 4_999L))
        assertFalse(completed.foregroundReturnIsValid(nowElapsedRealtime = 5_001L))
        val consumed = completed.clearForegroundReturn()
        assertEquals(null, consumed.foregroundReturnExpiresAtElapsedRealtime)
        assertFalse(consumed.foregroundReturnIsValid(nowElapsedRealtime = 1L))
        assertEquals(1L, consumed.latestSessionId)
    }

    @Test
    fun terminalFailureKeepsTheAppLockedButAllowsAnExplicitRetrySession() {
        val active = attachedSession()
        val terminated = requireNotNull(active.terminate(sessionId = 1L, hostId = 10))
        val retry = terminated.begin()

        assertEquals(null, terminated.activeSessionId)
        assertEquals(2L, retry.activeSessionId)
        assertEquals(null, retry.foregroundReturnExpiresAtElapsedRealtime)
    }

    @Test
    fun recreatedHostInvalidatesCallbacksFromTheReplacedActivity() {
        val firstHost =
            requireNotNull(
                AppUnlockSessionState()
                    .begin()
                    .attachHost(1L, hostId = 10, replaceExistingHost = false),
            )
        assertEquals(null, firstHost.attachHost(1L, hostId = 20, replaceExistingHost = false))

        val replacement = requireNotNull(firstHost.attachHost(1L, hostId = 20, replaceExistingHost = true))

        assertEquals(
            null,
            replacement.complete(1L, hostId = 10, foregroundReturnExpiresAtElapsedRealtime = null),
        )
        assertEquals(null, replacement.terminate(1L, hostId = 10))
        assertTrue(replacement.owns(1L, hostId = 20))
        assertEquals(
            null,
            requireNotNull(
                replacement.complete(1L, hostId = 20, foregroundReturnExpiresAtElapsedRealtime = null),
            ).activeSessionId,
        )
    }

    @Test
    fun unlockSessionIdsNeverUseTheUninitializedZeroSentinel() {
        val wrapped = AppUnlockSessionState(latestSessionId = Long.MAX_VALUE).begin()

        assertEquals(1L, wrapped.activeSessionId)
        assertEquals(1L, wrapped.latestSessionId)
    }

    @Test
    fun controllerDistinguishesUnlaunchedAndHostedSessionsBeforeReattachment() {
        val controller = AppUnlockSessionController()

        assertTrue(controller.begin())
        assertFalse(controller.hasAttachedHost)
        assertTrue(controller.attachHost(sessionId = 1L, hostId = 10L, replaceExistingHost = false))
        assertTrue(controller.hasAttachedHost)

        controller.clear()

        assertEquals(null, controller.activeSessionId)
        assertFalse(controller.hasAttachedHost)
        assertTrue(controller.begin())
        assertEquals(2L, controller.activeSessionId)
        assertFalse(controller.owns(sessionId = 1L, hostId = 10L))
    }

    @Test
    fun reattachmentRequiresAnActiveHostedSessionAndTheRetainedActivityHost() {
        assertTrue(shouldReattachAppUnlockPrompt(true, true, true))
        assertFalse(shouldReattachAppUnlockPrompt(false, true, true))
        assertFalse(shouldReattachAppUnlockPrompt(true, false, true))
        assertFalse(shouldReattachAppUnlockPrompt(true, true, false))
    }

    private fun attachedSession(): AppUnlockSessionState =
        requireNotNull(
            AppUnlockSessionState()
                .begin()
                .attachHost(1L, hostId = 10, replaceExistingHost = false),
        )
}
