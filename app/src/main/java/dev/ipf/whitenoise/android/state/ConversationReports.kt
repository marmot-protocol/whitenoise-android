package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.marmotkit.TimelineEditHistoryPageFfi
import dev.ipf.marmotkit.TimelineEditVersionFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AuthoritativeEdit
import dev.ipf.whitenoise.android.core.EditVersion

/** Edit revisions requested per history page. */
internal const val EDIT_HISTORY_PAGE_LIMIT: UInt = 50u

/**
 * Pages read for one history at most, so a runaway cursor cannot loop; 20 × 50 exceeds any real edit
 * count.
 */
internal const val EDIT_HISTORY_MAX_PAGES = 20

/** One page read: the cursor is the first version of the page after it, or null for the newest page. */
internal typealias EditHistoryPageFetch = suspend (before: TimelineEditVersionFfi?) -> TimelineEditHistoryPageFfi

/**
 * Reads a message's whole accepted-edit history from MarmotKit's latest-first pages. Each page is oldest
 * first within itself, and the engine documents that both cursor fields of a page's first version load the
 * page before it, so the pages are collected newest to oldest and returned oldest first as one list.
 */
internal suspend fun collectEditHistory(fetchPage: EditHistoryPageFetch): List<TimelineEditVersionFfi> {
    val pages = ArrayDeque<List<TimelineEditVersionFfi>>()
    var before: TimelineEditVersionFfi? = null
    repeat(EDIT_HISTORY_MAX_PAGES) {
        val page = fetchPage(before)
        pages.addFirst(page.versions)
        val oldest = page.versions.firstOrNull()
        if (!page.hasMoreBefore || oldest == null) return pages.flatten()
        before = oldest
    }
    return pages.flatten()
}

/**
 * Reports [messageIdHex] to this group's admins with [reason] and an optional explanation. MarmotKit
 * publishes the report encrypted to the group, so the outcome says whether it was published, accepted
 * for a later send, or refused, rather than only whether the call returned.
 */
internal suspend fun ConversationController.reportMessage(
    messageIdHex: String,
    reason: ReportReasonFfi,
    explanation: String,
): ReportOutcome {
    val account = boundAccountRef ?: return ReportOutcome.NotAllowed
    return runCatchingCancellable {
        appState.marmotIo {
            reportMessage(account, group.groupIdHex, messageIdHex, reason, boundedExplanation(explanation))
        }
    }.fold(
        onSuccess = ::reportOutcome,
        onFailure = { failure ->
            recordMutationFailure(R.string.report_message_failed, "MESSAGE_REPORT", failure)
            if (failure is MarmotKitException.MemberNotInGroup) ReportOutcome.NotAllowed else ReportOutcome.Failed
        },
    )
}

/** Reports against [messageIdHex], newest first with dismissed ones last; empty when none exist or the read failed. */
internal suspend fun ConversationController.reportsFor(messageIdHex: String): List<ContentReportFfi> {
    val account = boundAccountRef ?: return emptyList()
    return runCatchingCancellable {
        appState.marmotIo {
            contentReports(account, group.groupIdHex, messageIdHex, null, CONTENT_REPORT_PAGE_LIMIT)
        }
    }.getOrNull()?.reports?.newestFirst().orEmpty()
}

/**
 * Dismisses [reportIds] with a shared explanation, which MarmotKit shares with the group's other admins.
 * Only an admin may do this, and the engine enforces that too.
 */
internal suspend fun ConversationController.dismissReports(
    reportIds: List<String>,
    explanation: String,
): ReportOutcome {
    val account = boundAccountRef?.takeIf { reportIds.isNotEmpty() } ?: return ReportOutcome.NotAllowed
    return runCatchingCancellable {
        appState.marmotIo {
            dismissReports(account, group.groupIdHex, reportIds, boundedExplanation(explanation))
        }
    }.fold(
        onSuccess = ::reportOutcome,
        onFailure = { failure ->
            recordMutationFailure(R.string.report_dismiss_failed, "REPORT_DISMISS", failure)
            if (failure is MarmotKitException.NotGroupAdmin) ReportOutcome.NotAllowed else ReportOutcome.Failed
        },
    )
}

/**
 * MarmotKit's complete accepted-edit history for one message, oldest first, or null when the engine could
 * not answer. The history view then falls back to whatever edits the loaded window contains.
 */
internal suspend fun ConversationController.authoritativeEditHistory(messageIdHex: String): List<EditVersion>? {
    val account = boundAccountRef ?: return null
    val groupIdHex = group.groupIdHex
    return runCatchingCancellable {
        collectEditHistory { before ->
            appState.marmotIo {
                messageEditHistory(
                    account,
                    groupIdHex,
                    messageIdHex,
                    before?.editedAt,
                    before?.messageIdHex,
                    EDIT_HISTORY_PAGE_LIMIT,
                )
            }
        }
    }.getOrNull()?.map { version ->
        EditVersion(messageIdHex = version.messageIdHex, text = version.plaintext, recordedAt = version.editedAt)
    }
}

/**
 * The engine's accepted-edit summaries carried by [records] (0.10.1). A record's plaintext is already the
 * effective edited body, so it is the text the summary stands for.
 */
internal fun authoritativeEditsOf(records: Collection<TimelineMessageRecordFfi>): List<AuthoritativeEdit> =
    records.mapNotNull { record ->
        record.edit?.let {
            AuthoritativeEdit(
                messageIdHex = record.messageIdHex,
                editCount = it.editCount.toInt(),
                effectiveText = record.plaintext,
            )
        }
    }
