@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.CollectionChunkState
import dev.ipf.whitenoise.android.state.CollectionItem
import dev.ipf.whitenoise.android.state.CollectionItemKind
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.CollectionOperationPhase
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentCollections
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn

private val ITEM_LIST_MAX_HEIGHT = 240.dp
private val SHOW_ITEMS = R.string.attachment_collection_show_items

/** What the user can do to one collection operation from its panel. */
internal class AttachmentCollectionPanelActions(
    val onRetryChunk: (String) -> Unit,
    val onSkipChunk: (String) -> Unit,
    val onCancel: () -> Unit,
    val onResume: () -> Unit,
    val onDiscard: () -> Unit,
)

/**
 * Restores this conversation's journaled collection sends once per open, drops them when the destination is
 * gone, and returns the composer accessory that shows each live operation, or null when none needs a panel.
 */
@Composable
internal fun rememberAttachmentCollectionPanels(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    mediaSender: ConversationMediaSender,
): (@Composable () -> Unit)? {
    val context = LocalContext.current
    val account = controller.boundAccountRef
    val groupIdHex = controller.group.groupIdHex
    val gone = controller.group.disbanded || controller.group.unrecoverable
    LaunchedEffect(account, groupIdHex, gone) {
        if (account == null) return@LaunchedEffect
        if (gone) {
            appState.attachmentCollections.invalidateConversation(account, groupIdHex)
        } else {
            appState.restoreCollectionOperations(account, groupIdHex, readable = { canReadCollectionUri(context, it) })
        }
    }
    val operations by appState.attachmentCollections.operations.collectAsState()
    val shown =
        operations.values
            .filter { it.accountRef == account && it.groupIdHex == groupIdHex && it.needsPanel() }
            .sortedBy { it.createdAtMs }
    if (shown.isEmpty()) return null
    return {
        shown.forEach { operation ->
            AttachmentCollectionProgress(
                operation = operation,
                actions =
                    AttachmentCollectionPanelActions(
                        onRetryChunk = { chunk ->
                            appState.launchMutation { controller.retryCollectionChunk(operation.id, chunk) }
                        },
                        onSkipChunk = { appState.launchMutation { controller.skipCollectionChunk(operation.id, it) } },
                        onCancel = { appState.launchMutation { controller.cancelCollection(operation.id) } },
                        onResume = { mediaSender.resumeCollection(operation.id) },
                        onDiscard = { appState.attachmentCollections.discard(operation.id) },
                    ),
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** Whether a journaled URI can still be opened for reading; call off the main thread. */
internal fun canReadCollectionUri(
    context: android.content.Context,
    uri: String,
): Boolean =
    runCatching {
        context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

/** Whether an operation deserves a panel: several chunks, or any state the user must act on. */
internal fun AttachmentCollectionOperation.needsPanel(): Boolean =
    chunks.size > 1 || phase == CollectionOperationPhase.BLOCKED || phase == CollectionOperationPhase.PAUSED

/**
 * The composer-side progress panel for one collection send: aggregate `sent / total` with a monotonic bar,
 * a status line when a failed chunk blocks the rest or a restart paused it, the matching actions, and an
 * expandable virtualized list of every item with its own state and, when failed, its own Retry.
 */
@Composable
internal fun AttachmentCollectionProgress(
    operation: AttachmentCollectionOperation,
    actions: AttachmentCollectionPanelActions,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(operation.id) { mutableStateOf(false) }
    val phase = operation.phase
    val title =
        if (phase == CollectionOperationPhase.PAUSED) {
            stringResource(R.string.attachment_collection_paused, operation.published, operation.total)
        } else {
            stringResource(R.string.attachment_collection_progress, operation.published, operation.total)
        }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("conversation.attachment.collection.panel"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.attachment_collection_hide_items else SHOW_ITEMS))
                }
            }
            LinearProgressIndicator(
                progress = { operation.settled.toFloat() / operation.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().testTag("conversation.attachment.collection.progress"),
            )
            AttachmentCollectionStatus(operation)
            AttachmentCollectionActionRow(operation, actions)
            if (expanded) AttachmentCollectionItemList(operation, actions)
        }
    }
}

/** The line that explains why nothing is moving, and how many items need picking again. */
@Composable
private fun AttachmentCollectionStatus(operation: AttachmentCollectionOperation) {
    val style = MaterialTheme.typography.bodySmall
    if (operation.phase == CollectionOperationPhase.BLOCKED) {
        Text(stringResource(R.string.attachment_collection_blocked), style = style)
    }
    val unrecoverable = operation.unrecoverableItemIds.size
    if (operation.phase == CollectionOperationPhase.PAUSED && unrecoverable > 0) {
        Text(
            pluralStringResource(R.plurals.attachment_collection_unrecoverable, unrecoverable, unrecoverable),
            style = style,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Resume and Discard for a paused operation, Retry, Skip and Cancel for a blocked one, Cancel otherwise. */
@Composable
private fun AttachmentCollectionActionRow(
    operation: AttachmentCollectionOperation,
    actions: AttachmentCollectionPanelActions,
) {
    val blocked = operation.nextChunk?.takeIf { it.state == CollectionChunkState.FAILED }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            operation.phase == CollectionOperationPhase.PAUSED -> {
                TextButton(onClick = actions.onResume) { Text(stringResource(R.string.attachment_collection_resume)) }
                TextButton(onClick = actions.onDiscard) { Text(stringResource(R.string.discard)) }
            }
            blocked != null -> {
                TextButton(onClick = { actions.onRetryChunk(blocked.id) }) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = { actions.onSkipChunk(blocked.id) }) {
                    Text(stringResource(R.string.attachment_collection_skip_failed))
                }
                TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.attachment_collection_cancel)) }
            }
            !operation.cancelRequested ->
                TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.attachment_collection_cancel)) }
        }
    }
}

