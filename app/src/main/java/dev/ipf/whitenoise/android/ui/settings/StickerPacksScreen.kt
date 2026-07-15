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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    var operationOwner by remember(account) { mutableStateOf<Any?>(null) }
    var busyOwner by remember(account) { mutableStateOf<Any?>(null) }
    var error by remember(account) { mutableStateOf<String?>(null) }
    var preview by remember(account) { mutableStateOf<StickerPackFfi?>(null) }
    val operationMutex = remember(account) { Mutex() }
    val scope = rememberCoroutineScope()
    val latestInitialInput by rememberUpdatedState(initialInput)
    val latestInitialInputConsumed by rememberUpdatedState(onInitialInputConsumed)
    val unsupportedImportError = stringResource(R.string.sticker_external_signer_unsupported)
    val genericStickerError = stringResource(R.string.sticker_operation_failed)
    val operationInProgress = operationOwner != null
    val busy = busyOwner != null

    suspend fun reload(
        current: String,
        requestedSearch: String?,
    ) {
        val loaded =
            appState.marmotIo {
                stickerPacks(
                    accountRef = current,
                    installedOnly = false,
                    search = requestedSearch,
                    limit = 100u,
                )
            }
        if (
            shouldApplyStickerPackReload(
                requestedAccount = current,
                requestedSearch = requestedSearch,
                activeAccount = appState.activeAccountRef,
                activeSearch = search.trim().takeIf { it.isNotEmpty() },
            )
        ) {
            packs = loaded
        }
    }

    suspend fun runAction(
        showBusy: Boolean = true,
        reportFailure: Boolean = true,
        action: suspend (String) -> Unit,
    ) {
        val requestedAccount = account ?: return
        operationMutex.withLock {
            if (appState.activeAccountRef != requestedAccount) return@withLock
            val owner = Any()
            operationOwner = owner
            if (showBusy) busyOwner = owner
            if (reportFailure) error = null
            try {
                action(requestedAccount)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                if (reportFailure && appState.activeAccountRef == requestedAccount) {
                    error = sanitizedStickerActionError(failure, unsupportedImportError, genericStickerError)
                }
            } finally {
                if (busyOwner === owner) busyOwner = null
                if (operationOwner === owner) operationOwner = null
            }
        }
    }

    suspend fun processStickerInput(
        stickerInput: StickerInput,
        onStarted: () -> Unit = {},
    ) {
        runAction { current ->
            // Clear inbound Signal key material only after this operation owns
            // the account-scoped lock, but still before entering native code.
            onStarted()
            when (stickerInput.kind) {
                StickerInputKind.Pack -> {
                    val loadedPreview = appState.marmotIo { fetchStickerPack(current, stickerInput.value) }
                    if (appState.activeAccountRef == current) preview = loadedPreview
                }

                StickerInputKind.SignalImport -> {
                    val result =
                        appState.marmotIo {
                            importSignalStickerPack(current, stickerInput.value, null)
                        }
                    if (appState.activeAccountRef == current) preview = result.pack
                    reload(current, search.trim().takeIf { it.isNotEmpty() })
                }
            }
        }
    }

    LaunchedEffect(account) {
        if (account == null) return@LaunchedEffect
        runAction { current ->
            reload(current, search.trim().takeIf { it.isNotEmpty() })
            runCatching { appState.marmotIo { syncStickerPacks(current) } }
                .onFailure { if (it is CancellationException) throw it }
            reload(current, search.trim().takeIf { it.isNotEmpty() })
        }
    }

    LaunchedEffect(search, account) {
        if (account == null) return@LaunchedEffect
        val requestedSearch = search.trim().takeIf { it.isNotEmpty() }
        delay(250)
        runAction(showBusy = false, reportFailure = false) { current ->
            reload(current, requestedSearch)
        }
    }

    LaunchedEffect(account) {
        if (account == null) return@LaunchedEffect
        snapshotFlow { latestInitialInput }
            .filterNotNull()
            .collect { pending ->
                input = ""
                processStickerInput(pending) {
                    latestInitialInputConsumed(pending)
                }
            }
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
                        enabled = !operationInProgress && account != null,
                        onClick = {
                            scope.launch {
                                runAction { current ->
                                    appState.marmotIo { syncStickerPacks(current) }
                                    reload(current, search.trim().takeIf { it.isNotEmpty() })
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
                        enabled = !operationInProgress && StickerLinks.classify(input) != null && account != null,
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
                    enabled = !operationInProgress,
                    onOpen = { preview = packs[index] },
                    onToggleInstall = {
                        val selected = packs[index]
                        scope.launch {
                            runAction { current ->
                                if (selected.installed) {
                                    appState.marmotIo { uninstallStickerPack(current, selected.coordinate) }
                                } else {
                                    appState.marmotIo { installStickerPack(current, selected.coordinate) }
                                }
                                reload(current, search.trim().takeIf { it.isNotEmpty() })
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
                    enabled = !operationInProgress && account != null,
                    onClick = {
                        scope.launch {
                            runAction { current ->
                                if (pack.installed) {
                                    appState.marmotIo { uninstallStickerPack(current, pack.coordinate) }
                                } else {
                                    appState.marmotIo { installStickerPack(current, pack.coordinate) }
                                }
                                if (appState.activeAccountRef == current) preview = null
                                reload(current, search.trim().takeIf { it.isNotEmpty() })
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

internal fun shouldApplyStickerPackReload(
    requestedAccount: String,
    requestedSearch: String?,
    activeAccount: String?,
    activeSearch: String?,
): Boolean = requestedAccount == activeAccount && requestedSearch == activeSearch

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
