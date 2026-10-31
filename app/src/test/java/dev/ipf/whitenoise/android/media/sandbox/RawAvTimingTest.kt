package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawAvTimingTest {
    @Test
    fun quarterSpeedClockReanchorsAfterMaximumRatePauseAndSeekWithoutAudioHardware() {
        val atMaximum = RawAvTiming.wallPosition(2_000_000, 0, 1000, RawPcmCapacity.MAX_SPEED)
        assertEquals(4_000_000L, atMaximum)
        val atQuarter = RawAvTiming.wallPosition(atMaximum, 1000, 3000, RawPcmCapacity.MIN_SPEED)
        assertEquals(4_500_000L, atQuarter)
        // A paused snapshot is its stored anchor; resuming starts a fresh elapsed interval.
        assertEquals(atQuarter, RawAvTiming.wallPosition(atQuarter, 3000, 3000, RawPcmCapacity.MIN_SPEED))
        assertEquals(4_750_000L, RawAvTiming.wallPosition(atQuarter, 3000, 4000, RawPcmCapacity.MIN_SPEED))
        val sought = RawAvTiming.wallPosition(5_000_000, 4000, 4000, RawPcmCapacity.MIN_SPEED)
        assertEquals(5_000_000L, sought)
        assertEquals(5_250_000L, RawAvTiming.wallPosition(sought, 4000, 5000, RawPcmCapacity.MIN_SPEED))
        assertTrue(RawAvTiming.videoIsDue(5_250_000, 5_250_000))
        assertFalse(RawAvTiming.videoIsDue(5_300_000, 5_250_000))
    }

    @Test
    fun seekKeepsThePcmSuffixCrossingTheTarget() {
        val slice = RawAvTiming.clipPcm(48000 * 4, 48000, 2, 0, 250_000)
        assertEquals(12000 * 4, slice.offset)
        assertEquals(36000 * 4, slice.byteCount)
        assertEquals(250_000L, slice.timestampUs)
    }

    @Test
    fun negativePrerollAndWholeBuffersBeforeTargetDoNotResetTheTimeline() {
        val crossing = RawAvTiming.clipPcm(4800 * 2, 48000, 1, -50_000, 0)
        assertEquals(2400 * 2, crossing.offset)
        assertEquals(2400 * 2, crossing.byteCount)
        assertEquals(0L, crossing.timestampUs)
        val before = RawAvTiming.clipPcm(4800 * 2, 48000, 1, -100_000, 0)
        assertEquals(0, before.byteCount)
        assertEquals(0L, before.timestampUs)
    }

    @Test
    fun fractionalSeekSkipsWholeFramesWithoutDroppingTheRestOfTheBuffer() {
        val slice = RawAvTiming.clipPcm(4410 * 4, 44100, 2, 0, 1)
        assertEquals(4, slice.offset)
        assertEquals(4410 * 4 - 4, slice.byteCount)
        assertTrue(slice.timestampUs >= 1)
    }

    @Test
    fun sampleRoundedClockCannotStallAtAVideoBoundary() {
        assertTrue(RawAvTiming.videoIsDue(33_333, 33_250))
        assertFalse(RawAvTiming.videoIsDue(33_333, 32_000))
    }

    @Test
    fun silentVideoClockPreservesAnchorAndPlaybackSpeed() {
        assertEquals(250_000L, RawAvTiming.wallPosition(250_000, 100, 100, 1f))
        assertEquals(400_000L, RawAvTiming.wallPosition(250_000, 100, 200, 1.5f))
        assertEquals(300_000L, RawAvTiming.wallPosition(250_000, 100, 200, 0.5f))
        assertEquals(250_000L, RawAvTiming.wallPosition(250_000, 100, 99, 1f))
        assertTrue(runCatching { RawAvTiming.wallPosition(0, 0, 100, Float.NaN) }.isFailure)
    }

    @Test
    fun oldPlaybackAndSeekOutputsCannotDriveAReplacement() {
        val requested = RawAvGeneration(4, 2)
        assertTrue(requested.accepts(RawAvGeneration(4, 2), true, true))
        assertFalse(requested.accepts(RawAvGeneration(5, 2), true, true))
        assertFalse(requested.accepts(RawAvGeneration(4, 3), true, true))
        assertFalse(requested.accepts(RawAvGeneration(4, 2), false, true))
        assertFalse(requested.accepts(RawAvGeneration(4, 2), true, false))
        assertTrue(requested.accepts(RawAvGeneration(4, 3), true, true, includeSeek = false))
    }

    @Test
    fun pausedPosterCannotReplaceAPlayingNewSeekOrRetiredAccount() {
        val target = RawAvPosterTarget(RawAvGeneration(4, 2), 1250)
        assertTrue(target.matches(RawAvGeneration(4, 2), true, false))
        assertFalse(target.matches(RawAvGeneration(4, 2), true, false, 0))
        val zeroProbe = RawAvPosterTarget(target.generation, 0)
        assertFalse(zeroProbe.matches(target.generation, true, false, 1250))
        assertFalse(target.matches(RawAvGeneration(4, 3), true, false))
        assertFalse(target.matches(RawAvGeneration(5, 2), true, false))
        assertFalse(target.matches(RawAvGeneration(4, 2), true, true))
        assertFalse(target.matches(RawAvGeneration(4, 2), false, false))
    }

    @Test
    fun pausedPosterUsesTheSameBoundedSeekTargetAsThePlaybackClock() {
        assertEquals(0L, RawAvPosterTarget.positionUs(-1, 2_000_000))
        assertEquals(1_250_000L, RawAvPosterTarget.positionUs(1250, 2_000_000))
        assertEquals(2_000_000L, RawAvPosterTarget.positionUs(3000, 2_000_000))
        assertEquals(3_000_000L, RawAvPosterTarget.positionUs(3000, 0))
        assertEquals(AvStreamProtocol.MAX_DURATION_US, RawAvPosterTarget.positionUs(Long.MAX_VALUE, 0))
    }

    @Test
    fun pausedSeekAcknowledgesOnlyTheCompletedCurrentAttempt() {
        val completion =
            com.google.common.util.concurrent.SettableFuture
                .create<Void>()
        val request = RawAvPausedSeekRequest(RawAvPosterTarget(RawAvGeneration(4, 2), 1250), completion)
        assertFalse(completion.isDone)
        request.finish(true) // Current refusal is also an ended attempt, without a fabricated poster.
        assertTrue(completion.isDone)
        assertFalse(completion.isCancelled)
        val stale =
            com.google.common.util.concurrent.SettableFuture
                .create<Void>()
        RawAvPausedSeekRequest(request.target, stale).finish(false)
        assertTrue(stale.isCancelled)
    }
}
