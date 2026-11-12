@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.LEGACY_SHARE_IMPORT_LIMITS
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.ShareImportProgress
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.ui.common.AnimatedAlertDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags

/** Payload-free progress and recovery stay behind the shell's existing app-lock gate. */
@Composable
internal fun ShareImportStatus(
    importing: Boolean,
    progress: ShareImportProgress?,
    request: ShareRequest?,
    onCancel: () -> Unit,
    importRequestId: String? = request?.requestId,
    content: @Composable (Boolean) -> Unit,
) {
    var importCancellationAccepted by remember(importRequestId) { mutableStateOf(false) }
    var errorsAcknowledged by remember(request?.requestId) { mutableStateOf(false) }
    val errors = request?.payload?.importErrors.orEmpty()
    val hasContent = request?.payload?.let { !it.text.isNullOrBlank() || it.streamUris.isNotEmpty() } == true
    when {
        importing || importCancellationAccepted ->
            AnimatedAlertDialog(
                onDismissRequest = onCancel,
                ownerKey = importRequestId,
                onDismissStart = { importCancellationAccepted = true },
                title = { Text(stringResource(R.string.share_import_title)) },
                text = {
                    Column {
                        progress?.let {
                            Text(stringResource(R.string.share_import_progress, it.item, it.count, it.bytes))
                        }
                        val total = progress?.total
                        if (progress != null && total != null) {
                            LinearProgressIndicator(
                                progress = { (progress.bytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .exposePerformanceTestTags()
                                        .testTag("share.import.determinate"),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .exposePerformanceTestTags()
                                        .testTag("share.import.indeterminate"),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
            )
        errors.isNotEmpty() && !errorsAcknowledged && request != null ->
            key(request.requestId) {
                ShareImportErrorDialog(request.payload, hasContent, onCancel) { errorsAcknowledged = true }
            }
        else -> content(!importing && hasContent)
    }
}

/** Requires explicit partial-batch acknowledgement; an empty batch offers close without staging a destination. */
@Composable
internal fun ShareImportErrorDialog(
    payload: SharePayload,
    hasContent: Boolean,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    val currentOnContinue = rememberUpdatedState(onContinue)
    val currentHasContent = rememberUpdatedState(hasContent)
    val currentPayload = rememberUpdatedState(payload)
    key(payload, hasContent) {
        AnimatedAlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.share_import_error_title)) },
            text = {
                Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
                    if (payload.importRejectedCount > 0) {
                        Text(stringResource(R.string.share_import_rejected_count, payload.importRejectedCount))
                    }
                    payload.importErrors.distinct().forEach { Text(it.localizedMessage(payload)) }
                    Text(stringResource(R.string.share_import_recovery))
                }
            },
            confirmButton = {
                if (hasContent) {
                    TextButton(onClick = {
                        close {
                            if (currentHasContent.value &&
                                currentPayload.value == payload
                            ) {
                                currentOnContinue.value()
                            }
                        }
                    }) { Text(stringResource(R.string.share_to)) }
                } else {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) }
                }
            },
            dismissButton = {
                if (hasContent) TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Formats the imported policy snapshot without linking native code or reading providers during composition. */
@Composable
private fun ShareImportError.localizedMessage(payload: SharePayload): String {
    val context = LocalContext.current

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    return when (this) {
        ShareImportError.FileTooLarge ->
            stringResource(
                R.string.share_import_file_limit,
                size(payload.importImageLimitBytes ?: LEGACY_SHARE_IMPORT_LIMITS.imageBytes),
                size(payload.importDocumentLimitBytes ?: LEGACY_SHARE_IMPORT_LIMITS.documentBytes),
            )
        ShareImportError.BatchTooLarge ->
            stringResource(
                R.string.share_import_batch_limit,
                size(payload.importBatchLimitBytes ?: LEGACY_SHARE_IMPORT_LIMITS.batchCiphertextBytes),
            )
        else -> stringResource(messageResource())
    }
}

/** Maps bounded import outcomes to localized, payload-free recovery explanations. */
private fun ShareImportError.messageResource(): Int =
    when (this) {
        ShareImportError.Unreadable -> R.string.share_import_unreadable
        ShareImportError.Scheme -> R.string.share_import_scheme
        ShareImportError.Empty -> R.string.share_import_empty
        ShareImportError.FileTooLarge -> R.string.share_import_file_limit
        ShareImportError.BatchTooLarge -> R.string.share_import_batch_limit
        ShareImportError.TooMany -> R.string.share_import_count_limit
        ShareImportError.Metadata -> R.string.share_import_metadata
        ShareImportError.Storage -> R.string.share_import_storage
        ShareImportError.Interrupted -> R.string.share_import_interrupted
    }
