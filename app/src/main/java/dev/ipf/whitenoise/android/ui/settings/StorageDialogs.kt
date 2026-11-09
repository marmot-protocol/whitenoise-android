@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.KeepMediaPeriod
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.RemoveOlderAge
import dev.ipf.whitenoise.android.state.StorageAction
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Which picker or confirmation is open, saved across configuration changes. A Remove older cutoff is captured
 * when the age is picked, so the confirmed date is exactly the one removed.
 */
internal class StorageDialogs(
    val keepPicker: MutableState<Boolean>,
    val pendingKeep: MutableState<KeepMediaPeriod?>,
    val olderPicker: MutableState<Boolean>,
    val olderCutoff: MutableState<Long?>,
    val clearCache: MutableState<Boolean>,
)

/** Saved dialog state for one storage screen. */
@Composable
internal fun rememberStorageDialogs(): StorageDialogs =
    StorageDialogs(
        keepPicker = rememberSaveable { mutableStateOf(false) },
        pendingKeep = rememberSaveable { mutableStateOf(null) },
        olderPicker = rememberSaveable { mutableStateOf(false) },
        olderCutoff = rememberSaveable { mutableStateOf(null) },
        clearCache = rememberSaveable { mutableStateOf(false) },
    )

/** The overview's Keep media, Remove older and Clear confirmations. */
@Composable
internal fun StorageOverviewDialogs(
    state: ManageStorageUiState,
    dialogs: StorageDialogs,
    onAction: (StorageAction) -> Unit,
) {
    KeepMediaDialogs(state.keepMedia, dialogs, onAction)
    RemoveOlderDialogs(dialogs, groupIdHex = null, onAction = onAction)
    if (dialogs.clearCache.value) {
        val freed = storageBytes(state.usage?.clearableCacheBytes ?: 0)
        ConfirmDialog(
            title = stringResource(R.string.storage_clear_cache_title),
            message = stringResource(R.string.storage_clear_cache_message, freed),
            confirmLabel = stringResource(R.string.storage_clear),
            destructive = true,
            onDismiss = { dialogs.clearCache.value = false },
            onConfirm = {
                dialogs.clearCache.value = false
                onAction(StorageAction.ClearCaches)
            },
        )
    }
}

/** The window picker, only a shorter window asks before removing, a longer one applies at once. */
@Composable
private fun KeepMediaDialogs(
    current: KeepMediaPeriod?,
    dialogs: StorageDialogs,
    onAction: (StorageAction) -> Unit,
) {
    if (dialogs.keepPicker.value) {
        val labels = KeepMediaPeriod.entries.associateWith { stringResource(it.labelRes) }
        ChoiceDialog(
            title = stringResource(R.string.storage_keep_media),
            values = KeepMediaPeriod.entries,
            selected = current,
            label = labels::getValue,
            supportingText = stringResource(R.string.storage_keep_media_help),
            onDismiss = { dialogs.keepPicker.value = false },
            onSelect = { period ->
                dialogs.keepPicker.value = false
                when {
                    period == current -> Unit
                    current != null && period.shortens(current) -> dialogs.pendingKeep.value = period
                    else -> onAction(StorageAction.SetKeepMedia(period))
                }
            },
        )
    }
    dialogs.pendingKeep.value?.let { period ->
        val label = stringResource(period.labelRes)
        ConfirmDialog(
            title = stringResource(R.string.storage_keep_confirm_title, label),
            message = stringResource(R.string.storage_keep_confirm_message, label),
            confirmLabel = stringResource(R.string.storage_remove),
            destructive = true,
            onDismiss = { dialogs.pendingKeep.value = null },
            onConfirm = {
                dialogs.pendingKeep.value = null
                onAction(StorageAction.SetKeepMedia(period))
            },
        )
    }
}

/** The age picker and its dated confirmation, across the account or within [groupIdHex]. */
@Composable
internal fun RemoveOlderDialogs(
    dialogs: StorageDialogs,
    groupIdHex: String?,
    onAction: (StorageAction) -> Unit,
) {
    if (dialogs.olderPicker.value) {
        val labels = RemoveOlderAge.entries.associateWith { stringResource(it.labelRes) }
        ChoiceDialog(
            title = stringResource(R.string.storage_remove_older),
            values = RemoveOlderAge.entries,
            selected = null,
            label = labels::getValue,
            onDismiss = { dialogs.olderPicker.value = false },
            onSelect = { age ->
                dialogs.olderPicker.value = false
                dialogs.olderCutoff.value = age.cutoff(Instant.now(), ZoneId.systemDefault()).epochSecond
            },
        )
    }
    dialogs.olderCutoff.value?.let { cutoff ->
        val date = cutoffDate(cutoff)
        val message =
            when (groupIdHex) {
                null -> R.string.storage_remove_older_message
                else -> R.string.storage_remove_older_chat_message
            }
        ConfirmDialog(
            title = stringResource(R.string.storage_remove_older_title),
            message = stringResource(message, date),
            confirmLabel = stringResource(R.string.storage_remove),
            destructive = true,
            onDismiss = { dialogs.olderCutoff.value = null },
            onConfirm = {
                dialogs.olderCutoff.value = null
                onAction(StorageAction.RemoveOlder(cutoff, groupIdHex))
            },
        )
    }
}

/** Confirms removing [count] attachments that free [bytes], from a chat or a selection. */
@Composable
internal fun RemoveAttachmentsDialog(
    count: Long,
    bytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ConfirmDialog(
        title = pluralStringResource(R.plurals.storage_remove_attachments_title, quantity, count),
        message = stringResource(R.string.storage_remove_attachments_message, storageBytes(bytes)),
        confirmLabel = stringResource(R.string.storage_remove),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** Confirms removing every attachment in a chat whose count and size are not known yet. */
@Composable
internal fun RemoveChatAttachmentsDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.storage_remove_chat_title),
        message = stringResource(R.string.storage_remove_chat_message),
        confirmLabel = stringResource(R.string.storage_remove),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** The cutoff as the long local date the confirmation names. */
private fun cutoffDate(cutoffSeconds: Long): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.LONG)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(cutoffSeconds))
