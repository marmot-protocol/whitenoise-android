package dev.ipf.whitenoise.android.widget

import dev.ipf.marmotkit.ChatListPageDirectionFfi
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
    var frame = checkNotNull(handle.snapshot())
    val generation = frame.subscriptionGeneration
    while (true) {
        currentCoroutineContext().ensureActive()
        check(frame.subscriptionGeneration == generation)
        for (presented in frame.rows) {
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
                if (matches.size == MAX_WIDGET_ROWS) return matches.values.toList()
            }
        }
        if (!unreadOnly || !frame.hasMoreAfter) return matches.values.toList()
        val edge =
            frame.rows
                .lastOrNull()
                ?.row
                ?.groupIdHex ?: return matches.values.toList()
        // Native capped windows need the retained tail as their anchor before advancing.
        val anchored = handle.setVisibleAnchor(frame.sequence, edge)
        check(anchored.subscriptionGeneration == generation)
        frame = handle.page(anchored.sequence, ChatListPageDirectionFfi.FORWARD, WIDGET_NATIVE_PAGE_SIZE)
        check(frame.subscriptionGeneration == generation)
        if (frame.rows
                .lastOrNull()
                ?.row
                ?.groupIdHex == edge
        ) {
            return matches.values.toList()
        }
    }
}

internal const val WIDGET_NATIVE_PAGE_SIZE = 50u
