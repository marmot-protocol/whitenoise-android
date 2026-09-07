package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Accepts visibility only from the exact controller and open request currently presented. */
internal fun conversationTimelineReportIsCurrent(
    sameController: Boolean,
    reportedVisible: Boolean,
    reportedNotificationOpenRequestId: Long,
    selectedNotificationOpenRequestId: Long,
): Boolean =
    sameController &&
        reportedVisible &&
        reportedNotificationOpenRequestId == selectedNotificationOpenRequestId

/** Owns the last committed timeline-visibility report as one indivisible request-scoped value. */
internal class ConversationTimelineVisibilityOwner<T : Any> {
    private var report by mutableStateOf<Report<T>?>(null)

    /** Publishes only when the candidate is still the exact selected owner and request. */
    fun reportIfCurrent(
        owner: T,
        notificationOpenRequestId: Long,
        visible: Boolean,
        selectedOwner: T?,
        selectedNotificationOpenRequestId: Long,
    ) {
        if (owner !== selectedOwner || notificationOpenRequestId != selectedNotificationOpenRequestId) return
        report = Report(owner, notificationOpenRequestId, visible)
    }

    /** Returns true only when the retained report belongs to this exact owner and request. */
    fun isCurrent(
        owner: T?,
        notificationOpenRequestId: Long,
    ): Boolean =
        report?.let { candidate ->
            conversationTimelineReportIsCurrent(
                sameController = candidate.owner === owner,
                reportedVisible = candidate.visible,
                reportedNotificationOpenRequestId = candidate.notificationOpenRequestId,
                selectedNotificationOpenRequestId = notificationOpenRequestId,
            )
        } ?: false

    private data class Report<T : Any>(
        val owner: T,
        val notificationOpenRequestId: Long,
        val visible: Boolean,
    )
}
