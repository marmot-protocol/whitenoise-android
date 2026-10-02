@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader

/** Picking and configuring a new filter never changes the draft until the user saves it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartFolderAddFilter(
    people: List<WhiteNoisePickerItem>,
    resolveKey: suspend (String) -> String?,
    onDismiss: () -> Unit,
    onDone: (SmartFolderFilter.Condition) -> Unit,
) {
    var field by rememberSaveable { mutableStateOf<FolderField?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    val selected = field
    if (selected == null) {
        WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
            WhiteNoiseSheetHeader(stringResource(R.string.smart_folder_add), onClose = onDismiss)
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                val common = listOf(FolderField.PARTICIPANTS, FolderField.UNREAD, FolderField.MENTIONS)
                val choices = if (more) common + FolderField.entries.filterNot { it in common } else common
                choices.forEach { choice ->
                    TextButton(
                        onClick = { field = choice },
                        modifier = Modifier.fillMaxWidth().testTag("folder.addField." + choice.name),
                    ) { FolderButtonLabel(stringResource(fieldLabel(choice)), R.drawable.ic_chevron_right) }
                }
                if (!more) {
                    TextButton(onClick = { more = true }, modifier = Modifier.testTag("folder.moreFilters")) {
                        Text(stringResource(R.string.smart_folder_more_filters))
                    }
                }
            }
        }
    } else {
        key(selected) {
            SmartFolderConditionDialog(
                initial = SmartFolderFilter.Condition(selected, defaultFolderMode(selected)),
                people = people,
                resolveKey = resolveKey,
                onDismiss = onDismiss,
                onRemove = null,
                onDone = onDone,
            )
        }
    }
}

private fun defaultFolderMode(field: FolderField): FolderMode =
    when (field) {
        FolderField.PARTICIPANTS -> FolderMode.ANY_OF
        FolderField.TYPE -> FolderMode.DIRECT
        FolderField.TITLE -> FolderMode.CONTAINS
        else -> FolderMode.PRESENT
    }
