package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner

/** A remounted deep result viewport must not measure a shorter list before its anchor returns. */
internal fun preserveSearchBodyAnchor(
    sameDataset: Boolean,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
): Boolean = sameDataset && (firstVisibleIndex > 0 || firstVisibleOffset > 0)

/** Only an empty result list or a saved deep anchor waits for the body stage. */
internal fun blockLocalSearchRows(
    loading: Boolean,
    hasLocalRows: Boolean,
    preservePendingAnchor: Boolean,
): Boolean = loading && (!hasLocalRows || preservePendingAnchor)

/** Body work has its own status; it does not replace already available local name matches. */
@Composable
@Suppress("FunctionNaming")
internal fun GlobalSearchBodyStage(
    loading: Boolean,
    failure: ErrorPresentation?,
    onRetry: () -> Unit,
) {
    if (failure != null) {
        InlineErrorBanner(
            error = failure.copy(message = AppText.Resource(R.string.search_message_content_failed)),
            onRetry = onRetry,
        )
    } else if (loading) {
        Text(
            text = stringResource(R.string.search_message_content_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}
