package dev.ipf.whitenoise.android.state

/**
 * Pure selection + skew helpers for the disappearing-message background sweep
 * (#745). The actual prune/secure-delete is owned by the engine
 * (`secureDeleteExpired`); these side-effect-free helpers pin the Android-side
 * decisions so they can be unit-tested without an engine, an Android context,
 * or WorkManager:
 *
 *  - which groups the sweep should touch (only those with a retention window
 *    set — a group with the timer off is a no-op), and
 *  - the device-clock cutoff (with a small skew tolerance) the background sweep
 *    uses before invoking the engine's raw-current-time prune.
 *
 * Keeping these out of the worker mirrors the `decideForegroundStart` pattern
 * (see [dev.ipf.whitenoise.android.notifications.decideForegroundStart]).
 */
object DisappearingMessageSweep {
    /**
     * Skew tolerance applied to the device clock when deciding what counts as
     * "expired". The engine owns the authoritative prune, but a coarse sweep
     * should never be more eager than a device whose clock runs fast: subtract
     * this margin so a message within the skew window of its expiry survives to
     * the next sweep rather than vanishing early. Mirrors the coarse-cadence
     * intent of the in-conversation sweep.
     */
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 5_000L

    /** Page size for the bounded local timeline scan that gates the raw engine prune. */
    val TIMELINE_SCAN_PAGE_LIMIT: UInt = 200u

    /**
     * Hard cap on pages the gating scan may fetch per group. The scan seeds
     * its cursor at the raw cutoff (see [TIMELINE_SCAN_SEED_MESSAGE_ID]), so
     * one page normally decides; this backstop keeps a pathological history
     * from pinning an IO thread, mirroring `SEARCH_MAX_PAGES`. Exhausting it
     * defers the group (never invokes the raw prune unproven). See #979.
     */
    const val TIMELINE_SCAN_MAX_PAGES: Int = 20

    /**
     * Compound-cursor id for seeding the gating scan's first page at the raw
     * cutoff instead of the newest message (#979). The engine requires
     * `before` and `beforeMessageId` together, and its cursor predicate is
     * `timelineAt < before OR (timelineAt == before AND messageIdHex < beforeMessageId)`;
     * the all-zeros id sorts before every real 32-byte message id, so
     * `(rawCutoffSeconds, this)` is an exclusive `timelineAt < rawCutoffSeconds`
     * bound — exactly the rows [classifyScanPage] can classify.
     */
    val TIMELINE_SCAN_SEED_MESSAGE_ID: String = "0".repeat(64)

    /** Inputs for read-anchored local expiry (#797). */
    data class LocalExpiryRow(
        val timelineAtSeconds: ULong,
        val expiresAtLocalSeconds: ULong? = null,
        val readAnchoredAtSeconds: ULong? = null,
        val deferSendTimeExpiry: Boolean = false,
    )

    /** One row in the background-prune gating scan. */
    data class TimelineScanRow(
        val timelineAtSeconds: ULong,
        val direction: String,
    )

    /** Outcome of classifying one scanned timeline page against the cutoffs. */
    enum class TimelineScanPageDecision {
        /** A row sits in the skew window; defer the whole group this pass. */
        DeferSkewWindow,

        /** A raw prune would delete an unread received row before its read anchor. */
        DeferUnreadReceived,

        /** A row is expired beyond skew (and none is in the window); prune. */
        InvokeSecureDelete,

        /** No row decides either way; keep paging (or give up at the caps). */
        KeepScanning,
    }

    /**
     * Classify one page of timeline rows for the background-prune gate. The
     * skew-window check deliberately wins over expired-beyond-skew when both
     * appear on the same page: the raw engine prune has no cutoff parameter,
     * so invoking it would also delete the near-boundary row the #745 skew
     * tolerance exists to protect.
     */
    fun classifyScanPage(
        timelineAtSeconds: Iterable<ULong>,
        rawCutoffSeconds: ULong,
        skewCutoffSeconds: ULong,
    ): TimelineScanPageDecision =
        classifyScanPage(
            rows = timelineAtSeconds.map { TimelineScanRow(it, direction = "sent") },
            rawCutoffSeconds = rawCutoffSeconds,
            skewCutoffSeconds = skewCutoffSeconds,
            lastReadTimelineAt = null,
        )

