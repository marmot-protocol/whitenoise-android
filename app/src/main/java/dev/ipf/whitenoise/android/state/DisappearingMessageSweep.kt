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
    /**
     * One message's complete local-expiry input. [expiresAtLocalSeconds] is
     * MDK's authoritative deadline; [retentionAtSendSeconds] is either its
     * matching duration for read anchoring or the bounded optimistic snapshot.
     */
    data class LocalExpiryRow(
        val timelineAtSeconds: ULong,
        val expiresAtLocalSeconds: ULong? = null,
        val retentionAtSendSeconds: ULong? = null,
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
     * Mirrors the foreground timeline filter using only the retention decision
     * pinned to this message. A projected row without an MDK expiry/duration and
     * without an optimistic send snapshot is retained; the group's current
     * policy is deliberately not an input because it cannot describe history.
     */
    fun isLocallyExpired(
        nowMillis: Long,
        row: LocalExpiryRow,
    ): Boolean {
        val expirySeconds = resolveLocalExpirySeconds(row) ?: return false
        if (expirySeconds > maxSafeExpirySeconds) return false
        val nowSeconds = (nowMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toULong()
        return expirySeconds <= nowSeconds
    }

    /** Resolves the row-owned deadline without consulting mutable group policy. */
    internal fun resolveLocalExpirySeconds(row: LocalExpiryRow): ULong? {
        val retentionAtSend = row.retentionAtSendSeconds?.takeIf { it > 0uL }
        row.readAnchoredAtSeconds?.let { anchor ->
            retentionAtSend?.let { return anchor.saturatingPlus(it) }
        }
        if (row.deferSendTimeExpiry) return null
        row.expiresAtLocalSeconds?.let { return it }
        return retentionAtSend?.let { row.timelineAtSeconds.saturatingPlus(it) }
    }

    /** Whether this row has a local deadline worth scheduling or filtering. */
    fun hasLocalExpiry(row: LocalExpiryRow): Boolean = resolveLocalExpirySeconds(row) != null

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
        rows: Iterable<LocalExpiryRow>,
    ): Long {
        val safeNowMillis = nowMillis.coerceAtLeast(0L)
        var bestDelay = FOREGROUND_SWEEP_MAX_DELAY_MS
        for (row in rows) {
            val expirySeconds = resolveLocalExpirySeconds(row) ?: continue
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
        rows: Iterable<LocalExpiryRow>,
    ): Boolean {
        if (!wakeSignalReceived) return true
        return shouldSweepAfterForegroundReschedule(
            nowMillis = nowMillis,
            lastSweepStartedAtMillis = lastSweepStartedAtMillis,
            rows = rows,
        )
    }

    /** Checks whether a publish signal exposed a newly due row. */
    private fun shouldSweepAfterForegroundReschedule(
        nowMillis: Long,
        lastSweepStartedAtMillis: Long,
        rows: Iterable<LocalExpiryRow>,
    ): Boolean {
        if (nowMillis - lastSweepStartedAtMillis < FOREGROUND_EXPIRED_RETRY_DELAY_MS) return false
        return rows.any {
            isLocallyExpired(
                nowMillis = nowMillis,
                row = it,
            )
        }
    }

    /** Adds retention seconds without wrapping an untrusted timestamp. */
    private fun ULong.saturatingPlus(value: ULong): ULong {
        val remaining = ULong.MAX_VALUE - this
        return if (remaining < value) ULong.MAX_VALUE else this + value
    }
}
