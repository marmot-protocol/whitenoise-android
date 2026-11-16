package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal const val CHAT_LIST_PINNED_BOUNDARY_KEY = Long.MIN_VALUE
internal const val CHAT_LIST_PINNED_BOUNDARY_TAG = "chat-list-pinned-boundary-divider"

/** Below every row: rows paint at `-targetIndex`. */
private const val CHAT_LIST_PINNED_BOUNDARY_Z_INDEX = -Float.MAX_VALUE

internal fun pinnedBoundaryIndex(
    pinnedStates: List<Boolean>,
    showArchived: Boolean,
): Int? {
    if (showArchived) return null
    val firstUnpinned = pinnedStates.indexOfFirst { !it }
    return firstUnpinned.takeIf { boundary ->
        boundary > 0 && pinnedStates.drop(boundary).none { it }
    }
}

/**
 * Pinned-section divider motion. It travels with the rows' placement timing so
 * the section boundary never jumps while rows animate around it. It paints
 * beneath every row, so an opaque row crossing the boundary covers the line
 * instead of the line drawing through the row. It fades in like an inserted row
 * and leaves at once when either section empties, so no stranded line remains.
 */
internal fun LazyItemScope.chatListPinnedBoundaryMotion(
    placementDurationMillis: Int = CHAT_LIST_ROW_PLACEMENT_MILLIS,
): Modifier =
    Modifier
        .animateItem(
            fadeInSpec = tween(CHAT_LIST_MEMBERSHIP_FADE_MILLIS),
            placementSpec = tween(placementDurationMillis),
            fadeOutSpec = null,
        ).zIndex(CHAT_LIST_PINNED_BOUNDARY_Z_INDEX)

@Composable
@Suppress("FunctionNaming")
internal fun ChatListPinnedBoundary(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag(CHAT_LIST_PINNED_BOUNDARY_TAG),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
