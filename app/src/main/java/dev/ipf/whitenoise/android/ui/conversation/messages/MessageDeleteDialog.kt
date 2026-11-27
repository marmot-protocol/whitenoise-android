package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteBreakdown

/**
 * Which supporting copy the unified delete dialog shows. Pure so the
 * copy-selection policy is unit-testable next to the capability matrix.
 */
internal enum class MessageDeleteSupportingCopy {
    /**
     * A moderator is removing another member's group message: the copy says
     * the message disappears for everyone. Deliberately not an admin-branded
     * action — moderation is just "Delete for everyone" being available.
     */
    MODERATOR_REMOVAL,

    /** Both scopes offered; the copy explains the device/everyone split. */
    SCOPE_CHOICE,

    /** Only local removal offered; the copy says others still see it. */
    LOCAL_ONLY,
}

internal fun messageDeleteSupportingCopy(
    capability: MessageDeleteCapability,
    mine: Boolean,
): MessageDeleteSupportingCopy =
    when {
        capability.canDeleteForEveryone && !mine -> MessageDeleteSupportingCopy.MODERATOR_REMOVAL
        capability.canDeleteForEveryone -> MessageDeleteSupportingCopy.SCOPE_CHOICE
        else -> MessageDeleteSupportingCopy.LOCAL_ONLY
    }

/**
 * Shows the scopes permitted by [capability]. The caller rechecks them when
 * submitting. A publish disables both destructive choices while leaving the
 * dialog dismissible, since the mutation survives dismissal.
 */
@Composable
@Suppress("FunctionNaming")
internal fun MessageDeleteDialog(
    capability: MessageDeleteCapability,
    mine: Boolean,
    senderDisplayName: String,
    deleteInFlight: Boolean,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val supportingCopy = messageDeleteSupportingCopy(capability, mine)
    MessageDeleteConfirmationDialog(
        title =
            if (supportingCopy == MessageDeleteSupportingCopy.MODERATOR_REMOVAL) {
                stringResource(R.string.confirm_delete_member_message_title, senderDisplayName)
            } else {
                stringResource(R.string.delete_message_title)
            },
        message =
            when (supportingCopy) {
                MessageDeleteSupportingCopy.MODERATOR_REMOVAL ->
                    stringResource(R.string.confirm_delete_member_message_message)
                MessageDeleteSupportingCopy.SCOPE_CHOICE ->
                    stringResource(R.string.delete_message_scope_choice)
                MessageDeleteSupportingCopy.LOCAL_ONLY ->
                    stringResource(R.string.delete_message_local_only)
            },
        deleteInFlight = deleteInFlight,
        onDeleteForEveryone = onDeleteForEveryone.takeIf { capability.canDeleteForEveryone },
        onDeleteForMe = onDeleteForMe.takeIf { capability.canDeleteForMe || !capability.canDeleteForEveryone },
        onDismissRequest = onDismissRequest,
    )
}

/** Bulk deletion shares the message scopes but blocks dismissal during a submitted delete. */
@Composable
@Suppress("FunctionNaming")
internal fun BatchMessageDeleteDialog(
    selectedCount: Int,
    breakdown: BatchDeleteBreakdown,
    deleteInFlight: Boolean,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    MessageDeleteConfirmationDialog(
        title = pluralStringResource(R.plurals.batch_delete_title, selectedCount, selectedCount),
        message =
            if (breakdown.canOfferDeleteForEveryone) {
                stringResource(R.string.batch_delete_scope_choice)
            } else {
                pluralStringResource(R.plurals.batch_delete_local_only, selectedCount, selectedCount)
            },
        deleteInFlight = deleteInFlight,
        onDeleteForEveryone = onDeleteForEveryone.takeIf { breakdown.canOfferDeleteForEveryone },
        onDeleteForMe = onDeleteForMe,
        onDismissRequest = onDismissRequest,
        dismissEnabled = { !deleteInFlight },
    )
}

/** One scope uses the standard Cancel/confirm row. Two scopes stack without squeezing translated labels. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun MessageDeleteConfirmationDialog(
    title: String,
    message: String,
    deleteInFlight: Boolean,
    onDeleteForEveryone: (() -> Unit)?,
    onDeleteForMe: (() -> Unit)?,
    onDismissRequest: () -> Unit,
    dismissEnabled: () -> Boolean = { true },
) {
    val stacked = onDeleteForEveryone != null && onDeleteForMe != null
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismissRequest,
        dismissEnabled = dismissEnabled,
        title = { Text(title, Modifier.semantics { heading() }) },
        text = { Text(message, Modifier.fadingVerticalScroll(rememberScrollState())) },
        dismissButton =
            if (stacked) {
                null
            } else {
                { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } }
            },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                onDeleteForEveryone?.let {
                    DeleteScopeButton(R.string.delete_for_everyone, deleteInFlight, it, showProgress = deleteInFlight)
                }
                onDeleteForMe?.let { DeleteScopeButton(R.string.delete_for_me, deleteInFlight, it) }
                if (stacked) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun DeleteScopeButton(
    labelRes: Int,
    deleteInFlight: Boolean,
    onDelete: () -> Unit,
    showProgress: Boolean = false,
) {
    TextButton(
        onClick = onDelete,
        enabled = !deleteInFlight,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(labelRes))
    }
}
