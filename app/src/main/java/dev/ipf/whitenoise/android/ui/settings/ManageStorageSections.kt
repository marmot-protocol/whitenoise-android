@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatStorageEntry
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StorageAction
import dev.ipf.whitenoise.android.state.StorageMigration
import dev.ipf.whitenoise.android.state.StorageUsage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private val StorageChatAvatarSize = 40.dp

/** The usage panel, a running migration's notice, and a retry row when figures could not be read. */
internal fun LazyListScope.storageUsageItems(
    state: ManageStorageUiState,
    onRefresh: () -> Unit,
) {
    item(key = "search.ManageStorage.1") { StorageUsagePanel(state.usage) }
    state.usage?.migration?.let { migration ->
        item(key = "storage.migration") { StorageMigrationCallout(migration, state.usage) }
    }
    if (state.usageFailed || !state.available) {
        item(key = "storage.usage.failed") {
            SettingsCallout(stringResource(R.string.storage_load_failed), isError = true)
            SettingsGroup {
                row("retry") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.storage_retry),
                        onClick = onRefresh,
                        enabled = state.available && !state.busy,
                        modifier = Modifier.testTag("storage.retry"),
                    )
                }
            }
        }
    }
}

/** Explains a background storage change, nothing is deleted while it runs. */
@Composable
private fun StorageMigrationCallout(
    migration: StorageMigration,
    usage: StorageUsage,
) {
    val bytes = storageBytes(usage.migrationBytes)
    val (title, text) =
        when (migration) {
            StorageMigration.Moving ->
                stringResource(R.string.storage_migration_moving_title) to
                    stringResource(R.string.storage_migration_moving, bytes)
            StorageMigration.Paused ->
                stringResource(R.string.storage_migration_paused_title) to
                    stringResource(R.string.storage_migration_paused, bytes)
            StorageMigration.Compacting ->
                stringResource(R.string.storage_migration_compacting_title) to
                    stringResource(R.string.storage_migration_compacting)
        }
    SettingsCallout(text = text, title = title, modifier = Modifier.testTag("storage.migration"))
}

/** Largest attachments, Remove older attachments and Clear pictures and temporary files. */
internal fun LazyListScope.storageFreeUpItems(
    state: ManageStorageUiState,
    dialogs: StorageDialogs,
    onOpenList: (String) -> Unit,
) {
    val usage = state.usage
    val ready = state.available && !state.busy && usage != null
    val hasAttachments = (usage?.attachmentCount ?: 0) > 0
    item(key = "search.ManageStorage.2") { SettingsSection(stringResource(R.string.storage_free_up_space)) }
    item(key = "search.ManageStorage.3") {
        SettingsGroup(modifier = Modifier.testTag("storage.free_up")) {
            row("largest") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.storage_largest_attachments),
                    onClick = { onOpenList(LARGEST_ATTACHMENTS_LIST) },
                    enabled = ready && hasAttachments,
                    modifier = Modifier.testTag("storage.largest"),
                )
            }
            row("older") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.storage_remove_older),
                    onClick = { dialogs.olderPicker.value = true },
                    enabled = ready && hasAttachments,
                    busy = state.action is StorageAction.RemoveOlder,
                    modifier = Modifier.testTag("storage.remove_older"),
                )
            }
            row("cache") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.storage_clear_cache),
                    subtitle = stringResource(R.string.storage_clear_cache_subtitle),
                    onClick = { dialogs.clearCache.value = true },
                    enabled = ready && (usage?.clearableCacheBytes ?: 0) > 0,
                    busy = state.action == StorageAction.ClearCaches,
                    trailing = {
                        Text(
                            storageBytes(usage?.clearableCacheBytes ?: 0),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.testTag("storage.clear_cache"),
                )
            }
        }
    }
    item(key = "search.ManageStorage.4") { SettingsExplainer(stringResource(R.string.storage_removal_help)) }
}

