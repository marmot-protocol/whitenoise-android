package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade

/** How far a chat-list row dissolves before it reaches the surrounding chrome. */
internal val CHAT_LIST_EDGE_FADE_HEIGHT: Dp = 28.dp

/** Which edges of the normal, non-reversed chat list currently have more content beyond them. */
internal data class ChatListEdgeFadeState(
    val top: Boolean,
    val bottom: Boolean,
)

/** Maps normal-list scrollability to the two independently gated fade edges. */
internal fun chatListEdgeFadeState(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): ChatListEdgeFadeState =
    ChatListEdgeFadeState(
        top = canScrollBackward,
        bottom = canScrollForward,
    )

/** Dissolves only scrollable chat-list content at edges that have off-screen rows. */
@Composable
internal fun Modifier.chatListEdgeFade(
    listState: LazyListState,
    fadeHeight: Dp = CHAT_LIST_EDGE_FADE_HEIGHT,
): Modifier = scrollEdgeFade(listState, fadeHeight = fadeHeight)
