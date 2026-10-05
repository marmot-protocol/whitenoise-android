package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
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
    if (horizontal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DestinationSearchField(query, onQueryChange, placeholder, onFocusChange, Modifier.weight(1f))
            DestinationFolderFilters(folders, filter, selected, { onQueryChange("") }, Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DestinationSearchField(query, onQueryChange, placeholder, onFocusChange)
            DestinationFolderFilters(folders, filter, selected, { onQueryChange("") })
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
