@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll

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
    val selected = field
    if (selected == null) {
        val common = listOf(FolderField.PARTICIPANTS, FolderField.UNREAD, FolderField.MENTIONS, FolderField.TYPE)
        val choices = common + FolderField.entries.filterNot { it in common }
        WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
            WhiteNoiseSheetHeader(stringResource(R.string.smart_folder_add), onClose = onDismiss)
            // Natural row heights keep every choice close together. The sheet wraps
            // short content and scrolls when text or the window needs more room.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .fadingVerticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("folder.filterChoices"),
            ) {
                choices.forEach { choice -> FilterChoice(choice) { field = choice } }
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

@Composable
private fun FilterChoice(
    field: FolderField,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("folder.addField." + field.name),
    ) { FolderButtonLabel(stringResource(fieldLabel(field)), R.drawable.ic_chevron_right) }
}

private fun defaultFolderMode(field: FolderField): FolderMode =
    when (field) {
        FolderField.PARTICIPANTS -> FolderMode.ANY_OF
        FolderField.TYPE -> FolderMode.DIRECT
        FolderField.TITLE -> FolderMode.CONTAINS
        else -> FolderMode.PRESENT
    }
