package dev.ipf.whitenoise.android.state

/** The scheduler that owns one visible transfer card. */
internal enum class AttachmentTransferOwner(
    val label: String,
) {
    UserInitiatedJob("user_initiated_job"),
    WorkManager("work_manager"),
}

/** How one scheduler run of a transfer ended, as recorded in the diagnostics. */
internal enum class AttachmentTransferOutcome(
    val label: String,
) {
    /** The attachment was retained locally. */
    Completed("completed"),

    /** The run ended without retaining the attachment and will not retry. */
    Failed("failed"),

    /** The run ended without retaining the attachment and the scheduler will run it again. */
    Retrying("retrying"),

    /** The scheduler or the process stopped the run, leaving retry ownership with the scheduler. */
    Stopped("stopped"),
}

/** Proof that a run began a transfer, so that only that run, and not an older one, can end it. */
internal class AttachmentTransferToken internal constructor(
    internal val key: Int,
    internal val sequence: Long,
)

/**
 * Process-wide record of the transfers whose notification card is currently live.
 *
 * Each logical attachment has at most one live entry, keyed by its stable job id: a handoff between schedulers
 * replaces the entry instead of counting it twice, and a stale run that ends after its replacement began cannot
 * remove the replacement. Entries carry only a process-local sequence number, the owning scheduler and an
 * attempt, so the diagnostics tie a card to its owner and outcome without naming any account, conversation,
 * message or file.
 *
 * [onLiveCountChanged] runs under the ledger lock with the new count, so counts reach it in order. It must not
 * call back into the ledger.
 */
internal class AttachmentTransferLedger(
    private val onLiveCountChanged: (Int) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var nextSequence = 1L
    private val live = mutableMapOf<Int, LiveTransfer>()

    private class LiveTransfer(
        val sequence: Long,
        val owner: AttachmentTransferOwner,
    )

    /** How many transfers currently have a live card. */
    fun liveCount(): Int = synchronized(lock) { live.size }

    /**
     * Records that [owner] began presenting the transfer with stable id [key], on scheduler [attempt] when it
     * has one. A transfer already live under [key] is replaced, which is how a handoff between schedulers
     * keeps one presentation for the attachment.
     */
    fun begin(
        key: Int,
        owner: AttachmentTransferOwner,
        attempt: Int? = null,
    ): AttachmentTransferToken =
        synchronized(lock) {
            val sequence = nextSequence++
            val previous = live.put(key, LiveTransfer(sequence, owner))
            val attemptLabel = attempt?.toString() ?: "n/a"
            val replaced = previous?.let { " replaced_seq=${it.sequence} replaced_owner=${it.owner.label}" }.orEmpty()
            log("attachment_transfer begin seq=$sequence owner=${owner.label} attempt=$attemptLabel$replaced")
            if (previous == null) onLiveCountChanged(live.size)
            AttachmentTransferToken(key, sequence)
        }

    /**
     * Ends the run that began [token] with [outcome]. Returns false, changing nothing, when that run was
     * already ended or has been replaced by a newer one.
     */
    fun end(
        token: AttachmentTransferToken,
        outcome: AttachmentTransferOutcome,
    ): Boolean =
        synchronized(lock) {
            val current = live[token.key]
            if (current == null || current.sequence != token.sequence) {
                log("attachment_transfer end_ignored seq=${token.sequence} outcome=${outcome.label}")
                false
            } else {
                live.remove(token.key)
                log(
                    "attachment_transfer end seq=${token.sequence} owner=${current.owner.label} " +
                        "outcome=${outcome.label}",
                )
                onLiveCountChanged(live.size)
                true
            }
        }
}
