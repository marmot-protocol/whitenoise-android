package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageDeleteCapability

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
 * capability the controller re-validates on the mutation path — with theme
 * error tokens for the destructive choices, so it holds up in light, dark,
 * and AMOLED themes alike.
 *
 * While a delete-for-everyone publish is running both destructive options are
 * disabled and the dialog stays up; the caller keeps it open on failure so
 * the error toast never has to compete with a silently vanished dialog.
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
        Spacer(Modifier.height(8.dp))
        if (capability.canDeleteForEveryone) {
            Button(
                onClick = onDeleteForEveryone,
                enabled = !deleteInFlight,
                shape = MaterialTheme.shapes.large,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
            ) {
                if (deleteInFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.delete_for_everyone))
            }
        }
        if (capability.canDeleteForMe) {
            OutlinedButton(
                onClick = onDeleteForMe,
                enabled = !deleteInFlight,
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.delete_for_me))
            }
        }
        TextButton(
            onClick = onCancel,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
}
