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
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.marmotkit.ChatHistoryImportStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatHistoryImports
import dev.ipf.whitenoise.android.state.ChatHistoryTransfer
import dev.ipf.whitenoise.android.state.ImportedHistorySource
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatHistoryTransfer
import dev.ipf.whitenoise.android.state.importedHistoryBoundary
import dev.ipf.whitenoise.android.ui.devices.followEventsWhileStarted
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val IMPORTED_HISTORY_BOUNDARY_TAG = "conversation.imported_history.boundary"
internal const val HISTORY_TRANSFER_NOTICE_TAG = "conversation.history_transfer"

/** MDK's history-import state for the conversation on screen; a function so readers follow later changes. */
internal val LocalChatHistoryImport = compositionLocalOf<() -> ChatHistoryImportStateFfi?> { { null } }

/**
 * Reads history imports for [accountRef] and follows MDK changes while the conversation is started. A new
 * holder on each account or runtime change keeps [groupIdHex] scoped to the current profile.
 */
@Composable
internal fun rememberChatHistoryImport(
    appState: WhiteNoiseAppState,
    accountRef: String,
    groupIdHex: String,
): () -> ChatHistoryImportStateFfi? {
    val imports = rememberChatHistoryImports(appState, accountRef)
    return remember(imports, groupIdHex) { { imports.state(groupIdHex) } }
}

/**
 * MDK's history imports for every chat of [accountRef], followed while the caller's lifecycle is started. A new
 * holder on each account or runtime change keeps the list scoped to the current profile.
 */
@Composable
internal fun rememberChatHistoryImports(
    appState: WhiteNoiseAppState,
    accountRef: String,
): ChatHistoryImports {
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
        appState.followEventsWhileStarted(lifecycle) { nextEvent ->
            if (nextEvent == null) imports.refresh() else imports.observe(nextEvent)
        }
    }
    return imports
}

/**
 * What a timeline row draws above itself for imported history, ahead of its day separator. At the top of the loaded
 * history, a transfer still under way says so. Where the rows before this one were imported and this one was not, a
 * label names where they came from, directly under them. Both are drawn inside the row, like the day separator, so
 * no row is added, removed or reordered and scroll position is unaffected while history arrives.
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
