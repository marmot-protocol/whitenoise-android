package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.ui.common.directionalSwipe
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedActionMenu
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageAction
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageReplySwipeGlyph
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberMessageReplySwipeState
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard

private const val DEMO_MESSAGE_ID = "walkthrough-message"

@Suppress("FunctionNaming")
@Composable
internal fun LearnMessageDemo() {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var replyOpen by rememberSaveable { mutableStateOf(false) }
    var reacted by rememberSaveable { mutableStateOf(false) }
    val beginReply: () -> Unit = {
        menuOpen = false
        replyOpen = true
    }
    val message = stringResource(R.string.learn_example_message)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.learn_demo_label), style = MaterialTheme.typography.labelLarge)
        LearnDemoBubble(message, onShowActions = { menuOpen = !menuOpen }, onReply = beginReply)
        if (reacted) {
            ReactionPillRow(
                tallies = listOf(ReactionTally("👍", count = 1, mine = true)),
                enabled = false,
                onOpenDetails = {},
            )
        }
        if (menuOpen) {
            FocusedActionMenu(
                listOf(
                    demoAction(stringResource(R.string.reply), R.drawable.ic_reply_swipe, beginReply),
                    demoAction(stringResource(R.string.message_react), R.drawable.ic_emoji_smileys) {
                        menuOpen = false
                        reacted = !reacted
                    },
                ),
            )
        }
        if (replyOpen) {
            ReplyPreviewCard(
                senderTitle = stringResource(R.string.learn_demo_sender),
                isOwn = false,
                body = message,
                mediaKind = ReplyMediaKind.None,
                onClick = null,
                onDismiss = { replyOpen = false },
            )
        }
    }
}

private fun demoAction(
    label: String,
    icon: Int,
    onClick: () -> Unit,
) = FocusedMessageAction(
    label = label,
    supportingLabel = null,
    enabled = true,
    destructive = false,
    icon = { Icon(painterResource(icon), contentDescription = null) },
    onClick = onClick,
)

@Suppress("FunctionNaming")
@Composable
private fun LearnDemoBubble(
    message: String,
    onShowActions: () -> Unit,
    onReply: () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val swipe = rememberMessageReplySwipeState(DEMO_MESSAGE_ID, rtl)
    val showActions = stringResource(R.string.learn_show_actions)
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { if (swipe.atRest) swipe.rowBoundsInRoot = it.boundsInRoot() }
            .directionalSwipe(
                owner = DEMO_MESSAGE_ID,
                settings = rtl,
                left = rtl,
                right = !rtl,
                onDistance = { distance, _ -> swipe.dragTo(distance) },
                onRelease = { swipe.release(onReply) },
                onCancel = swipe::cancel,
            ),
    ) {
        MessageReplySwipeGlyph(state = swipe, messageIdHex = DEMO_MESSAGE_ID)
        MessageBubbleFrame(
            presentation = messageBubblePresentation(deleted = false, mine = false),
            highlighted = false,
            mine = false,
            mentionedSelf = false,
            mentionedYouLabel = "",
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .onGloballyPositioned { if (swipe.atRest) swipe.bubbleBoundsInRoot = it.boundsInRoot() }
                    .graphicsLayer { translationX = if (rtl) -swipe.displayedDistance else swipe.displayedDistance }
                    .combinedClickable(
                        onClickLabel = showActions,
                        onClick = onShowActions,
                        onLongClick = onShowActions,
                        onLongClickLabel = showActions,
                    ),
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
