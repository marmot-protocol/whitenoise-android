@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.emojiSetAddress
import dev.ipf.whitenoise.android.state.EmojiSetNotice
import dev.ipf.whitenoise.android.state.EmojiSetsController
import dev.ipf.whitenoise.android.state.EmojiSetsState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.emojiSetsBackend
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EmojiSetSpacing = 8.dp
private val EmojiSetEntrySpacing = 4.dp
private val EmojiSetImageSize = 96.dp

/** The account's NIP-51 browser and preview. Its controller is discarded when the account changes. */
@Composable
internal fun EmojiSetsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    initialAddress: String? = null,
) {
    val account = appState.activeAccountRef
    if (account == null) {
        SettingsScaffold(title = stringResource(R.string.emoji_sets_title), onBack = onBack) {
            SettingsExplainer(stringResource(R.string.emoji_sets_account_required))
        }
        return
    }
    val generation = appState.runtimeGeneration
    key(appState, account, generation, initialAddress) {
        val controller =
            remember {
                EmojiSetsController(appState.emojiSetsBackend(account, generation), appState.emojiSetLibrary::invalidate)
            }
        val scope = rememberCoroutineScope()
        val back = {
            when {
                controller.state.progress != null -> controller.cancelInstall()
                controller.state.preview != null && !controller.state.busy -> controller.dismissPreview()
                else -> onBack()
            }
        }
        LaunchedEffect(controller) {
            if (initialAddress == null) controller.load() else controller.open(initialAddress)
        }
        DisposableEffect(controller) { onDispose(controller::close) }
        BackHandler(onBack = back)
        EmojiSetsContent(
            state = controller.state,
            onBack = back,
            onSearch = { query ->
                val address = query.takeIf { it.startsWith("30030:") } ?: emojiSetAddress(query)
                scope.launch {
                    if (address == null) controller.discover(query) else controller.open(address)
                }
            },
            onShow = controller::show,
            onLoadArtwork = { scope.launch { controller.loadArtwork() } },
            onInstall = { appState.launchMutation { controller.install() } },
            onCancel = controller::cancelInstall,
            onRemove = { appState.launchMutation { controller.remove() } },
        )
    }
}

/** Pure presentation for the browser and preview, with only the editable query held in Compose. */
@Suppress("LongParameterList")
@Composable
internal fun EmojiSetsContent(
    state: EmojiSetsState,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onShow: (EmojiSetFfi) -> Unit,
    onLoadArtwork: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.emoji_sets_title), onBack = onBack) {
        val set = state.preview
        if (set == null) {
            EmojiSetBrowser(state, onSearch, onShow)
        } else {
            EmojiSetPreview(state, set, onLoadArtwork, onInstall, onCancel, onRemove) { onSearch(set.coordinate) }
        }
    }
}

