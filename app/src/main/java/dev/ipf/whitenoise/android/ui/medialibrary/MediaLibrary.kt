package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DateTimeDisplay
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaInventory
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.currentDateTimeDisplay
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade
import dev.ipf.whitenoise.android.ui.common.trackWhiteNoiseHeader
import dev.ipf.whitenoise.android.ui.conversation.media.FullScreenMediaViewer
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.conversation.media.OpenAttachmentResult
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentSource
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentTypeDescription
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentTypeLabel
import dev.ipf.whitenoise.android.ui.conversation.media.fileIconFor
import dev.ipf.whitenoise.android.ui.conversation.media.materializeDocumentFile
import dev.ipf.whitenoise.android.ui.conversation.media.presentMediaLaunchFailure
import dev.ipf.whitenoise.android.ui.conversation.media.presentMediaSaveOutcome
import dev.ipf.whitenoise.android.ui.conversation.media.rememberAttachmentOpener
import dev.ipf.whitenoise.android.ui.conversation.media.rememberDocumentSaveFallback
import dev.ipf.whitenoise.android.ui.conversation.media.rememberMediaViewerChromeIconProvider
import dev.ipf.whitenoise.android.ui.conversation.media.rememberMediaViewerForwardActions
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.saveDocumentWithFallback
import dev.ipf.whitenoise.android.ui.conversation.media.shareAttachmentExternally
import dev.ipf.whitenoise.android.ui.conversation.rememberForwardEligibilityNowSeconds
import dev.ipf.whitenoise.android.ui.design.AnimatedDropdownMenu
import dev.ipf.whitenoise.android.ui.settings.rememberSettingsNativeSurface
import dev.ipf.whitenoise.android.ui.settings.settingsCardMorph
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.FormatStyle
import java.util.Locale

// Walk the loaded timeline once and project image/video tiles, newest first.
// Projected rows provide typed media carrying the real source epoch; only
// optimistic/compatibility records fall back to MarmotKit tag parsing. Keyed
// on timeline identity so it rebuilds on projection changes, not per frame.

/** Remembers the shared media tiles derived from the loaded timeline. */
@Composable
internal fun rememberSharedMediaTiles(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    myAccountId: String? = appState.activeAccount?.accountIdHex,
): SharedMediaTiles = rememberSharedMediaTilesState(controller, appState, myAccountId).value

/**
 * Remembers the shared media tiles as state, so a caller that only needs them in a nested scope can read them there
 * and leave its own scope untouched when a rebuild lands.
 */
@Composable
internal fun rememberSharedMediaTilesState(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    myAccountId: String? = appState.activeAccount?.accountIdHex,
): State<SharedMediaTiles> {
    val timeline = controller.timeline
    val deletedMessageIds = controller.deletedMessageIds
    val pendingTimelineRemovedMessageIds = controller.pendingTimelineRemovedMessageIds
    val retentionExpiries =
        remember(timeline) {
            timeline.mapNotNull { it.record.retentionExpiresAt?.takeIf { expiry -> expiry > 0uL } }
        }
    val nowSeconds = rememberForwardEligibilityNowSeconds(retentionExpiries)
    return key(
        controller,
        myAccountId,
        timeline,
        deletedMessageIds,
        pendingTimelineRemovedMessageIds,
        nowSeconds,
    ) {
        // The build sweep is O(N) over the timeline; run it off the composition
        // thread. Re-keying on every visibility input also clears a stale result
        // synchronously, so deleted or expired media cannot remain swipeable while
        // the replacement projection is still being built.
        produceState(
            initialValue = emptySharedMediaTiles().copy(isLoading = true),
            timeline,
            deletedMessageIds,
            pendingTimelineRemovedMessageIds,
            myAccountId,
            nowSeconds,
        ) {
            val timelineSnapshot = timeline
            value =
                withContext(Dispatchers.Default) {
                    buildVisibleSharedMediaTiles(
                        messages = timelineSnapshot,
                        myAccountId = myAccountId,
                        deletedMessageIds = deletedMessageIds,
                        pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds,
                        nowSeconds = nowSeconds,
                    )
                }
        }
    }
}

