package dev.ipf.whitenoise.android.state

/** The next step for a host that watches one retained timeline row for deletion or local expiry. */
internal sealed interface RetainedRowWatch {
    /** The row is deleted or past its deadline, so UI hosted outside the row should close. */
    data object Gone : RetainedRowWatch

    /** The row is absent and no deadline was ever seen, so there is nothing left to wait for. */
    data object Unwatched : RetainedRowWatch

    /** Check again after [delayMillis], carrying [deadlineMillis] (the row's, or the remembered one) forward. */
    data class Waiting(
        val delayMillis: Long,
        val deadlineMillis: Long?,
    ) : RetainedRowWatch
}

private const val MIN_RECHECK_DELAY_MS = 1L

/**
 * Decides the next [RetainedRowWatch] step. Each wait is capped at the controller sweep cadence, because the
 * host timer is monotonic and does not count deep sleep: after a long lock the engine may prune the row
 * before the timer fires. A retained row without a deadline (a received poll whose expiry waits for read)
 * keeps being polled at the cap. A row absent from the bounded window is unknown, not gone, until the
 * remembered deadline has passed, after which the engine has pruned it.
 */
internal fun decideRetainedRowWatch(
    nowMillis: Long,
    retained: Boolean,
    gone: Boolean,
    deadlineMillis: Long?,
    rememberedDeadlineMillis: Long?,
): RetainedRowWatch {
    val cap = DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS
    return when {
        retained && gone -> RetainedRowWatch.Gone
        retained ->
            RetainedRowWatch.Waiting(
                delayMillis = deadlineMillis?.let { (it - nowMillis).coerceIn(MIN_RECHECK_DELAY_MS, cap) } ?: cap,
                deadlineMillis = deadlineMillis,
            )
        rememberedDeadlineMillis == null -> RetainedRowWatch.Unwatched
        nowMillis >= rememberedDeadlineMillis -> RetainedRowWatch.Gone
        else ->
            RetainedRowWatch.Waiting(
                delayMillis = (rememberedDeadlineMillis - nowMillis).coerceIn(MIN_RECHECK_DELAY_MS, cap),
                deadlineMillis = rememberedDeadlineMillis,
            )
    }
}
