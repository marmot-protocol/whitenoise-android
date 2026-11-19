@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.EmojiData
import dev.ipf.whitenoise.android.ui.EmojiEntry
import dev.ipf.whitenoise.android.ui.EmojiPickerTab
import dev.ipf.whitenoise.android.ui.EmojiSection
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.LocalHiddenBuiltinEmojiSets
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyVerticalGrid
import dev.ipf.whitenoise.android.ui.customEmojiSearchSection
import dev.ipf.whitenoise.android.ui.emojiBrowseSections
import dev.ipf.whitenoise.android.ui.emojiPlainLabel
import dev.ipf.whitenoise.android.ui.emojiSearchLayout
import dev.ipf.whitenoise.android.ui.emojiSearchSections
import dev.ipf.whitenoise.android.ui.hides
import dev.ipf.whitenoise.android.ui.installedSetSearchSections
import dev.ipf.whitenoise.android.ui.libraryEmojiArtwork
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val EMOJI_PICKER_SEARCH_TEST_TAG = "emoji.picker.search"
internal const val EMOJI_PICKER_GRID_TEST_TAG = "emoji.picker.grid"

/** Stable test tag for an emoji section header. */
internal fun emojiPickerHeaderTestTag(category: EmojiPickerTab): String = "emoji.picker.header.${category.id}"

/** Stable test tag for an emoji cell. */
internal fun emojiPickerItemTestTag(
    category: EmojiPickerTab,
    index: Int,
): String = "emoji.picker.item.${category.id}.$index"

internal val EmojiPickerMinimumCellSize = 48.dp
internal val EmojiPickerEmojiSize = 32.dp
private val EmojiPickerSectionHeaderMinimumHeight = 36.dp

private data class EmojiSectionRange(
    val category: EmojiPickerTab,
    val firstItemIndex: Int,
)

/** Grid index of every section header: each header and each emoji is one grid item. */
private fun emojiSectionRanges(sections: List<EmojiSection>): List<EmojiSectionRange> {
    var itemIndex = 0
    return sections.map { section ->
        EmojiSectionRange(section.category, itemIndex).also { itemIndex += section.emoji.size + 1 }
    }
}

private class EmojiPickerModel(
    val sections: List<EmojiSection>,
    val searching: Boolean,
    val searchedQuery: String,
) {
    val sectionRanges = emojiSectionRanges(sections)

    /** Grid index of [category]'s section header, or null when the category is not shown. */
    fun firstItemIndex(category: EmojiPickerTab): Int? {
        val range = sectionRanges.firstOrNull { it.category == category }
        return range?.firstItemIndex
    }
}

/** Filtered sections for the query with the user's emoji and then recents first. */
@Composable
private fun rememberEmojiPickerModel(
    query: String,
    recentEmojis: List<String>,
    purpose: EmojiPickerPurpose,
): EmojiPickerModel {
    val context = LocalContext.current
    val loadedEntries by produceState(initialValue = emptyList<EmojiEntry>(), context, purpose) {
        value =
            withContext(Dispatchers.IO) {
                val loaded = EmojiData.load(context)
                if (purpose == EmojiPickerPurpose.GROUP_IMAGE) {
                    loaded.filter { !EmojiShortcodes.isShortcode(it.emoji) }
                } else {
                    loaded
                }
            }
    }
    // Removed pre-installed sets leave the picker and search; their emoji still render in messages.
    val hidden = LocalHiddenBuiltinEmojiSets.current
    val entries = remember(loadedEntries, hidden) { loadedEntries.filterNot { hidden.hides(it.emoji) } }
    // Group images are drawn from a system-font glyph, so artwork emoji cannot become one.
    val customEmoji = LocalCustomEmoji.current
    val pickerSets = remember(customEmoji, purpose, hidden) { emojiPickerSets(customEmoji, purpose, hidden) }
    val custom = pickerSets.custom
    val recents =
        remember(recentEmojis, purpose, customEmoji, hidden) {
            pickerRecents(recentEmojis, purpose, customEmoji, hidden)
        }
    val browseSections =
        remember(entries, recents, pickerSets) { emojiBrowseSections(entries, recents, custom, pickerSets.sets) }
    var searchSections by remember { mutableStateOf<List<EmojiSection>>(emptyList()) }
    var searchedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query, entries) {
        searchSections =
            if (query.isBlank()) {
                emptyList()
            } else {
                withContext(Dispatchers.Default) { emojiSearchSections(EmojiData.search(entries, query)) }
            }
        searchedQuery = query
    }
    val searching = query.isNotBlank()
    return remember(browseSections, searchSections, searching, searchedQuery, pickerSets) {
        EmojiPickerModel(
            sections =
                if (searching) {
                    emojiSearchLayout(
                        customEmojiSearchSection(custom, searchedQuery),
                        searchSections,
                        installedSetSearchSections(pickerSets.sets, searchedQuery),
                    )
                } else {
                    browseSections
                },
            searching = searching,
            searchedQuery = searchedQuery,
        )
    }
}