    /**
     * Classify one page for the background-prune gate, deferring send-time
     * expiry for received rows the user has not read yet (#797).
     */
    fun classifyScanPage(
        rows: Iterable<TimelineScanRow>,
        rawCutoffSeconds: ULong,
        skewCutoffSeconds: ULong,
        lastReadTimelineAt: ULong?,
    ): TimelineScanPageDecision {
        val scannedRows = rows.toList()
        return when {
            scannedRows.any {
                isWithinSkewWindow(
                    timelineAtSeconds = it.timelineAtSeconds,
                    rawCutoffSeconds = rawCutoffSeconds,
                    skewCutoffSeconds = skewCutoffSeconds,
                )
            } -> TimelineScanPageDecision.DeferSkewWindow
            scannedRows.any {
                it.timelineAtSeconds < rawCutoffSeconds &&
                    isSendTimeExpiryDeferredForBackgroundScan(
                        direction = it.direction,
                        timelineAtSeconds = it.timelineAtSeconds,
                        lastReadTimelineAt = lastReadTimelineAt,
                    )
            } -> TimelineScanPageDecision.DeferUnreadReceived
            scannedRows.any {
                isExpiredBeyondSkew(it.timelineAtSeconds, skewCutoffSeconds)
            } -> TimelineScanPageDecision.InvokeSecureDelete
            else -> TimelineScanPageDecision.KeepScanning
        }
    }

    /**
     * True when a received row is still unread on the persisted watermark, so
     * its send-time expiry must not hide or prune it yet (#797).
     */
    fun isSendTimeExpiryDeferredForBackgroundScan(
        direction: String,
        timelineAtSeconds: ULong,
        lastReadTimelineAt: ULong?,
    ): Boolean {
        if (direction != "received") return false
        if (lastReadTimelineAt != null) return timelineAtSeconds > lastReadTimelineAt
        return true
    }

    /**
     * Slow-path cap for the in-conversation sweep when no loaded row is about
     * to expire.
     */
    const val FOREGROUND_SWEEP_MAX_DELAY_MS: Long = 60_000L

    /** Retry cadence for rows already hidden locally but not yet engine-pruned. */
    const val FOREGROUND_EXPIRED_RETRY_DELAY_MS: Long = 1_000L

    private const val MILLIS_PER_SECOND = 1_000L
    private val maxSafeExpirySeconds = (Long.MAX_VALUE / MILLIS_PER_SECOND).toULong()

    /**
     * Whether a group with the given retention should be swept. `0` means the
     * disappearing-messages timer is off for that group, so the sweep must be a
     * no-op for it (acceptance criterion). Matches the in-conversation guard
     * `group.disappearingMessageSecs > 0uL`.
     */
    fun shouldSweepGroup(disappearingMessageSecs: ULong): Boolean = disappearingMessageSecs > 0uL

    /**
     * Mirrors the foreground timeline filter: a loaded row is hidden once its
     * local expiry second is less than or equal to the current wall-clock second.
     */
    fun isLocallyExpired(
        nowMillis: Long,
        disappearingMessageSecs: ULong,
        timelineAtSeconds: ULong,
    ): Boolean =
        isLocallyExpired(
            nowMillis = nowMillis,
            disappearingMessageSecs = disappearingMessageSecs,
            row = LocalExpiryRow(timelineAtSeconds = timelineAtSeconds),
        )

