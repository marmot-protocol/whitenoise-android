package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R

/** Reports requested per page; a message with more is paged by the view that asks for them. */
internal const val CONTENT_REPORT_PAGE_LIMIT: UInt = 50u

/**
 * Longest explanation a reporter or an admin may attach. MarmotKit carries it to the group verbatim, and
 * White Noise on iOS caps it at the same length so a report reads the same on both.
 */
internal const val REPORT_EXPLANATION_LIMIT = 1000

/** How a report attempt ended, so the surface can say something truthful without inspecting an exception. */
internal enum class ReportOutcome {
    /** MarmotKit published the report to the group. */
    Sent,

    /** MarmotKit accepted the report and will publish it once the group's queue drains or a relay answers. */
    Queued,

    /** The account cannot report here, for example because it is no longer a member. */
    NotAllowed,

    /** The attempt failed; nothing was published and it can be retried. */
    Failed,
}

/** The outcome MarmotKit's send summary describes: published now, or accepted and still on its way. */
internal fun reportOutcome(summary: SendSummaryFfi): ReportOutcome =
    if (summary.acceptDisposition == SendAcceptDispositionFfi.PUBLISHED) ReportOutcome.Sent else ReportOutcome.Queued

/**
 * Reasons a reader may choose, in the order the sheet lists them: the same order White Noise on iOS uses,
 * with `OTHER` last because it is the catch-all the explanation exists for.
 */
internal val REPORT_REASONS: List<ReportReasonFfi> =
    listOf(
        ReportReasonFfi.SPAM,
        ReportReasonFfi.NUDITY,
        ReportReasonFfi.MALWARE,
        ReportReasonFfi.PROFANITY,
        ReportReasonFfi.ILLEGAL,
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
 * Whether this reader may report a message. A report is published to the whole group, so in a direct chat
 * it would only reach the person being reported; reporting your own message is pointless, and a
 * tombstone has no content left to report.
 */
internal fun canReportMessage(
    mine: Boolean,
    deleted: Boolean,
    directConversation: Boolean,
): Boolean = !mine && !deleted && !directConversation

/** Reports against one message, newest first, open ones before dismissed ones. */
internal fun List<ContentReportFfi>.newestFirst(): List<ContentReportFfi> =
    sortedWith(compareBy<ContentReportFfi> { it.dismissed }.thenByDescending { it.reportedAt })
