@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.settings.launchAccountWipe

internal const val UNLINKED_ACCOUNT_PROMPT_TAG = "device_link.unlinked_account"
internal const val UNLINKED_ACCOUNT_REMOVE_TAG = "device_link.unlinked_account.remove"
internal const val UNLINKED_ACCOUNT_CLOSE_TAG = "device_link.unlinked_account.close"

/**
 * Covers the app while the profile in view is one another of the account's devices unlinked. MDK has signed it out
 * and will not sign it in again, so its chats must not stay on screen as if they worked. The way forward is to
 * remove the account from this device, after which signing in links it anew. A removal that ran and left the
 * account here failed, so from then on the prompt can be closed to reach Settings, another account or diagnostics.
 * [blocked] holds the prompt back while the app is locked, and it stays down while the removal is running.
 */
@Composable
internal fun UnlinkedAccountPromptHost(
    appState: WhiteNoiseAppState,
    blocked: Boolean,
) {
    UnlinkedAccountPromptGate(
        unlinkedLabel = appState.activeAccount?.takeIf { it.unlinked }?.label,
        inView = appState.activeAccountRef,
        hidden = blocked || appState.wipeInProgress,
        onRemove = { launchAccountWipe(appState) },
    )
}

/**
 * Shows the prompt for the unlinked account in view, [unlinkedLabel], unless it is [hidden] or was closed. It
 * remembers which account a removal was attempted for, and which was closed, by label. A removal takes the account
 * out of view while it runs and a failed one puts it back, and the attempt has to survive that gap for Close to be
 * offered. Another account coming [inView], unlinked or not, forgets both, so returning to the first account shows
 * its prompt again.
 */
@Composable
internal fun UnlinkedAccountPromptGate(
    unlinkedLabel: String?,
    hidden: Boolean,
    inView: String? = unlinkedLabel,
    onRemove: () -> Unit,
) {
    var attemptedFor by remember { mutableStateOf<String?>(null) }
    var closedFor by remember { mutableStateOf<String?>(null) }
    // No account in view is the gap a removal leaves, which keeps both.
    LaunchedEffect(inView) {
        if (inView != null && inView != attemptedFor) attemptedFor = null
        if (inView != null && inView != closedFor) closedFor = null
    }
    val label = unlinkedLabel ?: return
    if (hidden || closedFor == label) return
    UnlinkedAccountPrompt(
        onRemove = {
            attemptedFor = label
            onRemove()
        },
        onClose = { closedFor = label }.takeIf { attemptedFor == label },
    )
}

/**
 * The prompt itself. Back and a tap outside never close it, because that would return to chats of an account that
 * cannot be used. [onClose] is offered only after a removal failed, so a device that cannot remove is not stuck.
 */
@Composable
internal fun UnlinkedAccountPrompt(
    onRemove: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        modifier = Modifier.testTag(UNLINKED_ACCOUNT_PROMPT_TAG),
        title = { Text(stringResource(R.string.device_unlinked_title)) },
        text = { Text(stringResource(R.string.unlinked_account_remove_detail)) },
        confirmButton = {
            TextButton(onClick = onRemove, modifier = Modifier.testTag(UNLINKED_ACCOUNT_REMOVE_TAG)) {
                Text(stringResource(R.string.unlinked_account_remove_confirm))
            }
        },
        dismissButton =
            onClose?.let { close ->
                {
                    TextButton(onClick = close, modifier = Modifier.testTag(UNLINKED_ACCOUNT_CLOSE_TAG)) {
                        Text(stringResource(R.string.close))
                    }
                }
            },
    )
}
