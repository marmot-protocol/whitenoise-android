package dev.ipf.whitenoise.android.share

import dev.ipf.whitenoise.android.state.AppPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRoutingTest {
    @Test
    fun shouldPresentInboundShare_falseWhileAppLocked() {
        assertFalse(
            shouldPresentInboundShare(
                phase = AppPhase.Ready,
                appLockScreenVisible = true,
            ),
        )
    }

    @Test
    fun shouldPresentInboundShare_falseBeforeReady() {
        assertFalse(
            shouldPresentInboundShare(
                phase = AppPhase.Bootstrapping,
                appLockScreenVisible = false,
            ),
        )
    }

    @Test
    fun shouldPresentInboundShare_trueWhenReadyAndUnlocked() {
        assertTrue(
            shouldPresentInboundShare(
                phase = AppPhase.Ready,
                appLockScreenVisible = false,
            ),
        )
    }
}
