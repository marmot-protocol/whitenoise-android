package dev.ipf.whitenoise.android.ui.conversation.stickers

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.state.WhiteNoiseStickerPacks

/**
 * White Noise's own packs that aren't installed, fetched through links naming its relays. The lookup runs again on
 * each Refresh ([refreshes]) and whenever one of these packs is added or removed, so a lookup that failed offline,
 * or a pack removed while the browser is open, comes back without reopening it. Add stays a tap away on the pack.
 */
@Composable
internal fun rememberWhiteNoisePacks(
    library: StickerLibrary,
    refreshes: Int,
): List<StickerPackFfi> {
    var fetched by remember(library) { mutableStateOf(emptyList<StickerPackFfi>()) }
    val installed = WhiteNoiseStickerPacks.coordinates.filter(library::isInstalled)
    LaunchedEffect(library, refreshes, installed) { fetched = library.whiteNoisePacks() }
    return fetched.filterNot { library.isInstalled(it.coordinate) }
}

/** White Noise's own packs, ahead of the installed and discoverable ones; nothing when there are none to offer. */
internal fun LazyListScope.whiteNoiseSection(
    packs: List<StickerPackFfi>,
    onOpen: (StickerPackFfi) -> Unit,
) {
    if (packs.isEmpty()) return
    item { StickerSectionTitle(stringResource(R.string.white_noise)) }
    items(packs, key = { "white-noise:${it.coordinate}" }) { pack -> PackRow(pack) { onOpen(pack) } }
}
