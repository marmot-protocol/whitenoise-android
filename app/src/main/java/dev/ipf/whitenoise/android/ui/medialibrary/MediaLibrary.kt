package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.media.MediaInventory
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.SectionCardWithAction
import dev.ipf.whitenoise.android.ui.conversation.media.FullScreenMediaViewer
import dev.ipf.whitenoise.android.ui.conversation.media.MediaImageGridTile
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVideoGridTile
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.conversation.media.OpenAttachmentResult
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentTypeDescription
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentTypeLabel
import dev.ipf.whitenoise.android.ui.conversation.media.fileIconFor
import dev.ipf.whitenoise.android.ui.conversation.media.materializeDocumentAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.presentMediaLaunchFailure
import dev.ipf.whitenoise.android.ui.conversation.media.presentMediaSaveOutcome
import dev.ipf.whitenoise.android.ui.conversation.media.rememberAttachmentOpener
import dev.ipf.whitenoise.android.ui.conversation.media.rememberDocumentSaveFallback
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.saveDocumentWithFallback
import dev.ipf.whitenoise.android.ui.conversation.media.shareImage
import dev.ipf.whitenoise.android.ui.conversation.media.voicePlaybackKey
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import androidx.compose.foundation.lazy.grid.items as gridItems

// A renderable image/video tile resolved from the conversation timeline. Unlike
// MediaInventory's MediaEntry (which is transport-free and carries only the
// reference), this also carries the protocol-level attachmentIndex and the
// receive-side `mine` flag the existing decrypt/download pipeline needs to
// materialize the thumbnail through the same path the bubbles use.
internal data class SharedMediaTile(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val recordedAt: ULong,
    val sender: String,
    val isVideo: Boolean,
)

// An attachment row for the Voice/Files vertical lists. Like SharedMediaTile it
// carries the protocol-level attachmentIndex and `mine` flag the decrypt path
// needs, plus the sender hex so the row can resolve a display name + avatar.
internal data class SharedMediaRow(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val recordedAt: ULong,
    val sender: String,
)

internal data class MediaMonthSection<T>(
    val monthKey: Int,
    val items: List<T>,
)

internal data class SharedMediaTiles(
    val images: List<SharedMediaTile>,
    val videos: List<SharedMediaTile>,
    val voice: List<SharedMediaRow>,
    val files: List<SharedMediaRow>,
    val urls: List<MediaInventory.UrlEntry>,
    val imageSections: List<MediaMonthSection<SharedMediaTile>>,
    val videoSections: List<MediaMonthSection<SharedMediaTile>>,
    val voiceSections: List<MediaMonthSection<SharedMediaRow>>,
    val fileSections: List<MediaMonthSection<SharedMediaRow>>,
    val urlSections: List<MediaMonthSection<IndexedValue<MediaInventory.UrlEntry>>>,
    // True when the conversation carries media beyond the rendered image/video
    // grids — voice, files, urls, or bare image-URL links that aren't gridded.
    // Carried so the section can decide between the strip, the single
    // "View shared media" row, and hiding entirely without re-deriving.
    val hasOther: Boolean,
) {
    val isEmpty: Boolean
        get() =
            images.isEmpty() &&
                videos.isEmpty() &&
                voice.isEmpty() &&
                files.isEmpty() &&
                urls.isEmpty() &&
                !hasOther
}

// Walk the loaded timeline once and project image/video tiles, newest first.
// Projected rows provide typed media carrying the real source epoch; only
// optimistic/compatibility records fall back to MarmotKit tag parsing. Keyed
// on timeline identity so it rebuilds on projection changes, not per frame.
@Composable
internal fun rememberSharedMediaTiles(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): SharedMediaTiles {
    val myAccountId = appState.activeAccount?.accountIdHex
    // The build sweep is O(N) over the timeline; run it off the composition
    // thread and surface an empty result until it lands (consumers treat empty
    // as "hide section / empty tabs", so the brief initial state is graceful).
    val tiles by produceState(
        initialValue =
            SharedMediaTiles(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                hasOther = false,
            ),
        controller.timeline,
        myAccountId,
    ) {
        val timelineSnapshot = controller.timeline
        value =
            withContext(Dispatchers.Default) {
                buildTiles(timelineSnapshot, myAccountId)
            }
    }
    return tiles
}

