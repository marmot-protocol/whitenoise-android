package dev.ipf.whitenoise.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.labelRes
import dev.ipf.whitenoise.android.ui.common.whiteNoiseDialogSelection

internal const val GLOBAL_SEARCH_CONTENT_FILTER_TAG = "global-search-content-filter"

/** Stable identity preserved for the existing typed-filter state and tests. */
internal fun globalSearchContentChipTag(kind: GlobalSearchContentKind): String {
    val kindName = kind.name
    return "global-search-content-chip-" + kindName
}

/** Prototype check rows for existing typed state; execution availability stays caller-owned. */
@Suppress("FunctionNaming")
@Composable
internal fun GlobalSearchContentFilterChips(
    selection: GlobalSearchContentFilterSelection,
    onSelectionChange: (GlobalSearchContentFilterSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .testTag(GLOBAL_SEARCH_CONTENT_FILTER_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlobalSearchContentKind.entries.forEach { kind ->
            GlobalSearchContentChip(
                kind = kind,
                selected = kind in selection.selectedKinds,
                onClick = { onSelectionChange(selection.toggle(kind)) },
                modifier = Modifier.testTag(globalSearchContentChipTag(kind)),
            )
        }
    }
}

/** One checkable target with native selected semantics and at least 48 dp for every content kind. */
@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchContentChip(
    kind: GlobalSearchContentKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(kind.labelRes())
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .whiteNoiseDialogSelection(selected)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics {
                contentDescription = label
                this.selected = selected
            }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(selected, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