/**
 * Prototype emoji picker: search field, adaptive grid of 48dp cells with section headers, and
 * (while not searching) a divider plus the category bar. Shared by the reaction sheet, the
 * composer pane and the group image picker.
 *
 * [searchBehindIcon] selects the composer layout ([ComposerEmojiKeyboard]): the search field stays hidden
 * until the rail's search action opens it, and closing the field or moving focus away
 * returns to browsing. Its search shows one row of matches under the field, and
 * [onSearchContentHeightChange] reports their height so the composer can make room above the keyboard.
 */
@Suppress("LongMethod") // Owns the picker state shared by the composer and sheet layouts.
@Composable
internal fun EmojiPickerContent(
    onEmojiPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    purpose: EmojiPickerPurpose = EmojiPickerPurpose.USE,
    recentEmojis: List<String> = emptyList(),
    onEmojiUsed: (String) -> Unit = {},
    onBackspace: (() -> Unit)? = null,
    onConfigure: (() -> Unit)? = null,
    onSearchActiveChange: (Boolean) -> Unit = {},
    selectionEnabled: Boolean = true,
    searchBehindIcon: Boolean = false,
    onSearchContentHeightChange: (Dp) -> Unit = {},
    onBrowseSets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val search = rememberEmojiSearchState(searchBehindIcon)
    val query = search.query
    val model = rememberEmojiPickerModel(query, recentEmojis, purpose)
    val gridState = rememberLazyGridState()
    val rowState = rememberLazyListState()
    val categoryState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val activeCategory by remember(gridState, model) {
        derivedStateOf {
            model.sectionRanges.lastOrNull { it.firstItemIndex <= gridState.firstVisibleItemIndex }?.category
                ?: model.sections.firstOrNull()?.category
        }
    }
    EmojiPickerScrollSync(
        model,
        activeCategory,
        if (searchBehindIcon) rowState else null,
        gridState,
        categoryState,
    )
    EmojiSearchEffects(search)

    // The existing recents store is device-wide. Keep account-owned set codes out of it.
    val emojiLibrary = LocalCustomEmoji.current
    val pick: (String) -> Unit = { emoji ->
        if (selectionEnabled) {
            if (purpose != EmojiPickerPurpose.CONFIGURE_QUICK_REACTION &&
                !(emojiLibrary.usesInstalledArtwork(emoji) || emojiLibrary.usesConversationArtwork(emoji))
            ) {
                onEmojiUsed(emoji)
            }
            onEmojiPicked(emoji)
        }
    }
    val noResults = model.searching && model.searchedQuery == query && model.sections.isEmpty()
    val categories = model.sections.map { it.category }
    val onCategory: (EmojiPickerTab) -> Unit = { category ->
        model.firstItemIndex(category)?.let { scope.launch { gridState.animateScrollToItem(it) } }
    }
    if (searchBehindIcon) {
        ComposerEmojiKeyboard(
            search = search,
            sections = model.sections,
            searching = model.searching,
            noResults = noResults,
            onSearchContentHeightChange = onSearchContentHeightChange,
            gridState = gridState,
            rowState = rowState,
            categoryState = categoryState,
            activeCategory = activeCategory,
            selectionEnabled = selectionEnabled,
            onPick = pick,
            onCategory = onCategory,
            onBackspace = onBackspace,
            onSearchActiveChange = onSearchActiveChange,
            modifier = modifier,
            onBrowseSets = onBrowseSets,
            onOpenSettings = onOpenSettings,
        )
        return
    }
    Column(modifier) {
        if (search.isOpen) EmojiSearchField(search, onSearchActiveChange, Modifier.fillMaxWidth())
        EmojiPickerResults(
            sections = model.sections,
            noResults = noResults,
            gridState = gridState,
            selectionEnabled = selectionEnabled,
            onPick = pick,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (!model.searching) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EmojiCategoryBar(
                categories = categories,
                selected = activeCategory,
                state = categoryState,
                onSearch = null,
                onConfigure = onConfigure,
                onBackspace = onBackspace,
                onCategory = onCategory,
            )
        }
    }
}

