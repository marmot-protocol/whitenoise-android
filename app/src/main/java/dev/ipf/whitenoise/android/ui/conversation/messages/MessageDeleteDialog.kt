package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * The one adaptive confirmation dialog behind the single "Delete" message
 * action. It renders only the scopes [capability] permits — the same
 * capability the controller re-validates on the mutation path — as plain
 * dialog text buttons (error-tinted for the destructive choices, matching
 * [dev.ipf.whitenoise.android.ui.common.ConfirmDialog]'s destructive
 * affordance), so it holds up in light, dark, and AMOLED themes alike.
 * With both scopes the actions stack end-aligned; with one they sit in a
 * single end-aligned row with Cancel leading.
 *
 * While a delete-for-everyone publish is running both destructive options are
 * disabled; the dialog stays dismissible (Cancel/back/outside) because the
 * mutation survives dismissal. Success closes it, and on failure the caller
 * keeps it open — when still shown — so the error toast never has to compete
 * with a silently vanished dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageDeleteDialog(
    capability: MessageDeleteCapability,
    mine: Boolean,
    senderDisplayName: String,
    deleteInFlight: Boolean,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            MessageDeleteDialogContent(
                capability = capability,
                mine = mine,
                senderDisplayName = senderDisplayName,
                deleteInFlight = deleteInFlight,
                onDeleteForEveryone = onDeleteForEveryone,
                onDeleteForMe = onDeleteForMe,
                onCancel = onDismissRequest,
            )
        }
    }
}

/**
 * The dialog body, separated from the [BasicAlertDialog] wrapper so behavior
 * and screenshot tests can compose it directly (a dialog renders in its own
 * window, which the JVM screenshot harness can't capture).
 */
@Composable
internal fun MessageDeleteDialogContent(
    capability: MessageDeleteCapability,
    mine: Boolean,
    senderDisplayName: String,
    deleteInFlight: Boolean,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onCancel: () -> Unit,
) {
    val supportingCopy = messageDeleteSupportingCopy(capability, mine)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text =
                if (supportingCopy == MessageDeleteSupportingCopy.MODERATOR_REMOVAL) {
                    stringResource(R.string.confirm_delete_member_message_title, senderDisplayName)
                } else {
                    stringResource(R.string.delete_message_title)
                },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text =
                when (supportingCopy) {
                    MessageDeleteSupportingCopy.MODERATOR_REMOVAL ->
                        stringResource(R.string.confirm_delete_member_message_message)
                    MessageDeleteSupportingCopy.SCOPE_CHOICE ->
                        stringResource(R.string.delete_message_scope_choice)
                    MessageDeleteSupportingCopy.LOCAL_ONLY ->
                        stringResource(R.string.delete_message_local_only)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        val destructiveColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        val deleteForEveryone: @Composable () -> Unit = {
            TextButton(
                onClick = onDeleteForEveryone,
                enabled = !deleteInFlight,
                colors = destructiveColors,
            ) {
                if (deleteInFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.delete_for_everyone))
            }
        }
        val deleteForMe: @Composable () -> Unit = {
            TextButton(
                onClick = onDeleteForMe,
                enabled = !deleteInFlight,
                colors = destructiveColors,
            ) {
                Text(stringResource(R.string.delete_for_me))
            }
        }
        val cancel: @Composable () -> Unit = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
        if (capability.canDeleteForEveryone && capability.canDeleteForMe) {
            Column(
                modifier = Modifier.align(Alignment.End),
                horizontalAlignment = Alignment.End,
            ) {
                deleteForEveryone()
                deleteForMe()
                cancel()
            }
        } else {
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cancel()
                if (capability.canDeleteForEveryone) deleteForEveryone() else deleteForMe()
            }
        }
    }
}

/**
 * Confirmation for multi-select message deletion, styled to match the
 * single-message [MessageDeleteDialogContent]. When at least one selected
 * message can be removed for the whole group ([BatchDeleteBreakdown.canOfferDeleteForEveryone])
 * the user chooses between removing for everyone (each message where they are
 * allowed, the rest hidden locally) and hiding all on this device; otherwise
 * only the local-hide action is offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchMessageDeleteDialog(
    selectedCount: Int,
    breakdown: BatchDeleteBreakdown,
    deleteInFlight: Boolean,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            BatchMessageDeleteDialogContent(
                selectedCount = selectedCount,
                breakdown = breakdown,
                deleteInFlight = deleteInFlight,
                onDeleteForEveryone = onDeleteForEveryone,
                onDeleteForMe = onDeleteForMe,
                onCancel = onDismissRequest,
            )
        }
    }
}

/** Body of [BatchMessageDeleteDialog], split out for direct test composition. */
@Composable
internal fun BatchMessageDeleteDialogContent(
    selectedCount: Int,
    breakdown: BatchDeleteBreakdown,
    deleteInFlight: Boolean,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onCancel: () -> Unit,
) {
    val offerEveryone = breakdown.canOfferDeleteForEveryone
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = pluralStringResource(R.plurals.batch_delete_title, selectedCount, selectedCount),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text =
                if (offerEveryone) {
                    stringResource(R.string.batch_delete_scope_choice)
                } else {
                    pluralStringResource(R.plurals.batch_delete_local_only, selectedCount, selectedCount)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        val destructiveColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        val deleteForEveryone: @Composable () -> Unit = {
            TextButton(
                onClick = onDeleteForEveryone,
                enabled = !deleteInFlight,
                colors = destructiveColors,
            ) {
                if (deleteInFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.delete_for_everyone))
            }
        }
        val deleteForMe: @Composable () -> Unit = {
            TextButton(
                onClick = onDeleteForMe,
                enabled = !deleteInFlight,
                colors = destructiveColors,
            ) {
                Text(stringResource(R.string.delete_for_me))
            }
        }
        val cancel: @Composable () -> Unit = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
        if (offerEveryone) {
            Column(
                modifier = Modifier.align(Alignment.End),
                horizontalAlignment = Alignment.End,
            ) {
                deleteForEveryone()
                deleteForMe()
                cancel()
            }
        } else {
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cancel()
                deleteForMe()
            }
        }
    }
}