/** Searches the local results as the query is edited, and explicitly asks MDK to discover more. */
@Composable
private fun EmojiSetBrowser(
    state: EmojiSetsState,
    onSearch: (String) -> Unit,
    onShow: (EmojiSetFfi) -> Unit,
) {
    val field = rememberTextFieldState()
    val query = field.text.toString().trim()
    val sets =
        remember(state.sets, query) {
            state.sets
                .filter { set ->
                    set.installed != null ||
                        query.isBlank() ||
                        set.displayTitle().contains(query, ignoreCase = true) ||
                        set.description.orEmpty().contains(query, ignoreCase = true) ||
                        set.emoji.any { it.shortcode.contains(query, ignoreCase = true) }
                }.sortedBy { it.installed == null }
        }
    SettingsList {
        item {
            Column(
                modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                verticalArrangement = Arrangement.spacedBy(EmojiSetSpacing),
            ) {
                WhiteNoiseTextField(
                    state = field,
                    modifier = Modifier.fillMaxWidth().testTag("emoji_sets.query"),
                    label = { Text(stringResource(R.string.emoji_sets_query)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
                Button(onClick = { onSearch(query) }, enabled = !state.busy) {
                    Text(stringResource(R.string.emoji_sets_find))
                }
            }
        }
        item { EmojiSetStatus(state) }
        if (sets.isEmpty() && !state.busy) {
            item { SettingsExplainer(stringResource(R.string.emoji_sets_empty)) }
        }
        items(sets, key = { it.coordinate }) { set ->
            SettingsGroup {
                row("set") { context ->
                    SettingsAction(
                        context = context,
                        title = set.displayTitle(),
                        subtitle =
                            when {
                                set.installed?.updateAvailable == true -> stringResource(R.string.emoji_sets_update_available)
                                set.installed != null -> stringResource(R.string.emoji_sets_installed)
                                else -> stringResource(R.string.emoji_sets_item_count, set.emoji.size)
                            },
                        enabled = !state.busy,
                        onClick = { onShow(set) },
                    )
                }
            }
        }
    }
}

/** Shows the exact version selected for install and discloses where its artwork is hosted. */
@Suppress("LongParameterList")
@Composable
private fun EmojiSetPreview(
    state: EmojiSetsState,
    set: EmojiSetFfi,
    onLoadArtwork: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    SettingsList {
        item(key = "set.preview") {
            EmojiSetPreviewHeader(state, set, onLoadArtwork)
        }
        item(key = "set.status") { EmojiSetStatus(state) }
        item(key = "set.actions") {
            EmojiSetActions(state, set, onInstall, onCancel, onRemove, onCheckUpdate)
        }
        items(set.emoji, key = { it.shortcode }) { emoji ->
            Column(
                modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                verticalArrangement = Arrangement.spacedBy(EmojiSetEntrySpacing),
            ) {
                Text(":${emoji.shortcode}:", style = MaterialTheme.typography.bodyLarge)
                Uri.parse(emoji.url).host?.let { host ->
                    Text(host, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The preview opens as text. Loading its image is a separate explicit action. */
@Composable
private fun EmojiSetPreviewHeader(
    state: EmojiSetsState,
    set: EmojiSetFfi,
    onLoadArtwork: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(EmojiSetSpacing),
    ) {
        Text(set.displayTitle(), style = MaterialTheme.typography.headlineSmall)
        set.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
        Text(stringResource(R.string.emoji_sets_item_count, set.emoji.size))
        Text(
            stringResource(R.string.emoji_sets_image_privacy),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        val imageUrl = set.imageUrl ?: set.emoji.firstOrNull()?.url
        Uri.parse(imageUrl.orEmpty()).host?.let { host ->
            Text(stringResource(R.string.emoji_sets_preview_host, host))
        }
        if (state.artwork != null) {
            key(state.artwork) {
                val art by produceState<EmojiArt?>(null) {
                    value = withContext(Dispatchers.IO) { decodeEmojiImage(state.artwork)?.let(EmojiArt::Decoded) }
                }
                art?.let {
                    EmojiArtImage(it, stringResource(R.string.emoji_sets_load_image), Modifier.size(EmojiSetImageSize))
                }
            }
        } else if (imageUrl != null) {
            TextButton(onClick = onLoadArtwork, enabled = !state.busy) {
                Text(stringResource(R.string.emoji_sets_load_image))
            }
        }
    }
}

/** Displays only MDK's progress and keeps cancellation available until the install finishes. */
@Suppress("LongParameterList")
@Composable
private fun EmojiSetActions(
    state: EmojiSetsState,
    set: EmojiSetFfi,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(EmojiSetSpacing),
    ) {
        val progress = state.progress
        if (progress != null) {
            if (progress.totalEmoji == 0u) {
                Text(stringResource(R.string.emoji_sets_preparing_install))
            } else {
                Text(
                    stringResource(
                        R.string.emoji_sets_install_progress,
                        progress.completedEmoji.toInt(),
                        progress.totalEmoji.toInt(),
                    ),
                )
            }
            TextButton(onClick = onCancel, enabled = !state.cancelling) {
                Text(stringResource(R.string.cancel))
            }
        } else {
            val installed = set.installed
            if (installed == null || installed.updateAvailable) {
                Button(onClick = onInstall, enabled = !state.busy) {
                    val label = if (installed == null) R.string.emoji_sets_install else R.string.emoji_sets_update
                    Text(stringResource(label))
                }
            }
            if (installed != null && !installed.updateAvailable) {
                TextButton(onClick = onCheckUpdate, enabled = !state.busy) {
                    Text(stringResource(R.string.emoji_sets_check_update))
                }
            }
            if (installed != null) {
                TextButton(onClick = onRemove, enabled = !state.busy) {
                    Text(stringResource(R.string.remove))
                }
            }
        }
    }
}

/** Renders progress and the typed result of the most recent operation. */
@Composable
private fun EmojiSetStatus(state: EmojiSetsState) {
    val progress = state.progress
    if (progress != null && progress.totalEmoji > 0u) {
        LinearProgressIndicator(
            progress = { progress.completedEmoji.toFloat() / progress.totalEmoji.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        )
    } else if (state.busy) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        )
    }
    val notice =
        when (state.notice) {
            EmojiSetNotice.Error -> R.string.emoji_sets_error
            EmojiSetNotice.NotFound -> R.string.emoji_sets_not_found
            EmojiSetNotice.Changed -> R.string.emoji_sets_changed
            EmojiSetNotice.Cancelled -> R.string.emoji_sets_cancelled
            EmojiSetNotice.DiscoveryIncomplete -> R.string.emoji_sets_discovery_incomplete
            null -> null
        }
    notice?.let { SettingsExplainer(stringResource(it)) }
    state.installedWithSkips?.let { SettingsExplainer(stringResource(R.string.emoji_sets_skipped, it)) }
}

private fun EmojiSetFfi.displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: identifier.ifBlank { coordinate }
