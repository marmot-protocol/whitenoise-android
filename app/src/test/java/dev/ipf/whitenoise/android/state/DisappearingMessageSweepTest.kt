package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaRecordFfi
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
    fun foregroundSweepTimeoutTargetsLoadedExpiryBoundary() {
        // The await loop uses this delay as its timeout; when it elapses the
        // caller immediately runs the foreground secure-delete/publish sweep.
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
    fun skewToleranceIsSmallButNonZero() {
        // Coarse cadence, small tolerance: enough to absorb device-clock jitter
        // without meaningfully extending the retention window.
        assertTrue(DisappearingMessageSweep.CLOCK_SKEW_TOLERANCE_MS in 1L..60_000L)
    }

    @Test
    fun scanSeedMessageIdSortsBeforeEveryRealMessageId() {
        // #979: the scan seeds its first cursor at (rawCutoff, all-zeros id).
        // The engine cursor predicate is `timelineAt < before OR
        // (timelineAt == before AND messageIdHex < beforeMessageId)`, so the
        // seed id must sort at-or-before every real 64-hex message id for the
        // seeded page to be exactly `timelineAt < rawCutoff`.
        val seed = DisappearingMessageSweep.TIMELINE_SCAN_SEED_MESSAGE_ID
        assertEquals(64, seed.length)
        assertTrue(seed.all { it == '0' })
        val smallestRealId = "0".repeat(63) + "1"
        assertTrue(seed < smallestRealId)
        assertTrue(seed <= "0".repeat(64))
    }

    @Test
    fun cutoffSeededFirstPageCannotMissAClassifiableRow() {
        // The seed excludes rows with timelineAt >= rawCutoff, so neither
        // classification may ever match such a row — otherwise the seeded scan
        // would silently skip a decision the newest-first scan used to make.
        val rawCutoff = DisappearingMessageSweep.rawExpiryCutoffSeconds(1_000_000L, 60uL) ?: error("raw cutoff")
        val skewCutoff = DisappearingMessageSweep.expiryCutoffSeconds(1_000_000L, 60uL) ?: error("skew cutoff")
        for (timelineAt in listOf(rawCutoff, rawCutoff + 1uL, rawCutoff + 1_000uL)) {
            assertFalse(DisappearingMessageSweep.isWithinSkewWindow(timelineAt, rawCutoff, skewCutoff))
            assertFalse(DisappearingMessageSweep.isExpiredBeyondSkew(timelineAt, skewCutoff))
        }
        // ...while both boundary rows strictly below the cutoff stay classifiable.
        assertTrue(DisappearingMessageSweep.isWithinSkewWindow(rawCutoff - 1uL, rawCutoff, skewCutoff))
        assertTrue(DisappearingMessageSweep.isExpiredBeyondSkew(skewCutoff - 1uL, skewCutoff))
    }

    @Test
    fun classifyScanPageDefersWhenSkewWindowRowSharesPageWithExpiredRows() {
        // The raw engine prune has no cutoff parameter: if the page holds both
        // an expired-beyond-skew row and a skew-window row, pruning would also
        // delete the near-boundary row. Defer must win.
        val rawCutoff = 940uL
        val skewCutoff = 935uL
        assertEquals(
            DisappearingMessageSweep.TimelineScanPageDecision.DeferSkewWindow,
            DisappearingMessageSweep.classifyScanPage(
                timelineAtSeconds = listOf(930uL, 939uL, 950uL),
                rawCutoffSeconds = rawCutoff,
                skewCutoffSeconds = skewCutoff,
            ),
        )
    }

    @Test
    fun classifyScanPageInvokesPruneOnlyForRowsExpiredBeyondSkew() {
        assertEquals(
            DisappearingMessageSweep.TimelineScanPageDecision.InvokeSecureDelete,
            DisappearingMessageSweep.classifyScanPage(
                timelineAtSeconds = listOf(934uL, 950uL),
                rawCutoffSeconds = 940uL,
                skewCutoffSeconds = 935uL,
            ),
        )
    }

    @Test
    fun classifyScanPageKeepsScanningWhenNoRowIsAtOrPastTheBoundary() {
        // Rows at/above the raw cutoff (and an empty page) decide nothing.
        assertEquals(
            DisappearingMessageSweep.TimelineScanPageDecision.KeepScanning,
            DisappearingMessageSweep.classifyScanPage(
                timelineAtSeconds = listOf(940uL, 941uL),
                rawCutoffSeconds = 940uL,
                skewCutoffSeconds = 935uL,
            ),
        )
        assertEquals(
            DisappearingMessageSweep.TimelineScanPageDecision.KeepScanning,
            DisappearingMessageSweep.classifyScanPage(
                timelineAtSeconds = emptyList(),
                rawCutoffSeconds = 940uL,
                skewCutoffSeconds = 935uL,
            ),
        )
    }

    @Test
    fun everyRowBelowTheSeededCutoffClassifiesDecisively() {
        // With the cursor seeded at rawCutoff, every returned row satisfies
        // timelineAt < rawCutoff and must land in exactly one bucket — so a
        // non-empty seeded page always decides and the scan can't walk history.
        val rawCutoff = 940uL
        val skewCutoff = 935uL
        for (timelineAt in 0uL until rawCutoff) {
            val skew = DisappearingMessageSweep.isWithinSkewWindow(timelineAt, rawCutoff, skewCutoff)
            val expired = DisappearingMessageSweep.isExpiredBeyondSkew(timelineAt, skewCutoff)
            assertTrue("row $timelineAt must classify", skew != expired)
        }
    }

    @Test
    fun scanPageCapIsBoundedLikeSearch() {
        // Mirrors SEARCH_MAX_PAGES: a backstop, not the primary bound (the
        // cutoff seed is), so it stays small.
        assertTrue(DisappearingMessageSweep.TIMELINE_SCAN_MAX_PAGES in 1..100)
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
    fun expiresAtLocalSecondsOverrideReadAnchor() {
        val row =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = 100uL,
                readAnchoredAtSeconds = 1_000uL,
                expiresAtLocalSeconds = 1_010uL,
            )
        assertTrue(
            DisappearingMessageSweep.isLocallyExpired(
                nowMillis = 1_010_000L,
                disappearingMessageSecs = 60uL,
                row = row,
            ),
        )
    }

    @Test
    fun backgroundScanDefersUnreadReceivedRowsPastSendTimeCutoff() {
        val rawCutoff = 940uL
        val skewCutoff = 935uL
        assertEquals(
            DisappearingMessageSweep.TimelineScanPageDecision.DeferUnreadReceived,
            DisappearingMessageSweep.classifyScanPage(
                rows =
                    listOf(
                        DisappearingMessageSweep.TimelineScanRow(930uL, "received"),
                    ),
                rawCutoffSeconds = rawCutoff,
                skewCutoffSeconds = skewCutoff,
                lastReadTimelineAt = null,
            ),
        )
        assertEquals(
            DisappearingMessageSweep.TimelineScanPageDecision.InvokeSecureDelete,
            DisappearingMessageSweep.classifyScanPage(
                rows =
                    listOf(
                        DisappearingMessageSweep.TimelineScanRow(930uL, "received"),
                    ),
                rawCutoffSeconds = rawCutoff,
                skewCutoffSeconds = skewCutoff,
                lastReadTimelineAt = 930uL,
            ),
        )
    }

    @Test
    fun backgroundScanDefersMixedPageWithUnreadReceivedRowsPastSendTimeCutoff() {
        assertEquals(
            DisappearingMessageSweep.TimelineScanPageDecision.DeferUnreadReceived,
            DisappearingMessageSweep.classifyScanPage(
                rows =
                    listOf(
                        DisappearingMessageSweep.TimelineScanRow(930uL, "received"),
                        DisappearingMessageSweep.TimelineScanRow(920uL, "sent"),
                    ),
                rawCutoffSeconds = 940uL,
                skewCutoffSeconds = 935uL,
                lastReadTimelineAt = null,
            ),
        )
    }

    @Test
    fun expiredCiphertextMapsToScopedMediaCacheKeys() {
        val keys =
            mediaCacheKeysForCiphertextTags(
                account = "account-a",
                groupIdHex = "group-a",
                mediaRecords =
                    listOf(
                        mediaRecord(messageIdHex = "message-a", attachmentIndex = 0u, ciphertextSha256 = "expired-a"),
                        mediaRecord(messageIdHex = "message-a", attachmentIndex = 1u, ciphertextSha256 = "fresh"),
                        mediaRecord(messageIdHex = "message-b", attachmentIndex = 0u, ciphertextSha256 = "expired-b"),
                    ),
                ciphertextTags = setOf("expired-a", "expired-b"),
            )

        assertEquals(
            setOf(
                mediaCacheKey("account-a", "group-a", "message-a", 0),
                mediaCacheKey("account-a", "group-a", "message-b", 0),
            ),
            keys,
        )
    }

    @Test
    fun expiredCiphertextMapperIgnoresEmptyTags() {
        val keys =
            mediaCacheKeysForCiphertextTags(
                account = "account-a",
                groupIdHex = "group-a",
                mediaRecords = listOf(mediaRecord(messageIdHex = "message-a", attachmentIndex = 0u, ciphertextSha256 = "expired")),
                ciphertextTags = emptySet(),
            )

        assertTrue(keys.isEmpty())
    }

    private fun mediaRecord(
        messageIdHex: String,
        attachmentIndex: UInt,
        ciphertextSha256: String,
    ): MediaRecordFfi =
        MediaRecordFfi(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            direction = "received",
            groupIdHex = "group-a",
            sender = "sender-a",
            reference =
                MediaAttachmentReferenceFfi(
                    locators = emptyList(),
                    ciphertextSha256 = ciphertextSha256,
                    plaintextSha256 = "plaintext-$messageIdHex-$attachmentIndex",
                    nonceHex = "nonce-$messageIdHex-$attachmentIndex",
                    fileName = "media.bin",
                    mediaType = "application/octet-stream",
                    version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
                    sourceEpoch = 0u,
                    dim = null,
                    thumbhash = null,
                ),
            caption = null,
            recordedAt = 0u,
            receivedAt = 0u,
        )
}
