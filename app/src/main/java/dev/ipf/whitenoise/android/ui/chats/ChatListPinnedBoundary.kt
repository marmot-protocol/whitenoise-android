package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val CHAT_LIST_PINNED_BOUNDARY_KEY = Long.MIN_VALUE
internal const val CHAT_LIST_PINNED_BOUNDARY_TAG = "chat-list-pinned-boundary-divider"

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