// Pure tile projection extracted from the composable so it can run on a
// background dispatcher. Projected rows carry authoritative typed media;
// optimistic/compatibility records alone fall back to MarmotKit tag parsing.
private fun buildTiles(
    messages: List<TimelineMessage>,
    myAccountId: String?,
): SharedMediaTiles {
    val images = ArrayList<SharedMediaTile>()
    val videos = ArrayList<SharedMediaTile>()
    val voice = ArrayList<SharedMediaRow>()
    val files = ArrayList<SharedMediaRow>()
    for (message in messages) {
        val record = message.record
        val mine = MessageProjector.isMine(record, myAccountId)
        val references =
            message.projected?.media
                ?: MediaReferenceSupport.parseAllImetaTags(
                    tags = record.tags,
                    sourceEpoch = record.sourceEpoch ?: 0uL,
                )
        references.forEachIndexed { index, reference ->
            when {
                MediaReferenceSupport.isImageMedia(reference) ->
                    images.add(
                        SharedMediaTile(record.messageIdHex, index, reference, mine, record.recordedAt, record.sender, isVideo = false),
                    )
                MediaReferenceSupport.isVideoMedia(reference) ->
                    videos.add(
                        SharedMediaTile(record.messageIdHex, index, reference, mine, record.recordedAt, record.sender, isVideo = true),
                    )
                MediaReferenceSupport.isAudioMedia(reference) ->
                    voice.add(
                        SharedMediaRow(record.messageIdHex, index, reference, mine, record.recordedAt, record.sender),
                    )
                else ->
                    files.add(
                        SharedMediaRow(record.messageIdHex, index, reference, mine, record.recordedAt, record.sender),
                    )
            }
        }
    }
    // Newest first for the grids and the vertical lists.
    images.reverse()
    videos.reverse()
    voice.reverse()
    files.reverse()
    // URLs are sorted newest-first to match the lists; the inventory keeps
    // them in timeline order (oldest first).
    val urls = MediaInventory.urls(messages.map { it.record }).asReversed()
    // Bare image-URL links aren't rendered in any grid/list yet, so they don't
    // count toward `hasOther` — otherwise a links-only conversation would show
    // the "View shared media" entry into a library with every tab empty.
    return SharedMediaTiles(
        images = images,
        videos = videos,
        voice = voice,
        files = files,
        urls = urls,
        imageSections = groupIntoMonthSections(images) { it.recordedAt },
        videoSections = groupIntoMonthSections(videos) { it.recordedAt },
        voiceSections = groupIntoMonthSections(voice) { it.recordedAt },
        fileSections = groupIntoMonthSections(files) { it.recordedAt },
        urlSections = groupIntoMonthSections(urls.withIndex().toList()) { it.value.recordedAt },
        hasOther = voice.isNotEmpty() || files.isNotEmpty() || urls.isNotEmpty(),
    )
}

// Month bucketing keyed off the local-time calendar so the separators match
// what the user sees on each message. `recordedAt` is epoch SECONDS.
internal fun monthKeyForMedia(recordedAtSeconds: ULong): Int {
    val zdt = Instant.ofEpochSecond(recordedAtSeconds.toLong()).atZone(mediaMonthGroupingZone)
    // monthLabel() decodes the month with Calendar.MONTH (0-based).
    return zdt.year * 100 + (zdt.monthValue - 1)
}

// Group already-newest-first items by calendar month, preserving order so
// section headers read newest → oldest. Runs during tile projection on a
// background dispatcher — composition only renders the pre-built sections.
internal fun <T> groupIntoMonthSections(
    items: List<T>,
    recordedAtOf: (T) -> ULong,
): List<MediaMonthSection<T>> {
    if (items.isEmpty()) return emptyList()
    val sections = LinkedHashMap<Int, ArrayList<T>>()
    for (item in items) {
        val key = monthKeyForMedia(recordedAtOf(item))
        sections.getOrPut(key) { ArrayList() }.add(item)
    }
    return sections.map { (key, bucket) -> MediaMonthSection(key, bucket) }
}

private val mediaMonthGroupingZone: ZoneId = ZoneId.systemDefault()

private val ThumbStripSize = 96.dp

internal enum class SharedMediaFallbackType {
    Generic,
    Voice,
    Files,
    Urls,
}

internal data class SharedMediaFallback(
    val type: SharedMediaFallbackType,
    val count: Int = 0,
)

