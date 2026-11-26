package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal const val CHAT_LIST_PINNED_BOUNDARY_KEY = Long.MIN_VALUE
internal const val CHAT_LIST_PINNED_BOUNDARY_TAG = "chat-list-pinned-boundary-divider"
private const val CHAT_LIST_PINNED_BOUNDARY_CONTENT_TYPE = "pinned-boundary"

/** Below every row's target z-index, so an opaque row crossing the boundary covers the line. */
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
 * The retained boundary between pinned and unpinned rows. It moves with the rows' placement
 * tween rather than jumping to its new slot, and sits beneath them while they cross it. A
 * newly shown boundary fades in like a row; a removed one leaves at once, so no line is left
 * behind at its old position.
 */
internal fun LazyListScope.chatListPinnedBoundaryItem(placementDurationMillis: Int) {
    item(key = CHAT_LIST_PINNED_BOUNDARY_KEY, contentType = CHAT_LIST_PINNED_BOUNDARY_CONTENT_TYPE) {
        ChatListPinnedBoundary(
            chatListPlacementMotion(placementDurationMillis).zIndex(CHAT_LIST_PINNED_BOUNDARY_Z_INDEX),
        )
    }
}

/** The line between pinned and unpinned chats, in full `outlineVariant` in every theme. */
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
        // Full outlineVariant in every theme: the muted AMOLED seam color is too dark for this boundary.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
