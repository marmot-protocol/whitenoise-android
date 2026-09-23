package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.verticalEdgeFade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
): Modifier {
    val topFade = remember(listState) { Animatable(0.dp, Dp.VectorConverter) }
    val bottomFade = remember(listState) { Animatable(0.dp, Dp.VectorConverter) }
    LaunchedEffect(listState, fadeHeight) {
        snapshotFlow { chatListEdgeFadeState(listState.canScrollBackward, listState.canScrollForward) }
            .collectLatest { state ->
                coroutineScope {
                    launch { topFade.animateTo(if (state.top) fadeHeight else 0.dp) }
                    launch { bottomFade.animateTo(if (state.bottom) fadeHeight else 0.dp) }
                }
            }
    }
    return verticalEdgeFade(topFade = { topFade.value }, bottomFade = { bottomFade.value })
}
