package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * A persistent error inside content: [WhiteNoiseCallout] in its error tone with the shared Copy, Retry and Dismiss
 * actions. [report] is the privacy-safe diagnostic text Copy puts on the clipboard; without it there is no Copy.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ErrorCallout(
    message: String?,
    report: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    messageModifier: Modifier = Modifier,
    copyModifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    WhiteNoiseCallout(
        modifier = modifier,
        title = title,
        icon = R.drawable.ic_error,
        isError = true,
        content =
            message?.let {
                {
                    SelectionContainer(messageModifier) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
        trailing =
            if (report == null && onRetry == null && onDismiss == null) {
                null
            } else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (report != null) {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(report)) },
                                modifier = copyModifier,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_content_copy),
                                    contentDescription = stringResource(R.string.copy),
                                )
                            }
                        }
                        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                        if (onDismiss != null) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.dismiss),
                                )
                            }
                        }
                    }
                }
            },
    )
}

/** Keeps a loaded surface visible while exposing copy and optional retry actions. */
@Suppress("FunctionNaming")
@Composable
internal fun InlineErrorBanner(
    error: ErrorPresentation,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ErrorCallout(
        message = error.message.resolve(context),
        report = error.report,
        modifier =
            modifier.padding(
                horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                vertical = WhiteNoiseSpacing.Related,
            ),
        onRetry = onRetry.takeIf { error.retryable },
    )
}
