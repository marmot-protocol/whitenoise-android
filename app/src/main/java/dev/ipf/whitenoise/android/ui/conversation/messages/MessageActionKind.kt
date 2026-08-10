package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

internal enum class MessageActionKind {
    Reply,
    Edit,
    Select,
    SelectText,
    CopyText,
    Speak,
    Forward,
    Save,
    Info,
}

internal const val MESSAGE_ACTION_MENU_TEST_TAG = "message-action-menu"
internal const val MESSAGE_ACTION_REACTION_TEST_TAG = "message-action-reaction"
private val actionSectionSpacing = 8.dp

internal fun messageActionKinds(
    canReply: Boolean,
    canEdit: Boolean,
    canSelect: Boolean,
    canSelectText: Boolean,
    canCopyText: Boolean,
    canSpeak: Boolean,
    canForward: Boolean,
    canSave: Boolean,
): List<MessageActionKind> =
    buildList {
        if (canReply) add(MessageActionKind.Reply)
        if (canEdit) add(MessageActionKind.Edit)
        if (canSelect) add(MessageActionKind.Select)
        if (canSelectText) add(MessageActionKind.SelectText)
        if (canCopyText) add(MessageActionKind.CopyText)
        if (canSpeak) add(MessageActionKind.Speak)
        if (canForward) add(MessageActionKind.Forward)
        if (canSave) add(MessageActionKind.Save)
        add(MessageActionKind.Info)
    }

internal fun messageActionColumnCount(
    availableWidth: Dp,
    minimumCellWidth: Dp,
): Int = if (availableWidth >= minimumCellWidth * 2 + 2.dp) 2 else 1

internal fun estimatedMessageActionMenuHeight(
    actionCount: Int,
    columns: Int,
    canReact: Boolean,
    canDelete: Boolean,
    actionRowHeight: Dp = 48.dp,
    reactionRowHeight: Dp = 48.dp,
): Dp {
    val gridActionCount = actionCount + if (canDelete) 1 else 0
    val rows = (gridActionCount + columns - 1) / columns
    val actionHeight = actionRowHeight * rows + ((rows - 1).coerceAtLeast(0) * 2).dp
    val sectionHeights =
        buildList {
            if (canReact) add(reactionRowHeight + 9.dp) // row + internal gap + divider
            add(actionHeight)
        }
    return 16.dp + sectionHeights.fold(0.dp) { total, height -> total + height } +
        actionSectionSpacing * (sectionHeights.size - 1)
}

@Composable
internal fun messageActionLabel(kind: MessageActionKind): String =
    when (kind) {
        MessageActionKind.Reply -> stringResource(R.string.reply)
        MessageActionKind.Edit -> stringResource(R.string.edit)
        MessageActionKind.Select -> stringResource(R.string.select)
        MessageActionKind.SelectText -> stringResource(R.string.select_text)
        MessageActionKind.CopyText -> stringResource(R.string.copy_text)
        MessageActionKind.Speak -> stringResource(R.string.speak_aloud)
        MessageActionKind.Forward -> stringResource(R.string.forward)
        MessageActionKind.Save -> stringResource(R.string.shared_media_save)
        MessageActionKind.Info -> stringResource(R.string.message_info)
    }
