package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.annotation.DrawableRes
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
    SpeakCodeLiterally,
    Forward,
    KeepOnScreen,
    Share,
    Save,
    Info,
    Report,
}

internal const val MESSAGE_ACTION_MENU_TEST_TAG = "message-action-menu"

/** The safe frame the lifted stack travels inside; tagged so its resting placement can be captured. */
internal const val FOCUSED_OVERLAY_FRAME_TEST_TAG = "focused-overlay-frame"
internal const val MESSAGE_ACTION_REACTION_TEST_TAG = "message-action-reaction"
internal val messageActionColumnGap = 2.dp
private val actionSectionSpacing = 8.dp

/**
 * The prototype's action order: editing first, then the reply/forward/keep
 * cluster, then the export cluster, then reading, and finally the
 * selection/info/delete tail. Production-only actions sit beside the shared
 * action they extend — literal code reading follows Read Aloud.
 */
@Suppress("LongParameterList") // One boolean per capability keeps the call site self-documenting.
internal fun messageActionKinds(
    canReply: Boolean,
    canEdit: Boolean,
    canSelect: Boolean,
    canSelectText: Boolean,
    canCopyText: Boolean,
    canSpeak: Boolean,
    canSpeakCodeLiterally: Boolean = false,
    canForward: Boolean,
    canKeepOnScreen: Boolean = false,
    canShare: Boolean = false,
    canSave: Boolean,
    canInfo: Boolean = true,
    canReport: Boolean = false,
): List<MessageActionKind> =
    buildList {
        if (canEdit) add(MessageActionKind.Edit)
        if (canSelectText) add(MessageActionKind.SelectText)
        if (canReply) add(MessageActionKind.Reply)
        if (canForward) add(MessageActionKind.Forward)
        if (canKeepOnScreen) add(MessageActionKind.KeepOnScreen)
        if (canShare) add(MessageActionKind.Share)
        if (canSave) add(MessageActionKind.Save)
        if (canCopyText) add(MessageActionKind.CopyText)
        if (canSpeak) add(MessageActionKind.Speak)
        if (canSpeak && canSpeakCodeLiterally) add(MessageActionKind.SpeakCodeLiterally)
        if (canSelect) add(MessageActionKind.Select)
        addAll(trailingMessageActionKinds(canInfo, canReport))
    }

/** The closing actions: Info, then Report, which sits beside delete because both hand the message to someone else. */
private fun trailingMessageActionKinds(
    canInfo: Boolean,
    canReport: Boolean,
): List<MessageActionKind> =
    buildList {
        if (canInfo) add(MessageActionKind.Info)
        if (canReport) add(MessageActionKind.Report)
    }

internal fun messageActionColumnCount(
    availableWidth: Dp,
    minimumCellWidth: Dp,
): Int = if (availableWidth >= minimumCellWidth * 2 + messageActionColumnGap) 2 else 1

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
    val actionHeight = actionRowHeight * rows + messageActionColumnGap * (rows - 1).coerceAtLeast(0)
    val sectionHeights =
        buildList {
            if (canReact) add(reactionRowHeight + 9.dp) // row + internal gap + divider
            add(actionHeight)
        }
    return 16.dp + sectionHeights.fold(0.dp) { total, height -> total + height } +
        actionSectionSpacing * (sectionHeights.size - 1)
}

/** The menu row label for an action, matching the prototype's wording. */
@Composable
internal fun messageActionLabel(kind: MessageActionKind): String =
    when (kind) {
        MessageActionKind.Reply -> stringResource(R.string.reply)
        MessageActionKind.Edit -> stringResource(R.string.edit)
        MessageActionKind.Select -> stringResource(R.string.select)
        MessageActionKind.SelectText -> stringResource(R.string.select_text)
        MessageActionKind.CopyText -> stringResource(R.string.copy)
        MessageActionKind.Speak -> stringResource(R.string.read_aloud)
        MessageActionKind.SpeakCodeLiterally -> stringResource(R.string.read_code_literally)
        MessageActionKind.Forward -> stringResource(R.string.forward)
        MessageActionKind.KeepOnScreen -> stringResource(R.string.floating_keep)
        MessageActionKind.Share -> stringResource(R.string.shared_media_share)
        MessageActionKind.Save -> stringResource(R.string.save_attachments)
        MessageActionKind.Info -> stringResource(R.string.info)
        MessageActionKind.Report -> stringResource(R.string.report_message)
    }

/** The drawable the menu row leads with, mirroring the prototype's icon set. */
@DrawableRes
internal fun messageActionIconRes(kind: MessageActionKind): Int =
    when (kind) {
        MessageActionKind.Reply -> R.drawable.ic_reply
        MessageActionKind.Edit -> R.drawable.ic_edit
        MessageActionKind.Select -> R.drawable.ic_check
        MessageActionKind.SelectText -> R.drawable.ic_content_copy
        MessageActionKind.CopyText -> R.drawable.ic_content_copy
        MessageActionKind.Speak, MessageActionKind.SpeakCodeLiterally -> R.drawable.ic_volume_up
        MessageActionKind.Forward -> R.drawable.ic_forward
        MessageActionKind.Report -> R.drawable.ic_emoji_flags
        MessageActionKind.KeepOnScreen -> R.drawable.ic_floating_message
        MessageActionKind.Share -> R.drawable.ic_share
        MessageActionKind.Save -> R.drawable.ic_download
        MessageActionKind.Info -> R.drawable.ic_info
    }
