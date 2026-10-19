package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.GroupChangeCaution
import dev.ipf.whitenoise.android.state.GroupChangeConfirmation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.ownsGroupChange
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupActingAccountIdentity
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMemberReviewPair
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupReviewDestinationIdentity
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/**
 * Asks whether to wait or let one refused group change through (#3289). It names the chat the change is for, because
 * the question can outlive the screen that started it. Continuing is the risky choice, so it takes the error role, and
 * dismissing the dialog waits.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupChangeConfirmationDialog(
    question: GroupChangeConfirmation,
    appState: WhiteNoiseAppState? = null,
) {
    val (title, message, confirm) = groupChangeConfirmationText(question.caution, question.invite)
    val chat = question.target.title
    ConfirmDialog(
        title = stringResource(title),
        message = stringResource(message),
        confirmLabel = stringResource(confirm),
        onConfirm = { question.answer(proceed = appState?.ownsGroupChange(question.target) != false) },
        onDismiss = { question.answer(proceed = false) },
        destructive = true,
        dismissLabel = stringResource(R.string.group_change_wait),
        identity = {
            Column(Modifier.testTag(GROUP_CHANGE_TARGET_TAG)) {
                if (appState == null) {
                    chat?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                } else {
                    val target = question.target
                    val group = MemberReviewGroup(target.groupIdHex, chat.orEmpty())
                    GroupActingAccountIdentity(appState, target.accountRef)
                    target.actionLabel?.let { Text(stringResource(it)) }
                    if (target.actionLabel == R.string.remove_admin) {
                        Text(stringResource(R.string.group_action_revoke_explanation))
                    }
                    target.retentionSeconds?.let {
                        Text(
                            stringResource(
                                R.string.disappearing_confirm_message,
                                disappearingMessagesLabel(it.toLong()),
                            ),
                        )
                    }
                    if (target.affectedMemberIds.isEmpty()) {
                        GroupReviewDestinationIdentity(group)
                    } else {
                        Text(
                            pluralStringResource(
                                R.plurals.selected_members_count,
                                target.affectedMemberIds.size,
                                target.affectedMemberIds.size,
                            ),
                        )
                        target.affectedMemberIds.forEach { memberId ->
                            GroupMemberReviewPair(
                                appState = appState,
                                group = group,
                                member = RecipientSearch.Candidate(memberId, "", appState.npubForDisplay(memberId)),
                                busy = true,
                                initialAdminIds = target.initialAdminIds,
                                onInitialAdminChange = if (target.initialAdminIds.isEmpty()) null else ({ _, _ -> }),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Test tag of the chat name a group-change question is about. */
internal const val GROUP_CHANGE_TARGET_TAG = "group.change.target"

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
