package dev.ipf.whitenoise.android.widget

import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.ChatListWindowHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Scan native windows under the caller's deadline, retaining only the widget's bounded matches. */
internal suspend fun widgetWindowRows(
    account: String,
    unreadOnly: Boolean,
    handle: ChatListWindowHandle,
): List<ConversationWidgetRow> {
    val matches = linkedMapOf<String, ConversationWidgetRow>()
    var frame: ChatListWindowSnapshotFfi? = checkNotNull(handle.snapshot())
    val generation = checkNotNull(frame).subscriptionGeneration
    while (frame != null && matches.size < MAX_WIDGET_ROWS) {
        currentCoroutineContext().ensureActive()
        check(frame.subscriptionGeneration == generation)
        appendWidgetRows(account, unreadOnly, frame, matches)
        frame =
            if (unreadOnly && matches.size < MAX_WIDGET_ROWS && frame.hasMoreAfter) {
                nextWidgetFrame(handle, frame)
            } else {
                null
            }
    }
    return matches.values.toList()
}

private fun appendWidgetRows(
    account: String,
    unreadOnly: Boolean,
    frame: ChatListWindowSnapshotFfi,
    matches: MutableMap<String, ConversationWidgetRow>,
) {
    for (presented in frame.rows) {
        if (matches.size == MAX_WIDGET_ROWS) break
        val row = presented.row
        val unread = row.hasUnread || row.manuallyMarkedUnread
        if (!unreadOnly || unread) {
            matches[row.groupIdHex] =
                ConversationWidgetRow(
                    account,
                    row.groupIdHex,
                    ProfileSanitizer.displayName(row.groupName).orEmpty(),
                    unread,
                    preview = null,
                    activity = row.activitySortAt,
                )
        }
    }
}

private suspend fun nextWidgetFrame(
    handle: ChatListWindowHandle,
    frame: ChatListWindowSnapshotFfi,
): ChatListWindowSnapshotFfi? {
    val edge =
        frame.rows
            .lastOrNull()
            ?.row
            ?.groupIdHex ?: return null
    // Native capped windows need the retained tail as their anchor before advancing.
    val anchored = handle.setVisibleAnchor(frame.sequence, edge)
    check(anchored.subscriptionGeneration == frame.subscriptionGeneration)
    val next = handle.page(anchored.sequence, ChatListPageDirectionFfi.FORWARD, WIDGET_NATIVE_PAGE_SIZE)
    check(next.subscriptionGeneration == frame.subscriptionGeneration)
    return next.takeUnless {
        it.rows
            .lastOrNull()
            ?.row
            ?.groupIdHex == edge
    }
}

internal const val WIDGET_NATIVE_PAGE_SIZE = 50u
