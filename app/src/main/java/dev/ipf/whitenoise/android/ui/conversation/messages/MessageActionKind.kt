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
    Translate,
    Forward,
    KeepOnScreen,
    ShowLess,
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
    canTranslate: Boolean = false,
    canForward: Boolean,
    canKeepOnScreen: Boolean = false,
    canShowLess: Boolean = false,
    canShare: Boolean = false,
    canSave: Boolean,
    canInfo: Boolean = true,
    canReport: Boolean = false,
): List<MessageActionKind> =
    // Keep on screen leads so it remains in the first row of a two-column menu.
    listOf(
        MessageActionKind.KeepOnScreen to canKeepOnScreen,
        MessageActionKind.ShowLess to canShowLess,
        MessageActionKind.Edit to canEdit,
        MessageActionKind.SelectText to canSelectText,
        MessageActionKind.Reply to canReply,
        MessageActionKind.Forward to canForward,
        MessageActionKind.Share to canShare,
        MessageActionKind.Save to canSave,
        MessageActionKind.CopyText to canCopyText,
        MessageActionKind.Translate to canTranslate,
        MessageActionKind.Speak to canSpeak,
        MessageActionKind.SpeakCodeLiterally to (canSpeak && canSpeakCodeLiterally),
        MessageActionKind.Select to canSelect,
        MessageActionKind.Info to canInfo,
        MessageActionKind.Report to canReport,
    ).filter { (_, enabled) -> enabled }.map { (kind, _) -> kind }

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
    stringResource(
        messageActionLabelResources.getValue(kind),
    )

private val messageActionLabelResources =
    mapOf(
        MessageActionKind.ShowLess to R.string.show_less,
        MessageActionKind.Reply to R.string.reply,
        MessageActionKind.Edit to R.string.edit,
        MessageActionKind.Select to R.string.select,
        MessageActionKind.SelectText to R.string.select_text,
        MessageActionKind.CopyText to R.string.copy,
        MessageActionKind.Speak to R.string.read_aloud,
        MessageActionKind.SpeakCodeLiterally to R.string.read_code_literally,
        MessageActionKind.Translate to R.string.translation_title,
        MessageActionKind.Forward to R.string.forward,
        MessageActionKind.KeepOnScreen to R.string.floating_keep,
        MessageActionKind.Share to R.string.shared_media_share,
        MessageActionKind.Save to R.string.save_attachments,
        MessageActionKind.Info to R.string.info,
        MessageActionKind.Report to R.string.report_message,
    )

/** The drawable the menu row leads with, mirroring the prototype's icon set. */
@DrawableRes
internal fun messageActionIconRes(kind: MessageActionKind): Int = messageActionIconResources.getValue(kind)

private val messageActionIconResources =
    mapOf(
        MessageActionKind.ShowLess to R.drawable.ic_arrow_up,
        MessageActionKind.Reply to R.drawable.ic_reply,
        MessageActionKind.Edit to R.drawable.ic_edit,
        MessageActionKind.Select to R.drawable.ic_check,
        MessageActionKind.SelectText to R.drawable.ic_text_fields,
        MessageActionKind.CopyText to R.drawable.ic_content_copy,
        MessageActionKind.Speak to R.drawable.ic_volume_up,
        MessageActionKind.SpeakCodeLiterally to R.drawable.ic_code,
        MessageActionKind.Translate to R.drawable.ic_translate,
        MessageActionKind.Forward to R.drawable.ic_forward,
        MessageActionKind.Report to R.drawable.ic_emoji_flags,
        MessageActionKind.KeepOnScreen to R.drawable.ic_floating_message,
        MessageActionKind.Share to R.drawable.ic_share,
        MessageActionKind.Save to R.drawable.ic_download,
        MessageActionKind.Info to R.drawable.ic_info,
    )
