@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.emojiSetAddress
import dev.ipf.whitenoise.android.state.EmojiSetNotice
import dev.ipf.whitenoise.android.state.EmojiSetsController
import dev.ipf.whitenoise.android.state.EmojiSetsState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.emojiSetsBackend
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSetChoices
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.rememberBuiltinEmojiSetChoices
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch

/** The browser's Refresh action, which repeats discovery for the current query. */
internal const val EMOJI_SETS_REFRESH_TEST_TAG = "emoji_sets.refresh"

private val EmojiSetSpacing = 8.dp

/** The account's NIP-51 browser and preview. Its controller is discarded when the account changes. */
@Composable
internal fun EmojiSetsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    initialAddress: String? = null,
    targetAccount: String? = null,
) {
    val activeOwner = appState.activeAccountRef
    val selected = targetAccount?.let { ref -> appState.accounts.firstOrNull { it.label == ref } }
    val account = if (targetAccount == null) activeOwner else selected?.label
    if (account == null) {
        SettingsScaffold(title = stringResource(R.string.emoji_sets_title), onBack = onBack) {
            SettingsExplainer(stringResource(R.string.emoji_sets_account_required))
        }
        return
    }
    val generation = appState.runtimeGeneration
    key(appState, activeOwner, account, generation, initialAddress) {
        val backend = remember { appState.emojiSetsBackend(account, generation, activeOwner) }
        val controller = remember { EmojiSetsController(backend, appState.emojiSetLibrary::invalidate) }
        val imageSource = remember { backend.imageSource() }
        // A new version, or the same one after install or removal, starts with fresh image consent and tiles.
        val images =
            controller.state.preview?.let { set ->
                remember(set.coordinate, set.eventIdHex, set.installed?.eventIdHex) {
                    EmojiSetImages(set, imageSource)
                }
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
            if (initialAddress == null) {
                // Show the local projection at once, then sync once per account and runtime like the sticker
                // browser, keeping the rows usable meanwhile (#3882). Refresh forces another sync.
                controller.load()
                val library = appState.emojiSetLibrary
                if (!library.hasDiscovered(account, generation) && controller.discoverInBackground()) {
                    library.markDiscovered(account, generation)
                }
            } else {
                controller.open(initialAddress)
            }
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
            images = images,
            onInstall = { appState.launchMutation { controller.install() } },
            onCancel = controller::cancelInstall,
            onRemove = { appState.launchMutation { controller.remove() } },
            profileName = selected?.let { appState.accountDisplayNameCached(it.accountIdHex) },
            // Pre-installed sets are a device choice, so the share flow's per-profile browser leaves them out.
            builtinSets = rememberBuiltinEmojiSetChoices().takeIf { targetAccount == null },
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
    images: EmojiSetImages?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    profileName: String? = null,
    builtinSets: BuiltinEmojiSetChoices? = null,
) {
    val title = stringResource(R.string.emoji_sets_title)
    val query = rememberTextFieldState()
    val accountTitle: (@Composable () -> Unit)? =
        if (profileName == null) {
            null
        } else {
            { EmojiSetsAccountTitle(title, profileName) }
        }
    SettingsScaffold(
        title = title,
        onBack = onBack,
        titleContent = accountTitle,
        topBarActions = {
            if (state.preview == null) {
                // Repeats the current search; also the retry path for failed or partial discovery.
                IconButton(
                    onClick = { onSearch(query.text.toString().trim()) },
                    enabled = !state.busy && !state.discovering,
                    modifier = Modifier.testTag(EMOJI_SETS_REFRESH_TEST_TAG),
                ) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
                }
            }
        },
    ) {
        val set = state.preview
        if (set == null) {
            EmojiSetBrowser(state, query, onSearch, onShow, builtinSets)
        } else {
            // Images belong to one exact version and install state; a mismatch shows the unloaded preview.
            val setImages =
                images?.takeIf {
                    it.set.coordinate == set.coordinate &&
                        it.set.eventIdHex == set.eventIdHex &&
                        it.set.installed?.eventIdHex == set.installed?.eventIdHex
                }
            EmojiSetPreview(state, set, setImages, onInstall, onCancel, onRemove) { onSearch(set.coordinate) }
        }
    }
}

/** Identifies the profile that will own the set opened from the share picker. */
@Composable
private fun EmojiSetsAccountTitle(
    title: String,
    profileName: String,
) {
    Column {
        EmojiLabel(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        EmojiLabel(
            profileName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Renders MDK search results and lets the user explicitly search or paste an address. */
@Composable
private fun EmojiSetBrowser(
    state: EmojiSetsState,
    field: TextFieldState,
    onSearch: (String) -> Unit,
    onShow: (EmojiSetFfi) -> Unit,
    builtinSets: BuiltinEmojiSetChoices?,
) {
    val loading = state.busy || state.discovering
    // Search results replace the library, so the pre-installed rows return when the search is cleared.
    val listedBuiltins = builtinSets.takeIf { !state.searched }
    SettingsList {
        item { EmojiSetQuery(field, busy = loading, onSearch = onSearch) }
        item { EmojiSetStatus(state, syncing = state.discovering) }
        // A failed or partial discovery shows only its notice, never the "no sets" message (#3882).
        if (!loading && state.showsNoSets(builtinsListed = listedBuiltins != null)) {
            item {
                WhiteNoiseEmptyState(
                    title = stringResource(R.string.emoji_sets_title),
                    detail = stringResource(R.string.emoji_sets_empty),
                )
            }
        }
        // Installed sets, then relay results, under the sticker browser's headings (#3882).
        val (installed, discovered) = state.sets.partition { it.installed != null }
        emojiSetSection(R.string.emoji_sets_section_installed, installed, state.busy, onShow, listedBuiltins)
        emojiSetSection(R.string.emoji_sets_section_discover, discovered, state.busy, onShow)
    }
}

/** Whether to say nothing matched: no rows at all, and no failed discovery notice that explains why. */
private fun EmojiSetsState.showsNoSets(builtinsListed: Boolean): Boolean {
    val discoveryFailed = notice == EmojiSetNotice.Error || notice == EmojiSetNotice.DiscoveryIncomplete
    return sets.isEmpty() && !builtinsListed && !discoveryFailed
}

/** The query field and its explicit Find action. */
@Composable
private fun EmojiSetQuery(
    field: TextFieldState,
    busy: Boolean,
    onSearch: (String) -> Unit,
) {
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
        val findLabel = stringResource(R.string.emoji_sets_find)
        Button(
            onClick = { onSearch(field.text.toString().trim()) },
            modifier =
                Modifier.explainOnLongPress(
                    Explanation(
                        findLabel,
                        stringResource(R.string.explanation_organize_emoji_find),
                    ),
                ),
            enabled = !busy,
        ) {
            Text(findLabel)
        }
    }
}

/** Renders progress, including the browser's opening sync, and the typed result of the most recent operation. */
@Composable
internal fun EmojiSetStatus(
    state: EmojiSetsState,
    syncing: Boolean = false,
) {
    val progress = state.progress
    if (progress != null && progress.totalEmoji > 0u) {
        LinearProgressIndicator(
            progress = { progress.completedEmoji.toFloat() / progress.totalEmoji.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        )
    } else if (state.busy || syncing) {
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
    state.installedWithSkips?.let { SettingsExplainer(pluralStringResource(R.plurals.emoji_sets_skipped, it, it)) }
}

/** The set's title, else a readable identifier, else "Untitled set"; never a raw key or random identifier. */
@Composable
internal fun EmojiSetFfi.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }
        ?: identifier.takeIf { it.isReadableIdentifier() }
        ?: stringResource(R.string.emoji_sets_untitled)

/** Hex digests, UUIDs, timestamps and random tokens read as noise, so they never stand in for a title. */
internal fun String.isReadableIdentifier(): Boolean {
    val id = trim()
    if (id.isEmpty()) return false
    val bare = id.removePrefix("0x")
    val randomToken =
        id.length >= RANDOM_TOKEN_LENGTH && id.none { it in WORD_SEPARATORS } &&
            id.any(Char::isDigit) && id.any(Char::isLetter)
    return !(bare.matches(HexLike) || id.matches(Timestamp) || randomToken)
}

private val HexLike = Regex("""[0-9a-fA-F-]{12,}""")
private val Timestamp = Regex("""\d{8,}""")
private const val WORD_SEPARATORS = " -_."
private const val RANDOM_TOKEN_LENGTH = 16
