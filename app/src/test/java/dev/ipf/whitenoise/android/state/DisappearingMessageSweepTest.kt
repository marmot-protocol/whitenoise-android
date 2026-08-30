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
    /** Verifies that projected history without a pinned deadline is permanent. */
    @Test
    fun projectedRowWithoutPinnedRetentionNeverExpires() {
        val row = DisappearingMessageSweep.LocalExpiryRow(timelineAtSeconds = 1uL)

        assertFalse(DisappearingMessageSweep.hasLocalExpiry(row))
        assertFalse(DisappearingMessageSweep.isLocallyExpired(Long.MAX_VALUE, row))
    }

    /** Verifies that opening an old row cannot assign the current policy to it. */
    @Test
    fun readAnchorCannotRetroactivelyCreateAPrePolicyDeadline() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 1uL,
                readAnchoredAtSeconds = 10uL,
            )

        assertFalse(DisappearingMessageSweep.hasLocalExpiry(row))
        assertFalse(DisappearingMessageSweep.isLocallyExpired(Long.MAX_VALUE, row))
    }

    /** Verifies the bounded send-time fallback used by an optimistic message. */
    @Test
    fun optimisticSendSnapshotOwnsItsFallbackDeadline() {
        val row = retainedRow(timelineAtSeconds = 940uL)

        assertFalse(DisappearingMessageSweep.isLocallyExpired(999_999L, row))
        assertTrue(DisappearingMessageSweep.isLocallyExpired(1_000_000L, row))
    }

    /** Keeps the foreground-sweep and publish-filter expiry boundary identical. */
    @Test
    fun foregroundLocalExpiryMatchesPublishFilterBoundary() {
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 999_999L,
                row = retainedRow(940uL),
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_000_000L,
                row = retainedRow(940uL),
            ),
        )
    }

    /** Ensures unpinned and empty windows use the low-frequency safety wake. */
    @Test
    fun foregroundSweepDelayIgnoresUnpinnedAndEmptyWindows() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                rows = listOf(DisappearingMessageSweep.LocalExpiryRow(940uL)),
            ),
        )
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                rows = emptyList(),
            ),
        )
    }

    /** Schedules the next wake for the earliest pinned loaded-row deadline. */
    @Test
    fun foregroundSweepDelayTargetsEarliestLoadedExpiry() {
        // 940s + 60s retention expires at 1_000_000ms, sooner than 980s + 60s.
        assertEquals(
            750L,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 999_250L,
                rows = listOf(retainedRow(980uL), retainedRow(940uL)),
            ),
        )
    }

    /** Proves the timeout path sweeps exactly when a loaded row expires. */
    @Test
    fun foregroundSweepTimeoutTargetsLoadedExpiryBoundary() {
        // The await loop uses this delay as its timeout; when it elapses the
        // caller immediately runs the foreground sweep/publish pass.
        assertEquals(
            1L,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 999_999L,
                rows = listOf(retainedRow(940uL)),
            ),
        )
        assertTrue(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = false,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 999_000L,
                rows = listOf(retainedRow(940uL)),
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_000_000L,
                row = retainedRow(940uL),
            ),
        )
    }

    /** Caps distant deadlines so engine convergence still gets periodic checks. */
    @Test
    fun foregroundSweepDelayCapsFarFutureExpiry() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                rows = listOf(retainedRow(10_000uL)),
            ),
        )
    }

    /** Retries only rows that have just crossed their expiry boundary. */
    @Test
    fun foregroundSweepDelayRetriesOnlyNearBoundaryExpiredRows() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_EXPIRED_RETRY_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                rows = listOf(retainedRow(940uL)),
            ),
        )
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_001_000L,
                rows = listOf(retainedRow(940uL)),
            ),
        )
    }

    /** A reschedule signal sweeps a loaded row that expired since the last pass. */
    @Test
    fun foregroundRescheduleSignalSweepsNewlyExpiredLoadedRow() {
        assertTrue(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 998_999L,
                rows = listOf(retainedRow(940uL)),
            ),
        )
    }

    /** A signal only reschedules when none of the loaded rows are newly expired. */
    @Test
    fun foregroundRescheduleSignalOnlyReschedulesWhenNoLoadedRowExpired() {
        assertFalse(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 998_999L,
                rows = listOf(retainedRow(941uL)),
            ),
        )
        assertFalse(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 998_999L,
                rows = emptyList(),
            ),
        )
    }

    /** The retry guard prevents a tight loop at an expiry boundary. */
    @Test
    fun foregroundRescheduleSignalRespectsNearBoundaryRetryGuard() {
        assertFalse(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 999_001L,
                rows = listOf(retainedRow(940uL)),
            ),
        )
        assertTrue(
            DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
                wakeSignalReceived = true,
                nowMillis = 1_000_000L,
                lastSweepStartedAtMillis = 999_000L,
                rows = listOf(retainedRow(940uL)),
            ),
        )
    }

    /** An unrepresentable millisecond deadline falls back to the safety wake. */
    @Test
    fun foregroundSweepDelayIgnoresExpiryThatCannotFitInMillis() {
        assertEquals(
            DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS,
            DisappearingMessageSweep.nextForegroundSweepDelayMillis(
                nowMillis = 1_000_000L,
                rows = listOf(retainedRow(940uL, ULong.MAX_VALUE)),
            ),
        )
    }

    /** Unread received rows retain their read-anchored deferral semantics. */
    @Test
    fun unreadReceivedRowsDeferSendTimeLocalExpiry() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 940uL,
                retentionAtSendSeconds = 60uL,
                deferSendTimeExpiry = true,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 10_000_000L,
                row = row,
            ),
        )
    }

    /** Read-anchored expiry is derived from the anchor and the row's duration. */
    @Test
    fun readAnchoredExpiryUsesDisplayAnchorPlusRetention() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                retentionAtSendSeconds = 60uL,
                readAnchoredAtSeconds = 1_000uL,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_059_000L,
                row = row,
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_060_000L,
                row = row,
            ),
        )
    }

    /** A valid display anchor outranks an earlier engine send-time deadline. */
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
                retentionAtSendSeconds = 60uL,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_000_000L,
                row = row,
            ),
        )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_060_000L,
                row = row,
            ),
        )
    }

    /** Unread deferral outranks an already-passed engine send-time deadline. */
    @Test
    fun unreadDeferralOutranksEngineSendTimeExpiry() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                expiresAtLocalSeconds = 160uL,
                retentionAtSendSeconds = 60uL,
                deferSendTimeExpiry = true,
            )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 10_000_000L,
                row = row,
            ),
        )
    }

    /** The engine's pinned source-epoch deadline is independent of current policy. */
    @Test
    fun engineExpiryUsesThePinnedSourceEpochDeadline() {
        // The engine value stands alone; no current group policy participates.
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                expiresAtLocalSeconds = 160uL,
            )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 160_000L,
                row = row,
            ),
        )
        assertFalse(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 159_000L,
                row = row,
            ),
        )
    }

    /** Matches only media-cache keys that belong to the requested group. */
    @Test
    fun mediaCacheKeyGroupPredicateMatchesOnlyItsGroupSlice() {
        val key = mediaCacheKey("account-a", "group-a", "message-a", 0)

        assertTrue(mediaCacheKeyInGroup(key, "account-a", "group-a"))
        assertFalse(mediaCacheKeyInGroup(key, "account-a", "group-b"))
        assertFalse(mediaCacheKeyInGroup(key, "account-b", "group-a"))
    }

    /** Rejects cache-key prefixes that do not contain the complete group id. */
    @Test
    fun mediaCacheKeyGroupPredicateRequiresTheFullGroupId() {
        // A group id that prefixes a longer one must not claim its keys; the
        // trailing separator in the predicate pins the full segment.
        val longerGroupKey = mediaCacheKey("account-a", "group-ab", "message-a", 0)

        assertFalse(mediaCacheKeyInGroup(longerGroupKey, "account-a", "group-a"))
        assertTrue(mediaCacheKeyInGroup(longerGroupKey, "account-a", "group-ab"))
    }

    /** Treats hexadecimal group-id casing as semantically equivalent. */
    @Test
    fun mediaCacheKeyGroupPredicateToleratesHexCasingDrift() {
        // Hex ids drift in casing across sources (projector precedent); a
        // drifted sweep outcome must still reach the minted keys.
        val key = mediaCacheKey("account-a", "0ABCDEF0", "message-a", 0)

        assertTrue(mediaCacheKeyInGroup(key, "account-a", "0abcdef0"))
        assertTrue(mediaCacheKeyInGroup(mediaCacheKey("account-a", "0abcdef0", "m", 0), "account-a", "0ABCDEF0"))
    }

    /** Drops only unloaded cache rows in the pruned group's media slice. */
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

    /** Preserves loaded group rows even when their key casing differs. */
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

    /** Builds a row whose expiry is pinned to its own send-time snapshot. */
    private fun retainedRow(
        timelineAtSeconds: ULong,
        retentionAtSendSeconds: ULong = 60uL,
    ) = DisappearingMessageSweep.LocalExpiryRow(
        timelineAtSeconds = timelineAtSeconds,
        retentionAtSendSeconds = retentionAtSendSeconds,
    )
}
