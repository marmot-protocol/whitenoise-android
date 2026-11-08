package dev.ipf.whitenoise.android.state

/** What happens to a discarded send's pending-send diagnostic. */
internal enum class DiscardedSendDiagnostic {
    /** Stop tracking without a terminal phase. */
    FORGET,

    /** Publication already committed, so record a dropped send completion. */
    COMPLETE_DROPPED,

    /**
     * Leave the entry tracked. Only the media-failure discard does this today;
     * the entry then emits pending checkpoints until it expires.
     */
    LEAVE_TRACKED,
}

/**
 * The intended differences between paths that drop an optimistic send from
 * Android presentation state. Every path removes the optimistic message, its
 * acceptance callback, its message index and both upload maps. The upload maps
 * are only filled for media sends, so removing them on a text path has no effect.
 */
internal enum class OptimisticSendDiscard(
    /** Abandon the pending edit handoff, drop optimistic edits and dismiss the send-failure notice. */
    val releasesEditsAndFailureNotice: Boolean,
    /**
     * Remove the send phase. Kept while a late parser, upload or retry
     * continuation can still observe it; removed when none can.
     */
    val removesPhase: Boolean,
    /**
     * Roll back the chat-list preview and drop send-time retention. False
     * after publication committed the preview and moved retention to the
     * confirmed id.
     */
    val rollsBackPreview: Boolean,
    val diagnostic: DiscardedSendDiagnostic,
) {
    /** The user cancelled the send; late continuations still read the phase. */
    CANCELLED(
        releasesEditsAndFailureNotice = true,
        removesPhase = false,
        rollsBackPreview = true,
        diagnostic = DiscardedSendDiagnostic.FORGET,
    ),

    /** The send can never publish: the account was evicted or the upload epoch went stale. */
    ABANDONED(
        releasesEditsAndFailureNotice = false,
        removesPhase = true,
        rollsBackPreview = true,
        diagnostic = DiscardedSendDiagnostic.FORGET,
    ),

    /** The user discarded the bubble while a send or retry was in flight, before publication committed. */
    DISCARDED_IN_FLIGHT(
        releasesEditsAndFailureNotice = false,
        removesPhase = false,
        rollsBackPreview = true,
        diagnostic = DiscardedSendDiagnostic.FORGET,
    ),

    /** The user discarded the bubble after publication committed its preview. */
    DISCARDED_AFTER_PUBLISH(
        releasesEditsAndFailureNotice = false,
        removesPhase = false,
        rollsBackPreview = false,
        diagnostic = DiscardedSendDiagnostic.COMPLETE_DROPPED,
    ),

    /** The user discarded the bubble and the media upload then failed. */
    DISCARDED_AFTER_MEDIA_FAILURE(
        releasesEditsAndFailureNotice = false,
        removesPhase = false,
        rollsBackPreview = true,
        diagnostic = DiscardedSendDiagnostic.LEAVE_TRACKED,
    ),
}
