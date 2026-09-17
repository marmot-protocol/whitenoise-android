package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.R

/** Reports requested per page; a message with more is paged by the view that asks for them. */
internal const val CONTENT_REPORT_PAGE_LIMIT: UInt = 50u

/** Longest explanation a reporter or an admin may attach; MarmotKit carries it to the group's admins. */
internal const val REPORT_EXPLANATION_LIMIT = 500

/** How a report attempt ended, so the surface can say something truthful without inspecting an exception. */
internal enum class ReportOutcome {
    /** MarmotKit accepted and published the report. */
    Sent,

    /** The account cannot report here, for example because it is no longer a member. */
    NotAllowed,

    /** The attempt failed; nothing was published and it can be retried. */
    Failed,
}

/**
 * Reasons a reader may choose, in the order the sheet lists them. `OTHER` sits last because it is the
 * catch-all the explanation exists for.
 */
internal val REPORT_REASONS: List<ReportReasonFfi> =
    listOf(
        ReportReasonFfi.SPAM,
        ReportReasonFfi.NUDITY,
        ReportReasonFfi.PROFANITY,
        ReportReasonFfi.ILLEGAL,
        ReportReasonFfi.MALWARE,
        ReportReasonFfi.IMPERSONATION,
        ReportReasonFfi.OTHER,
    )

/** The localized label for one reason. */
internal fun reportReasonLabel(reason: ReportReasonFfi): Int =
    when (reason) {
        ReportReasonFfi.NUDITY -> R.string.report_reason_nudity
        ReportReasonFfi.MALWARE -> R.string.report_reason_malware
        ReportReasonFfi.PROFANITY -> R.string.report_reason_profanity
        ReportReasonFfi.ILLEGAL -> R.string.report_reason_illegal
        ReportReasonFfi.SPAM -> R.string.report_reason_spam
        ReportReasonFfi.IMPERSONATION -> R.string.report_reason_impersonation
        ReportReasonFfi.OTHER -> R.string.report_reason_other
    }

/** Trims an explanation to what MarmotKit will carry, so the sheet and the send agree on the limit. */
internal fun boundedExplanation(raw: String): String = raw.trim().take(REPORT_EXPLANATION_LIMIT)

/**
 * Whether this reader may report [messageIdHex]'s author. Reporting your own message is pointless, and a
 * tombstone has no content left to report.
 */
internal fun canReportMessage(
    mine: Boolean,
    deleted: Boolean,
): Boolean = !mine && !deleted

/** Reports still open against one message, newest first, for the admin view. */
internal fun List<ContentReportFfi>.newestFirst(): List<ContentReportFfi> = sortedByDescending { it.reportedAt }
