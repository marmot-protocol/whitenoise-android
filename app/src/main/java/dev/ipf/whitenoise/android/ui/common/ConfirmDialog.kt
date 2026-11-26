package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiLabel

/**
 * A yes/no confirmation on the app dialog; a destructive confirm takes the error role, and [dismissLabel] replaces
 * Cancel. [identity] is the body-content slot shown below [message] (the acting identity, an identifier, or the whole
 * body when [message] is null). The title and message draw custom emoji artwork, since they often quote a folder,
 * chat or person name. The tags, [confirmModifier] and [closeOnConfirm] exist for the few flows that keep the dialog
 * open while they run.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String? = null,
    identity: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
    ownerKey: Any? = dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph.current,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    closeOnConfirm: Boolean = true,
    confirmModifier: Modifier = Modifier,
    confirmTag: String = "confirmation.confirm",
    cancelTag: String = "confirmation.cancel",
    emojiLabels: Boolean = true,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        ownerKey = ownerKey,
        modifier = modifier,
        icon = icon,
        confirmButton = {
            TextButton(
                onClick = { if (closeOnConfirm) close(onConfirm) else onConfirm() },
                enabled = confirmEnabled,
                modifier = confirmModifier.testTag(confirmTag),
                colors =
                    if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag(cancelTag)) {
                Text(dismissLabel ?: stringResource(R.string.cancel))
            }
        },
        title = { ConfirmText(title, emojiLabels) },
        text = {
            Column(
                Modifier.fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                message?.let { ConfirmText(it, emojiLabels) }
                identity?.invoke()
            }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun ConfirmText(
    text: String,
    emoji: Boolean,
) {
    if (emoji) EmojiLabel(text) else Text(text)
}
