package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState

/** Keeps sparse pages explicitly partial rather than claiming the selected category is empty. */
@Composable
@Suppress("FunctionNaming")
internal fun AttachmentLibraryEmptyState(state: GroupAttachmentState) {
    val title =
        if (state.hasMore || !state.initialized) {
            R.string.shared_content_partial
        } else {
            R.string.shared_content_empty
        }
    WhiteNoiseEmptyState(
        title = stringResource(title),
        detail = stringResource(R.string.shared_content_browse_history),
    )
}

/** Persistent, keyboard-accessible paging controls also work when the current category has no matches. */
@Composable
@Suppress("FunctionNaming")
internal fun AttachmentLibraryFooter(
    state: GroupAttachmentState,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("shared.history.status")
                .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.failed -> {
                Text(stringResource(R.string.shared_content_page_failed), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onLoadMore, modifier = Modifier.testTag("shared.history.retry")) {
                    Text(stringResource(R.string.retry))
                }
            }
            state.loading -> Text(stringResource(R.string.shared_content_loading))
            state.hasMore ->
                TextButton(onClick = onLoadMore, modifier = Modifier.testTag("shared.history.more")) {
                    Text(stringResource(R.string.poll_votes_load_more))
                }
            state.initialized ->
                Text(
                    stringResource(R.string.shared_content_history_end),
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}
