package dev.ipf.whitenoise.android.state

/**
 * Pure scheduling + local-hide helpers for the disappearing-message sweeps
 * (#745). Gate and prune are engine-owned: `sweepExpiredRetention` runs the
 * clock-skew, unread-anchor, and scan-cap deferrals atomically with the
 * prune. These side-effect-free helpers pin what stays Android's call so it
 * can be unit-tested without an engine, an Android context, or WorkManager:
 *
 *  - when a loaded row should hide locally while the engine's prune is still
 *    pending ([isLocallyExpired], #797 read anchors), and
 *  - when the open conversation's sweep loop should wake next
 *    ([nextForegroundSweepDelayMillis], [shouldRunForegroundSweepAfterWake]).
 *
 * Keeping these out of the worker mirrors the `decideForegroundStart` pattern
 * (see [dev.ipf.whitenoise.android.notifications.decideForegroundStart]).
 */
object DisappearingMessageSweep {
    /** Inputs for read-anchored local expiry (#797). */
    data class LocalExpiryRow(
        val timelineAtSeconds: ULong,
        val expiresAtLocalSeconds: ULong? = null,
        val readAnchoredAtSeconds: ULong? = null,
        val deferSendTimeExpiry: Boolean = false,
    )

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
     * Delay until the next foreground conversation sweep should run. Unlike the
     * coarse background pass, an open chat must re-publish at the first loaded
     * row's local expiry boundary so the bubble disappears while the user is
     * watching. Rows that are already past that boundary get a short retry delay:
     * the local filter has hidden them, but the engine's strict cutoff may need
     * the next wall-clock second before the engine sweep physically prunes
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

    private fun ULong.saturatingPlus(value: ULong): ULong = if (ULong.MAX_VALUE - this < value) ULong.MAX_VALUE else this + value
}
