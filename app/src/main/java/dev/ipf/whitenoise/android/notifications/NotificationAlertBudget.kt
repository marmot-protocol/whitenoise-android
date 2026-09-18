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
 * Decides whether one first post rings. Inside a catch-up cohort (see [NotificationCatchUpWindow]) the
 * post rings if its account has not rung for that cohort yet, whatever rang moments before, so a reconnect
 * backlog produces one attention event per signed-in account however many cards it writes. Outside a
 * cohort, a message rings unless another alert rang within the burst window, so a flood of live arrivals
 * rings once; a mention of the reader breaks the burst rule, never the cohort rule.
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
        catchUpGeneration != null -> NotificationAlertDecision.Alert
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
    internal val accountRef: String,
    internal val reservedAtMs: Long,
    private val budget: NotificationAlertBudget,
) {
    /** The alerting card was written: the ring is spent. */
    fun commit() = budget.commit(this)

    /** The card was not written: the ring goes back so the next arrival may take it. */
    fun release() = budget.release(this)

    /**
     * False once the claim was settled, or, for a live claim, once a later live alert rang while this post
     * was still waiting to write: the card is then written silently and [release] hands the charge back.
     * A cohort claim answers only for its own account, so two accounts' backlog cards can both ring.
     */
    fun stillHoldsTheRing(): Boolean = budget.holdsTheRing(this)
}

/**
 * Process-wide memory of the last audible alert and, per local account, of the catch-up cohort it rang
 * for. [reserve] takes the ring at decision time, so two first posts racing through the presenter cannot
 * both ring; a claim whose card is never written is released and leaves the next arrival free to ring.
 */
class NotificationAlertBudget(
    /** The cohort boundary this budget charges; the catch-up coordinator opens and closes it. */
    val catchUpWindow: NotificationCatchUpWindow = NotificationCatchUpWindow(),
    private val burstWindowMs: Long = NOTIFICATION_ALERT_BURST_WINDOW_MS,
) {
    /** An unsettled alert claim and the state it displaced, so releasing it can put that state back. */
    private class PendingClaim(
        val reservation: NotificationAlertReservation,
        val startedBurst: Boolean,
        val lastAlertBeforeMs: Long?,
        val cohortGenerationBefore: Long?,
    )

    private val lock = Any()

    /** When the last live alert rang. Cohort rings never set it, so post-catch-up live traffic rings normally. */
    private var lastAlertAtMs: Long? = null
    private val alertedCatchUpGenerations = mutableMapOf<String, Long>()
    private val pendingClaims = mutableMapOf<String, PendingClaim>()

    /** Decides for [accountRef]'s first post being written at [nowMs] and, when it may ring, holds the ring. */
    fun reserve(
        nowMs: Long,
        isMention: Boolean,
        accountRef: String,
    ): NotificationAlertReservation =
        synchronized(lock) {
            // Read once: the window has its own lock, and a cohort opening between two reads would let
            // a live post charge the cohort it never belonged to.
            val generation = catchUpWindow.currentGeneration()
            val decision =
                notificationAlertDecision(
                    catchUpGeneration = generation,
                    alertedCatchUpGeneration = alertedCatchUpGenerations[accountRef],
                    msSinceLastAlert = lastAlertAtMs?.let { nowMs - it },
                    isMention = isMention,
                    burstWindowMs = burstWindowMs,
                )
            val reservation = NotificationAlertReservation(decision, accountRef, nowMs, this)
            if (decision == NotificationAlertDecision.Alert) {
                pendingClaims[accountRef] =
                    PendingClaim(
                        reservation,
                        startedBurst = generation == null,
                        lastAlertBeforeMs = lastAlertAtMs,
                        cohortGenerationBefore = alertedCatchUpGenerations[accountRef],
                    )
                if (generation == null) lastAlertAtMs = nowMs else alertedCatchUpGenerations[accountRef] = generation
            }
            reservation
        }

    internal fun commit(reservation: NotificationAlertReservation) {
        synchronized(lock) {
            if (pendingClaims[reservation.accountRef]?.reservation === reservation) {
                pendingClaims.remove(reservation.accountRef)
            }
        }
    }

    /**
     * Puts back what the claim displaced: its account's cohort charge, and the last-alert time unless a
     * later alert has moved it since. A claim a newer reservation for the same account already replaced
     * is left alone, and other accounts' claims are never touched.
     */
    internal fun release(reservation: NotificationAlertReservation) {
        synchronized(lock) {
            val claim = pendingClaims[reservation.accountRef]?.takeIf { it.reservation === reservation } ?: return
            pendingClaims.remove(reservation.accountRef)
            if (claim.startedBurst && lastAlertAtMs == reservation.reservedAtMs) lastAlertAtMs = claim.lastAlertBeforeMs
            val previous = claim.cohortGenerationBefore
            if (previous == null) {
                alertedCatchUpGenerations.remove(reservation.accountRef)
            } else {
                alertedCatchUpGenerations[reservation.accountRef] = previous
            }
        }
    }

    internal fun holdsTheRing(reservation: NotificationAlertReservation): Boolean =
        synchronized(lock) {
            val claim = pendingClaims[reservation.accountRef]?.takeIf { it.reservation === reservation }
            claim != null && (!claim.startedBurst || lastAlertAtMs == reservation.reservedAtMs)
        }
}