/** The account's Keep media window and what changing it does. */
internal fun LazyListScope.storageKeepMediaItems(
    state: ManageStorageUiState,
    dialogs: StorageDialogs,
) {
    item(key = "search.ManageStorage.5") { SettingsSection(stringResource(R.string.storage_keep_media)) }
    item(key = "search.ManageStorage.6") {
        SettingsGroup(modifier = Modifier.testTag("storage.keep_media")) {
            row("keep") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.storage_keep_media),
                    onClick = { dialogs.keepPicker.value = true },
                    value =
                        state.keepMedia?.let { stringResource(it.labelRes) }
                            ?: stringResource(keepMediaPlaceholder(state.keepMediaFailed)),
                    enabled = state.available && !state.busy && state.keepMedia != null,
                    busy = state.action is StorageAction.SetKeepMedia,
                    modifier = Modifier.testTag("storage.keep_media.action"),
                )
            }
        }
    }
    item(key = "search.ManageStorage.7") { SettingsExplainer(stringResource(R.string.storage_keep_media_help)) }
}

/** What the Keep media row shows before its window is known. */
private fun keepMediaPlaceholder(failed: Boolean): Int =
    when (failed) {
        true -> R.string.storage_keep_media_failed
        false -> R.string.storage_calculating
    }

/** Chats by attachment size, paging in as the list nears its end. */
internal fun LazyListScope.storageChatItems(
    appState: WhiteNoiseAppState,
    state: ManageStorageUiState,
    actions: ManageStorageActions,
) {
    item(key = "search.ManageStorage.8") { SettingsSection(stringResource(R.string.storage_chats)) }
    if (state.chats.isNotEmpty()) {
        item(key = "storage.chats") {
            SettingsGroup(modifier = Modifier.testTag("storage.chats")) {
                state.chats.forEach { entry ->
                    row(entry.groupIdHex) { context ->
                        StorageChatRow(appState, entry, context, state.available && !state.busy) {
                            actions.onOpenList(entry.groupIdHex)
                        }
                    }
                }
            }
        }
    }
    item(key = "storage.chats.footer") {
        when {
            state.chatsFailed ->
                StorageListRetry(R.string.storage_chats_failed, enabled = state.available && !state.busy) {
                    if (state.chats.isEmpty()) actions.onRefresh() else actions.onLoadMoreChats()
                }
            state.chatsComplete && state.chats.isEmpty() ->
                SettingsExplainer(stringResource(R.string.storage_no_attachments))
            !state.chatsComplete -> {
                // The footer composes only once scrolled into view, so the next page loads on approach.
                LaunchedEffect(state.chats.size) { actions.onLoadMoreChats() }
                StorageLoadingRow()
            }
            else -> SettingsExplainer(stringResource(R.string.storage_chats_help))
        }
    }
}

/** One chat: its picture, title, attachment count and size. A chat no longer listed keeps a neutral title. */
@Composable
private fun StorageChatRow(
    appState: WhiteNoiseAppState,
    entry: ChatStorageEntry,
    context: SettingsRowContext,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val item = storageChatItem(appState, entry.groupIdHex)
    val title = storageChatTitle(appState, entry.groupIdHex)
    val count = entry.attachmentCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    SettingsLink(
        context = context,
        title = title,
        subtitle = pluralStringResource(R.plurals.storage_attachment_count, count, entry.attachmentCount),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag("storage.chat"),
        leading =
            item?.let {
                {
                    GroupAvatar(
                        appState = appState,
                        group = it.group,
                        title = title,
                        seed = it.selectedAvatarSeed ?: it.group.groupIdHex,
                        size = StorageChatAvatarSize,
                        durableAvatar = it.selectedAvatarAsset,
                    )
                }
            },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                Text(
                    storageBytes(entry.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
            }
        },
    )
}

/** The chat-list row for [groupIdHex] in the active account, including archived chats. */
internal fun storageChatItem(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
): ChatListItem? {
    val matches: (ChatListItem) -> Boolean = { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
    return appState.chatListItems.firstOrNull(matches) ?: appState.archivedChatListItems.firstOrNull(matches)
}

/** Fixed chat titles for previews and screenshot tests, which have no chat list, null defers to the chat list. */
internal val LocalStorageChatTitles = staticCompositionLocalOf<(String) -> String?> { { null } }

/** The chat's listed title, or a neutral one when the chat is no longer listed. */
@Composable
internal fun storageChatTitle(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
): String =
    LocalStorageChatTitles.current(groupIdHex)
        ?: appState.activeAccountRef?.let { appState.chatTitle(it, groupIdHex) }
        ?: stringResource(R.string.storage_chat_unavailable)