/** Empty display projection used while a new account or library generation is loading. */
internal fun emptySharedMediaTiles() =
    SharedMediaTiles(
        visuals = emptyList(),
        images = emptyList(),
        videos = emptyList(),
        voice = emptyList(),
        files = emptyList(),
        urls = emptyList(),
        imageSections = emptyList(),
        videoSections = emptyList(),
        voiceSections = emptyList(),
        fileSections = emptyList(),
        urlSections = emptyList(),
        hasOther = false,
    )

/** Tiles for the visible shared media of the loaded messages. */
internal fun buildVisibleSharedMediaTiles(
    messages: List<TimelineMessage>,
    myAccountId: String?,
    deletedMessageIds: Set<String>,
    pendingTimelineRemovedMessageIds: Set<String>,
    nowSeconds: ULong,
): SharedMediaTiles =
    buildTiles(
        messages =
            messages.filter { message ->
                val record = message.record
                MessageProjector.isChatKind(record.kind) &&
                    message.projected?.deleted != true &&
                    !MessageProjector.isDeleted(record.messageIdHex, deletedMessageIds) &&
                    record.messageIdHex !in pendingTimelineRemovedMessageIds &&
                    record.retentionExpiresAt
                        ?.takeIf { it > 0uL }
                        ?.let { it > nowSeconds } != false
            },
        myAccountId = myAccountId,
    )

// Pure tile projection extracted from the composable so it can run on a
// background dispatcher. Projected rows carry authoritative typed media;
// optimistic/compatibility records alone fall back to MarmotKit tag parsing.

