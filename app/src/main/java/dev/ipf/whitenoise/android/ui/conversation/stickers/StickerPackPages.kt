package dev.ipf.whitenoise.android.ui.conversation.stickers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StickerAssetCache
import dev.ipf.whitenoise.android.state.StickerLibrary
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.conversation.stickers.StickerPresentation.DiscoveryState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 250L

@Suppress("FunctionNaming")
@Composable
internal fun StickerBrowserPage(
    library: StickerLibrary,
    onFailure: StickerFailureReporter,
    onOpen: (StickerPackFfi) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<StickerPackFfi>>(emptyList()) }
    var syncing by remember { mutableStateOf(true) }
    var syncFailed by remember { mutableStateOf(false) }
    var refreshRequests by remember { mutableIntStateOf(0) }
    var syncGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshRequests) {
        syncing = true
        syncFailed = attemptSticker { library.sync(force = refreshRequests > 0) } != null
        syncing = false
        syncGeneration++
    }
    LaunchedEffect(search, library.installed, syncGeneration) {
        if (search.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        attemptSticker { discovered = library.discover(search) }
    }
    val openLink: () -> Unit = {
        StickerPresentation.lookupInput(link)?.takeUnless { opening }?.let { input ->
            opening = true
            scope.launch {
                attemptSticker { onOpen(library.lookUp(input)).also { link = "" } }?.let {
                    onFailure(R.string.sticker_pack_open_failed, "STICKER_PACK_OPEN", it)
                }
                opening = false
            }
        }
    }
    StickerSheetTopBar(stringResource(R.string.sticker_packs_title), onBack = onBack, onClose = onClose) {
        IconButton(onClick = { refreshRequests++ }, enabled = !syncing) {
            Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh))
        }
    }
    WhiteNoiseLazyColumn(
        contentPadding =
            PaddingValues(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Related),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        modifier = Modifier.fillMaxWidth().testTag("sticker.browser"),
    ) {
        item { StickerSectionTitle(stringResource(R.string.sticker_packs_add_by_link)) }
        item { StickerLinkField(link, onLinkChange = { link = it }, opening = opening, onOpen = openLink) }
        item { StickerDisclosure(stringResource(R.string.sticker_pack_link_footer)) }
        if (library.installed.isNotEmpty()) {
            item { StickerSectionTitle(stringResource(R.string.sticker_packs_installed)) }
            items(library.installed, key = { "installed:${it.coordinate}" }) { pack -> PackRow(pack) { onOpen(pack) } }
        }
        item { StickerSectionTitle(stringResource(R.string.sticker_packs_discover)) }
        item { StickerSearchField(search) { search = it } }
        discoveryResults(
            state = StickerPresentation.discoveryState(syncing, discovered.size, search, syncFailed),
            packs = discovered,
            onOpen = onOpen,
            onRetry = { refreshRequests++ },
        )
        item { StickerDisclosure(stringResource(R.string.sticker_packs_public_footer)) }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun StickerLinkField(
    link: String,
    onLinkChange: (String) -> Unit,
    opening: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        WhiteNoiseCompactSearchField(
            value = link,
            onValueChange = onLinkChange,
            placeholder = stringResource(R.string.sticker_pack_link),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onOpen() }),
            leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
            containerColor = Color.Transparent,
            outlined = true,
            modifier = Modifier.weight(1f).testTag("sticker.link"),
        )
        WhiteNoiseButton(
            onClick = onOpen,
            enabled = StickerPresentation.lookupInput(link) != null,
            loading = opening,
            loadingLabel = stringResource(R.string.sticker_pack_opening),
        ) { Text(stringResource(R.string.sticker_pack_open_link)) }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun StickerSearchField(
    search: String,
    onSearchChange: (String) -> Unit,
) {
    WhiteNoiseCompactSearchField(
        value = search,
        onValueChange = onSearchChange,
        placeholder = stringResource(R.string.sticker_packs_search),
        containerColor = Color.Transparent,
        outlined = true,
        modifier = Modifier.fillMaxWidth().testTag("sticker.search"),
    )
}

private fun LazyListScope.discoveryResults(
    state: DiscoveryState,
    packs: List<StickerPackFfi>,
    onOpen: (StickerPackFfi) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        DiscoveryState.Packs ->
            items(packs, key = { "discover:${it.coordinate}" }) { pack -> PackRow(pack) { onOpen(pack) } }
        DiscoveryState.Loading -> item { StickerProgress() }
        DiscoveryState.NoneOnRelays -> item { StickerDisclosure(stringResource(R.string.sticker_packs_none_on_relays)) }
        DiscoveryState.NoMatches -> item { StickerDisclosure(stringResource(R.string.sticker_packs_no_matches)) }
        DiscoveryState.Unreachable ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
                    StickerDisclosure(stringResource(R.string.sticker_packs_unreachable))
                    WhiteNoiseOutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.sticker_packs_try_again))
                    }
                }
            }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun PackRow(
    pack: StickerPackFfi,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(StickerTileShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stickerPackTitle(pack),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.sticker_pack_count, pack.stickers.size, pack.stickers.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun StickerPackDetailPage(
    pack: StickerPackFfi,
    library: StickerLibrary,
    cache: StickerAssetCache<ImageBitmap>,
    authorName: String,
    onFailure: StickerFailureReporter,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember(pack.coordinate) { mutableStateOf(false) }
    val installed = library.isInstalled(pack.coordinate)
    StickerSheetTopBar(stickerPackTitle(pack), onBack = onBack, onClose = onClose) {
        TextButton(
            enabled = !working,
            onClick = {
                scope.launch {
                    working = true
                    attemptSticker { if (installed) library.uninstall(pack) else library.install(pack) }?.let {
                        onFailure(
                            if (installed) R.string.sticker_pack_remove_failed else R.string.sticker_pack_add_failed,
                            if (installed) "STICKER_PACK_REMOVE" else "STICKER_PACK_ADD",
                            it,
                        )
                    }
                    working = false
                }
            },
            modifier = Modifier.testTag("sticker.pack.toggle"),
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (installed) R.string.remove else R.string.sticker_pack_add))
            }
        }
    }
    val description = StickerPresentation.description(pack)
    StickerGrid(
        stickers = pack.stickers,
        cache = cache,
        onPick = null,
        header = {
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.sticker_pack_by, authorName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Shown above the stickers here, since opening a pack loads them all.
            serverDisclosure()
        },
    )
}
