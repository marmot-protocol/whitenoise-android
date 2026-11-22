package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatFolderSort
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress

/** Everything the editor renders; the text fields are shared state so typing needs no round trip. */
internal data class ChatFolderEditFormState(
    val isNew: Boolean,
    val name: TextFieldState,
    val description: TextFieldState,
    val keyword: TextFieldState,
    val unreadOnly: Boolean,
    val includeMuted: Boolean,
    val groupsOnly: Boolean,
    val archivedOnly: Boolean,
    val manualChatCount: Int,
    val peopleCount: Int,
    val previewCount: Int,
    val canSave: Boolean,
    val canDelete: Boolean = !isNew,
    val error: String? = null,
    val unreadMentionsOnly: Boolean = false,
    val directChatsOnly: Boolean = false,
    val pinnedOnly: Boolean = false,
    val advancedRules: Boolean = false,
    val showWhenEmpty: Boolean = false,
    val sort: ChatFolderSort = ChatFolderSort.RECENT,
    val includeAll: Boolean = false,
)

/** Everything an existing folder autosaves, compared with what the store last accepted. */
internal data class FolderDraft(
    val name: String,
    val description: String,
    val rule: ChatFolderRule,
    val manualChatIds: Set<String>,
    val showWhenEmpty: Boolean,
    val sort: ChatFolderSort,
)

/** Save for a new folder; existing folders autosave instead. */
@Composable
@Suppress("FunctionNaming")
internal fun ChatFolderSaveAction(
    enabled: Boolean,
    onSave: () -> Unit,
) {
    TextButton(
        enabled = enabled,
        onClick = onSave,
        modifier =
            Modifier
                .testTag("folder.save")
                .explainOnLongPress(
                    Explanation(
                        stringResource(R.string.save),
                        stringResource(R.string.explanation_organize_folder_save),
                    ),
                ),
    ) { Text(stringResource(R.string.save)) }
}

/** Confirms leaving a new folder draft; dismissing keeps editing. */
@Composable
@Suppress("FunctionNaming")
internal fun ChatFolderDiscardDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.folder_discard_title)) },
        text = { Text(stringResource(R.string.folder_discard_detail)) },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                modifier =
                    Modifier.explainOnLongPress(
                        Explanation(
                            stringResource(R.string.folder_discard),
                            stringResource(R.string.explanation_organize_folder_discard),
                        ),
                    ),
            ) { Text(stringResource(R.string.folder_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.folder_keep_editing)) }
        },
    )
}

/** Optional metadata is revealed only when needed; existing descriptions remain visible. */
@Composable
internal fun folderDescription(description: TextFieldState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (expanded || description.text.isNotEmpty()) {
        WhiteNoiseTextField(
            state = description,
            emojiAction = true,
            modifier = Modifier.fillMaxWidth().testTag("folder.description"),
            label = { Text(stringResource(R.string.chat_folder_description_label)) },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 4),
        )
    } else {
        val addLabel = stringResource(R.string.smart_folder_add_description)
        TextButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .testTag("folder.addDescription")
                    .explainOnLongPress(
                        Explanation(
                            addLabel,
                            stringResource(R.string.explanation_organize_folder_add_description),
                        ),
                    ),
        ) {
            Text(addLabel)
        }
    }
}

/** Adds [id] when absent and removes it when present. */
internal fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/** Which picker sheet is open. */
internal enum class FolderPicker { Chats, People, Preview }

/** Only unsaved selected identifiers survive rotation; the existing per-account store remains authoritative. */
internal val FolderSelectionSaver =
    Saver<Set<String>, List<String>>(
        save = { it.toList() },
        restore = { it.toSet() },
    )

internal fun folderSortExplanation(sort: ChatFolderSort): Int =
    when (sort) {
        ChatFolderSort.RECENT -> R.string.explanation_organize_folder_sort_recent
        ChatFolderSort.NAME -> R.string.explanation_organize_folder_sort_name
        ChatFolderSort.UNREAD -> R.string.explanation_organize_folder_sort_unread
    }

/** Stable choices are persisted independently for each local folder. */
internal fun folderSortLabel(sort: ChatFolderSort): Int =
    when (sort) {
        ChatFolderSort.RECENT -> R.string.folder_sort_recent
        ChatFolderSort.NAME -> R.string.folder_sort_name
        ChatFolderSort.UNREAD -> R.string.folder_sort_unread
    }
