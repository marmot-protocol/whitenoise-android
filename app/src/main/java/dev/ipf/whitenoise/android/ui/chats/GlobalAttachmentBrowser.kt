@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.core.GlobalAttachmentPresentation
import dev.ipf.whitenoise.android.core.GlobalAttachmentSection
import dev.ipf.whitenoise.android.core.GlobalAttachmentSectionKey
import dev.ipf.whitenoise.android.core.GlobalAttachmentSections
import dev.ipf.whitenoise.android.core.globalAttachmentGridIsVisual
import dev.ipf.whitenoise.android.core.globalAttachmentPresentation
import dev.ipf.whitenoise.android.core.sectionGlobalAttachments
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyVerticalGrid
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

internal const val GLOBAL_LIBRARY_TAG = "global.library"
internal const val GLOBAL_LIBRARY_RESULTS_TAG = "global.library.results"
internal const val GLOBAL_LIBRARY_LOADING_TAG = "global.library.loading"
internal const val GLOBAL_LIBRARY_FOOTER_TAG = "global.library.footer"

/** Stable test tag for one library card; the group keeps equal message IDs in two chats apart. */
internal fun globalLibraryItemTag(item: GlobalAttachmentItem): String = "global.library.item.${item.groupIdHex}.${item.messageIdHex}#${item.attachmentIndex}"

/** Three columns on a typical phone, more on wider windows. */
private val LibraryGridCellSize = 96.dp
private val LibraryGridGap = 3.dp

/** Rows left below the viewport when the next bounded batch is requested. */
private const val LIBRARY_PREFETCH_ITEMS = 12

/**
 * How far the library has read. [hasMore] means MDK returned a continuation; [failed] stops automatic
 * paging until the user retries, and [filtersUnsupported] means MDK refused these filters outright.
 */
internal data class GlobalAttachmentPaging(
    val hasMore: Boolean = false,
    val failed: Boolean = false,
    val filtersUnsupported: Boolean = false,
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** What a library card shows beyond the item itself, and what tapping it does. */
internal data class GlobalAttachmentCardActions(
    val senderLabel: (GlobalAttachmentItem) -> String = { "" },
    val thumbnail: (GlobalAttachmentItem) -> ImageBitmap? = { null },
    val onOpen: (GlobalAttachmentItem) -> Unit = {},
    val onViewInChat: (GlobalAttachmentItem) -> Unit = {},
    /** Fixed time labels for previews and tests; null uses the live relative time. */
    val timeLabel: ((GlobalAttachmentItem) -> String)? = null,
)

/**
 * The account-wide Files & media library inside chat-list search (#2000). Photos and videos tile into
 * a grid; files and audio are full-width rows naming their sender and chat. Items keep MDK's newest-first
 * order under Today, Yesterday, This week, This month and month headings, and the next bounded batch
 * loads as the end comes into view.
 */
@Composable
internal fun GlobalAttachmentBrowser(
    items: List<GlobalAttachmentItem>,
    kinds: Set<GlobalSearchContentKind>,
    loading: Boolean,
    bottomPadding: Dp,
    actions: GlobalAttachmentCardActions,
    modifier: Modifier = Modifier,
    paging: GlobalAttachmentPaging = GlobalAttachmentPaging(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
    gridState: LazyGridState = rememberLazyGridState(),
    selectionOwner: GlobalSearchSelectionOwner? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val calendar =
        remember(today, zoneId, locale) {
            GlobalAttachmentSections(today, zoneId, WeekFields.of(locale).firstDayOfWeek)
        }
    val sections = remember(items, calendar) { sectionGlobalAttachments(items, calendar) }
    val returnedSelection = rememberReturnedSearchSelection(selectionOwner)
    globalSearchReturnFocusExpiryEffect(selectionOwner, ready = !loading) {
        val selected = returnedSelection?.takeIf { it == selectionOwner?.selected }
        val item =
            items.firstOrNull { selected?.matches(it.groupIdHex, it.messageIdHex, it.attachmentIndex) == true }
        item != null &&
            !gridState.isScrollInProgress &&
            gridState.layoutInfo.visibleItemsInfo.any { it.key == globalLibraryItemTag(item) }
    }
    Column(modifier.fillMaxSize().testTag(GLOBAL_LIBRARY_TAG)) {
        when {
            items.isEmpty() && loading -> LibraryLoading()
            items.isEmpty() -> LibraryEmpty(paging)
            else ->
                GlobalAttachmentGrid(
                    sections = sections,
                    layout =
                        GlobalAttachmentGridLayout(
                            visual = globalAttachmentGridIsVisual(kinds),
                            monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) },
                            bottomPadding = bottomPadding,
                            state = gridState,
                            selectionOwner = selectionOwner,
                            returnedSelection = returnedSelection,
                        ),
                    actions = actions,
                    paging = paging,
                    loading = loading,
                )
        }
    }
}

