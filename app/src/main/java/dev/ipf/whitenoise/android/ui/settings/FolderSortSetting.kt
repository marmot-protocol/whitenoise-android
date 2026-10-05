package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderSortOrder
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog

/** One compact editor row; choosing an order changes only the draft until the parent saves it. */
@Composable
@Suppress("FunctionNaming")
internal fun FolderSortSetting(
    order: ChatFolderSortOrder,
    onChange: (ChatFolderSortOrder) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SettingsGroup {
        row("sort") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.folder_sort_title),
                value = stringResource(folderSortLabel(order)),
                onClick = { choosing = true },
                modifier = Modifier.testTag("folder.sort"),
            )
        }
    }
    if (choosing) {
        FolderSortDialog(order, {
            onChange(it)
            choosing = false
        }, { choosing = false })
    }
}

/** Accessible choices explain why pending and manually pinned rows remain above alphabetical results. */
@Composable
@Suppress("FunctionNaming")
internal fun FolderSortDialog(
    order: ChatFolderSortOrder,
    onChange: (ChatFolderSortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_sort_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.folder_sort_precedence))
                SettingsGroup {
                    ChatFolderSortOrder.entries.forEach { choice ->
                        row(choice.name) { context ->
                            SettingsChoice(
                                context = context,
                                title = stringResource(folderSortLabel(choice)),
                                selected = order == choice,
                                onClick = { onChange(choice) },
                                modifier = Modifier.testTag("folder.sort.${choice.name}"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shares the localized name between the saved summary and selectable options. */
internal fun folderSortLabel(order: ChatFolderSortOrder): Int =
    when (order) {
        ChatFolderSortOrder.RECENT -> R.string.folder_sort_recent
        ChatFolderSortOrder.NAME -> R.string.folder_sort_name
        ChatFolderSortOrder.UNREAD -> R.string.folder_sort_unread
    }
