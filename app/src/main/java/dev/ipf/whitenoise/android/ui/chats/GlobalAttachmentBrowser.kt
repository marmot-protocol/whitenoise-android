@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GlobalAttachmentDay
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.core.GlobalAttachmentPresentation
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.globalAttachmentGridIsVisual
import dev.ipf.whitenoise.android.core.globalAttachmentIsVideo
import dev.ipf.whitenoise.android.core.globalAttachmentPresentation
import dev.ipf.whitenoise.android.core.groupGlobalAttachmentsByDay
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.GlobalAttachmentSource
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val GLOBAL_LIBRARY_TAG = "global.library"
internal const val GLOBAL_LIBRARY_RESULTS_TAG = "global.library.results"
internal const val GLOBAL_LIBRARY_LOADING_TAG = "global.library.loading"

/** Stable test tag for one library card. */
internal fun globalLibraryItemTag(item: GlobalAttachmentItem): String = "global.library.item.${item.messageIdHex}#${item.attachmentIndex}"

private val LibraryGridCellSize = 160.dp
private val LibraryPlaceholderIconSize = 40.dp
private val LibraryPlayBadgeIconSize = 24.dp

/**
 * The prototype's files-and-media library for chat-list search. Photos and videos tile
 * into an adaptive grid; the file-only and audio-only modes fall back to a single column.
 * Cards are grouped under a heading per day, newest day first, and open the message they
 * came from.
 */
@Composable
internal fun GlobalAttachmentBrowser(
    items: List<GlobalAttachmentItem>,
    kinds: Set<GlobalSearchContentKind>,
    loading: Boolean,
    bottomPadding: Dp,
    onOpenMessage: (groupIdHex: String, messageIdHex: String) -> Unit,
    thumbnail: (GlobalAttachmentItem) -> ImageBitmap? = { null },
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    gridState: LazyGridState = rememberLazyGridState(),
    selectionOwner: GlobalSearchSelectionOwner? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter =
        remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val days = remember(items, zoneId) { groupGlobalAttachmentsByDay(items, zoneId) }
    val visual = globalAttachmentGridIsVisual(kinds)
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
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag(GLOBAL_LIBRARY_LOADING_TAG))
            Text(
                text = stringResource(R.string.library_loading),
                modifier =
                    Modifier
                        .padding(WhiteNoiseSpacing.CompactScreenMargin)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        if (days.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WhiteNoiseEmptyState(
                    title = stringResource(R.string.library_empty),
                    detail = stringResource(R.string.chat_rows_no_results_detail),
                )
            }
            return@Column
        }
        GlobalAttachmentGrid(
            days = days,
            layout =
                GlobalAttachmentGridLayout(
                    visual,
                    formatter,
                    bottomPadding,
                    gridState,
                    selectionOwner,
                    returnedSelection,
                ),
            onOpenMessage = onOpenMessage,
            thumbnail = thumbnail,
        )
    }
}

/** The grid itself: a day heading spanning the row, then the cards of that day. */
private data class GlobalAttachmentGridLayout(
    val visual: Boolean,
    val formatter: DateTimeFormatter,
    val bottomPadding: Dp,
    val state: LazyGridState,
    val selectionOwner: GlobalSearchSelectionOwner?,
    val returnedSelection: GlobalSearchSelectedResult?,
)

