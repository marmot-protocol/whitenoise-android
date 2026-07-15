package dev.ipf.whitenoise.android.ui.conversation.composer

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.reference
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiData
import dev.ipf.whitenoise.android.ui.EmojiEntry
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import dev.ipf.whitenoise.android.ui.stickers.StickerImage
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(
    onDismissRequest: () -> Unit,
    onEmojiPicked: (String) -> Unit,
    recordRecentPicks: Boolean = true,
    restoreExpanded: Boolean = false,
    messageReactionEmojis: List<String> = emptyList(),
    onCustomizeReactions: ((Boolean) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    LaunchedEffect(restoreExpanded, sheetState) {
        if (restoreExpanded) {
            runCatching { sheetState.expand() }
        }
    }
    val contentExpanded =
        sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded
    val visibleContentFraction = emojiPickerSheetVisibleContentFraction(expanded = contentExpanded)
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismissRequest,
        // Start at the partial detent, but keep the sheet composed under the
        // customize screen so an expanded picker returns expanded without a
        // visible collapse/re-expand.
        sheetState = sheetState,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(EmojiPickerSheetMaxHeightFraction)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            EmojiPickerContent(
                onEmojiPicked = onEmojiPicked,
                recordRecentPicks = recordRecentPicks,
                messageReactionEmojis = messageReactionEmojis,
                onCustomizeReactions =
                    onCustomizeReactions?.let { customize ->
                        { customize(sheetState.currentValue == SheetValue.Expanded) }
                    },
                searchFieldAlwaysVisible = true,
                searchStartsOpen = false,
                // Material3 measures the sheet at its full requested height, then
                // exposes only the top half at the partial detent. Keep the outer
                // shell tall enough for expansion, but lay out the picker controls
                // inside the actually visible partial viewport so the rail is not
                // hidden below the fold.
                // Focusing the search field expands the sheet to full screen;
                // imePadding then lifts the whole picker (grid + category rail)
                // above the keyboard so nothing hides behind the IME (#1104).
                onSearchActiveChange = { active ->
                    if (active) {
                        scope.launch { runCatching { sheetState.expand() } }
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(visibleContentFraction),
            )
        }
    }
}

@Composable
internal fun ComposerEmojiPickerPane(
    height: Dp,
    alpha: Float,
    onEmojiPicked: (String) -> Unit,
    onBackspace: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    appState: WhiteNoiseAppState? = null,
    stickerPacks: List<StickerPackFfi> = emptyList(),
    onStickerPicked: ((StickerFfi) -> Unit)? = null,
    onStickerPaneOpened: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var stickersSelected by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .alpha(alpha),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (appState != null && onStickerPicked != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !stickersSelected,
                        onClick = { stickersSelected = false },
                        label = { Text(stringResource(R.string.sticker_emoji_tab)) },
                    )
                    FilterChip(
                        selected = stickersSelected,
                        onClick = {
                            if (!stickersSelected) onStickerPaneOpened()
                            stickersSelected = true
                            onSearchActiveChange(false)
                        },
                        label = { Text(stringResource(R.string.sticker_stickers_tab)) },
                    )
                }
            }
            if (stickersSelected && appState != null && onStickerPicked != null) {
                StickerPickerGrid(
                    appState = appState,
                    packs = stickerPacks,
                    onStickerPicked = onStickerPicked,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmojiPickerContent(
                    onEmojiPicked = onEmojiPicked,
                    recordRecentPicks = false,
                    onBackspace = onBackspace,
                    searchStartsOpen = false,
                    onSearchActiveChange = onSearchActiveChange,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StickerPickerGrid(
    appState: WhiteNoiseAppState,
    packs: List<StickerPackFfi>,
    onStickerPicked: (StickerFfi) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (packs.isEmpty()) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.sticker_no_installed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(72.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        packs.forEach { pack ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "pack:${pack.coordinate}") {
                Text(pack.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            }
            items(pack.stickers, key = { "${pack.coordinate}:${it.shortcode}:${it.sha256}" }) { sticker ->
                Surface(
                    modifier = Modifier.aspectRatio(1f).clickable { onStickerPicked(sticker) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    StickerImage(
                        appState = appState,
                        stickerRef = sticker.reference(),
                        contentDescription = sticker.alt ?: sticker.shortcode,
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
        }
    }
}

internal const val EmojiPickerSheetMaxHeightFraction = 0.86f

private const val EmojiPickerSheetPartialDetentFraction = 0.48f

internal fun emojiPickerSheetVisibleContentFraction(expanded: Boolean): Float =
    if (expanded) {
        1f
    } else {
        (EmojiPickerSheetPartialDetentFraction / EmojiPickerSheetMaxHeightFraction).coerceIn(0f, 1f)
    }

internal val ComposerEmojiPickerFallbackHeight = 320.dp

internal val ComposerEmojiPickerSearchExtraHeight = 112.dp

internal fun composerEmojiPaneTargetHeight(
    currentImeHeight: Dp,
    targetImeHeight: Dp,
    rememberedImeHeight: Dp,
): Dp {
    val knownHeight =
        when {
            targetImeHeight > 0.dp -> targetImeHeight
            rememberedImeHeight > 0.dp -> rememberedImeHeight
            else -> currentImeHeight
        }
    return if (knownHeight > 0.dp) knownHeight else ComposerEmojiPickerFallbackHeight
}

internal fun composerEmojiPaneHeight(
    lockedPaneHeight: Dp,
    currentImeHeight: Dp,
    targetImeHeight: Dp,
    rememberedImeHeight: Dp,
): Dp =
    if (lockedPaneHeight > 0.dp) {
        lockedPaneHeight
    } else {
        composerEmojiPaneTargetHeight(currentImeHeight, targetImeHeight, rememberedImeHeight)
    }

internal fun updatedComposerRememberedImeHeight(
    previousRememberedImeHeight: Dp,
    currentImeHeight: Dp,
    freezeUpdates: Boolean,
): Dp =
    if (!freezeUpdates && currentImeHeight > 0.dp) {
        currentImeHeight
    } else {
        previousRememberedImeHeight
    }

/**
 * Whether the IME has finished animating and is resting at a visible height.
 * Handing the bottom region to imePadding before this point moves the
 * composer twice: measured on-device, the keyboard's show animation can aim
 * at a transient overshoot target (its with-toolbar height) that it abandons
 * one frame after arriving, snapping back to the plain height. A swap made
 * mid-animation rides that overshoot up and back — a visible bounce in a
 * perfectly gentle transition. A stale full-height inset right after a hide
 * request (target 0) is rejected by the same check.
 */
internal fun composerImeHasSettled(
    currentImeHeight: Dp,
    imeTargetHeight: Dp,
): Boolean = imeTargetHeight > 0.dp && currentImeHeight == imeTargetHeight

/**
 * Whether a pending attachment-pane restore can hand the bottom region back
 * to imePadding. The attachment pane's minimum height already rides the live
 * inset, so it needs no pane-height matching — only a settled keyboard.
 */
internal fun shouldSwapComposerEmojiPaneToIme(
    keyboardRestorePending: Boolean,
    currentImeHeight: Dp,
    imeTargetHeight: Dp,
): Boolean =
    keyboardRestorePending &&
        composerImeHasSettled(currentImeHeight = currentImeHeight, imeTargetHeight = imeTargetHeight)

internal enum class ComposerPaneRestoreStep {
    /** Keep the pane exactly where it is; the IME is not resting yet. */
    HOLD,

    /** The keyboard settled at a different height; glide the pane to it. */
    MATCH_PANE_TO_KEYBOARD,

    /** Pane and settled keyboard occupy identical space; release the pane. */
    SWAP_TO_KEYBOARD,
}

/**
 * One step of the emoji-pane-to-keyboard handoff. The pane is released only
 * when the keyboard has settled AND the rendered pane occupies exactly the
 * keyboard's space — if the keyboard settles at a different height than the
 * pane reserved (a toolbar row appeared or disappeared, or the pane opened at
 * its fallback height before any keyboard was measured), the pane first
 * animates to the settled height so the swap is always seamless instead of a
 * one-frame jump.
 */
internal fun composerEmojiPaneRestoreStep(
    keyboardRestorePending: Boolean,
    currentImeHeight: Dp,
    imeTargetHeight: Dp,
    lockedPaneHeight: Dp,
    renderedPaneHeight: Dp,
): ComposerPaneRestoreStep =
    when {
        !keyboardRestorePending || !composerImeHasSettled(currentImeHeight, imeTargetHeight) ->
            ComposerPaneRestoreStep.HOLD
        lockedPaneHeight != imeTargetHeight -> ComposerPaneRestoreStep.MATCH_PANE_TO_KEYBOARD
        renderedPaneHeight != imeTargetHeight -> ComposerPaneRestoreStep.HOLD
        else -> ComposerPaneRestoreStep.SWAP_TO_KEYBOARD
    }

/**
 * When the restore window times out the pane is always released — the user
 * asked for the keyboard, so staying on (or returning to) the picker would
 * override that intent. Focus is cleared only when no IME arrived at all; if
 * a keyboard is up at a different height than the pane reserved, it keeps
 * focus and imePadding simply takes over at the keyboard's real height.
 */
internal fun composerKeyboardRestoreTimeoutClearsFocus(currentImeHeight: Dp): Boolean = currentImeHeight == 0.dp

@Composable
private fun EmojiPickerContent(
    onEmojiPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    recordRecentPicks: Boolean = true,
    messageReactionEmojis: List<String> = emptyList(),
    onBackspace: (() -> Unit)? = null,
    onCustomizeReactions: (() -> Unit)? = null,
    searchStartsOpen: Boolean = false,
    searchFieldAlwaysVisible: Boolean = false,
    onSearchActiveChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(searchStartsOpen) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val browseEmoji by produceState(initialValue = emptyList<EmojiEntry>(), context) {
        value = withContext(Dispatchers.IO) { EmojiData.load(context) }
    }
    val searchResults =
        remember(searchQuery, browseEmoji) {
            EmojiData.search(browseEmoji, searchQuery)
        }
    val grouped = remember(browseEmoji) { browseEmoji.groupBy { it.group } }
    val messageReactions =
        remember(messageReactionEmojis) {
            messageReactionEmojis.filter { it.isNotBlank() }.distinct()
        }
    var recents by remember(context) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(context) {
        recents = withContext(Dispatchers.IO) { RecentEmojiPreferences.load(context).filter { it.isNotBlank() } }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var activeCategory by remember(recents.isNotEmpty()) { mutableStateOf(if (recents.isNotEmpty()) -1 else 0) }
    // Grid item index of each section's header, so a bottom category tab scrolls
    // straight to it. Layout: recents (header + cells) then each group (header +
    // cells); every header and every emoji counts as one grid item.
    val sectionHeaderIndex =
        remember(grouped, messageReactions, recents) {
            IntArray(EmojiData.GroupCount).also { arr ->
                var index = if (messageReactions.isEmpty()) 0 else 1 + messageReactions.size
                if (recents.isNotEmpty()) index += 1 + recents.size
                for (group in 0 until EmojiData.GroupCount) {
                    arr[group] = index
                    val count = grouped[group]?.size ?: 0
                    if (count > 0) index += 1 + count
                }
            }
        }
    LaunchedEffect(searchOpen) {
        onSearchActiveChange(searchOpen)
        if (searchOpen) {
            withFrameNanos { }
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            searchQuery = ""
        }
    }
    LaunchedEffect(gridState, sectionHeaderIndex, messageReactions.size, recents.size) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .collect { index ->
                val recentsHeaderIndex = if (messageReactions.isEmpty()) 0 else 1 + messageReactions.size
                activeCategory =
                    if (recents.isNotEmpty() && index >= recentsHeaderIndex && index < (sectionHeaderIndex.firstOrNull() ?: 0)) {
                        -1
                    } else {
                        sectionHeaderIndex
                            .withIndex()
                            .lastOrNull { (_, headerIndex) -> index >= headerIndex }
                            ?.index
                            ?: 0
                    }
            }
    }

    fun pick(emoji: String) {
        if (recordRecentPicks) {
            scope.launch {
                recents = withContext(Dispatchers.IO) { RecentEmojiPreferences.recordPicked(context, emoji).filter { it.isNotBlank() } }
            }
        }
        onEmojiPicked(emoji)
    }

    // Keep the rail out of the weighted grid column so the partial sheet detent
    // reserves it before the emoji grid takes the remaining height.
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            EmojiCategoryRail(
                onCustomizeReactions = onCustomizeReactions,
                showSearch = !searchFieldAlwaysVisible,
                searchSelected = searchOpen,
                onSearch = { searchOpen = true },
                showRecents = recents.isNotEmpty(),
                recentsSelected = activeCategory == -1,
                onRecents = {
                    searchOpen = false
                    keyboardController?.hide()
                    activeCategory = -1
                    scope.launch {
                        gridState.scrollToItem(if (messageReactions.isEmpty()) 0 else 1 + messageReactions.size)
                    }
                },
                selectedGroup = activeCategory,
                onGroup = { group ->
                    searchOpen = false
                    keyboardController?.hide()
                    activeCategory = group
                    scope.launch { gridState.scrollToItem(sectionHeaderIndex[group]) }
                },
                onBackspace = onBackspace,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searchFieldAlwaysVisible || searchOpen) {
                EmojiSearchField(
                    value = searchQuery,
                    onValueChange = {
                        if (searchFieldAlwaysVisible) searchOpen = true
                        searchQuery = it
                    },
                    onClose = {
                        searchOpen = false
                        keyboardController?.hide()
                    },
                    onSearchIntent = { searchOpen = true },
                    focusRequester = searchFocusRequester,
                    showBackButton = !searchFieldAlwaysVisible,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!searchOpen || searchQuery.isBlank()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(9),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (messageReactions.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmojiSectionHeader(stringResource(R.string.emoji_category_this_message))
                        }
                        items(messageReactions, key = { "message-reaction:$it" }) { emoji ->
                            EmojiSearchResultCell(emoji = emoji, onClick = { pick(emoji) })
                        }
                    }
                    if (recents.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmojiSectionHeader(stringResource(R.string.emoji_category_recent))
                        }
                        items(recents, key = { "recent:$it" }) { emoji ->
                            EmojiSearchResultCell(emoji = emoji, onClick = { pick(emoji) })
                        }
                    }
                    for (group in 0 until EmojiData.GroupCount) {
                        val groupEmoji = grouped[group].orEmpty()
                        if (groupEmoji.isEmpty()) continue
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmojiSectionHeader(stringResource(emojiGroupTitleRes(group)))
                        }
                        items(groupEmoji, key = { it.emoji }) { entry ->
                            EmojiSearchResultCell(emoji = entry.emoji, onClick = { pick(entry.emoji) })
                        }
                    }
                }
            } else {
                EmojiSearchResultsGrid(
                    results = searchResults,
                    onEmojiPicked = { pick(it) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmojiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    onSearchIntent: () -> Unit,
    focusRequester: FocusRequester,
    showBackButton: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(64.dp).padding(top = 14.dp, bottom = 6.dp),
        // Transparent so the search row blends with the picker surface in both
        // light and AMOLED themes, instead of a hardcoded dark box that reads
        // as black in light mode.
        color = Color.Transparent,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = if (showBackButton) onClose else onSearchIntent,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (showBackButton) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Search,
                    contentDescription =
                        if (showBackButton) {
                            stringResource(R.string.back)
                        } else {
                            stringResource(R.string.emoji_search_hint)
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.size(26.dp),
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle =
                        LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (it.isFocused) onSearchIntent() },
                )
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.emoji_search_hint),
                        style =
                            LocalTextStyle.current.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.emoji_search_clear),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiSearchResultsGrid(
    results: List<EmojiEntry>,
    onEmojiPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.emoji_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(9),
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(results, key = { it.emoji }) { entry ->
            EmojiSearchResultCell(
                emoji = entry.emoji,
                onClick = { onEmojiPicked(entry.emoji) },
            )
        }
    }
}

@Composable
private fun EmojiSearchResultCell(
    emoji: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun EmojiCategoryRail(
    onCustomizeReactions: (() -> Unit)?,
    showSearch: Boolean,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    showRecents: Boolean,
    recentsSelected: Boolean,
    onRecents: () -> Unit,
    selectedGroup: Int,
    onGroup: (Int) -> Unit,
    onBackspace: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        // No fill and no vertical padding of its own: the rail reads as part
        // of the picker surface, not a separate bar. Each entry is an equal
        // weighted slot so search stays leading and backspace stays trailing.
        modifier = modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCustomizeReactions != null) {
            EmojiRailIconButton(
                onClick = onCustomizeReactions,
                selected = false,
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.customize_reactions),
                modifier = Modifier.weight(1f),
            )
        }
        if (showSearch) {
            EmojiRailIconButton(
                onClick = onSearch,
                selected = searchSelected,
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.emoji_search_hint),
                modifier = Modifier.weight(1f),
            )
        }
        if (showRecents) {
            EmojiRailIconButton(
                onClick = onRecents,
                selected = recentsSelected,
                icon = Icons.Default.History,
                contentDescription = stringResource(R.string.emoji_category_recent),
                modifier = Modifier.weight(1f),
            )
        }
        for (group in 0 until EmojiData.GroupCount) {
            EmojiRailIconButton(
                onClick = { onGroup(group) },
                selected = selectedGroup == group,
                icon = emojiGroupIcon(group),
                contentDescription = stringResource(emojiGroupTitleRes(group)),
                modifier = Modifier.weight(1f),
            )
        }
        if (onBackspace != null) {
            EmojiRailIconButton(
                onClick = onBackspace,
                selected = false,
                icon = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = stringResource(R.string.emoji_backspace),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// Compact circular chip the selected-category highlight paints; small enough
// to fit the narrowest weighted slot when all twelve rail entries are shown.
internal val EmojiRailHighlightSize = 34.dp

internal val EmojiRailIconSize = 22.dp

/**
 * The selected fill is an onSurface overlay rather than a container role or a
 * fixed color: surface-container tokens are all pure black in the AMOLED
 * scheme (so a chip painted with them would vanish), and a hardcoded fill only
 * reads correctly in one theme. A translucent onSurface wash adapts to light,
 * dark, and AMOLED alike.
 */
@Composable
private fun EmojiRailIconButton(
    onClick: () -> Unit,
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    // The chip is a fixed-size circle centered in the weighted slot, so the
    // highlight never stretches into a slot-wide pill. The clickable Surface
    // keeps Material's minimum interactive size, so the touch target stays
    // larger than the visual circle.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                } else {
                    Color.Transparent
                },
            contentColor =
                if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                },
            modifier = Modifier.size(EmojiRailHighlightSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(EmojiRailIconSize),
                )
            }
        }
    }
}

@Composable
private fun EmojiSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
    )
}

private fun emojiGroupIcon(group: Int): ImageVector =
    when (group) {
        0 -> Icons.Default.EmojiEmotions
        1 -> Icons.Default.Person
        2 -> Icons.Default.Pets
        3 -> Icons.Default.Restaurant
        4 -> Icons.Default.DirectionsCar
        5 -> Icons.Default.SportsSoccer
        6 -> Icons.Default.Favorite
        7 -> Icons.Default.Tag
        else -> Icons.Default.Public
    }

@StringRes
private fun emojiGroupTitleRes(group: Int): Int =
    when (group) {
        0 -> R.string.emoji_category_smileys
        1 -> R.string.emoji_category_people
        2 -> R.string.emoji_category_animals
        3 -> R.string.emoji_category_food
        4 -> R.string.emoji_category_travel
        5 -> R.string.emoji_category_activities
        6 -> R.string.emoji_category_objects
        7 -> R.string.emoji_category_symbols
        else -> R.string.emoji_category_flags
    }
