package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure decisions that stay Android's call around the #745 sweep now
 * that gate and prune are engine-owned (`sweepExpiredRetention`): when a
 * loaded row hides locally ahead of the engine prune, when the open
 * conversation's sweep loop wakes next, and which in-memory cache keys belong
 * to a pruned group.
 */
class DisappearingMessageSweepTest {
    @Test
    fun timerOffMeansNoLocalExpiryDecisions() {
        // Retention 0 == disappearing messages off; every local decision is a no-op.
        assertFalse(DisappearingMessageSweep.shouldSweepGroup(0uL))
        assertTrue(DisappearingMessageSweep.shouldSweepGroup(1uL))
        assertTrue(DisappearingMessageSweep.shouldSweepGroup(60uL))
        assertTrue(DisappearingMessageSweep.shouldSweepGroup(ULong.MAX_VALUE))
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
    fun foregroundSweepTimeoutTargetsLoadedExpiryBoundary() {
        // The await loop uses this delay as its timeout; when it elapses the
        // caller immediately runs the foreground sweep/publish pass.
        assertEquals(
            1L,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 999_999L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(940uL),
            ),
        )
        assertTrue(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = false,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 999_000L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(940uL),
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = 940uL,
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
    fun foregroundRescheduleSignalSweepsNewlyExpiredLoadedRow() {
        assertTrue(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 998_999L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(940uL),
            ),
        )
    }

    @Test
    fun foregroundRescheduleSignalOnlyReschedulesWhenNoLoadedRowExpired() {
        assertFalse(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 998_999L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(941uL),
            ),
        )
        assertFalse(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 998_999L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = emptyList(),
            ),
        )
    }

    @Test
    fun foregroundRescheduleSignalRespectsNearBoundaryRetryGuard() {
        assertFalse(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 999_001L,
                disappearingMessageSecs = 60uL,
                timelineAtSeconds = listOf(940uL),
            ),
        )
        assertTrue(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 999_000L,
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
    fun unreadReceivedRowsDeferSendTimeLocalExpiry() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 940uL,
                deferSendTimeExpiry = true,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 10_000_000L,
                disappearingMessageSecs = 60uL,
                row = row,
            ),
        )
    }

    @Test
    fun readAnchoredExpiryUsesDisplayAnchorPlusRetention() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                readAnchoredAtSeconds = 1_000uL,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_059_000L,
                disappearingMessageSecs = 60uL,
                row = row,
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_060_000L,
                disappearingMessageSecs = 60uL,
                row = row,
            ),
        )
    }

    @Test
    fun readAnchorOutranksEngineSendTimeExpiry() {
        // The engine's expires_at is send-time based; a session read anchor
        // must keep the #797 display window even when the engine value has
        // already passed.
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                readAnchoredAtSeconds = 1_000uL,
                expiresAtLocalSeconds = 160uL,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_000_000L,
                disappearingMessageSecs = 60uL,
                row = row,
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_060_000L,
                disappearingMessageSecs = 60uL,
                row = row,
            ),
        )
    }

    @Test
    fun unreadDeferralOutranksEngineSendTimeExpiry() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                expiresAtLocalSeconds = 160uL,
                deferSendTimeExpiry = true,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 10_000_000L,
                disappearingMessageSecs = 60uL,
                row = row,
            ),
        )
    }

    @Test
    fun engineExpiryPinsSourceEpochRetentionOverTheCurrentWindow() {
        // A message sent under a 60s timer keeps its original expiry even
        // after the group's window widens: the engine value replaces the
        // send-time arithmetic.
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                expiresAtLocalSeconds = 160uL,
            )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 160_000L,
                disappearingMessageSecs = 3_600uL,
                row = row,
            ),
        )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 159_000L,
                disappearingMessageSecs = 3_600uL,
                row = row,
            ),
        )
    }

    @Test
    fun mediaCacheKeyGroupPredicateMatchesOnlyItsGroupSlice() {
        val key = mediaCacheKey("account-a", "group-a", "message-a", 0)

        assertTrue(mediaCacheKeyInGroup(key, "account-a", "group-a"))
        assertFalse(mediaCacheKeyInGroup(key, "account-a", "group-b"))
        assertFalse(mediaCacheKeyInGroup(key, "account-b", "group-a"))
    }

    @Test
    fun mediaCacheKeyGroupPredicateRequiresTheFullGroupId() {
        // A group id that prefixes a longer one must not claim its keys; the
        // trailing separator in the predicate pins the full segment.
        val longerGroupKey = mediaCacheKey("account-a", "group-ab", "message-a", 0)

        assertFalse(mediaCacheKeyInGroup(longerGroupKey, "account-a", "group-a"))
        assertTrue(mediaCacheKeyInGroup(longerGroupKey, "account-a", "group-ab"))
    }

    @Test
    fun mediaCacheKeyGroupPredicateToleratesHexCasingDrift() {
        // Hex ids drift in casing across sources (projector precedent); a
        // drifted sweep outcome must still reach the minted keys.
        val key = mediaCacheKey("account-a", "0ABCDEF0", "message-a", 0)

        assertTrue(mediaCacheKeyInGroup(key, "account-a", "0abcdef0"))
        assertTrue(mediaCacheKeyInGroup(mediaCacheKey("account-a", "0abcdef0", "m", 0), "account-a", "0ABCDEF0"))
    }

    @Test
    fun staleGroupSliceKeysDropOnlyUnloadedRowsOfThatGroup() {
        val loadedKey = mediaCacheKey("account-a", "group-a", "message-loaded", 0)
        val trimmedKey = mediaCacheKey("account-a", "group-a", "message-trimmed", 1)
        val otherGroupKey = mediaCacheKey("account-a", "group-b", "message-other", 0)
        val otherAccountKey = mediaCacheKey("account-b", "group-a", "message-other", 0)

        val stale =
            staleGroupMediaCacheKeys(
                cachedKeys = listOf(loadedKey, trimmedKey, otherGroupKey, otherAccountKey),
                account = "account-a",
                groupIdHex = "group-a",
                loadedMessageIds = setOf("message-loaded"),
            )

        // Only the trimmed row's key goes: loaded rows keep their entries and
        // other groups/accounts are untouched.
        assertEquals(listOf(trimmedKey), stale)
    }

    @Test
    fun staleGroupSliceKeysMatchLoadedRowsCaseInsensitively() {
        val key = mediaCacheKey("account-a", "group-a", "0MESSAGE0", 0)

        assertTrue(
            staleGroupMediaCacheKeys(
                cachedKeys = listOf(key),
                account = "account-a",
                groupIdHex = "group-a",
                loadedMessageIds = setOf("0message0"),
            ).isEmpty(),
        )
    }
}