internal fun sharedMediaFallbackContent(
    videoCount: Int,
    voiceCount: Int,
    fileCount: Int,
    urlCount: Int,
): SharedMediaFallback =
    when {
        videoCount == 0 && voiceCount > 0 && fileCount == 0 && urlCount == 0 ->
            SharedMediaFallback(SharedMediaFallbackType.Voice, voiceCount)
        videoCount == 0 && voiceCount == 0 && fileCount > 0 && urlCount == 0 ->
            SharedMediaFallback(SharedMediaFallbackType.Files, fileCount)
        videoCount == 0 && voiceCount == 0 && fileCount == 0 && urlCount > 0 ->
            SharedMediaFallback(SharedMediaFallbackType.Urls, urlCount)
        else -> SharedMediaFallback(SharedMediaFallbackType.Generic)
    }

@Composable
internal fun SharedMediaFallbackRow(
    fallback: SharedMediaFallback,
    onSeeAll: () -> Unit,
) {
    val icon =
        when (fallback.type) {
            SharedMediaFallbackType.Generic -> Icons.Default.Image
            SharedMediaFallbackType.Voice -> Icons.Default.Mic
            SharedMediaFallbackType.Files -> Icons.Default.Description
            SharedMediaFallbackType.Urls -> Icons.Default.Language
        }
    val label =
        when (fallback.type) {
            SharedMediaFallbackType.Generic -> stringResource(R.string.shared_media_view)
            SharedMediaFallbackType.Voice ->
                pluralStringResource(R.plurals.shared_media_voice_count, fallback.count, fallback.count)
            SharedMediaFallbackType.Files ->
                pluralStringResource(R.plurals.shared_media_files_count, fallback.count, fallback.count)
            SharedMediaFallbackType.Urls ->
                pluralStringResource(R.plurals.shared_media_links_count, fallback.count, fallback.count)
        }
    SectionCard(title = stringResource(R.string.shared_media)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .amoledSurfaceBorder(RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSeeAll() }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * "Shared media" section for the group/DM details sheet. Shows a horizontal
 * strip of the most recent image thumbnails with a "See all" affordance into
 * [MediaLibraryRoute]. When there are no images or videos and exactly one of
 * voice, files, or URLs exists, it collapses to a type-specific counted row;
 * videos and mixed media retain the generic "View shared media" row. Renders
 * nothing when the conversation has no media at all.
 */
@Composable
internal fun SharedMediaSection(
    tiles: SharedMediaTiles,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onSeeAll: () -> Unit,
    onJumpToMessage: (String) -> Unit,
) {
    if (tiles.isEmpty) return

    if (tiles.images.isEmpty()) {
        SharedMediaFallbackRow(
            fallback =
                sharedMediaFallbackContent(
                    videoCount = tiles.videos.size,
                    voiceCount = tiles.voice.size,
                    fileCount = tiles.files.size,
                    urlCount = tiles.urls.size,
                ),
            onSeeAll = onSeeAll,
        )
        return
    }

    // Cross-image gallery for the strip: tapping any thumbnail opens the
    // full-screen swipeable viewer spanning every shared image (newest first,
    // matching the strip order), starting at the tapped one. Each page carries
    // its own message context so save/share/decrypt act on the visible page.
    val imagePages = remember(tiles.images) { tiles.images.toViewerPages() }
    var viewerStartIndex by remember(tiles.images) { mutableStateOf<Int?>(null) }
    viewerStartIndex?.let { start ->
        FullScreenMediaViewer(
            controller = controller,
            appState = appState,
            pages = imagePages,
            startIndex = start,
            onDismiss = { viewerStartIndex = null },
        )
    }

    SectionCardWithAction(
        title = stringResource(R.string.shared_media),
        action = {
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.shared_media_see_all))
            }
        },
    ) {
        val strip = remember(tiles.images) { tiles.images.take(12) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(strip, key = { "${it.messageIdHex}#${it.attachmentIndex}" }) { tile ->
                Box(
                    modifier =
                        Modifier
                            .size(ThumbStripSize)
                            .clip(RoundedCornerShape(10.dp)),
                ) {
                    MediaImageGridTile(
                        messageIdHex = tile.messageIdHex,
                        attachmentIndex = tile.attachmentIndex,
                        reference = tile.reference,
                        controller = controller,
                        appState = appState,
                        mine = tile.mine,
                        onTap = {
                            val index =
                                imagePages.indexOfFirst {
                                    it.messageIdHex == tile.messageIdHex && it.attachmentIndex == tile.attachmentIndex
                                }
                            viewerStartIndex = index.coerceAtLeast(0)
                        },
                        overflowCount = 0,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// Project resolved image/video tiles onto the per-page descriptors the
// full-screen viewer pages over. Order is preserved (the tiles are already
// newest-first), so the gallery swipes newest → oldest matching the grid.
private fun List<SharedMediaTile>.toViewerPages(): List<MediaViewerPage> =
    map { MediaViewerPage(it.messageIdHex, it.attachmentIndex, it.reference, it.mine, it.sender, it.recordedAt) }

private enum class MediaTab(
    val labelRes: Int,
) {
    Images(R.string.shared_media_tab_images),
    Videos(R.string.shared_media_tab_videos),
    Voice(R.string.shared_media_tab_voice),
    Files(R.string.shared_media_tab_files),
    Urls(R.string.shared_media_tab_urls),
}

/**
 * Full media library reachable from the "See all" affordance. A sticky tab bar
 * switches between Images, Videos, Voice, Files, and URLs. Images and Videos are
 * month-grouped grids; Voice, Files, and URLs are month-grouped vertical lists.
 * Tapping an image/video tile opens the full-screen swipeable viewer spanning
 * the whole tab; a voice/file row jumps back to that message in the
 * conversation; a URL row opens in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaLibraryRoute(
    tiles: SharedMediaTiles,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onJumpToMessage: (String) -> Unit,
) {
    // The entry point opens for video/voice/file/URL-only conversations too, so
    // seed the selection to the first non-empty tab rather than always Images.
    val initialTab =
        when {
            tiles.images.isNotEmpty() -> MediaTab.Images
            tiles.videos.isNotEmpty() -> MediaTab.Videos
            tiles.voice.isNotEmpty() -> MediaTab.Voice
            tiles.files.isNotEmpty() -> MediaTab.Files
            tiles.urls.isNotEmpty() -> MediaTab.Urls
            else -> MediaTab.Images
        }
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    // Cross-message viewer state. Pages span the whole tapped tab (images or
    // videos), so swiping crosses message boundaries; each page carries its own
    // message context. Keyed null when closed.
    var viewerPages by remember { mutableStateOf<List<MediaViewerPage>>(emptyList()) }
    var viewerStartIndex by remember { mutableStateOf<Int?>(null) }
    viewerStartIndex?.let { start ->
        FullScreenMediaViewer(
            controller = controller,
            appState = appState,
            pages = viewerPages,
            startIndex = start,
            onDismiss = { viewerStartIndex = null },
        )
    }
    val openGallery: (List<SharedMediaTile>, SharedMediaTile) -> Unit = { tabTiles, tapped ->
        val pages = tabTiles.toViewerPages()
        val index =
            pages.indexOfFirst {
                it.messageIdHex == tapped.messageIdHex && it.attachmentIndex == tapped.attachmentIndex
            }
        viewerPages = pages
        viewerStartIndex = index.coerceAtLeast(0)
    }
    // One grid state per visual tab so scroll position is preserved when
    // switching back and forth.
    val imagesGridState = rememberLazyGridState()
    val videosGridState = rememberLazyGridState()
    val voiceListState = rememberLazyListState()
    val filesListState = rememberLazyListState()
    val urlsListState = rememberLazyListState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.shared_media)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.shared_media_back),
                            )
                        }
                    },
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 12.dp,
                ) {
                    MediaTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab.ordinal,
                            onClick = { selectedTab = tab.ordinal },
                            text = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (MediaTab.entries[selectedTab]) {
                MediaTab.Images ->
                    MediaTileGrid(
                        sections = tiles.imageSections,
                        gridState = imagesGridState,
                        controller = controller,
                        appState = appState,
                        emptyLabel = stringResource(R.string.shared_media_empty_images),
                        onTapTile = { tapped -> openGallery(tiles.images, tapped) },
                    )
                MediaTab.Videos ->
                    MediaTileGrid(
                        sections = tiles.videoSections,
                        gridState = videosGridState,
                        controller = controller,
                        appState = appState,
                        emptyLabel = stringResource(R.string.shared_media_empty_videos),
                        onTapTile = { tapped -> openGallery(tiles.videos, tapped) },
                    )
                MediaTab.Voice ->
                    VoiceLibraryTab(
                        tiles = tiles,
                        listState = voiceListState,
                        controller = controller,
                        appState = appState,
                        onJumpToMessage = onJumpToMessage,
                    )
                MediaTab.Files ->
                    FileLibraryTab(
                        tiles = tiles,
                        listState = filesListState,
                        controller = controller,
                        appState = appState,
                    )
                MediaTab.Urls ->
                    UrlLibraryTab(
                        tiles = tiles,
                        listState = urlsListState,
                        appState = appState,
                    )
            }
        }
    }
}

@Composable
private fun MediaTileGrid(
    sections: List<MediaMonthSection<SharedMediaTile>>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    emptyLabel: String,
    onTapTile: (SharedMediaTile) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyPlaceholder(emptyLabel)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(key = "header-${section.monthKey}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    monthLabel(section.monthKey),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            gridItems(section.items, key = { "${it.messageIdHex}#${it.attachmentIndex}" }) { tile ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))) {
                    if (tile.isVideo) {
                        MediaVideoGridTile(
                            messageIdHex = tile.messageIdHex,
                            attachmentIndex = tile.attachmentIndex,
                            reference = tile.reference,
                            controller = controller,
                            appState = appState,
                            mine = tile.mine,
                            onTap = { onTapTile(tile) },
                            overflowCount = 0,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MediaImageGridTile(
                            messageIdHex = tile.messageIdHex,
                            attachmentIndex = tile.attachmentIndex,
                            reference = tile.reference,
                            controller = controller,
                            appState = appState,
                            mine = tile.mine,
                            onTap = { onTapTile(tile) },
                            overflowCount = 0,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPlaceholder(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Shared LazyColumn skeleton for the Voice/Files/URLs tabs: groups already
// newest-first items by calendar month and emits a sticky-style month header
// per section, matching the grids' separators. [keyOf] keys each row stably.
@Composable
private fun <T> MonthSectionedColumn(
    sections: List<MediaMonthSection<T>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    emptyLabel: String,
    keyOf: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyPlaceholder(emptyLabel)
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(key = "header-${section.monthKey}") {
                Text(
                    monthLabel(section.monthKey),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            items(section.items, key = { keyOf(it) }) { row(it) }
        }
    }
}

@Composable
private fun VoiceLibraryTab(
    tiles: SharedMediaTiles,
    listState: androidx.compose.foundation.lazy.LazyListState,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onJumpToMessage: (String) -> Unit,
) {
    MonthSectionedColumn(
        sections = tiles.voiceSections,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_voice),
        keyOf = { "${it.messageIdHex}#${it.attachmentIndex}" },
    ) { row ->
        VoiceLibraryRow(
            row = row,
            controller = controller,
            appState = appState,
            onJumpToMessage = onJumpToMessage,
        )
    }
}

@Composable
private fun VoiceLibraryRow(
    row: SharedMediaRow,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onJumpToMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey =
        voicePlaybackKey(
            row.messageIdHex,
            row.attachmentIndex,
            row.reference.sourceEpoch,
        )
    var localFile by remember(pillKey) { mutableStateOf<java.io.File?>(null) }
    var loading by remember(pillKey) { mutableStateOf(false) }

    val isPlayingThis by remember(pillKey) {
        VoicePlaybackController.state
            .map { playback -> playback.key == pillKey && playback.isPlaying }
            .distinctUntilChanged()
    }.collectAsState(false)
    val recordedAtLabel = rememberRelativeTimestamp(row.recordedAt)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .amoledSurfaceBorder(RoundedCornerShape(12.dp))
                .clickable { onJumpToMessage(row.messageIdHex) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Play/pause control. Reuses the process-wide VoicePlaybackController so
        // starting a clip here pauses any clip playing elsewhere (single
        // playback). The tap target is isolated from the row's jump-to-message
        // click so the two affordances don't collide.
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier =
                Modifier
                    .size(44.dp)
                    .clickable(enabled = !loading) {
                        if (isPlayingThis) {
                            VoicePlaybackController.pause()
                            return@clickable
                        }
                        scope.launch {
                            val file =
                                localFile ?: runCatching {
                                    loading = true
                                    materializeVoiceAttachment(
                                        context = context,
                                        controller = controller,
                                        messageIdHex = row.messageIdHex,
                                        attachmentIndex = row.attachmentIndex,
                                        reference = row.reference,
                                        mine = row.mine,
                                    )
                                }.onFailure { error ->
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    appState.presentFailure(
                                        R.string.shared_media_voice_failed,
                                        "MEDIA_LIBRARY_VOICE_LOAD",
                                        error,
                                    )
                                }.also { loading = false }
                                    .getOrNull() ?: return@launch
                            localFile = file
                            VoicePlaybackController.play(pillKey, file)
                        }
                    },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription =
                            stringResource(
                                if (isPlayingThis) R.string.voice_message_pause else R.string.voice_message_play,
                            ),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        SenderAvatar(sender = row.sender, appState = appState)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                appState.displayName(row.sender),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Voice duration isn't carried on the imeta reference and probing it
            // requires decoding the clip, which would force an auto-download
            // here; the send timestamp stands in until playback materializes the
            // file. follow-up: surface duration once the file is local.
            Text(
                recordedAtLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileLibraryTab(
    tiles: SharedMediaTiles,
    listState: androidx.compose.foundation.lazy.LazyListState,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
) {
    val documentSaveFallback = rememberDocumentSaveFallback()
    MonthSectionedColumn(
        sections = tiles.fileSections,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_files),
        keyOf = { "${it.messageIdHex}#${it.attachmentIndex}" },
    ) { row ->
        FileLibraryRow(
            row = row,
            controller = controller,
            appState = appState,
            documentSaveFallback = documentSaveFallback,
        )
    }
}

@Composable
private fun FileLibraryRow(
    row: SharedMediaRow,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    documentSaveFallback: dev.ipf.whitenoise.android.ui.conversation.media.DocumentSaveFallback,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inFlight by remember(row.messageIdHex, row.attachmentIndex) { mutableStateOf(false) }
    var menuOpen by remember(row.messageIdHex, row.attachmentIndex) { mutableStateOf(false) }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val recordedAtLabel = rememberRelativeTimestamp(row.recordedAt)
    val presentation =
        remember(row.reference.mediaType, row.reference.fileName) {
            resolveAttachmentPresentation(row.reference.mediaType, row.reference.fileName)
        }

    val openAttachment = rememberAttachmentOpener()

    // The tap is the user-initiated download trigger — files never auto-fetch
    // in the library. Prefer retained bytes for own in-flight sends, mirroring
    // the conversation file bubble.
    suspend fun fetchBytes(): ByteArray {
        val retained =
            if (row.mine) {
                controller
                    .pendingAttachmentsList(row.messageIdHex)
                    .getOrNull(row.attachmentIndex)
                    ?.plaintextBytes
            } else {
                null
            }
        return controller
            .requestAttachmentTransfer(
                messageIdHex = row.messageIdHex,
                attachmentIndex = row.attachmentIndex,
                reference = row.reference,
                retainedPlaintext = retained,
            ).await()
    }

    suspend fun fetchFile() =
        materializeDocumentAttachment(
            context = context,
            messageIdHex = row.messageIdHex,
            attachmentIndex = row.attachmentIndex,
            reference = row.reference,
            resolveBytes = { fetchBytes() },
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .amoledSurfaceBorder(RoundedCornerShape(12.dp))
                .clickable(enabled = !inFlight) {
                    inFlight = true
                    scope.launch {
                        val outcome =
                            runCatchingCancellable {
                                openAttachment(fetchFile(), row.reference.mediaType)
                            }.getOrElse { error ->
                                appState.presentFailure(
                                    R.string.media_couldnt_open,
                                    "MEDIA_LIBRARY_FILE_OPEN",
                                    error,
                                )
                                inFlight = false
                                return@launch
                            }
                        when (outcome) {
                            OpenAttachmentResult.Opened -> Unit
                            OpenAttachmentResult.NoHandler -> appState.present(noOpenAppMessage)
                            OpenAttachmentResult.InstallPermissionRequired -> appState.present(R.string.media_couldnt_open)
                            OpenAttachmentResult.Error ->
                                appState.presentFailure(
                                    R.string.media_couldnt_open,
                                    "MEDIA_LIBRARY_FILE_OPEN",
                                    IllegalStateException("attachment open returned $outcome"),
                                )
                        }
                        inFlight = false
                    }
                }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = fileIconFor(presentation.iconCategory),
            contentDescription = attachmentTypeDescription(presentation.iconCategory),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.reference.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // File size isn't carried on the imeta reference, so it's omitted
            // until the bytes are fetched; the MIME label + sender + timestamp
            // give the row enough identity without forcing a download.
            Text(
                "${attachmentTypeLabel(presentation)} · ${appState.displayName(row.sender)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                recordedAtLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (inFlight) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.shared_media_file_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = MenuDefaults.shape,
                    border = amoledSurfaceBorderStroke(),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_media_save)) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                val outcome =
                                    runCatchingCancellable {
                                        val file = fetchFile()
                                        val saved =
                                            saveDocumentWithFallback(
                                                context = context,
                                                source = file,
                                                fileName = row.reference.fileName,
                                                mediaType = row.reference.mediaType,
                                                fallback = documentSaveFallback,
                                            )
                                        check(saved) { "MediaStore save returned false" }
                                    }
                                appState.presentMediaSaveOutcome(
                                    outcome = outcome,
                                    successTitleRes = R.string.shared_media_saved,
                                    failureTitleRes = R.string.shared_media_save_failed,
                                    operationCode = "MEDIA_LIBRARY_FILE_SAVE",
                                )
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_media_share)) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                runCatchingCancellable {
                                    shareImage(
                                        context,
                                        fetchBytes(),
                                        row.reference.fileName,
                                        row.reference.mediaType,
                                    ).getOrThrow()
                                }.onFailure { error ->
                                    appState.presentMediaLaunchFailure(
                                        R.string.media_couldnt_open,
                                        "MEDIA_LIBRARY_FILE_SHARE",
                                        error,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UrlLibraryTab(
    tiles: SharedMediaTiles,
    listState: androidx.compose.foundation.lazy.LazyListState,
    appState: WhiteNoiseAppState,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    MonthSectionedColumn(
        sections = tiles.urlSections,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_urls),
        keyOf = { "${it.index}#${it.value.messageIdHex}#${it.value.url}" },
    ) { indexed ->
        val entry = indexed.value
        UrlLibraryRow(
            entry = entry,
            appState = appState,
            onOpen = {
                runCatchingCancellable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure { error ->
                    appState.presentMediaLaunchFailure(
                        R.string.media_couldnt_open,
                        "MEDIA_LIBRARY_URL_OPEN",
                        error,
                    )
                }
            },
            onCopy = {
                clipboard.setText(AnnotatedString(entry.url))
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UrlLibraryRow(
    entry: MediaInventory.UrlEntry,
    appState: WhiteNoiseAppState,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    // No OpenGraph / link-preview cache exists in the app today, so this is the
    // URL-only v1: the host stands in for the title, with a globe glyph for the
    // favicon and no description. follow-up: when an in-conversation link-preview
    // cache lands, render the row immediately and populate title/desc/favicon
    // from it opportunistically; any Android-owned favicon/metadata fetch must
    // use SafeHttpsGet so safety is enforced at the network boundary.
    val host = remember(entry.url) { hostOf(entry.url) }
    val recordedAtLabel = rememberRelativeTimestamp(entry.recordedAt)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .amoledSurfaceBorder(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onOpen, onLongClick = onCopy)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .amoledSurfaceBorder(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                host,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${appState.displayName(entry.sender)} · $recordedAtLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberRelativeTimestamp(recordedAt: ULong): String {
    val context = LocalContext.current
    return remember(context, recordedAt) { relativeTimestamp(context, recordedAt) }
}

@Composable
private fun SenderAvatar(
    sender: String,
    appState: WhiteNoiseAppState,
) {
    Avatar(
        title = appState.displayName(sender),
        seed = sender,
        size = 36.dp,
        pictureUrl = appState.avatarUrl(sender),
    )
}

// Host of an absolute URL for the URL-row title, falling back to the raw URL
// when it can't be parsed (the inventory already guarantees an http(s) prefix).
private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

// Compact relative-ish timestamp for the media rows: today shows the clock,
// older entries show the calendar date. `recordedAt` is epoch SECONDS.
private fun relativeTimestamp(
    context: android.content.Context,
    recordedAtSeconds: ULong,
): String {
    val millis = recordedAtSeconds.toLong() * 1000L
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay =
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val flags =
        if (sameDay) {
            android.text.format.DateUtils.FORMAT_SHOW_TIME
        } else {
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_ALL
        }
    return android.text.format.DateUtils
        .formatDateTime(context, millis, flags)
}

// Localized month/year header for a [monthKeyForMedia] key.
private fun monthLabel(key: Int): String {
    val year = key / 100
    val month = key % 100
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val monthName =
        cal.getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault())
            ?: (month + 1).toString()
    return "$monthName $year"
}