    /**
     * Read-anchored local expiry (#797). Prefers engine-owned
     * [LocalExpiryRow.expiresAtLocalSeconds] when present, then a session
     * read/display anchor, then send-time expiry unless
     * [LocalExpiryRow.deferSendTimeExpiry] suspends it for unread received
     * rows.
     */
    fun isLocallyExpired(
        nowMillis: Long,
        disappearingMessageSecs: ULong,
        row: LocalExpiryRow,
    ): Boolean {
        if (!shouldSweepGroup(disappearingMessageSecs)) return false
        val expirySeconds = resolveLocalExpirySeconds(disappearingMessageSecs, row) ?: return false
        if (expirySeconds > maxSafeExpirySeconds) return false
        val nowSeconds = (nowMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toULong()
        return expirySeconds <= nowSeconds
    }

    internal fun resolveLocalExpirySeconds(
        disappearingMessageSecs: ULong,
        row: LocalExpiryRow,
    ): ULong? {
        row.expiresAtLocalSeconds?.let { return it }
        row.readAnchoredAtSeconds?.let { return it.saturatingPlus(disappearingMessageSecs) }
        if (row.deferSendTimeExpiry) return null
        return row.timelineAtSeconds.saturatingPlus(disappearingMessageSecs)
    }

    /**
     * The device-clock instant the sweep should treat as "now" when reasoning
     * about expiry, pulled back by [CLOCK_SKEW_TOLERANCE_MS] and floored at zero
     * so an absurdly early clock can't produce a negative cutoff.
     */
    fun expiryCutoffMillis(nowMillis: Long): Long = (nowMillis - CLOCK_SKEW_TOLERANCE_MS).coerceAtLeast(0L)

    /**
     * Engine-equivalent cutoff before skew is applied: messages strictly before
     * this second are what `secureDeleteExpired` would prune if invoked now.
     */
    fun rawExpiryCutoffSeconds(
        nowMillis: Long,
        disappearingMessageSecs: ULong,
    ): ULong? = expiryCutoffSeconds(nowMillis, disappearingMessageSecs, skewToleranceMillis = 0L)

    /**
     * Skew-safe cutoff: the background sweep should only let the engine prune
     * messages strictly before this second. The FFI call has no cutoff parameter,
     * so callers use this with [isWithinSkewWindow] to defer near-boundary groups
     * before invoking the raw-current-time engine prune.
     */
    fun expiryCutoffSeconds(
        nowMillis: Long,
        disappearingMessageSecs: ULong,
    ): ULong? = expiryCutoffSeconds(nowMillis, disappearingMessageSecs, CLOCK_SKEW_TOLERANCE_MS)

    /** True when [timelineAtSeconds] is safely older than the skew-adjusted cutoff. */
    fun isExpiredBeyondSkew(
        timelineAtSeconds: ULong,
        skewCutoffSeconds: ULong,
    ): Boolean = skewCutoffSeconds > 0uL && timelineAtSeconds < skewCutoffSeconds

    /**
     * True when the raw engine call would prune [timelineAtSeconds] but the
     * skew-adjusted cutoff says to wait. If any local row is in this window,
     * the background pass defers the whole group to avoid deleting a
     * near-boundary message early.
     */
    fun isWithinSkewWindow(
        timelineAtSeconds: ULong,
        rawCutoffSeconds: ULong,
        skewCutoffSeconds: ULong,
    ): Boolean =
        rawCutoffSeconds > skewCutoffSeconds &&
            timelineAtSeconds >= skewCutoffSeconds &&
            timelineAtSeconds < rawCutoffSeconds

    /**
     * Delay until the next foreground conversation sweep should run. Unlike the
     * coarse background pass, an open chat must re-publish at the first loaded
     * row's local expiry boundary so the bubble disappears while the user is
     * watching. Rows that are already past that boundary get a short retry delay:
     * the local filter has hidden them, but the engine's strict cutoff may need
     * the next wall-clock second before `secureDeleteExpired` physically prunes
     * and reports their media tags.
     */
    fun nextForegroundSweepDelayMillis(
        nowMillis: Long,
        disappearingMessageSecs: ULong,
        timelineAtSeconds: Iterable<ULong>,
    ): Long =
        nextForegroundSweepDelayMillis(
            nowMillis = nowMillis,
            disappearingMessageSecs = disappearingMessageSecs,
            rows = timelineAtSeconds.map { LocalExpiryRow(timelineAtSeconds = it) },
        )

    @JvmName("nextForegroundSweepDelayMillisForRows")
    fun nextForegroundSweepDelayMillis(
        nowMillis: Long,
        disappearingMessageSecs: ULong,
        rows: Iterable<LocalExpiryRow>,
    ): Long {
        if (!shouldSweepGroup(disappearingMessageSecs)) return FOREGROUND_SWEEP_MAX_DELAY_MS
        val safeNowMillis = nowMillis.coerceAtLeast(0L)
        var bestDelay = FOREGROUND_SWEEP_MAX_DELAY_MS
        for (row in rows) {
            val expirySeconds = resolveLocalExpirySeconds(disappearingMessageSecs, row) ?: continue
            if (expirySeconds > maxSafeExpirySeconds) continue
            val expiryMillis = expirySeconds.toLong() * MILLIS_PER_SECOND
            val delay = expiryMillis - safeNowMillis
            val candidate =
                when {
                    delay > 0L -> delay.coerceAtMost(FOREGROUND_SWEEP_MAX_DELAY_MS)
                    safeNowMillis - expiryMillis < FOREGROUND_EXPIRED_RETRY_DELAY_MS ->
                        FOREGROUND_EXPIRED_RETRY_DELAY_MS
                    else ->
                        // The local filter has already hidden a long-stale row;
                        // use the coarse cap unless a later publish proves the
                        // loaded window still needs an immediate engine prune.
                        FOREGROUND_SWEEP_MAX_DELAY_MS
                }
            if (candidate < bestDelay) bestDelay = candidate
        }
        return bestDelay
    }

    /**
     * Foreground await-loop wake decision. Timeout wakes always run a sweep;
     * publish-signal wakes only run one when the latest loaded window now has an
     * expired row and the near-boundary retry tick has not just run.
     */
    fun shouldRunForegroundSweepAfterWake(
        wakeSignalReceived: Boolean,
        nowMillis: Long,
        lastSweepStartedAtMillis: Long,
        disappearingMessageSecs: ULong,
        timelineAtSeconds: Iterable<ULong>,
    ): Boolean =
        shouldRunForegroundSweepAfterWake(
            wakeSignalReceived = wakeSignalReceived,
            nowMillis = nowMillis,
            lastSweepStartedAtMillis = lastSweepStartedAtMillis,
            disappearingMessageSecs = disappearingMessageSecs,
            rows = timelineAtSeconds.map { LocalExpiryRow(timelineAtSeconds = it) },
        )

    @JvmName("shouldRunForegroundSweepAfterWakeForRows")
    fun shouldRunForegroundSweepAfterWake(
        wakeSignalReceived: Boolean,
        nowMillis: Long,
        lastSweepStartedAtMillis: Long,
        disappearingMessageSecs: ULong,
        rows: Iterable<LocalExpiryRow>,
    ): Boolean {
        if (!wakeSignalReceived) return true
        return shouldSweepAfterForegroundReschedule(
            nowMillis = nowMillis,
            lastSweepStartedAtMillis = lastSweepStartedAtMillis,
            disappearingMessageSecs = disappearingMessageSecs,
            rows = rows,
        )
    }

    private fun shouldSweepAfterForegroundReschedule(
        nowMillis: Long,
        lastSweepStartedAtMillis: Long,
        disappearingMessageSecs: ULong,
        rows: Iterable<LocalExpiryRow>,
    ): Boolean {
        if (!shouldSweepGroup(disappearingMessageSecs)) return false
        if (nowMillis - lastSweepStartedAtMillis < FOREGROUND_EXPIRED_RETRY_DELAY_MS) return false
        return rows.any {
            isLocallyExpired(
                nowMillis = nowMillis,
                disappearingMessageSecs = disappearingMessageSecs,
                row = it,
            )
        }
    }

    private fun expiryCutoffSeconds(
        nowMillis: Long,
        disappearingMessageSecs: ULong,
        skewToleranceMillis: Long,
    ): ULong? {
        if (!shouldSweepGroup(disappearingMessageSecs)) return null
        val safeNowMillis = nowMillis.coerceAtLeast(0L)
        val effectiveNowMillis = (safeNowMillis - skewToleranceMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
        val effectiveNowSeconds = (effectiveNowMillis / MILLIS_PER_SECOND).toULong()
        return effectiveNowSeconds.saturatingMinus(disappearingMessageSecs)
    }

    private fun ULong.saturatingMinus(value: ULong): ULong = if (this > value) this - value else 0uL

    private fun ULong.saturatingPlus(value: ULong): ULong = if (ULong.MAX_VALUE - this < value) ULong.MAX_VALUE else this + value
}
