@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import dev.ipf.whitenoise.android.R

/** Adds/removes only this complete visible result; selections outside it remain untouched. */
internal fun forwardSelectionAfterVisibleToggle(
    selected: List<String>,
    visibleIds: List<String>,
): List<String> = forwardSelectionAfterFolderToggle(selected, visibleIds)

/** A partial result can be browsed, but cannot authorize a bulk destination selection. */
@Composable
internal fun ForwardVisibleSelectionAction(
    selected: List<String>,
    visibleIds: List<String>,
    complete: Boolean,
    onSelectionChange: (List<String>) -> Unit,
) {
    val allSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
    val label = stringResource(if (allSelected) R.string.chat_list_deselect_all else R.string.chat_list_select_all)
    IconButton(
        onClick = { onSelectionChange(forwardSelectionAfterVisibleToggle(selected, visibleIds)) },
        enabled = complete && visibleIds.isNotEmpty(),
        modifier = Modifier.testTag("forward.selectAll"),
    ) {
        Icon(painterResource(if (allSelected) R.drawable.ic_deselect_all else R.drawable.ic_select_all), label)
    }
}
