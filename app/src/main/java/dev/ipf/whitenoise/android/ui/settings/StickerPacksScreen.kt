package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.StickerInput
import dev.ipf.whitenoise.android.core.StickerInputKind
import dev.ipf.whitenoise.android.core.StickerLinks
import dev.ipf.whitenoise.android.core.reference
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.stickers.StickerImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StickerPacksScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    initialInput: StickerInput? = null,
    onInitialInputConsumed: (StickerInput) -> Unit = {},
) {
    val account = appState.activeAccountRef
    var packs by remember(appState.activeAccountRef) { mutableStateOf(emptyList<StickerPackFfi>()) }
    var search by remember(account) { mutableStateOf("") }
    var input by remember(account) { mutableStateOf("") }
    var busy by remember(account) { mutableStateOf(false) }
    var error by remember(account) { mutableStateOf<String?>(null) }
    var preview by remember(account) { mutableStateOf<StickerPackFfi?>(null) }
    val scope = rememberCoroutineScope()
    val unsupportedImportError = stringResource(R.string.sticker_external_signer_unsupported)
    val genericStickerError = stringResource(R.string.sticker_operation_failed)

    suspend fun reload() {
        val current = account ?: return
        packs =
            appState.marmotIo {
                stickerPacks(
                    accountRef = current,
                    installedOnly = false,
                    search = search.trim().takeIf { it.isNotEmpty() },
                    limit = 100u,
                )
            }
    }

    suspend fun runAction(action: suspend () -> Unit) {
        busy = true
        error = null
        try {
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            error = sanitizedStickerActionError(failure, unsupportedImportError, genericStickerError)
        } finally {
            busy = false
        }
    }

    suspend fun processStickerInput(stickerInput: StickerInput) {
        val current = account ?: return
        runAction {
            when (stickerInput.kind) {
                StickerInputKind.Pack -> {
                    preview = appState.marmotIo { fetchStickerPack(current, stickerInput.value) }
                }

                StickerInputKind.SignalImport -> {
                    // The Signal key is cleared from Compose/Activity state before
                    // entering native code and is never persisted Android-side.
                    val result =
                        appState.marmotIo {
                            importSignalStickerPack(current, stickerInput.value, null)
                        }
                    preview = result.pack
                    reload()
                }
            }
        }
    }

    LaunchedEffect(account) {
        if (account == null) return@LaunchedEffect
        runAction {
            reload()
            runCatching { appState.marmotIo { syncStickerPacks(account) } }
                .onFailure { if (it is CancellationException) throw it }
            reload()
        }
    }

    LaunchedEffect(search, account) {
        if (account == null) return@LaunchedEffect
        delay(250)
        runCatching { reload() }
            .onFailure { if (it is CancellationException) throw it }
    }

    LaunchedEffect(initialInput, account) {
        val pending = initialInput ?: return@LaunchedEffect
        if (account == null) return@LaunchedEffect
        input = ""
        onInitialInputConsumed(pending)
        processStickerInput(pending)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stickers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = !busy && account != null,
                        onClick = {
                            scope.launch {
                                runAction {
                                    appState.marmotIo { syncStickerPacks(account!!) }
                                    reload()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.sticker_import_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(2048) },
                        label = { Text(stringResource(R.string.sticker_pack_input_hint)) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Uri,
                            ),
                    )
                    IconButton(
                        enabled = !busy && StickerLinks.classify(input) != null && account != null,
                        onClick = {
                            val classified = StickerLinks.classify(input) ?: return@IconButton
                            input = ""
                            scope.launch { processStickerInput(classified) }
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sticker_import))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.sticker_search_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (busy && packs.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (packs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sticker_no_packs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(packs.size, key = { packs[it].coordinate }) { index ->
                StickerPackCard(
                    appState = appState,
                    pack = packs[index],
                    enabled = !busy,
                    onOpen = { preview = packs[index] },
                    onToggleInstall = {
                        val selected = packs[index]
                        scope.launch {
                            runAction {
                                if (selected.installed) {
                                    appState.marmotIo { uninstallStickerPack(account!!, selected.coordinate) }
                                } else {
                                    appState.marmotIo { installStickerPack(account!!, selected.coordinate) }
                                }
                                reload()
                            }
                        }
                    },
                )
            }
        }
    }

    preview?.let { pack ->
        val previewScrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text(pack.title) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(previewScrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pack.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pack.stickers.take(20).forEach { sticker ->
                            StickerImage(
                                appState = appState,
                                stickerRef = sticker.reference(),
                                contentDescription = sticker.alt ?: sticker.shortcode,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && account != null,
                    onClick = {
                        scope.launch {
                            runAction {
                                if (pack.installed) {
                                    appState.marmotIo { uninstallStickerPack(account!!, pack.coordinate) }
                                } else {
                                    appState.marmotIo { installStickerPack(account!!, pack.coordinate) }
                                }
                                preview = null
                                reload()
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (pack.installed) R.string.sticker_uninstall else R.string.sticker_install))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { preview = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

internal fun sanitizedStickerActionError(
    failure: Throwable,
    unsupportedImportError: String,
    genericStickerError: String,
): String =
    if (failure is MarmotKitException.StickerImportUnsupported) {
        unsupportedImportError
    } else {
        // Native import errors can contain the full Signal URL, including its
        // decryption key. Keep all backend details out of the rendered UI.
        genericStickerError
    }

@Composable
private fun StickerPackCard(
    appState: WhiteNoiseAppState,
    pack: StickerPackFfi,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggleInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (pack.cover ?: pack.stickers.firstOrNull())?.let { sticker ->
                StickerImage(
                    appState = appState,
                    stickerRef = sticker.reference(),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(pack.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.sticker_count, pack.stickers.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(enabled = enabled, onClick = onToggleInstall) {
                Text(stringResource(if (pack.installed) R.string.sticker_uninstall else R.string.sticker_install))
            }
        }
    }
}
