package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the direct-message block gate without changing membership precedence. */
class BlockedDmComposerGateTest {
    @Test
    fun blockedDirectPeerReplacesUsableComposer() {
        assertEquals(ComposerGate.BLOCKED, blockedDmComposerGate(ComposerGate.COMPOSER, "peer", true))
    }

    @Test
    fun unknownOrUnblockedPeerKeepsComposer() {
        assertEquals(ComposerGate.COMPOSER, blockedDmComposerGate(ComposerGate.COMPOSER, "peer", null))
        assertEquals(ComposerGate.COMPOSER, blockedDmComposerGate(ComposerGate.COMPOSER, "peer", false))
    }

    @Test
    fun groupWithoutDirectPeerKeepsComposer() {
        assertEquals(ComposerGate.COMPOSER, blockedDmComposerGate(ComposerGate.COMPOSER, null, true))
    }

    @Test
    fun membershipAndInviteGatesOutrankBlockState() {
        listOf(ComposerGate.NOTICE, ComposerGate.INVITE, ComposerGate.FROZEN, ComposerGate.DISBANDED).forEach {
            assertEquals(it, blockedDmComposerGate(it, "peer", true))
        }
    }
}
