package dev.ipf.whitenoise.android.ui.conversation.stickers

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.StickerFfi
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
import java.net.URLEncoder

private const val SEARCH_DEBOUNCE_MS = 250L

/** Lists packs to discover, with search and an optional add-by-link field. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerBrowserPage(
    library: StickerLibrary,
    onFailure: StickerFailureReporter,
    onOpen: (StickerPackFfi) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var linkOpen by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var discovered by remember { mutableStateOf<List<StickerPackFfi>>(emptyList()) }
    var syncing by remember { mutableStateOf(true) }
    var syncFailed by remember { mutableStateOf(false) }
    var refreshRequests by remember { mutableIntStateOf(0) }
    var syncGeneration by remember { mutableIntStateOf(0) }
    var discoveryFailed by remember { mutableStateOf(false) }
    var discoveredSearch by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshRequests) {
        syncing = true
        syncFailed = attemptSticker { library.sync(force = refreshRequests > 0) } != null
        syncing = false
        syncGeneration++
    }
    LaunchedEffect(search, library.installed, syncGeneration) {
        if (search.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        // A failed read keeps this query's last results but never shows another query's.
        val failure =
            attemptSticker {
                discovered = library.discover(search)
                discoveredSearch = search
            }
        discoveryFailed = failure != null
        if (failure != null && discoveredSearch != search) discovered = emptyList()
    }
    StickerSheetTopBar(stringResource(R.string.sticker_packs_title), onBack = onBack, onClose = onClose) {
        IconToggleButton(
            checked = linkOpen,
            onCheckedChange = { linkOpen = it },
            modifier = Modifier.testTag("sticker.link.toggle"),
        ) {
            Icon(painterResource(R.drawable.ic_link), stringResource(R.string.sticker_packs_add_by_link))
        }
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
        if (linkOpen) {
            item {
                StickerLinkField(library, onFailure) { pack ->
                    linkOpen = false
                    onOpen(pack)
                }
            }
        }
        if (library.installed.isNotEmpty()) {
            item { StickerSectionTitle(stringResource(R.string.sticker_packs_installed)) }
            items(library.installed, key = { "installed:${it.coordinate}" }) { pack -> PackRow(pack) { onOpen(pack) } }
        }
        item { StickerSectionTitle(stringResource(R.string.sticker_packs_discover)) }
        item { StickerSearchField(search) { search = it } }
        discoveryResults(
            state = StickerPresentation.discoveryState(syncing, discovered.size, search, syncFailed, discoveryFailed),
            packs = discovered,
            onOpen = onOpen,
            onRetry = { refreshRequests++ },
        )
        item { StickerDisclosure(stringResource(R.string.sticker_packs_public_footer)) }
    }
}

/** Opens the pack behind a pasted link or address. */
@Suppress("FunctionNaming")
@Composable
private fun StickerLinkField(
    library: StickerLibrary,
    onFailure: StickerFailureReporter,
    onOpened: (StickerPackFfi) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    val onOpen: () -> Unit = {
        StickerPresentation.lookupInput(link)?.takeUnless { opening }?.let { input ->
            opening = true
            scope.launch {
                attemptSticker { onOpened(library.lookUp(input)) }
                    ?.let { onFailure(R.string.sticker_pack_open_failed, "STICKER_PACK_OPEN", it) }
                opening = false
            }
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        WhiteNoiseCompactSearchField(
            value = link,
            onValueChange = { link = it },
            placeholder = stringResource(R.string.sticker_pack_link),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onOpen() }),
            leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
            containerColor = Color.Transparent,
            outlined = true,
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("sticker.link"),
        )
        WhiteNoiseButton(
            onClick = onOpen,
            enabled = StickerPresentation.lookupInput(link) != null,
            loading = opening,
            loadingLabel = stringResource(R.string.sticker_pack_opening),
        ) { Text(stringResource(R.string.sticker_pack_open_link)) }
    }
}

/** Filters discovered packs by name. */
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

/** Adds the discovered packs, or a loading, empty or unreachable-with-retry state, to the list. */
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
        DiscoveryState.Unreachable -> item { DiscoveryRetry(R.string.sticker_packs_unreachable, onRetry) }
        DiscoveryState.LoadFailed -> item { DiscoveryRetry(R.string.sticker_packs_load_failed, onRetry) }
    }
}

/** Why Discover is empty, with a retry. */
@Suppress("FunctionNaming")
@Composable
private fun DiscoveryRetry(
    @StringRes message: Int,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
        StickerDisclosure(stringResource(message))
        WhiteNoiseOutlinedButton(onClick = onRetry) { Text(stringResource(R.string.sticker_packs_try_again)) }
    }
}

/** One discovered pack with its title and sticker count; opens the pack. */
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

/** Shows one pack: its stickers to send, its author, and controls to share or add it. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerPackDetailPage(
    pack: StickerPackFfi,
    library: StickerLibrary,
    cache: StickerAssetCache<ImageBitmap>,
    authorName: String,
    onOpenAuthor: () -> Unit,
    onSend: (StickerFfi) -> Unit,
    onFailure: StickerFailureReporter,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    StickerSheetTopBar(stickerPackTitle(pack), onBack = onBack, onClose = onClose) {
        IconButton(
            onClick = { context.startActivity(stickerPackShareIntent(pack)) },
            modifier = Modifier.testTag("sticker.pack.share"),
        ) {
            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share))
        }
        StickerPackToggle(pack, library, onFailure)
    }
    val description = StickerPresentation.description(pack)
    StickerGrid(
        stickers = pack.stickers,
        cache = cache,
        onPick = onSend,
        header = {
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.sticker_pack_by, authorName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .clickable(role = Role.Button, onClick = onOpenAuthor)
                                .heightIn(min = 32.dp)
                                .wrapContentHeight()
                                .testTag("sticker.pack.author"),
                    )
                    if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium)
                    StickerDisclosure(stringResource(R.string.sticker_pack_tap_to_send))
                }
            }
            // Shown above the stickers here, since opening a pack loads them all.
            serverDisclosure()
        },
    )
}

/** Adds or removes [pack] from the account's packs. */
@Suppress("FunctionNaming")
@Composable
private fun StickerPackToggle(
    pack: StickerPackFfi,
    library: StickerLibrary,
    onFailure: StickerFailureReporter,
) {
    val scope = rememberCoroutineScope()
    var working by remember(pack.coordinate) { mutableStateOf(false) }
    val installed = library.isInstalled(pack.coordinate)
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

/**
 * Shares [pack] as a Sonar viewer link: it opens in a browser for people without the app, and MDK reads it back
 * when pasted into "Add by link". The system sheet offers Copy as well.
 */
private fun stickerPackShareIntent(pack: StickerPackFfi): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, stickerPackShareLink(pack))
        },
        null,
    )

private const val STICKER_SHARE_LINK_BASE = "https://sonarprivacy.xyz/stickers?a="

/** A link to [pack] that opens on the web and that MDK reads back when pasted into "Add by link". */
internal fun stickerPackShareLink(pack: StickerPackFfi): String {
    val coordinate = URLEncoder.encode(pack.coordinate, "UTF-8")
    return STICKER_SHARE_LINK_BASE + coordinate
}
