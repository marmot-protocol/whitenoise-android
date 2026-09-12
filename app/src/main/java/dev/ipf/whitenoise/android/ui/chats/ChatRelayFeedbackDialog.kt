package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.border
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Keeps the native relay mutation result visible above the secure full-screen window, including safe-report Copy. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatRelayFeedbackDialog(
    feedback: ToastMessage,
    onDismiss: () -> Unit,
    securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val report = feedback.diagnosticReport?.takeIf { feedback.copyable }
    val outline = amoledOutlineBorder()
    AlertDialog(
        modifier = if (outline != null) Modifier.border(outline, MaterialTheme.shapes.extraLarge) else Modifier,
        properties = DialogProperties(securePolicy = securePolicy),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onDismissRequest = onDismiss,
        title = { Text(feedback.title.resolve(context)) },
        text = { feedback.detail?.let { SelectionContainer { Text(it.resolve(context)) } } },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("chat-relay-feedback-dismiss")) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton =
            report?.let { safeReport ->
                {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(safeReport)) },
                        modifier = Modifier.testTag("chat-relay-feedback-copy"),
                    ) { Text(stringResource(R.string.copy)) }
                }
            },
    )
}
