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

    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * Whether a group with the given retention should be swept. `0` means the
     * disappearing-messages timer is off for that group, so the sweep must be a
     * no-op for it (acceptance criterion). Matches the in-conversation guard
     * `group.disappearingMessageSecs > 0uL`.
     */
    fun shouldSweepGroup(disappearingMessageSecs: ULong): Boolean = disappearingMessageSecs > 0uL

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
}
