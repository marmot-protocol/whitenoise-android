package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.core.DiagnosticFormatter

private const val MAX_REPORT_CAUSE_DEPTH = 8

/** Exact public rejection strings from the pinned MarmotKit 0.12.0; never include arbitrary details. */
private enum class PollVoteReason(
    val nativeDetail: String,
) {
    POLL_UNAVAILABLE("poll response requires a valid locally accepted poll in this group"),
    POLL_CLOSED("poll is closed"),

    // MDK uses this rejection both before creation and after closing: it does not prove expiry.
    POLL_RESPONSE_TIME_REJECTED("poll is closed at the response event timestamp"),
    POLL_INVALID_SELECTION("poll response contains an invalid selection"),
    POLL_INVALID_TARGET("poll response target must be one lowercase 32-byte hex event id"),
}

private class PollVoteFailure(
    cause: Throwable,
    reason: PollVoteReason,
) : RuntimeException("Poll vote failed", cause),
    DiagnosticErrorMetadata {
    override val diagnosticErrorCode: String = DiagnosticFormatter.errorCode(cause)
    override val diagnosticTechnicalDetail: String = "poll_vote_reason=${reason.name}"
}

/**
 * Presentation compatibility for native validation errors currently exposed as Runtime.
 * Unknown details stay private, and native validation, send outcomes and retries remain unchanged.
 */
internal fun Throwable.withPollVoteFailureAttribution(): Throwable {
    // Reserve one of DiagnosticFormatter's eight cause slots for our metadata wrapper.
    val chain =
        generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }
            .take(MAX_REPORT_CAUSE_DEPTH)
            .toList()
    val native = chain.take(MAX_REPORT_CAUSE_DEPTH - 1).filterIsInstance<MarmotKitException>().firstOrNull()
    val reason =
        (native as? MarmotKitException.Runtime)?.let { failure ->
            PollVoteReason.entries.firstOrNull { reason ->
                failure.details == "invalid app message payload: ${reason.nativeDetail}"
            }
        }
    return if (reason == null || chain.any { it is java.util.concurrent.CancellationException }) {
        this
    } else {
        PollVoteFailure(this, reason)
    }
}
