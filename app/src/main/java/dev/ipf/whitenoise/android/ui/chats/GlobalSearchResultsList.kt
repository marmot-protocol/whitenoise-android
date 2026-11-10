@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val GLOBAL_SEARCH_RESULTS_FOOTER_TAG = "global.search.results.footer"
internal const val GLOBAL_SEARCH_RESULTS_LOADING_TAG = "global.search.results.loading"
private const val GLOBAL_SEARCH_RESULT_CONTENT_TYPE = "global-search-result"
private val GlobalSearchResultsProgressSize = 20.dp

/** What tapping a result and the list boundary do. */
internal data class GlobalSearchResultsActions(
    val onOpen: (GlobalSearchResult) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * Everything the message results need for one composition: the session state, its rows already named for
 * display, how many filters are active, and the callbacks. [selected] says which row a return from a chat
 * landed on.
 */
internal data class GlobalSearchResultsContent(
    val state: GlobalSearchResultsState,
    val rows: List<GlobalSearchResultPresentation>,
    val filterCount: Int = 0,
    val selected: (GlobalSearchResult) -> Boolean = { false },
    val actions: GlobalSearchResultsActions = GlobalSearchResultsActions(),
)

/** The lazy-list key of one result row, distinct from chat-row keys. */
internal fun globalSearchResultItemKey(result: GlobalSearchResult): String = "$GLOBAL_SEARCH_RESULT_CONTENT_TYPE:${result.key}"

/**
 * Message results below the chat-name matches (#1944): a Messages heading, one row per matching message keyed
 * by its stable id, and a footer that pages, retries or marks the end. Rows a later batch fails to add never
 * replace those already loaded.
 */
internal fun LazyListScope.globalSearchResultItems(
    content: GlobalSearchResultsContent,
    rowModifier: @Composable (GlobalSearchResult) -> Modifier = { Modifier },
) {
    if (content.rows.isNotEmpty()) {
        item(key = CHAT_LIST_SEARCH_MESSAGES_HEADER_KEY, contentType = CHAT_LIST_SEARCH_MESSAGES_HEADER_KEY) {
            ChatListSearchSectionHeader(
                title = stringResource(R.string.notification_channel_messages),
                testTag = CHAT_LIST_SEARCH_MESSAGES_HEADER_TAG,
            )
        }
    }
    items(
        items = content.rows,
        key = { globalSearchResultItemKey(it.result) },
        contentType = { GLOBAL_SEARCH_RESULT_CONTENT_TYPE },
    ) { row ->
        GlobalSearchResultRow(
            presentation = row,
            onOpen = { content.actions.onOpen(row.result) },
            modifier = rowModifier(row.result),
            selected = content.selected(row.result),
        )
    }
    item(key = GLOBAL_SEARCH_RESULTS_FOOTER_TAG, contentType = GLOBAL_SEARCH_RESULTS_FOOTER_TAG) {
        GlobalSearchResultsFooter(content)
    }
}

/**
 * The list boundary: progress while a batch reads, a localized retry after a failure, the empty state, an offer
 * to search older messages after several batches showed nothing, or the end of results. It is composed only near
 * the viewport, so reaching it is what asks for the next batch. Active filters are counted, never named by id.
 */
@Composable
internal fun GlobalSearchResultsFooter(content: GlobalSearchResultsContent) {
    val state = content.state
    val currentContent by rememberUpdatedState(content)
    val autoLoad = state.batches > 0 && !state.loading && state.autoReads
    // Keyed on the batch sequence: a batch that loads within one frame changes no other key.
    LaunchedEffect(state.generation, state.batches, autoLoad) {
        if (autoLoad) currentContent.actions.onLoadMore()
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(WhiteNoiseSpacing.CompactScreenMargin)
                .testTag(GLOBAL_SEARCH_RESULTS_FOOTER_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        GlobalSearchResultsBoundary(content, reading = state.loading || autoLoad || state.generation == 0)
        if (content.filterCount > 0) {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.chat_list_search_filters_active,
                        content.filterCount,
                        content.filterCount,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one boundary state that applies, in priority order. */
@Composable
private fun GlobalSearchResultsBoundary(
    content: GlobalSearchResultsContent,
    reading: Boolean,
) {
    val state = content.state
    val empty = content.rows.isEmpty()
    when {
        state.failed -> {
            BoundaryMessage(stringResource(R.string.search_message_content_failed))
            TextButton(onClick = content.actions.onRetry) { Text(stringResource(R.string.retry)) }
        }
        reading -> BoundaryProgress()
        empty -> {
            WhiteNoiseEmptyState(
                title = stringResource(R.string.no_results),
                detail = stringResource(R.string.chat_rows_no_results_detail),
            )
            if (state.hasMore) SearchOlderButton(content.actions.onLoadMore)
        }
        state.hasMore -> SearchOlderButton(content.actions.onLoadMore)
        else -> BoundaryMessage(stringResource(R.string.global_search_results_end))
    }
}

/** A batch is being read. */
@Composable
private fun BoundaryProgress() {
    Row(
        modifier = Modifier.testTag(GLOBAL_SEARCH_RESULTS_LOADING_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        CircularProgressIndicator(Modifier.size(GlobalSearchResultsProgressSize), strokeWidth = 2.dp)
        BoundaryMessage(stringResource(R.string.search_message_content_loading))
    }
}

/** A polite live-region line, so TalkBack hears a failure or the end without moving focus. */
@Composable
private fun BoundaryMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Several batches in a row showed nothing: reading further is the user's choice. */
@Composable
private fun SearchOlderButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.global_search_results_search_older), textAlign = TextAlign.Center)
    }
}
