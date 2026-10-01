package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.AvatarBytesFfi
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
    /** An exact ready/stale local payload belongs to its selected reference and immutable revision. */
    @Test
    fun durablePayloadMatchesOnlyTheSelectedReferenceAndRevision() {
        val selected = asset(AvatarAvailabilityFfi.READY)
        val bytes = payload()
        assertTrue(bytes.matchesAvatarAsset(selected))
        assertTrue(bytes.copy(availability = AvatarAvailabilityFfi.STALE).matchesAvatarAsset(selected))
        assertFalse(bytes.copy(reference = "unrelated").matchesAvatarAsset(selected))
        assertFalse(bytes.copy(contentRevision = 2uL).matchesAvatarAsset(selected))
        assertFalse(bytes.copy(deferred = true).matchesAvatarAsset(selected))
        assertFalse(bytes.copy(availability = AvatarAvailabilityFfi.INVALIDATED).matchesAvatarAsset(selected))
        assertFalse(bytes.copy(availability = AvatarAvailabilityFfi.MISSING).matchesAvatarAsset(selected))
        assertFalse(bytes.copy(bytes = byteArrayOf()).matchesAvatarAsset(selected))
    }

    /** Synthetic engine payload; Android checks presentation ownership, not protocol validity. */
    private fun payload() =
        AvatarBytesFfi(
            reference = "ref-1",
            availability = AvatarAvailabilityFfi.READY,
            contentRevision = 1uL,
            byteCount = 3uL,
            deferred = false,
            bytes = byteArrayOf(1, 2, 3),
            mediaType = "image/png",
            width = 1u,
            height = 1u,
        )

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
        val first = asset(AvatarAvailabilityFfi.READY, revision = 1uL).cacheKey("acct")
        val second = asset(AvatarAvailabilityFfi.READY, revision = 2uL).cacheKey("acct")

        assertNotEquals(first, second)
        assertEquals("marmot-avatar:acct:ref-1@1", first)
    }

    /** An asset the engine holds nothing for occupies no cache key at all. */
    @Test
    fun anAssetWithoutAReferenceHasNoCacheKey() {
        assertNull(asset(AvatarAvailabilityFfi.MISSING, reference = null).cacheKey("acct"))
    }

    /** Two rows sharing one avatar reference share its cache entry rather than decoding twice. */
    @Test
    fun theSameReferenceAndRevisionShareOneKey() {
        assertEquals(
            asset(AvatarAvailabilityFfi.READY).cacheKey("acct"),
            asset(AvatarAvailabilityFfi.STALE).cacheKey("acct"),
        )
        assertNotEquals(
            asset(AvatarAvailabilityFfi.READY).cacheKey("acct"),
            asset(AvatarAvailabilityFfi.READY).cacheKey("other"),
        )
    }

    /** A visible asset the engine does not hold, or holds stale, is requested; a ready one is not. */
    @Test
    fun onlyUnreadyAssetsAskForAcquisition() {
        assertFalse(asset(AvatarAvailabilityFfi.READY).wantsAcquisition())
        assertTrue(asset(AvatarAvailabilityFfi.MISSING).wantsAcquisition())
        assertTrue(asset(AvatarAvailabilityFfi.STALE).wantsAcquisition())
        assertTrue(asset(AvatarAvailabilityFfi.INVALIDATED).wantsAcquisition())
    }

    /** An acquisition the engine already queued or is fetching is not requested again. */
    @Test
    fun inFlightAcquisitionIsNotRepeated() {
        assertFalse(missing(AvatarAcquisitionStateFfi.QUEUED).wantsAcquisition())
        assertFalse(missing(AvatarAcquisitionStateFfi.FETCHING).wantsAcquisition())
        assertTrue(missing(AvatarAcquisitionStateFfi.RETRY_SCHEDULED).wantsAcquisition())
    }

    private fun missing(acquisition: AvatarAcquisitionStateFfi): AvatarAssetFfi {
        val availability = AvatarAvailabilityFfi.MISSING
        return asset(availability, acquisition = acquisition)
    }

    private fun asset(
        availability: AvatarAvailabilityFfi,
        reference: String? = "ref-1",
        revision: ULong = 1uL,
        acquisition: AvatarAcquisitionStateFfi = AvatarAcquisitionStateFfi.IDLE,
    ) = AvatarAssetFfi(
        target = "target-1",
        reference = reference,
        availability = availability,
        acquisition = acquisition,
        contentRevision = revision,
        byteCount = 1_024uL,
    )
}
