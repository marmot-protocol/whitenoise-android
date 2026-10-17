package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ContentCopy
import dev.ipf.whitenoise.android.ui.icons.filled.ErrorOutline
import dev.ipf.whitenoise.android.ui.icons.outlined.RestartAlt
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * The reviewed target of a local abandonment, captured when its confirmation opens so the acting account and the
 * exact conversation are fixed before anything is submitted (#3282 identity contract).
 */
internal data class GroupAbandonmentTarget(
    val accountRef: String,
    val accountName: String,
    val groupIdHex: String,
    val conversationTitle: String,
    val isDm: Boolean,
)

/** Transient confirmation state only — MDK's forget and the cleanup journal own everything durable. */
internal enum class GroupAbandonmentStatus { Review, Running, Failed }

/**
 * Group Details' transient abandonment review, recreated for each controller and account: the captured target,
 * whether its confirm ran, and whether the last Leave did not finish and should offer the escape path.
 */
internal class GroupAbandonmentReview {
    var target by mutableStateOf<GroupAbandonmentTarget?>(null)
        private set
    var attempted by mutableStateOf(false)
    var leaveBlocked by mutableStateOf(false)
        private set

    /** Starts a fresh review, so an earlier failure never carries into a new confirmation. */
    fun open(next: GroupAbandonmentTarget) {
        target = next
        attempted = false
    }

    /** Closes the review without submitting anything. */
    fun dismiss() {
        target = null
        attempted = false
    }

    /**
     * Records a finished group mutation: a Leave that did not finish offers the escape path, and any later success —
     * a Leave, or a mutation such as an admin transfer that unblocks leaving — retires it so it never goes stale.
     */
    fun recordMutation(
        leave: Boolean,
        succeeded: Boolean,
    ) {
        if (leave || succeeded) leaveBlocked = leave && !succeeded
    }

    /** True only while the reviewed account and conversation are still the ones on screen. */
    fun targets(
        accountRef: String?,
        groupIdHex: String,
    ): Boolean {
        val reviewed = target ?: return false
        return reviewed.accountRef == accountRef && reviewed.groupIdHex.equals(groupIdHex, ignoreCase = true)
    }
}

/**
 * The account a Details abandonment may review and confirm for: the conversation's own account, and only while it is
 * also the active one. During a notification-routed early open the two differ, and the review would otherwise name the
 * active account while the forget runs for the conversation's account.
 */
internal fun abandonmentAccount(
    conversationAccount: String?,
    activeAccount: String?,
): String? = conversationAccount?.takeIf { it == activeAccount }

/** The one label for MDK's local forget: abandoning a direct chat, or resetting a group to await a new invite. */
@StringRes
internal fun groupAbandonmentActionLabel(isDm: Boolean): Int =
    when {
        isDm -> R.string.chat_abandon_action
        else -> R.string.group_reset_action
    }

/**
 * The bulk label for the same forget: one selected chat keeps its own direct-chat or group wording, and several chats
 * always read as abandoning chats, whatever their kinds. [dmFlags] holds each selected chat's direct-chat flag.
 */
@StringRes
internal fun groupAbandonmentBatchLabel(dmFlags: Collection<Boolean>): Int =
    when (dmFlags.size) {
        1 -> groupAbandonmentActionLabel(dmFlags.single())
        else -> R.string.chats_abandon_action
    }

/**
 * Group Details entry for MDK 0.10.0's local forget (#3310): a direct chat is abandoned on this device and a group
 * is reset to wait for a Welcome created after the reset. It is deliberately separate from leaving, which tells the
 * other members, and from the ordinary local delete, which keeps receiving. Opening it only asks for confirmation.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupDetailsResetControl(
    isDm: Boolean,
    offered: Boolean,
    enabled: Boolean,
    inProgress: Boolean,
    onRequest: () -> Unit,
) {
    // [offered] is the shared group rule, so Details hides it exactly where the chat list does.
    if (!offered) return
    DangerActionRow(
        icon = if (isDm) ImageVector.vectorResource(R.drawable.ic_person_remove) else Icons.Outlined.RestartAlt,
        title = stringResource(groupAbandonmentActionLabel(isDm)),
        modifier = Modifier.testTag(if (isDm) "chat_info.abandon" else "chat_info.reset"),
        enabled = enabled,
        inProgress = inProgress,
        onClick = onRequest,
    )
}

/**
 * Shared confirmation for Group Details and the chat list. It names the action, the exact conversation and the
 * acting account, states that history and encryption state are erased on this account-device only, and keeps a
 * readable failure with a retry. Confirm is disabled while the forget runs, so repeated taps submit once. A failed
 * attempt shows [error]'s detail with its Copy action inside the dialog, since the screen's own error banner sits
 * behind this modal window and cannot be reached while it is open.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupAbandonmentDialog(
    target: GroupAbandonmentTarget,
    status: GroupAbandonmentStatus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    error: ErrorPresentation? = null,
) = GroupAbandonmentReviewDialog(listOf(target), status, onConfirm, onDismiss, error)

/**
 * The same confirmation for the chat list's bulk action: every reviewed chat of the one acting account, captured
 * when the action was chosen, with the consequences of each kind of chat among them. The batch dialog that follows
 * owns the progress and any per-chat failure.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupAbandonmentDialog(
    targets: List<GroupAbandonmentTarget>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = GroupAbandonmentReviewDialog(targets, GroupAbandonmentStatus.Review, onConfirm, onDismiss)

/** The review for one or several chats, where a single chat keeps its own title and confirm wording. */
@Suppress("FunctionNaming")
@Composable
private fun GroupAbandonmentReviewDialog(
    targets: List<GroupAbandonmentTarget>,
    status: GroupAbandonmentStatus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    error: ErrorPresentation? = null,
) {
    val running = status == GroupAbandonmentStatus.Running
    val failed = status == GroupAbandonmentStatus.Failed
    val anyDm = targets.any { it.isDm }
    val titleRes =
        when {
            targets.size > 1 -> R.string.chats_abandon_dialog_title
            anyDm -> R.string.chat_abandon_dialog_title
            else -> R.string.group_reset_dialog_title
        }
    // Several chats always confirm as one abandonment, and a single chat keeps its own wording.
    val confirmLabel =
        when {
            failed -> R.string.retry
            targets.size > 1 || anyDm -> R.string.chat_abandon_confirm
            else -> R.string.group_reset_confirm
        }
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("group.abandon.dialog"),
        title = { Text(stringResource(titleRes)) },
        text = { GroupAbandonmentBody(targets, failed, error) },
        icon = if (running) ({ CircularProgressIndicator() }) else null,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !running,
                modifier = Modifier.testTag("group.abandon.confirm"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(confirmLabel)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("group.abandon.cancel")) {
                Text(stringResource(if (failed) R.string.close else R.string.cancel))
            }
        },
    )
}

