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
     * Slow-path cap for the in-conversation sweep when no loaded row is about
     * to expire.
     */
    const val FOREGROUND_SWEEP_MAX_DELAY_MS: Long = 60_000L

    /** Retry cadence for rows already hidden locally but not yet engine-pruned. */
    const val FOREGROUND_EXPIRED_RETRY_DELAY_MS: Long = 1_000L

    private const val MILLIS_PER_SECOND = 1_000L
    private val maxSecondsAsMillis = (Long.MAX_VALUE / MILLIS_PER_SECOND).toULong()

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
    ): Boolean {
        if (!shouldSweepGroup(disappearingMessageSecs)) return false
        val expirySeconds = timelineAtSeconds.saturatingPlus(disappearingMessageSecs)
        if (expirySeconds > maxSecondsAsMillis) return false
        val nowSeconds = (nowMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toULong()
        return expirySeconds <= nowSeconds
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
    ): Long {
        if (!shouldSweepGroup(disappearingMessageSecs)) return FOREGROUND_SWEEP_MAX_DELAY_MS
        val safeNowMillis = nowMillis.coerceAtLeast(0L)
        var bestDelay = FOREGROUND_SWEEP_MAX_DELAY_MS
        for (timelineAt in timelineAtSeconds) {
            val expirySeconds = timelineAt.saturatingPlus(disappearingMessageSecs)
            if (expirySeconds > maxSecondsAsMillis) continue
            val expiryMillis = expirySeconds.toLong() * MILLIS_PER_SECOND
            val delay = expiryMillis - safeNowMillis
            val candidate =
                when {
                    delay > 0L -> delay.coerceAtMost(FOREGROUND_SWEEP_MAX_DELAY_MS)
                    safeNowMillis - expiryMillis < FOREGROUND_EXPIRED_RETRY_DELAY_MS ->
                        FOREGROUND_EXPIRED_RETRY_DELAY_MS
                    else -> FOREGROUND_SWEEP_MAX_DELAY_MS
                }
            if (candidate < bestDelay) bestDelay = candidate
        }
        return bestDelay
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
