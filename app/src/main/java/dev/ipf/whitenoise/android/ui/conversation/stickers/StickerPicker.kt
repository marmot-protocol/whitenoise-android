package dev.ipf.whitenoise.android.ui.conversation.stickers

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StickerAssetCache
import dev.ipf.whitenoise.android.state.StickerKey
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyVerticalGrid
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private val PackTabShape = RoundedCornerShape(10.dp)

/** The account's installed packs, one tab per pack, for sending a sticker. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerPickerPage(
    library: StickerLibrary,
    cache: StickerAssetCache<ImageBitmap>,
    onBrowse: () -> Unit,
    onSend: (StickerFfi) -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(library) {
        library.loadInstalledIfNeeded()
        attemptSticker { library.sync() }
    }
    val packs = library.installed.filter { it.stickers.isNotEmpty() }
    var selectedCoordinate by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = packs.firstOrNull { it.coordinate == selectedCoordinate } ?: packs.firstOrNull()
    StickerSheetTopBar(stringResource(R.string.stickers_title), onBack = null, onClose = onClose) {
        IconButton(onClick = onBrowse, modifier = Modifier.testTag("sticker.browse")) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.sticker_packs_title))
        }
    }
    when {
        !library.hasLoadedInstalled && packs.isEmpty() -> StickerProgress()
        selected == null -> EmptyStickerPicker(onBrowse)
        else -> {
            if (packs.size > 1) {
                PackTabs(packs, selected, cache) { selectedCoordinate = it.coordinate }
            }
            val title = stickerPackTitle(selected)
            StickerGrid(
                stickers = selected.stickers,
                cache = cache,
                onPick = onSend,
                header = { packHeading(title) },
                footer = { serverDisclosure() },
            )
        }
    }
}

/** Shown before any pack is added; leads to the pack browser. */
@Suppress("FunctionNaming")
@Composable
private fun EmptyStickerPicker(onBrowse: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(stringResource(R.string.stickers_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.stickers_empty_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WhiteNoiseButton(onClick = onBrowse) { Text(stringResource(R.string.stickers_browse)) }
    }
}

/** Tabs that switch between the installed packs. */
@Suppress("FunctionNaming")
@Composable
private fun PackTabs(
    packs: List<StickerPackFfi>,
    selected: StickerPackFfi,
    cache: StickerAssetCache<ImageBitmap>,
    onSelect: (StickerPackFfi) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = WhiteNoiseSpacing.Related),
    ) {
        items(packs, key = { it.coordinate }) { pack ->
            val isSelected = pack.coordinate == selected.coordinate
            val cover = pack.cover ?: pack.stickers.first()
            val title = stickerPackTitle(pack)
            Box(
                Modifier
                    .clip(PackTabShape)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, PackTabShape)
                        } else {
                            Modifier
                        },
                    ).clickable(role = Role.Tab, onClick = { onSelect(pack) })
                    .padding(6.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = title
                        this.selected = isSelected
                    },
            ) {
                StickerImage(
                    key = StickerKey.from(cover),
                    loadsAutomatically = true,
                    cache = cache,
                    modifier = Modifier.size(StickerMetrics.PACK_TAB_SIDE_DP.dp),
                )
            }
        }
    }
}

/** Stickers in a grid; tapping one calls [onPick]. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerGrid(
    stickers: List<StickerFfi>,
    cache: StickerAssetCache<ImageBitmap>,
    onPick: (StickerFfi) -> Unit,
    header: LazyGridScope.() -> Unit = {},
    footer: LazyGridScope.() -> Unit = {},
) {
    WhiteNoiseLazyVerticalGrid(
        columns = GridCells.Adaptive(StickerMetrics.GRID_MINIMUM_DP.dp),
        contentPadding = PaddingValues(WhiteNoiseSpacing.CompactScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("sticker.grid"),
    ) {
        header()
        items(stickers, key = { "${it.shortcode}:${it.plaintextSha256}" }) { sticker ->
            StickerImage(
                key = StickerKey.from(sticker),
                loadsAutomatically = true,
                cache = cache,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                onClick = { onPick(sticker) },
            )
        }
        footer()
    }
}

/** Loading a sticker contacts its pack author's server. */
internal fun LazyGridScope.serverDisclosure() {
    fullWidth { StickerDisclosure(stringResource(R.string.sticker_server_disclosure)) }
}

/** Adds [content] as one row spanning every grid column. */
internal fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Adds a full-width pack title row to the grid. */
private fun LazyGridScope.packHeading(title: String) {
    fullWidth {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}