/** Identities first, then the consequences for each kind of chat, then any failure, scrolling as one block. */
@Suppress("FunctionNaming")
@Composable
private fun GroupAbandonmentBody(
    targets: List<GroupAbandonmentTarget>,
    failed: Boolean,
    error: ErrorPresentation?,
) {
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.fadingVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.testTag("group.abandon.identity"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            targets.forEach { target ->
                val identity = if (target.isDm) R.string.abandon_identity_chat else R.string.abandon_identity_group
                Text(stringResource(identity, target.conversationTitle), style = style, color = color)
            }
            targets.firstOrNull()?.let { first ->
                Text(stringResource(R.string.abandon_identity_account, first.accountName), style = style, color = color)
            }
        }
        if (targets.any { it.isDm }) Text(stringResource(R.string.chat_abandon_dialog_message))
        if (targets.any { !it.isDm }) Text(stringResource(R.string.group_reset_dialog_message))
        if (failed) GroupAbandonmentFailure(targets.all { it.isDm }, error)
    }
}

/**
 * A readable, announced failure. The journal keeps any committed cleanup, so a retry never replays the forget. With
 * an [error], its selectable detail and a Copy action for the privacy-safe report stay inside the dialog.
 */
@Suppress("FunctionNaming")
@Composable
private fun GroupAbandonmentFailure(
    isDm: Boolean,
    error: ErrorPresentation?,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth().testTag("group.abandon.error"),
    ) {
        Row(
            Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val summary = if (isDm) R.string.toast_couldnt_abandon_chat else R.string.toast_couldnt_reset_group
                Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
                error?.let {
                    SelectionContainer(Modifier.testTag("group.abandon.error.detail")) {
                        Text(
                            stringResource(R.string.latest_group_error, it.message.resolve(context)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            error?.let {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(it.report)) },
                    modifier = Modifier.testTag("group.abandon.error.copy"),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
                }
            }
        }
    }
}

/**
 * Shown after a Leave did not finish, for example when the only other member cannot receive admin: the escape path
 * to abandon this conversation on this device without waiting for a handover.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupLeaveBlockedNotice(
    isDm: Boolean,
    enabled: Boolean,
    onAbandon: () -> Unit,
) {
    val hint = if (isDm) R.string.leave_blocked_abandon_hint_dm else R.string.leave_blocked_abandon_hint_group
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth().testTag("chat_info.leave_blocked"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = onAbandon,
                enabled = enabled,
                modifier = Modifier.align(Alignment.End).testTag("chat_info.leave_blocked.abandon"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(groupAbandonmentActionLabel(isDm))) }
        }
    }
}

/**
 * The You're the only admin gate when no member can receive admin, the fallback of the transfer-then-leave route.
 * Cancel only dismisses, and the error-coloured action opens the reviewed abandonment, which the body names as the
 * way out on this device (#3310). [identity] names the acting account below that guidance, as every group
 * action does (#3282).
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupSoleAdminLeaveDialog(
    groupName: String,
    isDm: Boolean,
    onAbandon: () -> Unit,
    onDismiss: () -> Unit,
    identity: (@Composable () -> Unit)? = null,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("chat_info.sole_admin_leave"),
        title = { Text(stringResource(R.string.confirm_leave_sole_admin_title)) },
        text = {
            Column(
                Modifier.fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmojiLabel(stringResource(R.string.confirm_leave_sole_admin_message, groupName))
                Text(
                    stringResource(
                        if (isDm) R.string.sole_admin_abandon_hint_dm else R.string.sole_admin_abandon_hint_group,
                    ),
                )
                identity?.invoke()
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAbandon,
                modifier = Modifier.testTag("chat_info.sole_admin_leave.abandon"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(groupAbandonmentActionLabel(isDm))) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("chat_info.sole_admin_leave.cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
