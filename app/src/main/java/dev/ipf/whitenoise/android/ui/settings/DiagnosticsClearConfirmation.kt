package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/**
 * Confirms Diagnostics › Clear (#4097). Clearing drops the in-memory event log for good, so the dialog names that loss
 * and Clear takes the error colour. Cancel keeps every entry. [onDone] closes the dialog either way.
 */
@Suppress("FunctionNaming")
@Composable
internal fun DiagnosticsClearConfirmation(
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.diagnostics_clear_title),
        message = stringResource(R.string.diagnostics_clear_detail),
        confirmLabel = stringResource(R.string.clear),
        destructive = true,
        onConfirm = {
            onDone()
            onClear()
        },
        onDismiss = onDone,
        confirmTag = "diagnostics.clear.confirm",
        cancelTag = "diagnostics.clear.cancel",
    )
}
