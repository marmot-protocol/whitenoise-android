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

/**
 * White Noise's own packs that aren't installed, fetched once per browser through links naming its relays. An added
 * pack drops out as soon as the installed list changes; Add itself stays a tap away on the pack's page.
 */
@Composable
internal fun rememberWhiteNoisePacks(library: StickerLibrary): List<StickerPackFfi> {
    var fetched by remember(library) { mutableStateOf(emptyList<StickerPackFfi>()) }
    LaunchedEffect(library) { fetched = library.whiteNoisePacks() }
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
