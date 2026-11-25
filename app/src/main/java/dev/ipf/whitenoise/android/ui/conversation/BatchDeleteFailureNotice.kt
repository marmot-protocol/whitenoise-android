package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val BATCH_DELETE_FAILURE_NOTICE_TAG = "batch-delete-failure-notice"

/** Failed-delete recovery kept adjacent to the still-selected message actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming")
internal fun BatchDeleteFailureNotice(
    state: BatchDeleteRetryState,
    retryInFlight: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCopyReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionContentColor = MaterialTheme.colorScheme.onErrorContainer
    val disabledActionContentColor = actionContentColor.copy(alpha = 0.38f)
    val actionColors =
        ButtonDefaults.textButtonColors(
            contentColor = actionContentColor,
            disabledContentColor = disabledActionContentColor,
        )
    WhiteNoiseCallout(
        modifier =
            modifier
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Related)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(BATCH_DELETE_FAILURE_NOTICE_TAG),
        title = stringResource(R.string.batch_delete_failure_progress, state.succeeded, state.attempted),
        text = batchDeleteFailureScopeText(state),
        icon = R.drawable.ic_error,
        isError = true,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCopyReport, colors = actionColors) {
                Text(text = stringResource(R.string.copy), color = actionContentColor)
            }
            TextButton(onClick = onDismiss, enabled = !retryInFlight, colors = actionColors) {
                Text(
                    text = stringResource(R.string.dismiss),
                    color = if (retryInFlight) disabledActionContentColor else actionContentColor,
                )
            }
            TextButton(onClick = onRetry, enabled = !retryInFlight, colors = actionColors) {
                Text(
                    text = stringResource(R.string.retry),
                    color = if (retryInFlight) disabledActionContentColor else actionContentColor,
                )
            }
        }
    }
}

@Composable
private fun batchDeleteFailureScopeText(state: BatchDeleteRetryState): String =
    when {
        state.failedLocalHides > 0 && state.failedGroupDeletes > 0 ->
            stringResource(
                R.string.batch_delete_failures_mixed,
                state.failedLocalHides,
                state.failedGroupDeletes,
            )
        state.failedGroupDeletes > 0 ->
            stringResource(
                R.string.batch_delete_failures_everyone,
                state.failedGroupDeletes,
            )
        else ->
            stringResource(
                R.string.batch_delete_failures_local,
                state.failedLocalHides,
            )
    }
