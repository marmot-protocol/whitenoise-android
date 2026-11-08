@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val ComposerEmojiRailPadding = 4.dp

/** Matches the pane's height glide closely enough that the search and the grid trade places in one motion. */
private const val COMPOSER_EMOJI_SEARCH_CROSSFADE_MILLIS = 250
private val ComposerEmojiRailToggleSize = 32.dp
private val ComposerEmojiRailToggleShape = RoundedCornerShape(10.dp)
private val ComposerEmojiRailIconSize = 20.dp

/**
 * The composer's emoji keyboard. Browsing fills the pane with the section grid above a bottom rail:
 * search in the bottom-start corner, the category toggles spread evenly across the middle and
 * backspace at the end. Search crossfades to the field with one row of matches under it, or recents
 * before anything is typed; the keyboard covers the rest of the pane meanwhile.
 */
@Suppress("LongParameterList")
@Composable
internal fun ComposerEmojiKeyboard(
    search: EmojiSearchState,
    sections: List<EmojiSection>,
    searching: Boolean,
    noResults: Boolean,
    onSearchContentHeightChange: (Dp) -> Unit,
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
    val density = LocalDensity.current
    Crossfade(
        targetState = search.isOpen,
        modifier = modifier,
        animationSpec = tween(durationMillis = COMPOSER_EMOJI_SEARCH_CROSSFADE_MILLIS),
        label = "composerEmojiSearch",
    ) { searchOpen ->
        if (searchOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onSearchContentHeightChange(with(density) { it.height.toDp() }) },
            ) {
                EmojiSearchField(search, onSearchActiveChange, Modifier.fillMaxWidth())
                val rowSections =
                    if (searching) sections else sections.filter { it.category == EmojiCategory.Recent }
                EmojiSearchRow(rowSections, noResults, rowState, selectionEnabled, onPick = onPick)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                EmojiSectionGrid(
                    sections = sections,
                    state = gridState,
                    selectionEnabled = selectionEnabled,
                    onPick = onPick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                ComposerEmojiRail(
                    categories = sections.map { it.category },
                    selected = activeCategory,
                    state = categoryState,
                    onSearch = search::open,
                    onCategory = onCategory,
                    onBackspace = onBackspace,
                )
            }
        }
    }
}

/**
 * Bottom rail of the composer's emoji keyboard: search at the start, one rounded toggle per section
 * in equal-width slots, and backspace at the end. The toggles scroll only when they cannot fit.
 */
@Suppress("LongParameterList")
@Composable
internal fun ComposerEmojiRail(
    categories: List<EmojiCategory>,
    selected: EmojiCategory?,
    state: LazyListState,
    onSearch: () -> Unit,
    onCategory: (EmojiCategory) -> Unit,
    onBackspace: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val iconColors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ComposerEmojiRailPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSearch,
            colors = iconColors,
            modifier = Modifier.testTag(EMOJI_PICKER_SEARCH_OPEN_TEST_TAG),
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.emoji_search_hint))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val slot = maxOf(ComposerEmojiRailToggleSize, maxWidth / categories.size.coerceAtLeast(1))
            LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth().testTag(EMOJI_PICKER_CATEGORIES_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(categories, key = { it.id }) { category ->
                    Box(Modifier.width(slot), contentAlignment = Alignment.Center) {
                        ComposerEmojiRailToggle(category, selected == category) { onCategory(category) }
                    }
                }
            }
        }
        if (onBackspace != null) {
            IconButton(onClick = onBackspace, colors = iconColors) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.emoji_backspace))
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
