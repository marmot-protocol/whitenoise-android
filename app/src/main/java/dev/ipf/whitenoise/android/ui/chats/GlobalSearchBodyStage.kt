package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** One restored viewport waits for its first body load; retries keep already visible names usable. */
@Composable
internal fun rememberSearchBodyAnchorRestoration(
    datasetKey: ChatListDatasetKey,
    sameDataset: Boolean,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    loading: Boolean,
): Boolean {
    val restoration =
        remember(datasetKey) {
            SearchBodyAnchorRestoration(preserveSearchBodyAnchor(sameDataset, firstVisibleIndex, firstVisibleOffset))
        }
    SideEffect { restoration.observeLoading(loading) }
    return restoration.pending
}

private class SearchBodyAnchorRestoration(
    initiallyPending: Boolean,
) {
    var pending by mutableStateOf(initiallyPending)
        private set
    private var observedLoading = false

    fun observeLoading(loading: Boolean) {
        if (loading) {
            observedLoading = true
        } else if (observedLoading) {
            pending = false
        }
    }
}

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