/** The first batch is still being read. */
@Composable
private fun LibraryLoading() {
    LinearProgressIndicator(Modifier.fillMaxWidth().testTag(GLOBAL_LIBRARY_LOADING_TAG))
    Text(
        text = stringResource(R.string.library_loading),
        modifier =
            Modifier
                .padding(WhiteNoiseSpacing.CompactScreenMargin)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Nothing matched, the first read failed, or MDK refused the filters. */
@Composable
private fun LibraryEmpty(paging: GlobalAttachmentPaging) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            paging.filtersUnsupported ->
                WhiteNoiseEmptyState(
                    title = stringResource(R.string.library_empty),
                    detail = stringResource(R.string.library_filters_unsupported),
                )
            paging.failed ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WhiteNoiseEmptyState(
                        title = stringResource(R.string.library_empty),
                        detail = stringResource(R.string.library_load_more_failed),
                    )
                    TextButton(onClick = paging.onRetry) { Text(stringResource(R.string.retry)) }
                }
            else ->
                WhiteNoiseEmptyState(
                    title = stringResource(R.string.library_empty),
                    detail = stringResource(R.string.chat_rows_no_results_detail),
                )
        }
    }
}

/** The grid's fixed inputs for one composition. */
private data class GlobalAttachmentGridLayout(
    val visual: Boolean,
    val monthFormat: DateTimeFormatter,
    val bottomPadding: Dp,
    val state: LazyGridState,
    val selectionOwner: GlobalSearchSelectionOwner?,
    val returnedSelection: GlobalSearchSelectedResult?,
)

/** Date sections with pinned headings, then a footer that pages or offers a retry. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlobalAttachmentGrid(
    sections: List<GlobalAttachmentSection>,
    layout: GlobalAttachmentGridLayout,
    actions: GlobalAttachmentCardActions,
    paging: GlobalAttachmentPaging,
    loading: Boolean,
) {
    LibraryPagingEffect(layout.state, paging, loading)
    WhiteNoiseLazyVerticalGrid(
        columns = if (layout.visual) GridCells.Adaptive(LibraryGridCellSize) else GridCells.Fixed(1),
        state = layout.state,
        modifier = Modifier.fillMaxWidth().testTag(GLOBAL_LIBRARY_RESULTS_TAG),
        contentPadding =
            PaddingValues(
                start = WhiteNoiseSpacing.CompactScreenMargin,
                end = WhiteNoiseSpacing.CompactScreenMargin,
                bottom = layout.bottomPadding + WhiteNoiseSpacing.Section,
            ),
        horizontalArrangement = Arrangement.spacedBy(LibraryGridGap),
        verticalArrangement = Arrangement.spacedBy(LibraryGridGap),
    ) {
        sections.forEachIndexed { index, section ->
            stickyHeader(key = "section-$index-${section.key}", contentType = "section") {
                LibrarySectionHeader(section.key, layout.monthFormat)
            }
            items(
                items = section.items,
                key = ::globalLibraryItemTag,
                contentType = { globalAttachmentPresentation(it.mediaType) },
                span = { item ->
                    val tile = globalAttachmentPresentation(item.mediaType) == GlobalAttachmentPresentation.VISUAL
                    GridItemSpan(if (tile) 1 else maxLineSpan)
                },
            ) { item ->
                GlobalAttachmentGridCard(item, layout, actions)
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
            LibraryFooter(paging, loading)
        }
    }
}

/** Requests the next bounded batch once the last rows come into view, never while one is in flight. */
@Composable
private fun LibraryPagingEffect(
    state: LazyGridState,
    paging: GlobalAttachmentPaging,
    loading: Boolean,
) {
    val currentPaging by rememberUpdatedState(paging)
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index
            last != null && last >= info.totalItemsCount - LIBRARY_PREFETCH_ITEMS
        }
    }
    LaunchedEffect(nearEnd, loading, paging.hasMore, paging.failed) {
        val wanted = currentPaging.hasMore && !currentPaging.failed
        if (nearEnd && !loading && wanted) currentPaging.onLoadMore()
    }
}

