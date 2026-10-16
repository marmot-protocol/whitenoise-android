package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.icons.Icons
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

/** The one label for MDK's local forget: abandoning a direct chat, or resetting a group to await a new invite. */
@StringRes
internal fun groupAbandonmentActionLabel(isDm: Boolean): Int =
    when {
        isDm -> R.string.chat_abandon_action
        else -> R.string.group_reset_action
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
    readOnlyInvite: Boolean,
    enabled: Boolean,
    inProgress: Boolean,
    onRequest: () -> Unit,
) {
    if (readOnlyInvite) return
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
 * readable failure with a retry. Confirm is disabled while the forget runs, so repeated taps submit once.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupAbandonmentDialog(
    target: GroupAbandonmentTarget,
    status: GroupAbandonmentStatus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = status == GroupAbandonmentStatus.Running
    val failed = status == GroupAbandonmentStatus.Failed
    val titleRes = if (target.isDm) R.string.chat_abandon_dialog_title else R.string.group_reset_dialog_title
    val confirmLabel =
        when {
            failed -> R.string.retry
            target.isDm -> R.string.chat_abandon_confirm
            else -> R.string.group_reset_confirm
        }
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("group.abandon.dialog"),
        title = { Text(stringResource(titleRes)) },
        text = { GroupAbandonmentBody(target, failed) },
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

/** Identity first, then consequences, then any failure, scrolling as one block at large text sizes. */
@Suppress("FunctionNaming")
@Composable
private fun GroupAbandonmentBody(
    target: GroupAbandonmentTarget,
    failed: Boolean,
) {
    Column(
        Modifier.fadingVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.testTag("group.abandon.identity"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    if (target.isDm) R.string.abandon_identity_chat else R.string.abandon_identity_group,
                    target.conversationTitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.abandon_identity_account, target.accountName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val consequences =
            if (target.isDm) R.string.chat_abandon_dialog_message else R.string.group_reset_dialog_message
        Text(stringResource(consequences))
        if (failed) GroupAbandonmentFailure(target.isDm)
    }
}

/** A readable, announced failure. The journal keeps any committed cleanup, so a retry never replays the forget. */
@Suppress("FunctionNaming")
@Composable
private fun GroupAbandonmentFailure(isDm: Boolean) {
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
            Text(
                stringResource(if (isDm) R.string.toast_couldnt_abandon_chat else R.string.toast_couldnt_reset_group),
                style = MaterialTheme.typography.bodySmall,
            )
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

