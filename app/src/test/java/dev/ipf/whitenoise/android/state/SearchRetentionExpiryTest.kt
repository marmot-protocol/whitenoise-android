package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message the user watched disappear must stop answering searches, even while it survives in the
 * store waiting for the engine's periodic prune (#797 retention, hourly sweep).
 */
class SearchRetentionExpiryTest {
    /** A row past its engine-projected deadline is out, and the same row before it is still in. */
    @Test
    fun anExpiredRowLeavesSearchAtItsDeadline() {
        val record = record(timelineAtSeconds = 1_000uL, expiresAtSeconds = 2_000uL)

        assertFalse(isRetentionExpiredForSearch(record, nowMillis = 1_999_000L))
        assertTrue(isRetentionExpiredForSearch(record, nowMillis = 2_000_000L))
        assertTrue(isRetentionExpiredForSearch(record, nowMillis = 9_000_000L))
    }

    /** Without an engine deadline the send time plus the retention duration decides. */
    @Test
    fun sendTimePlusRetentionDecidesWhenNoDeadlineIsProjected() {
        val record = record(timelineAtSeconds = 1_000uL, retentionSeconds = 60uL)

        assertFalse(isRetentionExpiredForSearch(record, nowMillis = 1_059_000L))
        assertTrue(isRetentionExpiredForSearch(record, nowMillis = 1_060_000L))
    }

    /** Retention off must never read as expired, however the engine spells it. */
    @Test
    fun rowsWithoutRetentionNeverLeaveSearch() {
        val noRetention = record(timelineAtSeconds = 1_000uL)
        val zeroed = record(timelineAtSeconds = 1_000uL, expiresAtSeconds = 0uL, retentionSeconds = 0uL)

        assertFalse(isRetentionExpiredForSearch(noRetention, nowMillis = Long.MAX_VALUE / 2))
        assertFalse("zero is retention off, not the epoch", isRetentionExpiredForSearch(zeroed, nowMillis = 9_000_000L))
    }

    private fun record(
        timelineAtSeconds: ULong,
        expiresAtSeconds: ULong? = null,
        retentionSeconds: ULong? = null,
    ) = timelineRecord(messageId = "message", timelineAt = timelineAtSeconds).copy(
        retentionSeconds = retentionSeconds,
        retentionExpiresAt = expiresAtSeconds,
    )
}