/** Grid of matches or browse sections, or a centred notice when a search finds nothing. */
@Composable
internal fun EmojiPickerResults(
    sections: List<EmojiSection>,
    noResults: Boolean,
    gridState: LazyGridState,
    selectionEnabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (noResults) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.emoji_search_no_results),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        EmojiSectionGrid(
            sections = sections,
            state = gridState,
            selectionEnabled = selectionEnabled,
            onPick = onPick,
            modifier = modifier,
        )
    }
}

/**
 * A new search result set starts at the top of the grid, or at the start of the search row when one
 * is shown; in browse mode the category bar follows the visible section.
 */
@Composable
private fun EmojiPickerScrollSync(
    model: EmojiPickerModel,
    activeCategory: EmojiPickerTab?,
    rowState: LazyListState?,
    gridState: LazyGridState,
    categoryState: LazyListState,
) {
    LaunchedEffect(model.searchedQuery, rowState) {
        when {
            rowState != null -> rowState.scrollToItem(0)
            model.sections.isNotEmpty() -> gridState.scrollToItem(0)
        }
    }
    LaunchedEffect(activeCategory, model.searching) {
        if (!model.searching) {
            val index = model.sections.indexOfFirst { it.category == activeCategory }
            if (index >= 0) categoryState.animateScrollToItem(index)
        }
    }
}

/** Grid of emoji sections with sticky headers. */
@Composable
internal fun EmojiSectionGrid(
    sections: List<EmojiSection>,
    state: LazyGridState,
    selectionEnabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues =
        PaddingValues(
            start = WhiteNoiseSpacing.CompactScreenMargin,
            end = WhiteNoiseSpacing.CompactScreenMargin,
            bottom = WhiteNoiseSpacing.Related,
        ),
) {
    WhiteNoiseLazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = EmojiPickerMinimumCellSize),
        state = state,
        modifier = modifier.testTag(EMOJI_PICKER_GRID_TEST_TAG),
        contentPadding = contentPadding,
    ) {
        sections.forEach { section ->
            item(key = "${section.category.id}:header", span = { GridItemSpan(maxLineSpan) }) {
                EmojiSectionHeader(section.category)
            }
            itemsIndexed(
                section.emoji,
                key = { index, emoji -> "${section.category.id}:$index:$emoji" },
            ) { index, emoji ->
                EmojiCell(
                    emoji = emoji,
                    enabled = selectionEnabled,
                    onClick = { onPick(emoji) },
                    modifier = Modifier.testTag(emojiPickerItemTestTag(section.category, index)),
                )
            }
        }
    }
}

/** Header text of one emoji section. */
@Composable
private fun EmojiSectionHeader(category: EmojiPickerTab) {
    Text(
        text = emojiPickerTabTitle(category),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = EmojiPickerSectionHeaderMinimumHeight)
                .padding(top = WhiteNoiseSpacing.Related)
                .semantics { heading() }
                .testTag(emojiPickerHeaderTestTag(category)),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

/** One tappable emoji cell. */
@Composable
private fun EmojiCell(
    emoji: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(EmojiPickerMinimumCellSize),
        contentAlignment = Alignment.Center,
    ) {
        EmojiTapTarget(emoji = emoji, enabled = enabled, onClick = onClick, modifier = modifier)
    }
}

/** Shortcode artwork or a system-font emoji fitted into the picker's fixed square. */
@Composable
internal fun EmojiGlyph(
    emoji: String,
    modifier: Modifier = Modifier,
    size: Dp = EmojiPickerEmojiSize,
) {
    val baseStyle = MaterialTheme.typography.headlineMedium
    val (fontSize, lineHeight) =
        emojiPickerCellTextMetrics(
            cellSizeDp = size,
            baseStyle = baseStyle,
            densityFontScale = LocalDensity.current.fontScale,
        )
    val customEmoji = LocalCustomEmoji.current
    val art =
        if (customEmoji.usesInstalledArtwork(emoji) || customEmoji.usesConversationArtwork(emoji)) {
            libraryEmojiArtwork(emoji)
        } else {
            EmojiShortcodes.art(emoji, customEmoji, emptyMap())
        }
    if (art != null) {
        // Artwork takes the Unicode glyph's em box, so every emoji in a row reads at one size.
        val artSize = with(LocalDensity.current) { emojiGlyphArtworkSize(fontSize, size) }
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            EmojiArtImage(art, contentDescription = emojiPlainLabel(emoji), modifier = Modifier.size(artSize))
        }
        return
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = emoji,
            style = baseStyle,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            softWrap = false,
        )
    }
}
