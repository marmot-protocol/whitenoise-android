@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.ChatHistoryImportStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.state.ChatHistoryImports
import dev.ipf.whitenoise.android.state.ChatHistoryTransfer
import dev.ipf.whitenoise.android.state.ImportedHistorySource
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatHistoryTransfer
import dev.ipf.whitenoise.android.state.importedHistoryBoundary
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val IMPORTED_HISTORY_BOUNDARY_TAG = "conversation.imported_history.boundary"
internal const val HISTORY_TRANSFER_NOTICE_TAG = "conversation.history_transfer"

private const val HISTORY_IMPORT_SUBSCRIPTION_RETRY_MS = 2_000L

/** MDK's history-import state for the conversation on screen; a function so readers follow later changes. */
internal val LocalChatHistoryImport = compositionLocalOf<() -> ChatHistoryImportStateFfi?> { { null } }

/**
 * Binds MDK's history-import state for [groupIdHex] of [accountRef] to this composition, or a constant null in
 * builds without linked devices. While the conversation is started it reads once and then follows MDK's change
 * event; a new holder is made for every account or runtime change so no state crosses accounts.
 */
@Composable
internal fun rememberChatHistoryImport(
    appState: WhiteNoiseAppState,
    accountRef: String,
    groupIdHex: String,
    available: Boolean = linkedDevicesAvailable(),
): () -> ChatHistoryImportStateFfi? {
    if (!available) return NoHistoryImport
    val runtimeGeneration = appState.runtimeGeneration
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val imports =
        remember(accountRef, runtimeGeneration) {
            ChatHistoryImports(
                accountRef = accountRef,
                readImports = { account -> appState.marmotIo { chatHistoryImports(account) } },
                owns = { appState.runtimeGeneration == runtimeGeneration },
            )
        }
    LaunchedEffect(imports, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val subscription = runCatchingCancellable { appState.marmotIo { subscribeEvents() } }.getOrNull()
                try {
                    if (subscription == null) {
                        imports.refresh()
                    } else {
                        imports.observe { withContext(Dispatchers.IO) { subscription.next() } }
                    }
                } finally {
                    subscription?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { it.destroy() } } }
                }
                delay(HISTORY_IMPORT_SUBSCRIPTION_RETRY_MS)
            }
        }
    }
    return remember(imports, groupIdHex) { { imports.state(groupIdHex) } }
}

private val NoHistoryImport: () -> ChatHistoryImportStateFfi? = { null }

/**
 * What a timeline row draws above itself for imported history. At the top of the loaded history, a transfer still
 * under way says so. Where the rows before this one were imported and this one was not, a label names where they
 * came from. Both are drawn inside the row, like the day separator, so no row is added, removed or reordered and
 * scroll position is unaffected while history arrives.
 */
@Composable
internal fun ImportedHistoryMarkers(
    older: TimelineMessage?,
    item: TimelineMessage,
    atHistoryStart: Boolean,
) {
    val state = LocalChatHistoryImport.current()
    if (atHistoryStart) chatHistoryTransfer(state)?.let { ChatHistoryTransferNotice(it) }
    importedHistoryBoundary(older?.projected, item.projected, state)?.let { ImportedHistoryBoundary(it) }
}

/** Says that earlier messages are arriving from another device, or that MDK will try again after a failed attempt. */
@Composable
internal fun ChatHistoryTransferNotice(
    transfer: ChatHistoryTransfer,
    modifier: Modifier = Modifier,
) {
    val text =
        when (transfer) {
            ChatHistoryTransfer.Receiving -> R.string.history_receiving
            ChatHistoryTransfer.Retrying -> R.string.history_retrying
        }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag(HISTORY_TRANSFER_NOTICE_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The line where imported history ends, naming its source. Announced as a heading, like the day separators. */
@Composable
internal fun ImportedHistoryBoundary(
    source: ImportedHistorySource,
    modifier: Modifier = Modifier,
) {
    val text =
        when (source) {
            is ImportedHistorySource.Device -> stringResource(R.string.history_imported_from_device, source.label)
            ImportedHistorySource.AnotherDevice -> stringResource(R.string.history_imported_from_another_device)
            ImportedHistorySource.Backup -> stringResource(R.string.history_restored_from_backup)
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp)
                .testTag(IMPORTED_HISTORY_BOUNDARY_TAG)
                .semantics(mergeDescendants = true) { heading() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
