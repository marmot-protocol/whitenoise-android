package dev.ipf.whitenoise.android.notifications

/** Fresh arrivals inside this window after an audible alert are shown silently: one ring per burst. */
internal const val NOTIFICATION_ALERT_BURST_WINDOW_MS = 10_000L

/** What a first post may do about sound and vibration. Every outcome still writes the card. */
enum class NotificationAlertDecision(
    val silent: Boolean,
) {
    /** Sound and vibration as the channel allows. */
    Alert(false),

    /** The catch-up cohort this post belongs to has already rung once; the card joins it without ringing. */
    SilentCatchUp(true),

    /** Another alert rang moments ago; this arrival joins it on screen without ringing again. */
    SilentBurst(true),
}

/**
 * Decides whether one first post rings. A post inside a catch-up cohort (see [NotificationCatchUpWindow])
 * rings only if that cohort has not rung yet, so a reconnect backlog produces at most one attention
 * event however many cards it writes. Outside a cohort, a message rings unless another alert rang
 * within the burst window, so a flood of live arrivals rings once; a mention of the reader breaks the
 * burst rule, never the cohort rule.
 */
internal fun notificationAlertDecision(
    catchUpGeneration: Long?,
    alertedCatchUpGeneration: Long?,
    msSinceLastAlert: Long?,
    isMention: Boolean,
    burstWindowMs: Long = NOTIFICATION_ALERT_BURST_WINDOW_MS,
): NotificationAlertDecision =
    when {
        catchUpGeneration != null && catchUpGeneration == alertedCatchUpGeneration ->
            NotificationAlertDecision.SilentCatchUp
        !isMention && msSinceLastAlert != null && msSinceLastAlert < burstWindowMs ->
            NotificationAlertDecision.SilentBurst
        else -> NotificationAlertDecision.Alert
    }

/**
 * Process-wide memory of the last audible alert and of the catch-up cohort it rang for. [decide] never
 * consumes the budget; the presenter calls [markAlerted] once the alerting card has actually been
 * written, so a post that the eligibility gates reject leaves the next arrival free to ring.
 */
class NotificationAlertBudget(
    /** The cohort boundary this budget charges; the catch-up coordinator opens and closes it. */
    val catchUpWindow: NotificationCatchUpWindow = NotificationCatchUpWindow(),
    private val burstWindowMs: Long = NOTIFICATION_ALERT_BURST_WINDOW_MS,
) {
    private val lock = Any()
    private var lastAlertAtMs: Long? = null
    private var alertedCatchUpGeneration: Long? = null

    /** The decision for a first post being written at [nowMs]. */
    fun decide(
        nowMs: Long,
        isMention: Boolean,
    ): NotificationAlertDecision =
        synchronized(lock) {
            notificationAlertDecision(
                catchUpGeneration = catchUpWindow.currentGeneration(),
                alertedCatchUpGeneration = alertedCatchUpGeneration,
                msSinceLastAlert = lastAlertAtMs?.let { nowMs - it },
                isMention = isMention,
                burstWindowMs = burstWindowMs,
            )
        }

    /** Records that an alerting card was written at [nowMs], charging the current cohort if there is one. */
    fun markAlerted(nowMs: Long) {
        synchronized(lock) {
            lastAlertAtMs = nowMs
            catchUpWindow.currentGeneration()?.let { alertedCatchUpGeneration = it }
        }
    }
}
