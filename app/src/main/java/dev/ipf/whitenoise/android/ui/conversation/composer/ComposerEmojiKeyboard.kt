@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiCategory
import dev.ipf.whitenoise.android.ui.EmojiSection
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.Backspace
import dev.ipf.whitenoise.android.ui.theme.outlineSelectionColor

internal const val EMOJI_PICKER_SEARCH_PULL_TEST_TAG = "emoji.picker.search_pull"

private val ComposerEmojiTopRowPadding = 4.dp
private val ComposerEmojiRailPadding = 8.dp
private val ComposerEmojiRailSpacing = 4.dp
private val ComposerEmojiRailToggleSize = 36.dp
private val ComposerEmojiRailToggleShape = RoundedCornerShape(12.dp)
private val ComposerEmojiRailIconSize = 22.dp
private val ComposerEmojiRailScrollThreshold = 4.dp
private val ComposerEmojiPullFieldSearchIconSize = 20.dp

/**
 * Top row of the composer's emoji keyboard: search at the start, backspace at the end. The search
 * action fades out while the pull-down field at the top of the grid is on screen.
 */
@Composable
internal fun ComposerEmojiTopRow(
    searchFieldRevealed: Boolean,
    onSearch: () -> Unit,
    onBackspace: (() -> Unit)?,
) {
    val searchAlpha by animateFloatAsState(if (searchFieldRevealed) 0f else 1f, label = "emojiSearchAlpha")
    val iconColors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ComposerEmojiTopRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSearch,
            enabled = !searchFieldRevealed,
            colors = iconColors,
            modifier = Modifier.alpha(searchAlpha).testTag(EMOJI_PICKER_SEARCH_OPEN_TEST_TAG),
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.emoji_search_hint))
        }
        Spacer(Modifier.weight(1f))
        if (onBackspace != null) {
            IconButton(onClick = onBackspace, colors = iconColors) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.emoji_backspace))
            }
        }
    }
}

/**
 * The composer's emoji keyboard. Browsing shows a search and backspace row, the section grid with a
 * pull-down search field above its first section, and a category rail that slides away while the grid
 * scrolls down. Searching shows the field and, while the system keyboard is up, one row of matches
 * under it, or recents before anything is typed.
 */
@Suppress("LongParameterList")
@Composable
internal fun ComposerEmojiKeyboard(
    search: EmojiSearchState,
    sections: List<EmojiSection>,
    searching: Boolean,
    noResults: Boolean,
    searchRow: Boolean,
    gridState: LazyGridState,
    rowState: LazyListState,
    categoryState: LazyListState,
    activeCategory: EmojiCategory?,
    selectionEnabled: Boolean,
    onPick: (String) -> Unit,
    onCategory: (EmojiCategory) -> Unit,
    onBackspace: (() -> Unit)?,
    onSearchActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (search.isOpen) {
            EmojiSearchField(search, onSearchActiveChange, Modifier.fillMaxWidth())
        } else {
            val pullFieldVisible by remember(gridState) { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
            ComposerEmojiTopRow(
                searchFieldRevealed = pullFieldVisible,
                onSearch = search::open,
                onBackspace = onBackspace,
            )
        }
        if (searchRow) {
            val rowSections =
                if (searching) sections else sections.filter { it.category == EmojiCategory.Recent }
            EmojiSearchRow(rowSections, noResults, rowState, selectionEnabled, onPick = onPick)
            return@Column
        }
        if (search.isOpen) {
            // The system keyboard is on its way up or down; the grid keeps the pane's space meanwhile.
            EmojiPickerResults(
                sections = sections,
                noResults = noResults,
                gridState = gridState,
                selectionEnabled = selectionEnabled,
                onPick = onPick,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            return@Column
        }
        val atTop by remember(gridState) { derivedStateOf { !gridState.canScrollBackward } }
        ComposerEmojiRailLayout(
            contentAtTop = atTop,
            rail = {
                ComposerEmojiRail(sections.map { it.category }, activeCategory, categoryState, onCategory)
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { railPadding ->
            EmojiSectionGrid(
                sections = sections,
                state = gridState,
                selectionEnabled = selectionEnabled,
                onPick = onPick,
                contentPadding =
                    PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp + railPadding),
                leadingItem = { ComposerEmojiPullDownSearchField(onClick = search::open) },
            )
        }
    }
}

/** Pill-shaped search entry that sits above the first section and opens the search when tapped. */
@Composable
internal fun ComposerEmojiPullDownSearchField(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ComposerEmojiRailPadding, vertical = ComposerEmojiRailSpacing)
                .testTag(EMOJI_PICKER_SEARCH_PULL_TEST_TAG),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_search),
                contentDescription = null,
                modifier = Modifier.size(ComposerEmojiPullFieldSearchIconSize),
            )
            Text(stringResource(R.string.emoji_search_hint), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        }
    }
}

