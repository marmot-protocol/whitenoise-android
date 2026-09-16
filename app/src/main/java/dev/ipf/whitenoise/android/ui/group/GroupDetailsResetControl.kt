package dev.ipf.whitenoise.android.ui.group

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/**
 * MDK 0.10.0 local reset for a stuck group: erases this device's copy and waits for a Welcome created
 * after the reset. It is deliberately separate from the ordinary local delete and from leaving, and it
 * always asks for confirmation because it cannot be undone from this device.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupDetailsResetControl(
    isDm: Boolean,
    readOnlyInvite: Boolean,
    enabled: Boolean,
    inProgress: Boolean,
    onResetConfirmed: () -> Unit,
) {
    if (isDm || readOnlyInvite) return
    var confirmOpen by remember { mutableStateOf(false) }
    DangerActionRow(
        icon = Icons.Outlined.RestartAlt,
        title = stringResource(R.string.group_reset_action),
        enabled = enabled,
        inProgress = inProgress,
        onClick = { confirmOpen = true },
    )
    if (confirmOpen) {
        ConfirmDialog(
            title = stringResource(R.string.group_reset_dialog_title),
            message = stringResource(R.string.group_reset_dialog_message),
            confirmLabel = stringResource(R.string.group_reset_confirm),
            onConfirm = {
                confirmOpen = false
                onResetConfirmed()
            },
            onDismiss = { confirmOpen = false },
            destructive = true,
        )
    }
}
