package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure selection + skew decisions behind the #745 background sweep.
 * The engine owns the authoritative prune; these guarantee the Android side
 * (1) never sweeps a group whose timer is off and (2) never treats a message
 * within the clock-skew window as already expired.
 */
class DisappearingMessageSweepTest {
    @Test
    fun skipsGroupsWithTimerOff() {
        // Retention 0 == disappearing messages off; the sweep must be a no-op.
        assertFalse(DisappearingMessageSweep.shouldSweepGroup(0uL))
        assertNull(DisappearingMessageSweep.rawExpiryCutoffSeconds(1_000_000L, 0uL))
        assertNull(DisappearingMessageSweep.expiryCutoffSeconds(1_000_000L, 0uL))
    }

    @Test
    fun sweepsGroupsWithRetentionSet() {
        assertTrue(DisappearingMessageSweep.shouldSweepGroup(1uL))
        assertTrue(DisappearingMessageSweep.shouldSweepGroup(60uL))
        assertTrue(DisappearingMessageSweep.shouldSweepGroup(ULong.MAX_VALUE))
    }

    @Test
    fun expiryCutoffPullsBackBySkewTolerance() {
        val now = 1_000_000L
        assertEquals(
            now - DisappearingMessageSweep.CLOCK_SKEW_TOLERANCE_MS,
            DisappearingMessageSweep.expiryCutoffMillis(now),
        )
    }

    @Test
    fun expiryCutoffNeverGoesNegativeForAnEarlyClock() {
        // A clock reading below the skew margin must floor at zero rather than
        // produce a negative cutoff.
        assertEquals(0L, DisappearingMessageSweep.expiryCutoffMillis(0L))
        assertEquals(
            0L,
            DisappearingMessageSweep.expiryCutoffMillis(DisappearingMessageSweep.CLOCK_SKEW_TOLERANCE_MS - 1),
        )
    }

    @Test
    fun rawExpiryCutoffMatchesEngineCurrentTimeDecision() {
        // Engine cutoff is `unix_now_seconds() - retention` before skew is applied.
        assertEquals(940uL, DisappearingMessageSweep.rawExpiryCutoffSeconds(1_000_000L, 60uL))
    }

    @Test
    fun expiryCutoffSecondsCombinesRetentionAndSkewTolerance() {
        // (1_000_000ms - 5_000ms) / 1000 - 60s retention = 935.
        assertEquals(935uL, DisappearingMessageSweep.expiryCutoffSeconds(1_000_000L, 60uL))
    }

    @Test
    fun expiryCutoffSecondsFloorsWhenRetentionExceedsSkewedNow() {
        assertEquals(0uL, DisappearingMessageSweep.rawExpiryCutoffSeconds(1_000L, 60uL))
        assertEquals(0uL, DisappearingMessageSweep.expiryCutoffSeconds(1_000L, 60uL))
    }

    @Test
    fun rawExpiredRowsInsideSkewWindowAreDeferred() {
        val rawCutoff = DisappearingMessageSweep.rawExpiryCutoffSeconds(1_000_000L, 60uL) ?: error("raw cutoff")
        val skewCutoff = DisappearingMessageSweep.expiryCutoffSeconds(1_000_000L, 60uL) ?: error("skew cutoff")

        // The raw engine would prune timestamps in [935, 940), but the
        // skew-adjusted cutoff deliberately keeps them for the next coarse tick.
        assertTrue(DisappearingMessageSweep.isWithinSkewWindow(935uL, rawCutoff, skewCutoff))
        assertTrue(DisappearingMessageSweep.isWithinSkewWindow(939uL, rawCutoff, skewCutoff))
        assertFalse(DisappearingMessageSweep.isWithinSkewWindow(940uL, rawCutoff, skewCutoff))
        assertFalse(DisappearingMessageSweep.isWithinSkewWindow(934uL, rawCutoff, skewCutoff))
    }

    @Test
    fun onlyRowsOlderThanSkewCutoffAreSafeToPrune() {
        val skewCutoff = DisappearingMessageSweep.expiryCutoffSeconds(1_000_000L, 60uL) ?: error("skew cutoff")

        assertTrue(DisappearingMessageSweep.isExpiredBeyondSkew(934uL, skewCutoff))
        assertFalse(DisappearingMessageSweep.isExpiredBeyondSkew(935uL, skewCutoff))
    }

    @Test
    fun foregroundLocalExpiryMatchesPublishFilterBoundary() {
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 999_999L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = 940uL,
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = 940uL,
            ),
        )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 0uL,
                timelineAtSeconds = 940uL,
            ),
        )
    }

    @Test
    fun foregroundSweepDelayIgnoresTimerOffAndEmptyWindows() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 0uL,
                timelineAtSeconds = listOf(940uL),
            ),
        )
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = emptyList(),
            ),
        )
    }

    @Test
    fun foregroundSweepDelayTargetsEarliestLoadedExpiry() {
        // 940s + 60s retention expires at 1_000_000ms, sooner than 980s + 60s.
        assertEquals(
            750L,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 999_250L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(980uL, 940uL),
            ),
        )
    }

    @Test
    fun foregroundSweepDelayCapsFarFutureExpiry() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(10_000uL),
            ),
        )
    }

    @Test
    fun foregroundSweepDelayRetriesOnlyNearBoundaryExpiredRows() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_EXPIRED_RETRY_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(940uL),
            ),
        )
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_001_000L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(940uL),
            ),
        )
    }

    @Test
    fun foregroundSweepDelayIgnoresExpiryThatCannotFitInMillis() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = ULong.MAX_VALUE,
                timelineAtSeconds = listOf(940uL),
            ),
        )
    }

    @Test
    fun skewToleranceIsSmallButNonZero() {
        // Coarse cadence, small tolerance: enough to absorb device-clock jitter
        // without meaningfully extending the retention window.
        assertTrue(DisappearingMessageSweep.CLOCK_SKEW_TOLERANCE_MS in 1L..60_000L)
    }
}
