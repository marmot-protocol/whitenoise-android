package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** A short actionable message; guidance and the privacy-safe report stay behind Details. */
@Suppress("FunctionNaming") // Composable component follows framework naming convention.
@Composable
internal fun LocalDeleteSnackbar(
    data: SnackbarData,
    visuals: ToastSnackbarVisuals,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember(data) { mutableStateOf(false) }
    Snackbar(
        modifier = modifier.snackbarSurfaceBoundary(),
        actionOnNewLine = true,
        action = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                visuals.actionLabel?.let { label ->
                    TextButton(
                        onClick = data::performAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    ) { Text(label) }
                }
                TextButton(
                    onClick = { showDetails = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                ) { Text(stringResource(R.string.details)) }
            }
        },
        dismissAction = {
            IconButton(onClick = data::dismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss))
            }
        },
    ) { Text(visuals.message) }
    if (showDetails) {
        LocalDeleteDetails(visuals, onDismiss = { showDetails = false })
    }
}

@Suppress("FunctionNaming") // Composable component follows framework naming convention.
@Composable
private fun LocalDeleteDetails(
    visuals: ToastSnackbarVisuals,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("local-delete-details"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details)) },
        text = {
            SelectionContainer {
                Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
                    Text(requireNotNull(visuals.details))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
        dismissButton =
            visuals.copyText?.takeIf { visuals.copyable && it.isNotBlank() }?.let { report ->
                {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                        Text(stringResource(R.string.copy))
                    }
                }
            },
    )
}

/** Dismissal or a superseded message never authorizes a deletion retry. */
internal fun finishLocalDeleteSnackbar(
    appState: WhiteNoiseAppState,
    toast: ToastMessage,
    result: SnackbarResult,
) {
    val noticeIsCurrent = appState.toast === toast
    appState.clearToast(toast)
    if (result == SnackbarResult.ActionPerformed && noticeIsCurrent) {
        toast.localDeleteNotice?.let { it.retry?.invoke(it.groupIds) }
    }
}