@Composable
private fun GlobalAttachmentGrid(
    days: List<GlobalAttachmentDay>,
    layout: GlobalAttachmentGridLayout,
    onOpenMessage: (groupIdHex: String, messageIdHex: String) -> Unit,
    thumbnail: (GlobalAttachmentItem) -> ImageBitmap?,
) {
    LazyVerticalGrid(
        columns = if (layout.visual) GridCells.Adaptive(LibraryGridCellSize) else GridCells.Fixed(1),
        state = layout.state,
        modifier = Modifier.fillMaxWidth().testTag(GLOBAL_LIBRARY_RESULTS_TAG),
        contentPadding =
            PaddingValues(
                start = WhiteNoiseSpacing.CompactScreenMargin,
                end = WhiteNoiseSpacing.CompactScreenMargin,
                bottom = layout.bottomPadding + WhiteNoiseSpacing.Section,
            ),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        days.forEach { day ->
            item(key = "day-${day.day}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = day.day.format(layout.formatter),
                    modifier =
                        Modifier
                            .padding(vertical = WhiteNoiseSpacing.Related)
                            .semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(
                items = day.items,
                key = ::globalLibraryItemTag,
                span = { item ->
                    val tile = globalAttachmentPresentation(item.mediaType) == GlobalAttachmentPresentation.VISUAL
                    GridItemSpan(if (tile) 1 else maxLineSpan)
                },
            ) { item ->
                GlobalAttachmentGridCard(item, layout, onOpenMessage, thumbnail)
            }
        }
    }
}

/** The grid's real clickable card owns selection and its single-use return focus. */
@Composable
private fun GlobalAttachmentGridCard(
    item: GlobalAttachmentItem,
    layout: GlobalAttachmentGridLayout,
    onOpenMessage: (groupIdHex: String, messageIdHex: String) -> Unit,
    thumbnail: (GlobalAttachmentItem) -> ImageBitmap?,
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
    GlobalAttachmentCard(
        item = item,
        thumbnail = thumbnail(item),
        onClick = {
            layout.selectionOwner?.selected =
                GlobalSearchSelectedResult(item.groupIdHex, item.messageIdHex, item.attachmentIndex)
            onOpenMessage(item.groupIdHex, item.messageIdHex)
        },
        selected = returnedRow,
        modifier = returnFocus,
    )
}

/** One library card: a square thumbnail for visual media, then the chat and time it came from. */
@Composable
private fun GlobalAttachmentCard(
    item: GlobalAttachmentItem,
    thumbnail: ImageBitmap?,
    onClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visual = globalAttachmentPresentation(item.mediaType) == GlobalAttachmentPresentation.VISUAL
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(globalLibraryItemTag(item)).semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        if (visual) {
            GlobalAttachmentTile(item, thumbnail)
        }
        Column(
            modifier = Modifier.padding(WhiteNoiseSpacing.CompactScreenMargin),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            if (!visual) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EmojiLabel(
                text = stringResource(R.string.library_source, item.chatTitle, rememberedRelativeTime(item.timelineAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The square media face of a card. A thumbnail the account has already decoded is drawn
 * directly; anything still encrypted on a relay shows the kind's glyph rather than
 * fetching media the user never opened.
 */
@Composable
private fun GlobalAttachmentTile(
    item: GlobalAttachmentItem,
    thumbnail: ImageBitmap?,
) {
    val video = globalAttachmentIsVideo(item.mediaType)
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                painter = painterResource(if (video) R.drawable.ic_play_arrow else R.drawable.ic_image),
                contentDescription = null,
                modifier = Modifier.size(LibraryPlaceholderIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (video && thumbnail != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play_arrow),
                    contentDescription = null,
                    modifier = Modifier.padding(WhiteNoiseSpacing.Related).size(LibraryPlayBadgeIconSize),
                )
            }
        }
    }
}

/** Adapts a cached decoded bitmap to the browser's thumbnail slot. */
internal fun globalAttachmentThumbnail(bitmap: android.graphics.Bitmap?): ImageBitmap? = bitmap?.asImageBitmap()

/**
 * The chats the library scans, paired with the titles its cards show, so a card names
 * its conversation exactly as the chat row above it does.
 */
internal fun globalAttachmentSources(
    appState: WhiteNoiseAppState,
    scopedChats: List<ChatListItem>,
    titleCopy: GroupTitleCopy,
): List<GlobalAttachmentSource> =
    scopedChats.map { item ->
        GlobalAttachmentSource(
            groupIdHex = item.group.groupIdHex,
            title = chatListItemDisplayTitle(item, appState, titleCopy),
        )
    }

/**
 * The decoded thumbnail this account already holds for a library item, or null when the
 * bytes are still encrypted on a relay. The library never starts a download of its own:
 * media the user has not opened shows the kind's glyph instead.
 */
internal fun libraryThumbnail(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    item: GlobalAttachmentItem,
): ImageBitmap? {
    val account = accountRef ?: return null
    val key = mediaCacheKey(account, item.groupIdHex, item.messageIdHex, item.attachmentIndex)
    return globalAttachmentThumbnail(appState.cachedMediaThumbnail(key))
}
