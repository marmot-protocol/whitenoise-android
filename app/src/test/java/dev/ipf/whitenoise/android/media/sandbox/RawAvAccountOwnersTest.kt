package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RawAvAccountOwnersTest {
    @Test
    fun accountResetRevokesPausedAndActiveOwnersAndPreservesLaterOwners() {
        val owners = RawAvAccountOwners<Any>()
        val epoch = owners.epoch()
        val paused = Any()
        val active = Any()
        assertTrue(owners.register(paused, epoch))
        assertTrue(owners.register(active, epoch))
        assertTrue(owners.activate(active, epoch).accepted)
        val retired = owners.retireAll()
        assertEquals(2, retired.instances.size)
        assertTrue(retired.instances.contains(paused))
        assertTrue(retired.instances.contains(active))
        assertSame(active, retired.active)
        assertFalse(owners.current(paused, epoch))
        assertFalse(owners.current(active, epoch))
        val replacement = Any()
        val newEpoch = owners.epoch()
        assertTrue(owners.register(replacement, newEpoch))
        assertTrue(owners.activate(replacement, newEpoch).accepted)
        owners.remove(paused)
        owners.remove(active)
        assertTrue(owners.current(replacement, newEpoch))
        var replacementStillActive = false
        owners.withActive(replacement) { replacementStillActive = true }
        assertTrue(replacementStillActive)
    }

    @Test
    fun constructorFinishingAfterResetCannotRegisterItsOldEpoch() {
        val owners = RawAvAccountOwners<Any>()
        val constructingEpoch = owners.epoch()
        assertTrue(owners.retireAll().instances.isEmpty())
        val lateOldOwner = Any()
        assertFalse(owners.register(lateOldOwner, constructingEpoch))
        assertFalse(owners.activate(lateOldOwner, constructingEpoch).accepted)
        val newOwner = Any()
        assertTrue(owners.register(newOwner, owners.epoch()))
    }

    @Test
    fun finalRenderGateRejectsRevocationAfterTheEarlierReadinessCheck() {
        val owners = RawAvAccountOwners<Any>()
        val old = Any()
        val epoch = owners.epoch()
        assertTrue(owners.register(old, epoch))
        assertTrue(owners.current(old, epoch)) // A frame was ready before the account reset.
        owners.retireAll()
        var drewOldPixels = false
        assertFalse(owners.withCurrent(old, epoch) { drewOldPixels = true })
        assertFalse(drewOldPixels)
        val new = Any()
        val newEpoch = owners.epoch()
        assertTrue(owners.register(new, newEpoch))
        assertTrue(owners.withCurrent(new, newEpoch) { assertFalse(drewOldPixels) })
    }
}
