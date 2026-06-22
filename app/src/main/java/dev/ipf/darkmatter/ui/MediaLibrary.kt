package dev.ipf.darkmatter.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.audio.VoicePlaybackController
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.media.MediaInventory
import dev.ipf.darkmatter.media.MediaReferenceParser
import dev.ipf.darkmatter.state.ConversationController
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Calendar

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

internal data class SharedMediaTiles(
    val images: List<SharedMediaTile>,
    val videos: List<SharedMediaTile>,
    val voice: List<SharedMediaRow>,
    val files: List<SharedMediaRow>,
    val urls: List<MediaInventory.UrlEntry>,
    // True when the conversation carries media beyond the rendered image/video
    // grids — voice, files, urls, or bare image-URL links that aren't gridded.
    // Carried so the section can decide between the strip, the single
    // "View shared media" row, and hiding entirely without re-deriving.
    val hasOther: Boolean,
) {
    val isEmpty: Boolean
        get() =
            images.isEmpty() && videos.isEmpty() && voice.isEmpty() &&
                files.isEmpty() && urls.isEmpty() && !hasOther
}

// Walk the loaded timeline once and project image/video tiles, newest first.
// References come from the controller's listMedia cache (carries the real
// `sourceEpoch`) with the imeta parser as the optimistic-record fallback —
// mirroring the conversation bubble path so a tile here downloads through the
// exact same cache keys the bubble already populated. Keyed on the timeline
// identity and the media-reference map so it rebuilds on new-message arrival,
// not per frame.
@Composable
internal fun rememberSharedMediaTiles(
    controller: ConversationController,
    appState: DarkMatterAppState,
): SharedMediaTiles {
    val myAccountId = appState.activeAccount?.accountIdHex
    return remember(controller.timeline, controller.mediaReferences, myAccountId) {
        val records = controller.timeline.map { it.record }
        val inventory = MediaInventory.build(records)
        val images = ArrayList<SharedMediaTile>()
        val videos = ArrayList<SharedMediaTile>()
        val voice = ArrayList<SharedMediaRow>()
        val files = ArrayList<SharedMediaRow>()
        for (record in records) {
            val mine = MessageProjector.isMine(record, myAccountId)
            val references =
                controller.mediaReferences[record.messageIdHex]
                    ?: MediaReferenceParser.parseAllImetaTags(record.tags)
            references.forEachIndexed { index, reference ->
                when {
                    MediaReferenceParser.isImageMedia(reference) ->
                        images.add(
                            SharedMediaTile(record.messageIdHex, index, reference, mine, record.recordedAt, record.sender, isVideo = false),
                        )
                    MediaReferenceParser.isVideoMedia(reference) ->
                        videos.add(
                            SharedMediaTile(record.messageIdHex, index, reference, mine, record.recordedAt, record.sender, isVideo = true),
                        )
                    MediaReferenceParser.isAudioMedia(reference) ->
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
        val urls = inventory.urls.asReversed()
        // Bare image-URL links carry no attachment index, so they aren't
        // rendered in any grid/list; still count them so the section's
        // "View shared media" entry point appears when they're the only media.
        val linkedImageUrls = inventory.images.count { it.source is MediaInventory.Source.LinkedUrl }
        SharedMediaTiles(
            images = images,
            videos = videos,
            voice = voice,
            files = files,
            urls = urls,
            hasOther = linkedImageUrls > 0,
        )
    }
}

private val ThumbStripSize = 96.dp

/**
 * "Shared media" section for the group/DM details sheet. Shows a horizontal
 * strip of the most recent image thumbnails with a "See all" affordance into
 * [MediaLibraryRoute]. When there are no images but other media exists, it
 * collapses to a single "View shared media" row. Renders nothing when the
 * conversation has no media at all.
 */
@Composable
internal fun SharedMediaSection(
    tiles: SharedMediaTiles,
    controller: ConversationController,
    appState: DarkMatterAppState,
    onSeeAll: () -> Unit,
    onJumpToMessage: (String) -> Unit,
) {
    if (tiles.isEmpty) return

    if (tiles.images.isEmpty()) {
        SectionCard(title = stringResource(R.string.shared_media)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSeeAll() }
                        .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Text(
                    stringResource(R.string.shared_media_view),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
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

private enum class MediaTab(val labelRes: Int) {
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
    appState: DarkMatterAppState,
    onBack: () -> Unit,
    onJumpToMessage: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(MediaTab.Images.ordinal) }
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
                        tiles = tiles.images,
                        gridState = imagesGridState,
                        controller = controller,
                        appState = appState,
                        emptyLabel = stringResource(R.string.shared_media_empty_images),
                        onTapTile = { tapped -> openGallery(tiles.images, tapped) },
                    )
                MediaTab.Videos ->
                    MediaTileGrid(
                        tiles = tiles.videos,
                        gridState = videosGridState,
                        controller = controller,
                        appState = appState,
                        emptyLabel = stringResource(R.string.shared_media_empty_videos),
                        onTapTile = { tapped -> openGallery(tiles.videos, tapped) },
                    )
                MediaTab.Voice ->
                    VoiceLibraryTab(
                        rows = tiles.voice,
                        listState = voiceListState,
                        controller = controller,
                        appState = appState,
                        onJumpToMessage = onJumpToMessage,
                    )
                MediaTab.Files ->
                    FileLibraryTab(
                        rows = tiles.files,
                        listState = filesListState,
                        controller = controller,
                        appState = appState,
                    )
                MediaTab.Urls ->
                    UrlLibraryTab(
                        urls = tiles.urls,
                        listState = urlsListState,
                        appState = appState,
                    )
            }
        }
    }
}

@Composable
private fun MediaTileGrid(
    tiles: List<SharedMediaTile>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    controller: ConversationController,
    appState: DarkMatterAppState,
    emptyLabel: String,
    onTapTile: (SharedMediaTile) -> Unit,
) {
    if (tiles.isEmpty()) {
        EmptyPlaceholder(emptyLabel)
        return
    }
    // Group already-newest-first tiles by calendar month, preserving order so
    // the section headers read newest → oldest down the grid.
    val sections =
        remember(tiles) {
            tiles
                .groupBy { monthKey(it.recordedAt) }
                .entries
                .toList()
        }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEach { (key, monthTiles) ->
            item(key = "header-$key", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    monthLabel(key),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            gridItems(monthTiles, key = { "${it.messageIdHex}#${it.attachmentIndex}" }) { tile ->
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
    items: List<T>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    emptyLabel: String,
    monthOf: (T) -> ULong,
    keyOf: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyPlaceholder(emptyLabel)
        return
    }
    val sections =
        remember(items) {
            items
                .groupBy { monthKey(monthOf(it)) }
                .entries
                .toList()
        }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEach { (key, monthItems) ->
            item(key = "header-$key") {
                Text(
                    monthLabel(key),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            items(monthItems, key = { keyOf(it) }) { row(it) }
        }
    }
}

@Composable
private fun VoiceLibraryTab(
    rows: List<SharedMediaRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    controller: ConversationController,
    appState: DarkMatterAppState,
    onJumpToMessage: (String) -> Unit,
) {
    MonthSectionedColumn(
        items = rows,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_voice),
        monthOf = { it.recordedAt },
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
    appState: DarkMatterAppState,
    onJumpToMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "${row.messageIdHex}#${row.attachmentIndex}"
    var localFile by remember(pillKey) { mutableStateOf<java.io.File?>(null) }
    var loading by remember(pillKey) { mutableStateOf(false) }

    val playback by VoicePlaybackController.state.collectAsState()
    val isPlayingThis = playback.key == pillKey && playback.isPlaying

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
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
                                }.onFailure {
                                    if (it is kotlinx.coroutines.CancellationException) throw it
                                    appState.present(R.string.shared_media_voice_failed)
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
                relativeTimestamp(LocalContext.current, row.recordedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileLibraryTab(
    rows: List<SharedMediaRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    controller: ConversationController,
    appState: DarkMatterAppState,
) {
    MonthSectionedColumn(
        items = rows,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_files),
        monthOf = { it.recordedAt },
        keyOf = { "${it.messageIdHex}#${it.attachmentIndex}" },
    ) { row ->
        FileLibraryRow(
            row = row,
            controller = controller,
            appState = appState,
        )
    }
}

@Composable
private fun FileLibraryRow(
    row: SharedMediaRow,
    controller: ConversationController,
    appState: DarkMatterAppState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inFlight by remember(row.messageIdHex, row.attachmentIndex) { mutableStateOf(false) }
    var menuOpen by remember(row.messageIdHex, row.attachmentIndex) { mutableStateOf(false) }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)

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
        return retained
            ?: controller.downloadAttachment(row.messageIdHex, row.attachmentIndex, row.reference)
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !inFlight) {
                    inFlight = true
                    scope.launch {
                        val outcome =
                            runCatching {
                                openAttachmentExternally(
                                    context,
                                    fetchBytes(),
                                    row.reference.fileName,
                                    row.reference.mediaType,
                                )
                            }.onFailure {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                            }.getOrDefault(OpenAttachmentResult.Error)
                        when (outcome) {
                            OpenAttachmentResult.Opened -> Unit
                            OpenAttachmentResult.NoHandler -> appState.present(noOpenAppMessage)
                            OpenAttachmentResult.Error -> appState.present(couldntOpenMessage)
                        }
                        inFlight = false
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = fileIconFor(row.reference.mediaType),
            contentDescription = null,
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
                "${shortMediaTypeLabel(row.reference.mediaType)} · ${appState.displayName(row.sender)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                relativeTimestamp(LocalContext.current, row.recordedAt),
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
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_media_save)) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                val saved =
                                    runCatching {
                                        val bytes = fetchBytes()
                                        when {
                                            row.reference.mediaType.startsWith("image/", ignoreCase = true) ->
                                                saveImageToGallery(
                                                    context,
                                                    bytes,
                                                    row.reference.fileName,
                                                    row.reference.mediaType,
                                                )
                                            row.reference.mediaType.startsWith("video/", ignoreCase = true) ->
                                                saveVideoToGallery(
                                                    context,
                                                    bytes,
                                                    row.reference.fileName,
                                                    row.reference.mediaType,
                                                )
                                            else ->
                                                saveFileToDownloads(
                                                    context,
                                                    bytes,
                                                    row.reference.fileName,
                                                    row.reference.mediaType,
                                                )
                                        }
                                    }.getOrDefault(false)
                                appState.present(
                                    if (saved) R.string.shared_media_saved else R.string.shared_media_save_failed,
                                )
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_media_share)) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                runCatching {
                                    shareImage(
                                        context,
                                        fetchBytes(),
                                        row.reference.fileName,
                                        row.reference.mediaType,
                                    )
                                }.onFailure {
                                    if (it is kotlinx.coroutines.CancellationException) throw it
                                    appState.present(couldntOpenMessage)
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
    urls: List<MediaInventory.UrlEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    appState: DarkMatterAppState,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)
    MonthSectionedColumn(
        items = urls,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_urls),
        monthOf = { it.recordedAt },
        keyOf = { "${it.messageIdHex}#${it.url}" },
    ) { entry ->
        UrlLibraryRow(
            entry = entry,
            appState = appState,
            onOpen = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure { appState.present(couldntOpenMessage) }
            },
            onCopy = {
                clipboard.setText(AnnotatedString(entry.url))
                appState.present(R.string.copied)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UrlLibraryRow(
    entry: MediaInventory.UrlEntry,
    appState: DarkMatterAppState,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    // No OpenGraph / link-preview cache exists in the app today, so this is the
    // URL-only v1: the host stands in for the title, with a globe glyph for the
    // favicon and no description. follow-up: when an in-conversation link-preview
    // cache lands, render the row immediately and populate title/desc/favicon
    // from it opportunistically; any favicon/metadata fetch must be https-only
    // and pass HostSafety.isPrivateOrLoopbackHost before any network hop.
    val host = remember(entry.url) { hostOf(entry.url) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
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
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
                "${appState.displayName(entry.sender)} · ${relativeTimestamp(LocalContext.current, entry.recordedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SenderAvatar(
    sender: String,
    appState: DarkMatterAppState,
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
private fun hostOf(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

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
    return android.text.format.DateUtils.formatDateTime(context, millis, flags)
}

// Month bucketing keyed off the local-time calendar so the separators match
// what the user sees on each message. `recordedAt` is epoch SECONDS.
private fun monthKey(recordedAtSeconds: ULong): Int {
    val cal = Calendar.getInstance()
    cal.timeInMillis = recordedAtSeconds.toLong() * 1000L
    return cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
}

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

// Persist an arbitrary (non image/video) attachment to the public Downloads
// collection via the Downloads MediaStore so it lands somewhere the user can
// find it. Mirrors the image/video save flows' IS_PENDING dance. Returns false
// on any failure so the caller can surface the existing save-failed toast.
private fun saveFileToDownloads(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, dev.ipf.darkmatter.media.MediaPipeline.safeDisplayName(fileName))
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mediaType.ifBlank { "application/octet-stream" })
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/DarkMatter")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
    return try {
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw java.io.IOException("null output stream")
            out.write(bytes)
        }
        values.clear()
        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (_: Throwable) {
        resolver.delete(uri, null, null)
        false
    }
}
