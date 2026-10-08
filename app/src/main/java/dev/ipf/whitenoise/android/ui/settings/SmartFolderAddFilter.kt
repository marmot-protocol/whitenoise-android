@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val density = LocalDensity.current
        val windowHeight = LocalWindowInfo.current.containerSize.height
        var headerHeight by remember { mutableIntStateOf(0) }
        // Keep the four common choices inside the initial half-height viewport. Material owns
        // drag expansion and nested scrolling; every other choice follows directly below them.
        val commonHeight =
            with(density) {
                (windowHeight / 2 - headerHeight).toDp() - 48.dp
            }.coerceAtLeast(224.dp * density.fontScale)
        val common = listOf(FolderField.PARTICIPANTS, FolderField.UNREAD, FolderField.MENTIONS, FolderField.TYPE)
        WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Box(Modifier.onSizeChanged { headerHeight = it.height }) {
                WhiteNoiseSheetHeader(stringResource(R.string.smart_folder_add), onClose = onDismiss)
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().fadingVerticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().height(commonHeight)) {
                    common.forEach { choice ->
                        FilterChoice(choice, Modifier.weight(1f)) { field = choice }
                    }
                }
                FolderField.entries.filterNot { it in common }.forEach { choice ->
                    FilterChoice(choice) { field = choice }
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

@Composable
private fun FilterChoice(
    field: FolderField,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("folder.addField." + field.name),
    ) { FolderButtonLabel(stringResource(fieldLabel(field)), R.drawable.ic_chevron_right) }
}

private fun defaultFolderMode(field: FolderField): FolderMode =
    when (field) {
        FolderField.PARTICIPANTS -> FolderMode.ANY_OF
        FolderField.TYPE -> FolderMode.DIRECT
        FolderField.TITLE -> FolderMode.CONTAINS
        else -> FolderMode.PRESENT
    }
