package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the direct-message block gate without changing membership precedence. */
class BlockedDmComposerGateTest {
    /** A confirmed block replaces the composer only for a resolved direct peer. */
    @Test
    fun blockedDirectPeerReplacesUsableComposer() {
        assertEquals(ComposerGate.BLOCKED, blockedDmComposerGate(ComposerGate.COMPOSER, "peer", true))
    }

    /** Missing mirror data and an explicit unblocked state keep the composer usable. */
    @Test
    fun unknownOrUnblockedPeerKeepsComposer() {
        assertEquals(ComposerGate.COMPOSER, blockedDmComposerGate(ComposerGate.COMPOSER, "peer", null))
        assertEquals(ComposerGate.COMPOSER, blockedDmComposerGate(ComposerGate.COMPOSER, "peer", false))
    }

    /** A group without a direct peer cannot inherit the blocked-DM gate. */
    @Test
    fun groupWithoutDirectPeerKeepsComposer() {
        assertEquals(ComposerGate.COMPOSER, blockedDmComposerGate(ComposerGate.COMPOSER, null, true))
    }

    /** Membership and invitation restrictions take precedence over a block notice. */
    @Test
    fun membershipAndInviteGatesOutrankBlockState() {
        listOf(ComposerGate.NOTICE, ComposerGate.INVITE, ComposerGate.FROZEN, ComposerGate.DISBANDED).forEach {
            assertEquals(it, blockedDmComposerGate(it, "peer", true))
        }
    }
}
