package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyVerticalGrid
import dev.ipf.whitenoise.android.ui.conversation.media.MediaImageGridTile
import dev.ipf.whitenoise.android.ui.conversation.media.MediaVideoGridTile
import dev.ipf.whitenoise.android.ui.settings.settingsCardMorph
import androidx.compose.foundation.lazy.grid.items as gridItems

/** Month-sectioned grid of visual tiles; each section emits one header item followed by its tiles. */
@Suppress("FunctionNaming")
@Composable
internal fun MediaTileGrid(
    sections: List<MediaMonthSection<SharedMediaTile>>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    emptyLabel: String,
    tileContent: @Composable BoxScope.(SharedMediaTile) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyPlaceholder(emptyLabel)
        return
    }
    WhiteNoiseLazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().trackSharedContentHeader(gridState).testTag("shared.media.grid"),
    ) {
        sections.forEach { section ->
            item(key = "header-${section.sectionKey}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    monthLabel(section.monthKey),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp).semantics { heading() },
                )
            }
            gridItems(section.items, key = { "${it.messageIdHex}#${it.attachmentIndex}" }) { tile ->
                Box(Modifier.aspectRatio(1f).settingsCardMorph()) { tileContent(tile) }
            }
        }
    }
}

/** One decrypted thumbnail with its sender caption, plus the selection toggle while photos are being picked. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun BoxScope.SharedMediaGridTile(
    tile: SharedMediaTile,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    share: SharedPhotoShare,
    onTapTile: (SharedMediaTile) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val selecting = share.selection.active
    val selected = selecting && share.selection.isSelected(tile)
    val select = {
        if (!share.sharing) share.selection.toggle(tile)
    }
    // While selecting, the overlay below owns this tile's touch and accessibility.
    Box(
        Modifier
            .matchParentSize()
            .selectedTileInset(selected)
            .then(if (selecting) Modifier.clearAndSetSemantics {} else Modifier),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (tile.isVideo) {
            MediaVideoGridTile(
                messageIdHex = tile.messageIdHex,
                attachmentIndex = tile.attachmentIndex,
                reference = tile.reference,
                controller = controller,
                appState = appState,
                mine = tile.mine,
                onTap = { onTapTile(tile) },
                overflowCount = 0,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MediaImageGridTile(
                messageIdHex = tile.messageIdHex,
                attachmentIndex = tile.attachmentIndex,
                reference = tile.reference,
                controller = controller,
                appState = appState,
                mine = tile.mine,
                onTap = { onTapTile(tile) },
                overflowCount = 0,
                modifier = Modifier.fillMaxSize().selectPhotoAction(enabled = !selecting, onSelect = select),
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    select()
                },
            )
        }
        SharedMediaTileCaption(tile, appState)
    }
    if (selecting) {
        SharedTileSelectionToggle(
            tile = tile,
            selected = selected,
            enabled = !share.sharing,
            description = sharedTileSelectionDescription(tile, appState),
            onToggle = select,
        )
    }
}

/**
 * The prototype names the sender across the foot of every tile, and marks a video there
 * in words rather than with a badge, so a dense grid still says who sent what.
 */
@Suppress("FunctionNaming")
@Composable
private fun SharedMediaTileCaption(
    tile: SharedMediaTile,
    appState: WhiteNoiseAppState,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = SHARED_TILE_CAPTION_ALPHA),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            EmojiLabel(
                text = if (tile.mine) stringResource(R.string.you) else appState.displayName(tile.sender),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tile.isVideo) {
                Text(
                    text = stringResource(R.string.shared_media_tab_videos),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** What a tile announces while selecting: who sent a photo, or that a video cannot be selected. */
@Composable
private fun sharedTileSelectionDescription(
    tile: SharedMediaTile,
    appState: WhiteNoiseAppState,
): String =
    if (tile.isVideo) {
        stringResource(R.string.shared_media_video_not_selectable)
    } else {
        stringResource(
            R.string.shared_media_photo_from,
            if (tile.mine) stringResource(R.string.you) else appState.displayName(tile.sender),
        )
    }

/** Keeps the caption legible over bright artwork without hiding the photo behind it. */
private const val SHARED_TILE_CAPTION_ALPHA = 0.86f