/** Builds newest-first visual, voice, file, and link rows while preserving authored attachment order. */
private fun buildTiles(
    messages: List<TimelineMessage>,
    myAccountId: String?,
): SharedMediaTiles {
    val visuals = ArrayList<SharedMediaTile>()
    val images = ArrayList<SharedMediaTile>()
    val videos = ArrayList<SharedMediaTile>()
    val voice = ArrayList<SharedMediaRow>()
    val files = ArrayList<SharedMediaRow>()
    for (message in messages.asReversed()) {
        val record = message.record
        val mine = MessageProjector.isMine(record, myAccountId)
        message.mediaAttachments().forEach { (index, reference) ->
            when {
                MediaReferenceSupport.isImageMedia(reference) || MediaReferenceSupport.isVideoMedia(reference) -> {
                    val tile = message.toSharedMediaTile(index, reference, mine)
                    visuals.add(tile)
                    if (tile.isVideo) videos.add(tile) else images.add(tile)
                }
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
    // Messages are newest first for grids/lists while each album retains its authored attachment slots.
    // URLs are sorted newest-first to match the lists; the inventory keeps
    // them in timeline order (oldest first).
    val urls = MediaInventory.urls(messages.map { it.record }).asReversed()
    // Bare image-URL links aren't rendered in any grid/list yet, so they don't
    // count toward `hasOther` — otherwise a links-only conversation would show
    // the "View shared media" entry into a library with every tab empty.
    return SharedMediaTiles(
        visuals = visuals,
        images = images,
        videos = videos,
        voice = voice,
        files = files,
        urls = urls,
        visualSections = groupIntoMonthSections(visuals) { it.recordedAt },
        imageSections = groupIntoMonthSections(images) { it.recordedAt },
        videoSections = groupIntoMonthSections(videos) { it.recordedAt },
        voiceSections = groupIntoMonthSections(voice) { it.recordedAt },
        fileSections = groupIntoMonthSections(files) { it.recordedAt },
        urlSections = groupIntoMonthSections(urls.withIndex().toList()) { it.value.recordedAt },
        hasOther = voice.isNotEmpty() || files.isNotEmpty() || urls.isNotEmpty(),
    )
}

/**
 * Accepted attachments keyed by protocol index, minus the images the message's own emoji tags claim
 * as inline artwork, which are not shared media. Unprojected rows fall back to positional tag parsing.
 */
internal fun TimelineMessage.mediaAttachments(): List<IndexedAttachment> {
    val attachments =
        projected?.media?.let(MessageAttachments::accepted)
            ?: MessageAttachments.indexed(
                MediaReferenceSupport.parseAllImetaTags(
                    tags = record.tags,
                    sourceEpoch = record.sourceEpoch ?: 0uL,
                ),
            )
    val claimed = Nip30Emoji.attachmentShortcodes(record.tags, attachments).keys
    return if (claimed.isEmpty()) attachments else attachments.filter { it.index !in claimed }
}

private fun TimelineMessage.toSharedMediaTile(
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
) = SharedMediaTile(
    record.messageIdHex,
    attachmentIndex,
    reference,
    mine,
    record.recordedAt,
    record.sender,
    isVideo = MediaReferenceSupport.isVideoMedia(reference),
)

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

/** Counts fallback for media the grid cannot show. */
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

/** Opens available per-chat categories from the same bounded, visibility-filtered source. */
@Composable
internal fun SharedMediaSection(
    tiles: SharedMediaTiles,
    onOpenCategory: (SharedContentCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedContentCategories(tiles, onOpenCategory, modifier)
}

/** Gallery viewer pages follow its newest-first grid while preserving authored album slots. */
internal fun List<SharedMediaTile>.toGalleryViewerPages(): List<MediaViewerPage> =
    map {
        MediaViewerPage(it.messageIdHex, it.attachmentIndex, it.reference, it.mine, it.sender, it.recordedAt)
    }

/** Conversation pages use chronological message traversal while preserving authored album slots. */
internal fun List<SharedMediaTile>.toConversationViewerPages(): List<MediaViewerPage> =
    newestFirstMessageGroupsToChronological().map {
        MediaViewerPage(it.messageIdHex, it.attachmentIndex, it.reference, it.mine, it.sender, it.recordedAt)
    }

/** Reverses contiguous newest-first message groups without reversing slots within a message. */
private fun List<SharedMediaTile>.newestFirstMessageGroupsToChronological(): List<SharedMediaTile> {
    if (isEmpty()) return emptyList()
    val groups = ArrayList<List<SharedMediaTile>>()
    var start = 0
    for (index in 1..size) {
        if (index == size || this[index].messageIdHex != this[start].messageIdHex) {
            groups += subList(start, index)
            start = index
        }
    }
    return groups.asReversed().flatten()
}

/** Opens one native per-chat category, with state isolated to its controller/account/runtime owner. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun MediaLibraryRoute(
    tiles: SharedMediaTiles,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    category: SharedContentCategory,
    onBack: () -> Unit,
    onJumpToMessage: (String) -> Unit,
) {
    key(controller, appState.activeAccountRef, appState.runtimeGeneration, category) {
        if (category == SharedContentCategory.Links) {
            MediaLibraryContent(tiles, controller, appState, category, onBack, onJumpToMessage)
        } else {
            val pager = rememberGroupAttachmentPager(controller, appState)
            val state = pager?.state ?: GroupAttachmentState()
            val nativeTiles =
                key(state.entries, controller.boundAccountIdHex) {
                    val projected by produceState(emptySharedMediaTiles().copy(isLoading = true)) {
                        value =
                            withContext(Dispatchers.Default) {
                                attachmentLibraryTiles(state.entries, controller.boundAccountIdHex)
                            }
                    }
                    projected
                }
            val scope = rememberCoroutineScope()
            MediaLibraryContent(
                nativeTiles.copy(isLoading = nativeTiles.isLoading || (state.loading && !state.initialized)),
                controller,
                appState,
                category,
                onBack,
                onJumpToMessage,
                paging = state,
                onLoadMore = { scope.launch { pager?.loadMore() } },
                liveVisuals = pager?.liveVisuals { controller.boundAccountIdHex },
            )
        }
    }
}

/** Owns the existing source-aware gallery and independent scroll positions for each media filter. */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun MediaLibraryContent(
    tiles: SharedMediaTiles,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    category: SharedContentCategory,
    onBack: () -> Unit,
    onJumpToMessage: (String) -> Unit,
    paging: GroupAttachmentState? = null,
    onLoadMore: () -> Unit = {},
    liveVisuals: (suspend () -> List<SharedMediaTile>)? = null,
) {
    var filter by rememberSaveable { mutableStateOf(SharedVisualFilter.All) }
    val photoShare = rememberSharedPhotoShare(tiles, controller, appState, liveVisuals)
    var pendingDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val viewerSelection = remember { SharedMediaViewerSelection() }
    val viewerChromeIcons = rememberMediaViewerChromeIconProvider()
    val forwardActions = rememberMediaViewerForwardActions(controller, appState)
    val viewerStart = viewerSelection.source
    val visualTiles = tiles.visualsFor(filter)
    val viewerPages = remember(visualTiles) { visualTiles.toGalleryViewerPages() }
    val viewerIndex =
        viewerStart?.let { (messageId, attachmentIndex) ->
            viewerPages.indexOfFirst { it.messageIdHex == messageId && it.attachmentIndex == attachmentIndex }
        }
    if (!tiles.isLoading && viewerIndex != null && viewerIndex >= 0) {
        FullScreenMediaViewer(
            controller = controller,
            appState = appState,
            pages = viewerPages,
            startIndex = viewerIndex,
            onDismiss = viewerSelection::clear,
            icons = viewerChromeIcons.value,
            forwardActions = forwardActions,
            onGoToMessage = { page ->
                viewerSelection.clear()
                onJumpToMessage(page.messageIdHex)
            },
            onCurrentPageChange = { viewerSelection.select(it.messageIdHex, it.attachmentIndex) },
        )
    }
    LaunchedEffect(tiles.isLoading, viewerPages, viewerStart) {
        viewerSelection.reconcile(tiles.isLoading, viewerPages)
    }
    val allGridState = rememberLazyGridState()
    val imagesGridState = rememberLazyGridState()
    val videosGridState = rememberLazyGridState()
    val voiceListState = rememberLazyListState()
    val filesListState = rememberLazyListState()
    val urlsListState = rememberLazyListState()
    val sections =
        when (filter) {
            SharedVisualFilter.All -> tiles.visualSections
            SharedVisualFilter.Images -> tiles.imageSections
            SharedVisualFilter.Videos -> tiles.videoSections
        }
    val gridState =
        when (filter) {
            SharedVisualFilter.All -> allGridState
            SharedVisualFilter.Images -> imagesGridState
            SharedVisualFilter.Videos -> videosGridState
        }
    // A refresh restores its pages one at a time, so a chosen day waits for the whole range to return.
    val jumpReady = !tiles.isLoading && paging?.loading != true
    MediaDateJumpEffect(pendingDay, sections, gridState, jumpReady) { pendingDay = null }
    SharedContentScaffold(
        category = category,
        filter = filter,
        onFilter = {
            photoShare.exit()
            filter = it
            pendingDay = null
        },
        onBack = photoShare.backOr(onBack),
        loading = tiles.isLoading,
        paging = paging,
        onLoadMore = onLoadMore,
        selectionBar = photoShare.selectionBar(tiles.isLoading),
        filterRowAction = { MediaDateJumpAction(sections, gridState, jumpReady) { pendingDay = it } },
    ) {
        val selectedEmpty =
            when (category) {
                SharedContentCategory.Media -> visualTiles.isEmpty()
                SharedContentCategory.Documents -> tiles.files.isEmpty()
                SharedContentCategory.Voice -> tiles.voice.isEmpty()
                SharedContentCategory.Links -> tiles.urls.isEmpty()
            }
        if (paging != null && selectedEmpty) {
            AttachmentLibraryEmptyState(paging)
        } else {
            when (category) {
                SharedContentCategory.Media ->
                    MediaTileGrid(sections, gridState, stringResource(R.string.shared_content_empty)) { tile ->
                        SharedMediaGridTile(tile, controller, appState, photoShare) {
                            viewerSelection.select(it.messageIdHex, it.attachmentIndex)
                        }
                    }
                SharedContentCategory.Links ->
                    UrlLibraryTab(
                        tiles = tiles,
                        listState = urlsListState,
                        appState = appState,
                        onJumpToMessage = onJumpToMessage,
                    )
                SharedContentCategory.Documents ->
                    FileLibraryTab(
                        tiles = tiles,
                        listState = filesListState,
                        controller = controller,
                        appState = appState,
                        onJumpToMessage = onJumpToMessage,
                    )
                SharedContentCategory.Voice ->
                    VoiceLibraryTab(
                        tiles = tiles,
                        listState = voiceListState,
                        controller = controller,
                        appState = appState,
                        onJumpToMessage = onJumpToMessage,
                    )
            }
        }
    }
}

/** Empty state of a library tab. */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyPlaceholder(label: String) {
    SharedContentEmptyState(label)
}

// Shared LazyColumn skeleton for the Voice/Files/URLs tabs: groups already
// newest-first items by calendar month and emits a sticky-style month header
// per section, matching the grids' separators. [keyOf] keys each row stably.

/** Month-sectioned list shared by the voice, file and link tabs. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun <T> MonthSectionedColumn(
    sections: List<MediaMonthSection<T>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    emptyLabel: String,
    keyOf: (T) -> Any,
    messageIdOf: (T) -> String,
    onJumpToMessage: (String) -> Unit,
    row: @Composable (T) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyPlaceholder(emptyLabel)
        return
    }
    dev.ipf.whitenoise.android.ui.settings.SettingsNativeViewport { viewport ->
        WhiteNoiseLazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().then(viewport).trackWhiteNoiseHeader(listState),
        ) {
            sections.forEach { section ->
                item(key = "header-${section.sectionKey}") {
                    Text(
                        monthLabel(section.monthKey),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp).semantics { heading() },
                    )
                }
                items(section.items, key = { keyOf(it) }) { item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        row(item)
                        TextButton(
                            onClick = { onJumpToMessage(messageIdOf(item)) },
                            modifier = Modifier.testTag("shared.message.${keyOf(item)}"),
                        ) {
                            Text(stringResource(R.string.shared_content_go_to_message))
                        }
                    }
                }
            }
        }
    }
}

/** Files tab of the library. */
@Composable
private fun FileLibraryTab(
    tiles: SharedMediaTiles,
    listState: androidx.compose.foundation.lazy.LazyListState,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onJumpToMessage: (String) -> Unit,
) {
    val documentSaveFallback = rememberDocumentSaveFallback(appState, controller.group.groupIdHex)
    MonthSectionedColumn(
        sections = tiles.fileSections,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_files),
        keyOf = { "${it.messageIdHex}#${it.attachmentIndex}" },
        messageIdOf = { it.messageIdHex },
        onJumpToMessage = onJumpToMessage,
    ) { row ->
        FileLibraryRow(
            row = row,
            controller = controller,
            appState = appState,
            documentSaveFallback = documentSaveFallback,
        )
    }
}

/** Reports a library file open that did not reach an app, with the conversation file card's messages. */
internal fun WhiteNoiseAppState.presentLibraryFileOpenOutcome(outcome: OpenAttachmentResult) {
    when (outcome) {
        OpenAttachmentResult.Opened,
        OpenAttachmentResult.DestinationNotVisible,
        -> Unit
        OpenAttachmentResult.NoHandler -> present(R.string.media_no_app_to_open)
        OpenAttachmentResult.NoInstaller -> present(R.string.media_apk_no_installer)
        OpenAttachmentResult.InstallPermissionDenied,
        OpenAttachmentResult.InstallPermissionRequired,
        -> present(R.string.media_apk_permission_denied)
        OpenAttachmentResult.InstallPermissionUnavailable -> present(R.string.media_apk_permission_unavailable)
        OpenAttachmentResult.InstallUnsupported -> present(R.string.media_apk_install_unsupported)
        OpenAttachmentResult.InvalidPackage -> present(R.string.media_apk_invalid)
        OpenAttachmentResult.MissingArtifact,
        OpenAttachmentResult.SecurityFailure,
        OpenAttachmentResult.Error,
        ->
            presentFailure(
                R.string.media_couldnt_open,
                "MEDIA_LIBRARY_FILE_OPEN",
                IllegalStateException("attachment open returned $outcome"),
            )
    }
}

/**
 * Downloads a library file that is not on the device yet without opening it, reporting a failed download. The row
 * opens it from a later tap, once it is cached.
 */
internal suspend fun downloadLibraryFile(
    appState: WhiteNoiseAppState,
    fetch: suspend () -> Any,
) {
    runCatchingCancellable { fetch() }.onFailure { error ->
        appState.presentFailure(R.string.media_couldnt_load, "MEDIA_LIBRARY_FILE_DOWNLOAD", error)
    }
}

/** One file row with open and save actions. */
@Composable
internal fun FileLibraryRow(
    row: SharedMediaRow,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    documentSaveFallback: dev.ipf.whitenoise.android.ui.conversation.media.DocumentSaveFallback,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentRow by rememberUpdatedState(row)
    val shareChooserTitle = stringResource(R.string.shared_media_share)
    var inFlight by remember(row.messageIdHex, row.attachmentIndex) { mutableStateOf(false) }
    var menuOpen by remember(row.messageIdHex, row.attachmentIndex) { mutableStateOf(false) }
    val recordedAtLabel = rememberRelativeTimestamp(row.recordedAt)
    val presentation =
        remember(row.reference.mediaType, row.reference.fileName) {
            resolveAttachmentPresentation(row.reference.mediaType, row.reference.fileName)
        }

    val openAttachment = rememberAttachmentOpener()

    // The tap is the user-initiated download trigger — files never auto-fetch
    // in the library. A file that is not on the device yet is only downloaded,
    // and the next tap opens it. Prefer retained bytes for own in-flight sends,
    // mirroring the conversation file bubble.

    /** The sender's in-memory retry bytes for this row, or null so the attachment is read from MDK. */
    fun retainedBytes(): ByteArray? =
        if (row.mine) {
            controller
                .pendingAttachmentsList(row.messageIdHex)
                .getOrNull(row.attachmentIndex)
                ?.inMemoryBytes
        } else {
            null
        }

    /** Materializes the row's document for opening or saving by streaming it, whatever its size. */
    suspend fun fetchFile() =
        materializeDocumentFile(
            context = context,
            controller = controller,
            messageIdHex = row.messageIdHex,
            attachmentIndex = row.attachmentIndex,
            reference = row.reference,
            retained = retainedBytes(),
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    rememberSettingsNativeSurface(
                        destination = null,
                        fill = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large,
                        strokeColor =
                            amoledSurfaceBorderStroke()?.let { MaterialTheme.colorScheme.outlineVariant }
                                ?: Color.Unspecified,
                    ).first,
                ).settingsCardMorph()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .amoledSurfaceBorder(MaterialTheme.shapes.large)
                .clickable(enabled = !inFlight) {
                    inFlight = true
                    scope.launch {
                        val downloaded =
                            retainedBytes() != null ||
                                controller.hasCachedAttachmentAfterHydration(row.messageIdHex, row.attachmentIndex)
                        if (!downloaded) {
                            downloadLibraryFile(appState) { fetchFile() }
                            inFlight = false
                            return@launch
                        }
                        val outcome =
                            runCatchingCancellable {
                                openAttachment(
                                    fetchFile(),
                                    row.reference.mediaType,
                                    row.reference.fileName,
                                    null,
                                    null,
                                )
                            }.getOrElse { error ->
                                appState.presentFailure(
                                    R.string.media_couldnt_open,
                                    "MEDIA_LIBRARY_FILE_OPEN",
                                    error,
                                )
                                inFlight = false
                                return@launch
                            }
                        appState.presentLibraryFileOpenOutcome(outcome)
                        inFlight = false
                    }
                }.padding(8.dp),
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
            EmojiLabel(
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
                        painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.shared_media_file_actions),
                    )
                }
                val menuScrollState = rememberScrollState()
                AnimatedDropdownMenu(
                    scrollState = menuScrollState,
                    modifier = Modifier.scrollEdgeFade(menuScrollState, stableRenderTarget = true),
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = MenuDefaults.shape,
                    border = amoledSurfaceBorderStroke(),
                ) { surface ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_media_save)) },
                        onClick = {
                            surface.close {
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
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_media_share)) },
                        onClick = {
                            surface.close {
                                menuOpen = false
                                scope.launch {
                                    val sharedRow = row
                                    runCatchingCancellable {
                                        shareLibraryFile(
                                            context = context,
                                            accountRef = checkNotNull(controller.conversationAccountRef),
                                            row = sharedRow,
                                            chooserTitle = shareChooserTitle,
                                            currentRow = {
                                                currentRow.takeIf { controller.ownsRetainedForegroundPresentation }
                                            },
                                        ) {
                                            attachmentSource(
                                                controller,
                                                sharedRow.messageIdHex,
                                                sharedRow.attachmentIndex,
                                                sharedRow.reference,
                                                sharedRow.mine,
                                            )
                                        }
                                    }.onFailure { error ->
                                        appState.presentMediaLaunchFailure(
                                            R.string.media_couldnt_open,
                                            "MEDIA_LIBRARY_FILE_SHARE",
                                            error,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun SharedMediaRow.attachmentKey() = messageIdHex to attachmentIndex

/** Streams a verified file under its original name while [currentRow] still identifies [row]. */
internal suspend fun shareLibraryFile(
    context: Context,
    accountRef: String,
    row: SharedMediaRow,
    chooserTitle: String,
    currentRow: () -> SharedMediaRow?,
    source: suspend () -> AttachmentPlaintext,
) = shareAttachmentExternally(
    context = context,
    accountRef = accountRef,
    attachmentIndex = row.attachmentIndex,
    reference = row.reference,
    chooserTitle = chooserTitle,
    isCurrent = { currentRow()?.attachmentKey() == row.attachmentKey() },
    plaintext = source,
)

@Composable
internal fun rememberRelativeTimestamp(recordedAt: ULong): String {
    val locale = LocalConfiguration.current.locales[0]
    val display = currentDateTimeDisplay()
    return remember(recordedAt, locale, display) { relativeTimestamp(recordedAt, locale, display) }
}

// Compact relative-ish timestamp for the media rows: today shows the clock,
// older entries show the calendar date. `recordedAt` is epoch SECONDS.
private fun relativeTimestamp(
    recordedAtSeconds: ULong,
    locale: Locale,
    display: DateTimeDisplay,
): String {
    val zone = ZoneId.systemDefault()
    val sameDay = IdentityFormatter.localDate(recordedAtSeconds, zone) == LocalDate.now(zone)
    return IdentityFormatter.dateTime(
        recordedAtSeconds,
        locale,
        zone,
        display,
        dateStyle = if (sameDay) null else FormatStyle.MEDIUM,
        timeStyle = if (sameDay) FormatStyle.SHORT else null,
        withoutYear = IdentityFormatter.isCurrentYear(recordedAtSeconds, zone),
    )
}
