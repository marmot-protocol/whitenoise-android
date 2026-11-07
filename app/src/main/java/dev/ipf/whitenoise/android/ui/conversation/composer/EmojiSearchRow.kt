@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiSection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val EMOJI_PICKER_SEARCH_ROW_TEST_TAG = "emoji.picker.search_row"

/** One 48dp cell plus a little air above and below it. */
internal val EmojiSearchRowHeight = EmojiPickerMinimumCellSize + 8.dp
private val EmojiSearchRowPadding = 8.dp

/**
 * One row of 48dp cells holding every emoji of [sections] in order, scrolled horizontally. With no
 * matches the same height holds the no-results text, so the pane does not change size while typing;
 * with nothing typed yet the caller passes recents, and an empty row stays blank.
 */
@Composable
internal fun EmojiSearchRow(
    sections: List<EmojiSection>,
    noResults: Boolean,
    state: LazyListState,
    selectionEnabled: Boolean,
    onPick: (String) -> Unit,
) {
    val modifier =
        Modifier
            .fillMaxWidth()
            .height(EmojiSearchRowHeight)
            .testTag(EMOJI_PICKER_SEARCH_ROW_TEST_TAG)
    if (noResults) {
        Box(
            modifier = modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.emoji_search_no_results),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    val cells =
        remember(sections) {
            sections.flatMap { section ->
                section.emoji.mapIndexed { index, emoji -> Triple(section.category, index, emoji) }
            }
        }
    LazyRow(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = EmojiSearchRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(cells, key = { (category, index, emoji) -> "${category.id}:$index:$emoji" }) { (category, index, emoji) ->
            EmojiTapTarget(
                emoji = emoji,
                enabled = selectionEnabled,
                onClick = { onPick(emoji) },
                modifier = Modifier.testTag(emojiPickerItemTestTag(category, index)),
            )
        }
    }
}

/** The 48dp circular touch target around one emoji, shared by the grid, the search row and suggestions. */
@Composable
internal fun EmojiTapTarget(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyphSize: Dp = EmojiPickerEmojiSize,
    fitPickerArtwork: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(EmojiPickerMinimumCellSize),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            EmojiGlyph(emoji, size = glyphSize, fitPickerArtwork = fitPickerArtwork)
        }
    }
}