/** Every item in send order with its state; a failed item retries its own chunk. Only visible rows compose. */
@Composable
private fun AttachmentCollectionItemList(
    operation: AttachmentCollectionOperation,
    actions: AttachmentCollectionPanelActions,
) {
    val chunkOf = operation.chunks.flatMap { chunk -> chunk.itemIds.map { it to chunk.id } }.toMap()
    WhiteNoiseLazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = ITEM_LIST_MAX_HEIGHT)
                .testTag("conversation.attachment.collection.items"),
    ) {
        itemsIndexed(operation.items, key = { _, item -> item.id }) { index, item ->
            val name = collectionItemName(item, index + 1)
            val state = stringResource(item.state.labelResource())
            Row(
                modifier =
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$name, $state" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(state, style = MaterialTheme.typography.labelMedium)
                val chunkId = chunkOf[item.id]
                if (item.state == CollectionItemState.FAILED && chunkId != null) {
                    TextButton(onClick = { actions.onRetryChunk(chunkId) }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

/** A position-numbered name that says whether the item is visual media or a file. */
@Composable
private fun collectionItemName(
    item: CollectionItem,
    position: Int,
): String =
    if (item.kind == CollectionItemKind.MEDIA) {
        stringResource(R.string.attachment_collection_item_media, position)
    } else {
        stringResource(R.string.attachment_collection_item_document, position)
    }

/** The localized label for an item state. */
internal fun CollectionItemState.labelResource(): Int =
    when (this) {
        CollectionItemState.QUEUED -> R.string.attachment_collection_state_queued
        CollectionItemState.PREPARING -> R.string.attachment_collection_state_preparing
        CollectionItemState.UPLOADING -> R.string.attachment_collection_state_uploading
        CollectionItemState.PUBLISHED -> R.string.attachment_collection_state_published
        CollectionItemState.FAILED -> R.string.attachment_collection_state_failed
        CollectionItemState.CANCELLED -> R.string.attachment_collection_state_cancelled
        CollectionItemState.SKIPPED -> R.string.attachment_collection_state_skipped
    }
