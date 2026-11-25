@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiPickerTab
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.Backspace
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.outlineSelectionColor

internal const val EMOJI_PICKER_CATEGORIES_TEST_TAG = "emoji.picker.categories"
internal const val EMOJI_PICKER_CONFIGURE_TEST_TAG = "emoji.picker.configure"
internal const val EMOJI_PICKER_SEARCH_OPEN_TEST_TAG = "emoji.picker.search_open"
internal const val EMOJI_PICKER_BROWSE_SETS_TEST_TAG = "emoji.picker.browse_sets"
internal const val EMOJI_PICKER_SETTINGS_TEST_TAG = "emoji.picker.settings"

/** Stable test tag for an emoji category toggle. */
internal fun emojiPickerCategoryTestTag(category: EmojiPickerTab): String = "emoji.picker.category.${category.id}"

private val EmojiCategoryBarMinimumHeight = 56.dp
private val EmojiCategoryToggleSize = 48.dp
private val EmojiCategorySelectionSize = 36.dp
private val EmojiCategoryIconSize = 22.dp

/** Default gap between the bar's outer buttons and a panel's rounded bottom corners. */
private val EmojiCategoryBarEdgeInset = 12.dp

/**
 * Bottom bar of the emoji picker: optional search and configure actions, one toggle per visible
 * category in equal-width slots with a 36dp selection disc, and an optional backspace action for the composer pane.
 */
@Composable
internal fun EmojiCategoryBar(
    categories: List<EmojiPickerTab>,
    selected: EmojiPickerTab?,
    state: LazyListState,
    onSearch: (() -> Unit)?,
    onConfigure: (() -> Unit)?,
    onBackspace: (() -> Unit)?,
    onCategory: (EmojiPickerTab) -> Unit,
    onBrowseSets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    edgeInset: Dp = EmojiCategoryBarEdgeInset,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = EmojiCategoryBarMinimumHeight)
                    .testTag(EMOJI_PICKER_CATEGORIES_TEST_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val leadingActions = listOf(onSearch, onConfigure, onBrowseSets, onOpenSettings).any { it != null }
            if (leadingActions) {
                EmojiCategoryBarLeadingActions(edgeInset, onSearch, onConfigure, onBrowseSets, onOpenSettings)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                // Equal-width slots spread the toggles; they scroll only when they cannot all fit.
                val startPadding = if (leadingActions) 0.dp else WhiteNoiseSpacing.Related
                val endPadding = if (onBackspace == null) WhiteNoiseSpacing.Related else 0.dp
                val slot =
                    maxOf(
                        EmojiCategoryToggleSize,
                        (maxWidth - startPadding - endPadding) / categories.size.coerceAtLeast(1),
                    )
                LazyRow(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = startPadding, end = endPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(categories, key = { it.id }) { category ->
                        Box(Modifier.width(slot), contentAlignment = Alignment.Center) {
                            EmojiCategoryToggle(
                                category = category,
                                selected = selected == category,
                                onClick = { onCategory(category) },
                            )
                        }
                    }
                }
            }
            if (onBackspace != null) {
                IconButton(
                    onClick = onBackspace,
                    modifier = Modifier.padding(end = edgeInset),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = stringResource(R.string.emoji_backspace),
                    )
                }
            }
        }
    }
}

/** Search and configure actions at the start of the category bar. */
@Composable
private fun EmojiCategoryBarLeadingActions(
    edgeInset: Dp,
    onSearch: (() -> Unit)?,
    onConfigure: (() -> Unit)?,
    onBrowseSets: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    Spacer(Modifier.width(edgeInset))
    if (onSearch != null) {
        IconButton(
            onClick = onSearch,
            modifier =
                Modifier
                    .testTag(EMOJI_PICKER_SEARCH_OPEN_TEST_TAG)
                    .explainOnLongPress(
                        composerExplanation(
                            R.string.emoji_search_hint,
                            R.string.explanation_emoji_search,
                        ),
                    ),
        ) {
            Icon(
                painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.emoji_search_hint),
            )
        }
    }
    if (onBrowseSets != null) {
        IconButton(
            onClick = onBrowseSets,
            modifier = Modifier.testTag(EMOJI_PICKER_BROWSE_SETS_TEST_TAG),
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.emoji_sets_title))
        }
    }
    if (onOpenSettings != null) {
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag(EMOJI_PICKER_SETTINGS_TEST_TAG),
        ) {
            Icon(
                painterResource(R.drawable.ic_emoji_settings),
                contentDescription = stringResource(R.string.custom_emoji),
            )
        }
    }
    if (onConfigure != null) {
        IconButton(
            onClick = onConfigure,
            modifier =
                Modifier
                    .testTag(EMOJI_PICKER_CONFIGURE_TEST_TAG)
                    .explainOnLongPress(
                        composerExplanation(
                            R.string.configure_reactions,
                            R.string.explanation_emoji_configure,
                        ),
                    ),
        ) {
            Icon(
                painterResource(R.drawable.ic_emoji_settings),
                contentDescription = stringResource(R.string.configure_reactions),
            )
        }
    }
}

/** One category glyph toggle in the bar. */
@Composable
private fun EmojiCategoryToggle(
    category: EmojiPickerTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier =
            Modifier
                .size(EmojiCategoryToggleSize)
                .semantics { this.selected = selected }
                .testTag(emojiPickerCategoryTestTag(category)),
    ) {
        Surface(
            modifier = Modifier.size(EmojiCategorySelectionSize),
            shape = CircleShape,
            color =
                if (selected) {
                    outlineSelectionColor(MaterialTheme.colorScheme.surfaceContainerHighest)
                } else {
                    Color.Transparent
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                EmojiPickerTabIcon(category, EmojiCategoryIconSize, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
