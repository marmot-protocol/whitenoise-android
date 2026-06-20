package dev.ipf.darkmatter.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundCatchUpPolicyTest {
    @Test
    fun allowsCatchUpWhenReadyForegroundAndIdle() {
        assertTrue(
            ForegroundCatchUpPolicy.shouldCatchUp(
                appPhase = AppPhase.Ready,
                isCatchUpRunning = false,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun rejectsWhenNotReady() {
        assertFalse(
            ForegroundCatchUpPolicy.shouldCatchUp(
                appPhase = AppPhase.Bootstrapping,
                isCatchUpRunning = false,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun rejectsWhenCatchUpAlreadyRunning() {
        assertFalse(
            ForegroundCatchUpPolicy.shouldCatchUp(
                appPhase = AppPhase.Ready,
                isCatchUpRunning = true,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun rejectsWhenBackgrounded() {
        assertFalse(
            ForegroundCatchUpPolicy.shouldCatchUp(
                appPhase = AppPhase.Ready,
                isCatchUpRunning = false,
                appInForeground = false,
            ),
        )
    }
}