/** Category rail along the bottom of the composer's emoji keyboard, one rounded toggle per section. */
@Composable
internal fun ComposerEmojiRail(
    categories: List<EmojiCategory>,
    selected: EmojiCategory?,
    state: LazyListState,
    onCategory: (EmojiCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            state = state,
            modifier =
                Modifier
                    .padding(horizontal = ComposerEmojiRailPadding)
                    .testTag(EMOJI_PICKER_CATEGORIES_TEST_TAG),
            horizontalArrangement = Arrangement.spacedBy(ComposerEmojiRailSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(categories, key = { it.id }) { category ->
                ComposerEmojiRailToggle(category, selected == category) { onCategory(category) }
            }
        }
    }
}

@Composable
private fun ComposerEmojiRailToggle(
    category: EmojiCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        shape = ComposerEmojiRailToggleShape,
        colors =
            IconButtonDefaults.iconToggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                checkedContainerColor = outlineSelectionColor(MaterialTheme.colorScheme.primaryContainer),
                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        modifier =
            Modifier
                .padding(vertical = ComposerEmojiRailPadding)
                .size(ComposerEmojiRailToggleSize)
                .semantics { this.selected = selected }
                .testTag(emojiPickerCategoryTestTag(category)),
    ) {
        Icon(
            painter = painterResource(category.iconRes),
            contentDescription = stringResource(category.titleRes),
            modifier = Modifier.size(ComposerEmojiRailIconSize),
        )
    }
}

/**
 * Keeps [rail] pinned to the bottom of [content]. Scrolling the content down slides the rail away and
 * scrolling back up brings it back; it always shows while [contentAtTop]. [content] receives the
 * bottom padding that keeps its last row clear of the visible part of the rail.
 */
@Composable
internal fun ComposerEmojiRailLayout(
    contentAtTop: Boolean,
    rail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(bottomPadding: Dp) -> Unit,
) {
    val threshold = with(LocalDensity.current) { ComposerEmojiRailScrollThreshold.toPx() }
    var railHidden by remember { mutableStateOf(false) }
    var railHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(contentAtTop) { if (contentAtTop) railHidden = false }
    val hiddenFraction by animateFloatAsState(if (railHidden) 1f else 0f, label = "emojiRailHidden")
    val connection =
        remember(threshold) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        if (consumed.y < -threshold) {
                            railHidden = true
                        } else if (consumed.y + available.y.coerceAtLeast(0f) > threshold) {
                            railHidden = false
                        }
                    }
                    return Offset.Zero
                }
            }
        }
    val railHeight = with(LocalDensity.current) { railHeightPx.toDp() }
    Box(modifier = modifier.fillMaxSize().clipToBounds().nestedScroll(connection)) {
        content(railHeight * (1f - hiddenFraction))
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { railHeightPx = it.height }
                    .graphicsLayer { translationY = railHeightPx * hiddenFraction },
        ) {
            rail()
        }
    }
}
