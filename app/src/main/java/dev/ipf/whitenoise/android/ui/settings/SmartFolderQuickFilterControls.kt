@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter

/** Three-state controls edit the same draft tree as Advanced; neither half selected means unrestricted. */
@Composable
@Suppress("LongMethod") // One bounded paired-control list, with the same draft callback for every predicate.
internal fun SmartFolderQuickFilterControls(
    root: SmartFolderFilter.Group,
    onChange: (SmartFolderFilter.Group) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.smart_folder_quick_hint), style = MaterialTheme.typography.bodySmall)
        root.quickMode(FolderField.ACCEPTED)?.let { mode ->
            Text(
                stringResource(
                    if (mode == FolderMode.PRESENT) {
                        R.string.smart_folder_quick_accepted
                    } else {
                        R.string.smart_folder_quick_not_accepted
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (root.children.isEmpty()) {
            Text(stringResource(R.string.smart_folder_empty), style = MaterialTheme.typography.bodySmall)
        }
        quickFolderFields.forEach { field ->
            val positive = if (field == FolderField.TYPE) FolderMode.DIRECT else FolderMode.PRESENT
            val negative = if (field == FolderField.TYPE) FolderMode.GROUP else FolderMode.NONE
            val selection = root.quickMode(field)
            val stateLabel =
                selection?.let { stringResource(quickFilterLabel(field, it)) }
                    ?: stringResource(R.string.smart_folder_quick_neutral)
            val rowState =
                stringResource(
                    R.string.smart_folder_quick_state,
                    stringResource(fieldLabel(field)),
                    stateLabel,
                )
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(positive, negative).forEach { mode ->
                    val active = root.quickMode(field) == mode
                    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    OutlinedButton(
                        onClick = { onChange(root.toggleQuickFilter(field, mode)) },
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .heightIn(min = 48.dp)
                                .testTag("folder.quick.${field.name}.${mode.name}")
                                .semantics {
                                    selected = active
                                    stateDescription = rowState
                                },
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = color,
                            ),
                        border = BorderStroke(if (active) 2.dp else 1.dp, color),
                    ) {
                        if (active) {
                            Icon(
                                painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(stringResource(quickFilterLabel(field, mode)))
                    }
                }
            }
        }
        TextButton(
            onClick = { onChange(root.resetQuickFilters()) },
            enabled = quickFolderFields.any { root.quickMode(it) != null },
            modifier = Modifier.testTag("folder.quick.reset"),
        ) { Text(stringResource(R.string.smart_folder_quick_reset)) }
    }
}

private fun quickFilterLabel(
    field: FolderField,
    mode: FolderMode,
): Int =
    when (field) {
        FolderField.UNREAD ->
            if (mode == FolderMode.PRESENT) R.string.smart_folder_quick_unread else R.string.smart_folder_no_unread
        FolderField.MENTIONS ->
            if (mode == FolderMode.PRESENT) R.string.smart_folder_has_mentions else R.string.smart_folder_no_mentions
        FolderField.DRAFT ->
            if (mode == FolderMode.PRESENT) R.string.smart_folder_has_draft else R.string.smart_folder_no_draft
        FolderField.MUTED ->
            if (mode == FolderMode.PRESENT) R.string.smart_folder_muted else R.string.smart_folder_quick_not_muted
        FolderField.PINNED ->
            if (mode == FolderMode.PRESENT) R.string.smart_folder_pinned else R.string.smart_folder_quick_not_pinned
        FolderField.ARCHIVED ->
            if (mode == FolderMode.PRESENT) R.string.smart_folder_archived else R.string.smart_folder_quick_not_archived
        else -> if (mode == FolderMode.DIRECT) R.string.smart_folder_direct else R.string.smart_folder_chat_group
    }
