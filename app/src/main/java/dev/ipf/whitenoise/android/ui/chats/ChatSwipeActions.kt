package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipePreferenceState
import dev.ipf.whitenoise.android.ui.common.directionalSwipe
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberMessageReplySwipeState
import kotlin.math.roundToInt

/** Mirrors the existing context-menu capabilities and intentionally offers no destructive or archive command. */
internal fun chatSwipeAllowed(
    action: SwipeAction,
    item: ChatListItem,
    activeAccountId: String?,
): Boolean {
    val actions = item.actions
    return when (action) {
        SwipeAction.ReadUnread ->
            if (item.effectiveHasUnread(activeAccountId)) {
                actions?.canMarkRead != false
            } else {
                !item.removedFromGroup(activeAccountId) && actions?.canMarkUnread != false
            }
        SwipeAction.MuteUnmute -> if (item.engineMuted()) actions?.canUnmute != false else actions?.canMute != false
        SwipeAction.PinUnpin ->
            !item.group.archived &&
                (if (item.pinned()) actions?.canUnpin != false else actions?.canPin != false)
        else -> false
    }
}

/** Entire row participates only when opted in; the normal tap, hold and selection content remains intact. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun ChatSwipeActions(
    owner: Any?,
    settings: SwipePreferenceState,
    enabled: Boolean,
    leftAllowed: Boolean,
    rightAllowed: Boolean,
    hasUnread: Boolean,
    isMuted: Boolean,
    isPinned: Boolean,
    onCommit: (SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val left = settings.chatLeft != SwipeAction.Off
    val right = settings.chatRight != SwipeAction.Off
    val canRecognize by rememberUpdatedState<(Int) -> Boolean> { side ->
        enabled &&
            when {
                side < 0 -> leftAllowed
                side > 0 -> rightAllowed
                else -> leftAllowed || rightAllowed
            }
    }
    val state = rememberMessageReplySwipeState("chat", listOf(owner, settings, left, right))
    var direction by remember(state) { mutableIntStateOf(1) }
    val commit by rememberUpdatedState(onCommit)
    val mounted = remember(state) { booleanArrayOf(true) }
    DisposableEffect(state) {
        onDispose {
            mounted[0] = false
            state.cancel()
        }
    }
    LaunchedEffect(enabled, leftAllowed, rightAllowed) {
        if (!canRecognize(direction)) state.cancel()
    }
    val action = if (direction < 0) settings.chatLeft else settings.chatRight
    Box(
        Modifier.fillMaxWidth().directionalSwipe(
            owner = owner,
            settings = settings,
            left = left,
            right = right,
            onDistance = { distance, side ->
                direction = side
                state.dragTo(distance)
            },
            onRelease = { side ->
                state.release {
                    if (mounted[0] && canRecognize(side)) {
                        commit(if (side < 0) settings.chatLeft else settings.chatRight)
                    }
                }
            },
            onCancel = state::cancel,
            canRecognize = { canRecognize(it) },
        ),
    ) {
        if (!state.atRest && action != SwipeAction.Off) {
            ChatSwipeCue(action, direction, hasUnread, isMuted, isPinned)
        }
        Box(Modifier.absoluteOffset { IntOffset((state.displayedDistance * direction).roundToInt(), 0) }) { content() }
    }
}

/** Shows the next command using the same icons and localized labels as the chat context menu. */
@Composable
@Suppress("FunctionNaming")
private fun BoxScope.ChatSwipeCue(
    action: SwipeAction,
    direction: Int,
    hasUnread: Boolean,
    isMuted: Boolean,
    isPinned: Boolean,
) {
    val (icon, label) =
        when (action) {
            SwipeAction.ReadUnread ->
                if (hasUnread) {
                    R.drawable.ic_check to R.string.chat_row_action_mark_read
                } else {
                    R.drawable.ic_mark_unread to R.string.chat_row_action_mark_unread
                }
            SwipeAction.MuteUnmute ->
                if (isMuted) {
                    R.drawable.ic_settings_notifications to R.string.chat_row_action_unmute
                } else {
                    R.drawable.ic_notifications_off to R.string.chat_row_action_mute
                }
            SwipeAction.PinUnpin ->
                if (isPinned) {
                    R.drawable.ic_unpin to R.string.chat_row_action_unpin
                } else {
                    R.drawable.ic_push_pin to R.string.chat_row_action_pin
                }
            else -> return
        }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        painterResource(icon),
        contentDescription = stringResource(label),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .align(if ((direction < 0) == rtl) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
                .size(24.dp)
                .testTag("chat.swipe.cue"),
    )
}