/** A date heading pinned while its items scroll beneath it. */
@Composable
private fun LibrarySectionHeader(
    key: GlobalAttachmentSectionKey,
    monthFormat: DateTimeFormatter,
) {
    val label =
        when (key) {
            GlobalAttachmentSectionKey.Today -> stringResource(R.string.global_search_date_today)
            GlobalAttachmentSectionKey.Yesterday -> stringResource(R.string.relative_time_yesterday)
            GlobalAttachmentSectionKey.ThisWeek -> stringResource(R.string.library_section_this_week)
            GlobalAttachmentSectionKey.ThisMonth -> stringResource(R.string.library_section_this_month)
            is GlobalAttachmentSectionKey.Month -> key.month.format(monthFormat)
        }
    Text(
        text = label,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = WhiteNoiseSpacing.Related)
                .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Shows the in-flight batch, or the retry a failed batch needs; nothing once the history is exhausted. */
@Composable
private fun LibraryFooter(
    paging: GlobalAttachmentPaging,
    loading: Boolean,
) {
    Box(
        Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.CompactScreenMargin).testTag(GLOBAL_LIBRARY_FOOTER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            paging.failed ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.library_load_more_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = paging.onRetry) { Text(stringResource(R.string.retry)) }
                }
        }
    }
}

/** The grid's real clickable card owns selection and its single-use return focus. */
@Composable
private fun GlobalAttachmentGridCard(
    item: GlobalAttachmentItem,
    layout: GlobalAttachmentGridLayout,
    actions: GlobalAttachmentCardActions,
) {
    val returnedRow =
        layout.returnedSelection == layout.selectionOwner?.selected &&
            layout.returnedSelection?.matches(item.groupIdHex, item.messageIdHex, item.attachmentIndex) == true
    val returnFocus =
        globalSearchReturnFocusModifier(
            restoredSelection = returnedRow,
            returnGeneration = layout.selectionOwner?.returnGeneration ?: 0L,
            consumeReturn = { layout.selectionOwner?.let { it.consumeReturnFocus(it.returnGeneration) } == true },
        ) {
            !layout.state.isScrollInProgress &&
                layout.state.layoutInfo.visibleItemsInfo
                    .any { it.key == globalLibraryItemTag(item) }
        }
    val markSelected = {
        layout.selectionOwner?.selected =
            GlobalSearchSelectedResult(item.groupIdHex, item.messageIdHex, item.attachmentIndex)
    }
    GlobalAttachmentCard(
        item = item,
        actions =
            actions.copy(
                onOpen = {
                    markSelected()
                    actions.onOpen(it)
                },
                onViewInChat = {
                    markSelected()
                    actions.onViewInChat(it)
                },
            ),
        selected = returnedRow,
        modifier = returnFocus,
    )
}
