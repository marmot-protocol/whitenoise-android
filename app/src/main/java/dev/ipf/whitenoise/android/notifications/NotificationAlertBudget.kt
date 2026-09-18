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
 * One first post's claim on the budget. An [NotificationAlertDecision.Alert] claim already holds the ring,
 * so a concurrent first post for another conversation sees it and stays silent; the presenter settles the
 * claim with [commit] once the card is written or [release] when it is not, which hands the ring back.
 */
class NotificationAlertReservation internal constructor(
    val decision: NotificationAlertDecision,
    private val budget: NotificationAlertBudget,
) {
    /** The alerting card was written: the ring is spent. */
    fun commit() = budget.commit(this)

    /** The card was not written: the ring goes back so the next arrival may take it. */
    fun release() = budget.release(this)
}

/**
 * Process-wide memory of the last audible alert and of the catch-up cohort it rang for. [reserve] takes
 * the ring at decision time, so two first posts racing through the presenter cannot both ring; a claim
 * whose card is never written is released and leaves the next arrival free to ring.
 */
class NotificationAlertBudget(
    /** The cohort boundary this budget charges; the catch-up coordinator opens and closes it. */
    val catchUpWindow: NotificationCatchUpWindow = NotificationCatchUpWindow(),
    private val burstWindowMs: Long = NOTIFICATION_ALERT_BURST_WINDOW_MS,
) {
    private val lock = Any()
    private var lastAlertAtMs: Long? = null
    private var alertedCatchUpGeneration: Long? = null
    private var pendingAlert: NotificationAlertReservation? = null
    private var lastAlertBeforePendingMs: Long? = null
    private var alertedGenerationBeforePending: Long? = null

    /** Decides for a first post being written at [nowMs] and, when it may ring, holds the ring for it. */
    fun reserve(
        nowMs: Long,
        isMention: Boolean,
    ): NotificationAlertReservation =
        synchronized(lock) {
            // Read once: the window has its own lock, and a cohort opening between two reads would let
            // a live post charge the cohort it never belonged to.
            val generation = catchUpWindow.currentGeneration()
            val decision =
                notificationAlertDecision(
                    catchUpGeneration = generation,
                    alertedCatchUpGeneration = alertedCatchUpGeneration,
                    msSinceLastAlert = lastAlertAtMs?.let { nowMs - it },
                    isMention = isMention,
                    burstWindowMs = burstWindowMs,
                )
            val reservation = NotificationAlertReservation(decision, this)
            if (decision == NotificationAlertDecision.Alert) {
                lastAlertBeforePendingMs = lastAlertAtMs
                alertedGenerationBeforePending = alertedCatchUpGeneration
                lastAlertAtMs = nowMs
                generation?.let { alertedCatchUpGeneration = it }
                pendingAlert = reservation
            }
            reservation
        }

    internal fun commit(reservation: NotificationAlertReservation) {
        synchronized(lock) {
            if (pendingAlert === reservation) pendingAlert = null
        }
    }

    /** Restores the state before the claim, unless a later alert has been reserved since, which then stands. */
    internal fun release(reservation: NotificationAlertReservation) {
        synchronized(lock) {
            if (pendingAlert !== reservation) return
            pendingAlert = null
            lastAlertAtMs = lastAlertBeforePendingMs
            alertedCatchUpGeneration = alertedGenerationBeforePending
        }
    }
}
