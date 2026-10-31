package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure admission tests supply no kernel identity, process death or decoded-format evidence. */
class AvStreamPolicyTest {
    @Test
    fun uncertainStartupOrDisconnectCannotReleaseAdmission() {
        val lifetime = AvStreamLifetime()
        lifetime.bindCompleted(true)
        lifetime.close()
        assertTrue(lifetime.beginUnbind()) // Ask AMS to retire promptly, independently of endpoint cooperation.
        lifetime.unbindFinished(true)
        lifetime.deathObserved()
        assertFalse(lifetime.releasable()) // Binder death is not process death.
        lifetime.processGoneObserved()
        assertFalse(lifetime.beginUnbind())
        assertTrue(lifetime.releasable())
    }

    @Test
    fun unknownBindOutcomeRemainsHeldUntilExactDeathAndCleanup() {
        val lifetime = AvStreamLifetime()
        lifetime.bindOutcomeUnknown()
        lifetime.close()
        assertTrue(lifetime.beginUnbind())
        lifetime.unbindFinished(true)
        lifetime.deathObserved()
        assertFalse(lifetime.releasable())
        lifetime.processGoneObserved()
        assertTrue(lifetime.releasable())
    }

    @Test
    fun sourceReplacementWaitsForBothCallerCopyAndOutstandingRpc() {
        val lifetime = AvStreamLifetime()
        lifetime.beginCall()
        lifetime.beginRpc()
        lifetime.beginRetirement()
        lifetime.bindCompleted(true)
        lifetime.close()
        lifetime.deathObserved()
        assertTrue(lifetime.beginUnbind())
        lifetime.unbindFinished(true)
        lifetime.endCall()
        assertFalse(lifetime.releasable())
        lifetime.endRpc()
        assertFalse(lifetime.releasable())
        lifetime.processGoneObserved()
        assertFalse(lifetime.releasable())
        lifetime.endRetirement()
        assertTrue(lifetime.releasable())
    }

    @Test
    fun failedUnbindNeverOpensAnotherGeneration() {
        val lifetime = AvStreamLifetime()
        lifetime.bindCompleted(true)
        lifetime.close()
        lifetime.deathObserved()
        lifetime.processGoneObserved()
        assertTrue(lifetime.beginUnbind())
        lifetime.unbindFinished(false)
        assertFalse(lifetime.releasable())
        assertFalse(lifetime.beginUnbind())
    }

    @Test
    fun definitiveFalseBindNeedsNoInventedWorkerDeath() {
        val lifetime = AvStreamLifetime()
        lifetime.bindCompleted(false)
        assertFalse(lifetime.releasable())
        lifetime.close()
        assertFalse(lifetime.releasable())
        assertTrue(lifetime.beginUnbind())
        lifetime.unbindFinished(true)
        assertTrue(lifetime.releasable())
    }

    @Test
    fun preBindAllocationFailureHasNoFrameworkConnectionToUnbind() {
        val lifetime = AvStreamLifetime()
        lifetime.neverBound()
        lifetime.close()
        assertFalse(lifetime.beginUnbind())
        assertTrue(lifetime.releasable())
    }

    @Test
    fun falseBindStillRequiresOneSuccessfulConnectionRelease() {
        val lifetime = AvStreamLifetime()
        lifetime.bindCompleted(false)
        lifetime.close()
        assertTrue(lifetime.beginUnbind())
        lifetime.unbindFinished(false)
        assertFalse(lifetime.releasable())
        assertFalse(lifetime.beginUnbind())
    }

    @Test
    fun eofIsAStreamResultAndDoesNotCloseAdmission() {
        val lifetime = AvStreamLifetime()
        lifetime.bindCompleted(true)
        assertEquals(0, AvStreamOutput.from(LongArray(7), 4096, true, true).kind)
        assertFalse(lifetime.closed)
        assertFalse(lifetime.releasable())
        rejected { AvStreamOutput.from(longArrayOf(0, 0, 0, 0, 0, 1, 0), 4096, true, true) }
    }

