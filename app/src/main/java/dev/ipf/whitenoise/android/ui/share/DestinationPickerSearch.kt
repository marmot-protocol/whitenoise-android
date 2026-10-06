package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Shares scarce landscape height between search and filters so destinations remain reachable at large text. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun DestinationPickerSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: Int,
    folders: List<Pair<ChatFolder, List<String>>>,
    filter: DestinationFolderFilterState,
    selected: List<String>,
    horizontal: Boolean,
    onFocusChange: (Boolean) -> Unit = {},
) {
    // Both children stay at the same composition location when IME insets change the measure policy.
    Layout(content = {
        DestinationSearchField(query, onQueryChange, placeholder, onFocusChange)
        Box { DestinationFolderFilters(folders, filter, selected, { onQueryChange("") }) }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val childWidth = if (horizontal) width / 2 else width
        val childConstraints = constraints.copy(minWidth = childWidth, maxWidth = childWidth, minHeight = 0)
        val search = measurables[0].measure(childConstraints)
        val filters = measurables[1].measure(childConstraints)
        val gap = if (filters.height == 0) 0 else 8.dp.roundToPx()
        val height = if (horizontal) maxOf(search.height, filters.height) else search.height + gap + filters.height
        layout(width, constraints.constrainHeight(height)) {
            search.placeRelative(0, if (horizontal) (height - search.height) / 2 else 0)
            filters.placeRelative(
                if (horizontal) childWidth else 0,
                if (horizontal) (height - filters.height) / 2 else search.height + gap,
            )
        }
    }
}

/** Preserves the picker's search focus owner when its neighboring filters change layout. */
@Composable
@Suppress("FunctionNaming")
private fun DestinationSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: Int,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(placeholder),
        modifier = modifier.padding(horizontal = Dimens.spaceLg).onFocusChanged { onFocusChange(it.isFocused) },
    )
}
