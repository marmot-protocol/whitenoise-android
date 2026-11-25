package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCopier

/** Keeps the native create/Amber result visible above the secure full-screen window, including safe-report Copy. */
@Suppress("FunctionNaming")
@Composable
internal fun AddProfileFeedbackDialog(
    feedback: ToastMessage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = rememberClipboardCopier()
    val report = feedback.diagnosticReport?.takeIf { feedback.copyable }
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(feedback.title.resolve(context)) },
        text = { feedback.detail?.let { SelectionContainer { Text(it.resolve(context)) } } },
        confirmButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag("add-profile-feedback-dismiss")) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton =
            report?.let { safeReport ->
                {
                    TextButton(
                        onClick = { clipboard.copy(safeReport) },
                        modifier = Modifier.testTag("add-profile-feedback-copy"),
                    ) { Text(stringResource(R.string.copy)) }
                }
            },
    )
}
