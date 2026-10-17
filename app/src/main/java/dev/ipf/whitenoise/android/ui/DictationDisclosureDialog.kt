package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/** The app retains provider state and consent; both actual disclosure variants share these controls. */
@Composable
@Suppress("FunctionNaming")
internal fun DictationDisclosureDialog(
    offlineSpeechToText: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title =
        if (offlineSpeechToText) R.string.dictation_disclosure_offline_title else R.string.dictation_disclosure_title
    val message =
        if (offlineSpeechToText) {
            R.string.dictation_disclosure_offline_message
        } else {
            R.string.dictation_disclosure_message
        }
    ConfirmDialog(
        title = stringResource(title),
        message = stringResource(message),
        confirmLabel = stringResource(R.string.dictation_continue),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
