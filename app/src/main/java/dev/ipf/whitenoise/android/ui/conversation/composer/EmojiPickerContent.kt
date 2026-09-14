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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiCategory
import dev.ipf.whitenoise.android.ui.EmojiData
import dev.ipf.whitenoise.android.ui.EmojiEntry
import dev.ipf.whitenoise.android.ui.EmojiSection
import dev.ipf.whitenoise.android.ui.emojiBrowseSections
import dev.ipf.whitenoise.android.ui.emojiSearchSections
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val EMOJI_PICKER_SEARCH_TEST_TAG = "emoji.picker.search"
internal const val EMOJI_PICKER_GRID_TEST_TAG = "emoji.picker.grid"

internal fun emojiPickerHeaderTestTag(category: EmojiCategory): String = "emoji.picker.header.${category.id}"

internal fun emojiPickerItemTestTag(
    category: EmojiCategory,
    index: Int,
): String = "emoji.picker.item.${category.id}.$index"

internal val EmojiPickerMinimumCellSize = 48.dp
internal val EmojiPickerEmojiSize = 32.dp
private val EmojiPickerSectionHeaderMinimumHeight = 36.dp

private data class EmojiSectionRange(
    val category: EmojiCategory,
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
}

@Composable
private fun rememberEmojiPickerModel(
    query: String,
    recentEmojis: List<String>,
): EmojiPickerModel {
    val context = LocalContext.current
    val entries by produceState(initialValue = emptyList<EmojiEntry>(), context) {
        value = withContext(Dispatchers.IO) { EmojiData.load(context) }
    }
    val recents = remember(recentEmojis) { recentEmojis.filter { it.isNotBlank() }.distinct() }
    val browseSections = remember(entries, recents) { emojiBrowseSections(entries, recents) }
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
    return remember(browseSections, searchSections, searching, searchedQuery) {
        EmojiPickerModel(
            sections = if (searching) searchSections else browseSections,
            searching = searching,
            searchedQuery = searchedQuery,
        )
    }
}

/**
 * Prototype emoji picker: search field, adaptive grid of 48dp cells with section headers, and
 * (while not searching) a divider plus the category bar. Shared by the reaction sheet, the
 * composer pane and the group image picker.
 */
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
) {
    var query by rememberSaveable { mutableStateOf("") }
    val model = rememberEmojiPickerModel(query, recentEmojis)
    val gridState = rememberLazyGridState()
    val categoryState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val activeCategory by remember(gridState, model) {
        derivedStateOf {
            model.sectionRanges.lastOrNull { it.firstItemIndex <= gridState.firstVisibleItemIndex }?.category
                ?: model.sections.firstOrNull()?.category
        }
    }
    EmojiPickerScrollSync(model, activeCategory, gridState, categoryState)

    fun pick(emoji: String) {
        if (!selectionEnabled) return
        if (purpose == EmojiPickerPurpose.USE) onEmojiUsed(emoji)
        onEmojiPicked(emoji)
    }

    Column(modifier) {
        EmojiSearchField(
            value = query,
            onValueChange = { query = it },
            onFocusChanged = onSearchActiveChange,
            modifier = Modifier.fillMaxWidth(),
        )
        if (model.searching && model.searchedQuery == query && model.sections.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.emoji_search_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            EmojiSectionGrid(
                sections = model.sections,
                state = gridState,
                selectionEnabled = selectionEnabled,
                onPick = ::pick,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        if (!model.searching) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EmojiCategoryBar(
                categories = model.sections.map { it.category },
                selected = activeCategory,
                state = categoryState,
                onConfigure = onConfigure,
                onBackspace = onBackspace,
                onCategory = { category ->
                    val itemIndex =
                        model.sectionRanges.firstOrNull { it.category == category }?.firstItemIndex
                            ?: return@EmojiCategoryBar
                    scope.launch { gridState.animateScrollToItem(itemIndex) }
                },
            )
        }
    }
}

/** A new search result set starts at the top; in browse mode the category bar follows the visible section. */
@Composable
private fun EmojiPickerScrollSync(
    model: EmojiPickerModel,
    activeCategory: EmojiCategory?,
    gridState: LazyGridState,
    categoryState: LazyListState,
) {
    LaunchedEffect(model.searchedQuery) {
        if (model.sections.isNotEmpty()) gridState.scrollToItem(0)
    }
    LaunchedEffect(activeCategory, model.searching) {
        if (!model.searching) {
            val index = model.sections.indexOfFirst { it.category == activeCategory }
            if (index >= 0) categoryState.animateScrollToItem(index)
        }
    }
}

@Composable
private fun EmojiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .padding(
                    start = WhiteNoiseSpacing.CompactScreenMargin,
                    end = WhiteNoiseSpacing.CompactScreenMargin,
                    bottom = WhiteNoiseSpacing.Related,
                ).onFocusChanged { onFocusChanged(it.isFocused) }
                .testTag(EMOJI_PICKER_SEARCH_TEST_TAG),
        placeholder = { Text(stringResource(R.string.emoji_search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon =
            if (value.isNotEmpty()) {
                {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.emoji_search_clear),
                        )
                    }
                }
            } else {
                null
            },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = container,
                unfocusedContainerColor = container,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
    )
}

@Composable
private fun EmojiSectionGrid(
    sections: List<EmojiSection>,
    state: LazyGridState,
    selectionEnabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = EmojiPickerMinimumCellSize),
        state = state,
        modifier = modifier.testTag(EMOJI_PICKER_GRID_TEST_TAG),
        contentPadding =
            PaddingValues(
                start = WhiteNoiseSpacing.CompactScreenMargin,
                end = WhiteNoiseSpacing.CompactScreenMargin,
                bottom = WhiteNoiseSpacing.Related,
            ),
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

@Composable
private fun EmojiSectionHeader(category: EmojiCategory) {
    Text(
        text = stringResource(category.titleRes),
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
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(EmojiPickerMinimumCellSize),
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                EmojiGlyph(emoji)
            }
        }
    }
}

/** A system-font emoji fitted into a fixed square, the way the prototype draws its 32dp sprites. */
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
