package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.MAX_RETAINED_SYSTEM_SPLASH_MILLIS
import dev.ipf.whitenoise.android.shouldRetainSystemSplash
import dev.ipf.whitenoise.android.state.AppPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmResumeLifecycleRoutingTest {
    @Test
    fun appLockAlwaysOwnsTheFirstUsefulFrame() {
        assertEquals(
            WarmResumeFirstUsefulSurface.AppLock,
            warmResumeFirstUsefulSurface(
                appLockScreenVisible = true,
                inboundRoutePending = true,
                shellReady = true,
            ),
        )
        assertFalse(shouldComposeProtectedMainShell(WarmResumeFirstUsefulSurface.AppLock))
    }

    @Test
    fun inboundRouteTakesPrecedenceOverRetainedShellContent() {
        assertEquals(
            WarmResumeFirstUsefulSurface.InboundRoute,
            warmResumeFirstUsefulSurface(
                appLockScreenVisible = false,
                inboundRoutePending = true,
                shellReady = true,
            ),
        )
        assertTrue(shouldComposeProtectedMainShell(WarmResumeFirstUsefulSurface.InboundRoute))
    }

    @Test
    fun retainedProjectionSkipsStartupProgress() {
        assertEquals(
            WarmResumeFirstUsefulSurface.RestoredShell,
            warmResumeFirstUsefulSurface(
                appLockScreenVisible = false,
                inboundRoutePending = false,
                shellReady = true,
            ),
        )
        assertTrue(shouldComposeProtectedMainShell(WarmResumeFirstUsefulSurface.RestoredShell))
    }

    @Test
    fun coldLocalRehydrationKeepsTheBrandedStartupSurface() {
        assertEquals(
            WarmResumeFirstUsefulSurface.Startup,
            warmResumeFirstUsefulSurface(
                appLockScreenVisible = false,
                inboundRoutePending = false,
                shellReady = false,
            ),
        )
        assertFalse(shouldComposeProtectedMainShell(WarmResumeFirstUsefulSurface.Startup))
    }

    @Test
    fun readyPhaseHoldsSystemSplashOnlyUntilTheLocalUsefulFrameOrDeadline() {
        assertTrue(
            shouldRetainSystemSplash(
                phase = AppPhase.Ready,
                elapsedMs = MAX_RETAINED_SYSTEM_SPLASH_MILLIS - 1L,
                firstUsefulFrameReady = false,
            ),
        )
        assertFalse(
            shouldRetainSystemSplash(
                phase = AppPhase.Ready,
                elapsedMs = 0L,
                firstUsefulFrameReady = true,
            ),
        )
        assertFalse(
            shouldRetainSystemSplash(
                phase = AppPhase.Ready,
                elapsedMs = MAX_RETAINED_SYSTEM_SPLASH_MILLIS,
                firstUsefulFrameReady = false,
            ),
        )
    }
}
