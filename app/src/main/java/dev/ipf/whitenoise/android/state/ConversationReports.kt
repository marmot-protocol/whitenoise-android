package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AuthoritativeEdit
import dev.ipf.whitenoise.android.core.EditVersion

/** Edit revisions requested per history page; a message with more is paged by the view that needs it. */
internal const val EDIT_HISTORY_PAGE_LIMIT: UInt = 50u

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
 * MarmotKit's accepted-edit history for one message: the newest page, oldest first within it, or null when
 * the engine could not answer. The history view then falls back to whatever edits the loaded window contains.
 */
internal suspend fun ConversationController.authoritativeEditHistory(messageIdHex: String): List<EditVersion>? {
    val account = boundAccountRef ?: return null
    return runCatchingCancellable {
        appState.marmotIo {
            messageEditHistory(account, group.groupIdHex, messageIdHex, null, null, EDIT_HISTORY_PAGE_LIMIT)
        }
    }.getOrNull()?.versions?.map { version ->
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
