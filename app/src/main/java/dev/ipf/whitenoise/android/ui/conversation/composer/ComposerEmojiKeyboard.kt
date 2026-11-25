@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.EmojiCategory
import dev.ipf.whitenoise.android.ui.EmojiPickerTab
import dev.ipf.whitenoise.android.ui.EmojiSection

/** Matches the pane's height glide closely enough that the search and the grid trade places in one motion. */
private const val COMPOSER_EMOJI_SEARCH_CROSSFADE_MILLIS = 250

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
    activeCategory: EmojiPickerTab?,
    selectionEnabled: Boolean,
    onPick: (String) -> Unit,
    onCategory: (EmojiPickerTab) -> Unit,
    onBackspace: (() -> Unit)?,
    onSearchActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onBrowseSets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
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
                EmojiCategoryBar(
                    categories = sections.map { it.category },
                    selected = activeCategory,
                    state = categoryState,
                    onSearch = search::open,
                    onConfigure = null,
                    onBackspace = onBackspace,
                    onCategory = onCategory,
                    onBrowseSets = onBrowseSets,
                    onOpenSettings = onOpenSettings,
                    // The pane sits inside the composer card, which has no rounded corners to clear.
                    edgeInset = 0.dp,
                )
            }
        }
    }
}

/**
 * A focus request emits its own focus callback. Ignore that callback while a
 * pane-to-IME handoff is already running so it cannot start a second keyboard
 * request and a second transcript re-anchor.
 */
internal fun shouldStartComposerKeyboardRestore(
    paneOpen: Boolean,
    keyboardRestorePending: Boolean,
): Boolean = paneOpen && !keyboardRestorePending
