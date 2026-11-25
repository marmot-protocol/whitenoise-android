package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** The plain-language body held on a menu row; the row's own label is its title. A null [kind] is Delete. */
@Suppress("CyclomaticComplexMethod") // One flat branch per action kind.
@StringRes
internal fun messageActionExplanationRes(kind: MessageActionKind?): Int =
    when (kind) {
        MessageActionKind.Reply -> R.string.explanation_message_action_reply
        MessageActionKind.Edit -> R.string.explanation_message_action_edit
        MessageActionKind.Select -> R.string.explanation_message_action_select
        MessageActionKind.SelectText -> R.string.explanation_message_action_select_text
        MessageActionKind.CopyText -> R.string.explanation_message_action_copy
        MessageActionKind.Translate -> R.string.explanation_message_action_translate
        MessageActionKind.Transcribe -> R.string.explanation_message_action_transcribe
        MessageActionKind.Speak -> R.string.explanation_message_action_read_aloud
        MessageActionKind.SpeakCodeLiterally -> R.string.explanation_message_action_read_code_literally
        MessageActionKind.Forward -> R.string.explanation_message_action_forward
        MessageActionKind.KeepOnScreen -> R.string.explanation_message_action_keep_on_screen
        MessageActionKind.ShowLess -> R.string.explanation_message_action_show_less
        MessageActionKind.Share -> R.string.explanation_message_action_share
        MessageActionKind.Save -> R.string.explanation_message_action_save
        MessageActionKind.Info -> R.string.explanation_message_action_info
        MessageActionKind.Report -> R.string.explanation_message_action_report
        null -> R.string.explanation_message_action_delete
    }

/** Leading-glyph size shared by every message action row. */
private val messageActionIconSize = 24.dp

/**
 * Glyph for a message action row. A null [kind] is the destructive Delete row,
 * which the menu tints with the error colour on its own.
 */
@Composable
@Suppress("FunctionNaming")
internal fun MessageActionIcon(kind: MessageActionKind?) {
    Icon(
        painter = painterResource(kind?.let(::messageActionIconRes) ?: R.drawable.ic_delete),
        contentDescription = null,
        modifier = Modifier.size(messageActionIconSize),
    )
}
