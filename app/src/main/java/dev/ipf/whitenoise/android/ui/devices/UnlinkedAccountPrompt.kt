@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/**
 * Covers the app while the profile in view is one another of the account's devices unlinked. MDK has signed it out
 * and will not sign it in again, so its chats must not stay on screen as if they worked. The one way forward is to
 * remove the account from this device, after which signing in links it anew. [blocked] holds the prompt back
 * while the app is locked or the removal is already running.
 */
@Composable
internal fun UnlinkedAccountPromptHost(
    appState: WhiteNoiseAppState,
    blocked: Boolean,
) {
    if (appState.activeAccount?.unlinked != true || blocked || appState.wipeInProgress) return
    UnlinkedAccountPrompt(onRemove = { launchAccountWipe(appState) })
}

/** The prompt itself. It has no dismissal: closing it would return to chats of an account that cannot be used. */
@Composable
internal fun UnlinkedAccountPrompt(onRemove: () -> Unit) {
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
    )
}
