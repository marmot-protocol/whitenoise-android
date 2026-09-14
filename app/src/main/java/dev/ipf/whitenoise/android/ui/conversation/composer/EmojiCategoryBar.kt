@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
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
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiCategory
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.outlineSelectionColor

internal const val EMOJI_PICKER_CATEGORIES_TEST_TAG = "emoji.picker.categories"
internal const val EMOJI_PICKER_CONFIGURE_TEST_TAG = "emoji.picker.configure"

internal fun emojiPickerCategoryTestTag(category: EmojiCategory): String = "emoji.picker.category.${category.id}"

private val EmojiCategoryBarMinimumHeight = 56.dp
private val EmojiCategoryToggleSize = 48.dp
private val EmojiCategorySelectionSize = 36.dp
private val EmojiCategoryIconSize = 22.dp

/**
 * Bottom bar of the emoji picker: an optional configure action, one toggle per visible category
 * with a 36dp selection disc, and an optional backspace action for the composer pane.
 */
@Composable
internal fun EmojiCategoryBar(
    categories: List<EmojiCategory>,
    selected: EmojiCategory?,
    state: LazyListState,
    onConfigure: (() -> Unit)?,
    onBackspace: (() -> Unit)?,
    onCategory: (EmojiCategory) -> Unit,
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
            if (onConfigure != null) {
                IconButton(
                    onClick = onConfigure,
                    modifier =
                        Modifier
                            .padding(start = WhiteNoiseSpacing.Related)
                            .testTag(EMOJI_PICKER_CONFIGURE_TEST_TAG),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_emoji_settings),
                        contentDescription = stringResource(R.string.configure_reactions),
                    )
                }
            }
            LazyRow(
                state = state,
                modifier = Modifier.weight(1f),
                contentPadding =
                    PaddingValues(
                        start = if (onConfigure == null) WhiteNoiseSpacing.Related else 0.dp,
                        end = WhiteNoiseSpacing.Related,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(categories, key = { it.id }) { category ->
                    EmojiCategoryToggle(
                        category = category,
                        selected = selected == category,
                        onClick = { onCategory(category) },
                    )
                }
            }
            if (onBackspace != null) {
                IconButton(
                    onClick = onBackspace,
                    modifier = Modifier.padding(end = WhiteNoiseSpacing.Related),
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

@Composable
private fun EmojiCategoryToggle(
    category: EmojiCategory,
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
                Icon(
                    painter = painterResource(category.iconRes),
                    contentDescription = stringResource(category.titleRes),
                    modifier = Modifier.size(EmojiCategoryIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