    @Test
    fun metadataAcceptsAudioOnlyOrVideoOnlyAndRefusesInvalidLayout() {
        assertTrue(avStreamInfo(longArrayOf(0, 0, 1_000_000, 0, 48000, 2)).hasAudio)
        assertTrue(avStreamInfo(longArrayOf(1920, 1080, 1_000_000, 90, 0, 0)).hasVideo)
        for (invalid in listOf(
            longArrayOf(0, 0, 0, 0, 0, 0),
            longArrayOf(0, 1, 0, 0, 48000, 2),
            longArrayOf(1920, 1080, -1, 0, 0, 0),
            longArrayOf(1920, 1080, 1, 45, 0, 0),
            longArrayOf(0, 0, 1, 0, 192000, 2),
            longArrayOf(0, 0, 1, 0, 48000, 8),
            longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE, 1, 0, 0, 0),
        )) {
            rejected { avStreamInfo(invalid) }
        }
    }

    @Test
    fun pixelsRequireExactBoundedPackedRgbaAndRequestedTrack() {
        val valid = longArrayOf(1, 1920, 1080, 0, 0, 123, 1920L * 1080 * 4)
        assertEquals(1920 * 1080 * 4, AvStreamOutput.from(valid, 4096, true, true).byteCount)
        rejected { AvStreamOutput.from(valid, 4096, false, true) }
        rejected { AvStreamOutput.from(valid, 1280, true, true) }
        rejected { AvStreamOutput.from(valid.copyOf().also { it[6]-- }, 4096, true, true) }
        rejected { AvStreamOutput.from(valid.copyOf().also { it[1] = Long.MAX_VALUE }, 4096, true, true) }
        rejected { AvStreamOutput.from(valid.copyOf().also { it[3] = 48000 }, 4096, true, true) }
    }

    @Test
    fun pcmRequiresSmallWholePcm16FramesAndRequestedTrack() {
        val valid = longArrayOf(2, 0, 0, 48000, 2, 123, 4096)
        assertEquals(4096, AvStreamOutput.from(valid, 4096, true, true).byteCount)
        rejected { AvStreamOutput.from(valid, 4096, true, false) }
        rejected { AvStreamOutput.from(valid.copyOf().also { it[6] = 4095 }, 4096, true, true) }
        rejected {
            AvStreamOutput.from(
                valid.copyOf().also { it[6] = AvStreamProtocol.PCM_BYTES + 4L },
                4096,
                true,
                true,
            )
        }
        rejected { AvStreamOutput.from(valid.copyOf().also { it[4] = 0 }, 4096, true, true) }
        rejected { AvStreamOutput.from(valid.copyOf().also { it[5] = -10_000_001 }, 4096, true, true) }
    }

    @Test
    fun analysisCannotStarveOrReplaceAPlaybackOwner() {
        val admissions = AvStreamAdmissions<Any>()
        val epoch = admissions.epoch()
        val playback = Any()
        val analysis = Any()
        assertTrue(admissions.reserve(AvStreamLane.Playback, epoch) { playback } === playback)
        assertTrue(admissions.reserve(AvStreamLane.Analysis, epoch) { analysis } === analysis)
        assertEquals(null, admissions.reserve(AvStreamLane.Playback, epoch) { Any() })
        admissions.release(AvStreamLane.Playback, Any())
        assertTrue(admissions.current(AvStreamLane.Playback, epoch) === playback)
        admissions.release(AvStreamLane.Analysis, analysis)
        assertTrue(admissions.current(AvStreamLane.Playback, epoch) === playback)
    }

    @Test
    fun accountInvalidationRevokesWaitingOpenButRetainsBothPriorOwners() {
        val admissions = AvStreamAdmissions<Any>()
        val epoch = admissions.epoch()
        val playback = Any()
        val analysis = Any()
        admissions.reserve(AvStreamLane.Playback, epoch) { playback }
        admissions.reserve(AvStreamLane.Analysis, epoch) { analysis }
        assertEquals(setOf(playback, analysis), admissions.invalidateAll().toSet())
        rejected { admissions.current(AvStreamLane.Playback, epoch) }
        rejected { admissions.reserve(AvStreamLane.Analysis, epoch) { Any() } }
        val current = admissions.epoch()
        assertEquals(null, admissions.reserve(AvStreamLane.Playback, current) { Any() })
        admissions.release(AvStreamLane.Playback, playback)
        assertTrue(admissions.reserve(AvStreamLane.Playback, current) { Any() } != null)
        assertTrue(admissions.current(AvStreamLane.Analysis, current) === analysis)
    }

    @Test
    fun epochInvalidationRejectsAReplyBeforeCloseHasRun() {
        val admissions = AvStreamAdmissions<Any>()
        val epoch = admissions.epoch()
        val owner = Any()
        val lifetime = AvStreamLifetime()
        admissions.reserve(AvStreamLane.Playback, epoch) { owner }
        var delivered = false
        admissions.invalidateAll()
        assertFalse(lifetime.closed) // Exercise the gap before retireActive invokes per-owner close.
        assertFalse(
            admissions.accept(AvStreamLane.Playback, epoch, owner) {
                delivered = true
                true
            },
        )
        assertFalse(delivered)
        assertFalse(admissions.isCurrent(AvStreamLane.Playback, epoch, owner))
        assertTrue(admissions.current(AvStreamLane.Playback, admissions.epoch()) === owner)
    }

    private fun rejected(action: () -> Unit) {
        assertTrue(runCatching(action).isFailure)
    }
}
