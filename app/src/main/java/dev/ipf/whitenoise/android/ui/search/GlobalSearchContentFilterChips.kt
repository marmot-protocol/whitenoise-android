package dev.ipf.whitenoise.android.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind

internal const val GLOBAL_SEARCH_CONTENT_FILTER_TAG = "global-search-content-filter"

@Suppress("MaxLineLength")
internal fun globalSearchContentChipTag(kind: GlobalSearchContentKind): String = "global-search-content-chip-${kind.name}"

@Suppress("FunctionNaming")
@Composable
internal fun GlobalSearchContentFilterChips(
    selection: GlobalSearchContentFilterSelection,
    onSelectionChange: (GlobalSearchContentFilterSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(GLOBAL_SEARCH_CONTENT_FILTER_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchContentChip(
    kind: GlobalSearchContentKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(kind.labelRes())
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier =
            modifier.semantics {
                contentDescription = label
                role = Role.Checkbox
                this.selected = selected
            },
    )
}

@StringRes
private fun GlobalSearchContentKind.labelRes(): Int =
    when (this) {
        GlobalSearchContentKind.TEXT -> R.string.global_search_content_text
        GlobalSearchContentKind.LINKS -> R.string.global_search_content_links
        GlobalSearchContentKind.IMAGES_VIDEO -> R.string.global_search_content_images_video
        GlobalSearchContentKind.VOICE_AUDIO -> R.string.global_search_content_voice_audio
        GlobalSearchContentKind.FILES_DOCUMENTS -> R.string.global_search_content_files_documents
        GlobalSearchContentKind.ANY_ATTACHMENT -> R.string.global_search_content_any_attachment
    }
