package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MarmotKit 0.10.1 stores avatars durably and hands each row its asset. These pin which assets are worth
 * drawing and that a refreshed avatar can never render from the previous bytes.
 */
class AvatarAssetsTest {
    /** A ready asset draws, and a stale one keeps drawing while the engine refreshes it. */
    @Test
    fun readyAndStaleAssetsAreRenderable() {
        assertTrue(asset(AvatarAvailabilityFfi.READY).isRenderable())
        assertTrue(asset(AvatarAvailabilityFfi.STALE).isRenderable())
    }

    /** An asset with nothing stored has nothing to draw, whatever its acquisition is doing. */
    @Test
    fun missingAndInvalidatedAssetsAreNotRenderable() {
        assertFalse(asset(AvatarAvailabilityFfi.MISSING).isRenderable())
        assertFalse(asset(AvatarAvailabilityFfi.INVALIDATED).isRenderable())
        assertFalse(asset(AvatarAvailabilityFfi.READY, reference = null).isRenderable())
    }

    /** The cache key follows the content revision, so a refreshed avatar cannot reuse the old bytes. */
    @Test
    fun cacheKeyChangesWithTheContentRevision() {
        val first = asset(AvatarAvailabilityFfi.READY, revision = 1uL).cacheKey()
        val second = asset(AvatarAvailabilityFfi.READY, revision = 2uL).cacheKey()

        assertNotEquals(first, second)
        assertEquals("marmot-avatar:ref-1@1", first)
    }

    /** An asset the engine holds nothing for occupies no cache key at all. */
    @Test
    fun anAssetWithoutAReferenceHasNoCacheKey() {
        assertNull(asset(AvatarAvailabilityFfi.MISSING, reference = null).cacheKey())
    }

    /** Two rows sharing one avatar reference share its cache entry rather than decoding twice. */
    @Test
    fun theSameReferenceAndRevisionShareOneKey() {
        assertEquals(
            asset(AvatarAvailabilityFfi.READY).cacheKey(),
            asset(AvatarAvailabilityFfi.STALE).cacheKey(),
        )
    }

    private fun asset(
        availability: AvatarAvailabilityFfi,
        reference: String? = "ref-1",
        revision: ULong = 1uL,
    ) = AvatarAssetFfi(
        target = "target-1",
        reference = reference,
        availability = availability,
        acquisition = AvatarAcquisitionStateFfi.IDLE,
        contentRevision = revision,
        byteCount = 1_024uL,
    )
}
