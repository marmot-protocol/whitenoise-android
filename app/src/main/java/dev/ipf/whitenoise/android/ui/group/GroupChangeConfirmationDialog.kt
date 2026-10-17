package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.GroupChangeCaution
import dev.ipf.whitenoise.android.state.GroupChangeConfirmation
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/**
 * Asks whether to wait or let one refused group change through (#3289). Continuing is the risky choice, so it takes
 * the error role, and dismissing the dialog waits.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupChangeConfirmationDialog(question: GroupChangeConfirmation) {
    val (title, message, confirm) = groupChangeConfirmationText(question.caution, question.invite)
    ConfirmDialog(
        title = stringResource(title),
        message = stringResource(message),
        confirmLabel = stringResource(confirm),
        onConfirm = { question.answer(proceed = true) },
        onDismiss = { question.answer(proceed = false) },
        destructive = true,
        dismissLabel = stringResource(R.string.group_change_wait),
    )
}

/** The title, message and continue label for [caution], with MDK's stronger wording when inviting. */
internal fun groupChangeConfirmationText(
    caution: GroupChangeCaution,
    invite: Boolean,
): Triple<Int, Int, Int> =
    when (caution) {
        GroupChangeCaution.CatchingUp ->
            if (invite) {
                Triple(
                    R.string.group_change_catching_up_title,
                    R.string.group_change_catching_up_invite_message,
                    R.string.group_change_invite_anyway,
                )
            } else {
                Triple(
                    R.string.group_change_catching_up_title,
                    R.string.group_change_catching_up_message,
                    R.string.group_change_continue_anyway,
                )
            }
        GroupChangeCaution.Undelivered ->
            Triple(
                R.string.group_change_undelivered_title,
                R.string.group_change_undelivered_message,
                R.string.group_change_continue_anyway,
            )
    }
