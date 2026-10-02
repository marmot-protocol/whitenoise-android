@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

@Composable
internal fun <T> FolderChoice(
    label: String,
    options: List<Pair<T, String>>,
    tag: String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            FolderButtonLabel(label, R.drawable.ic_expand_more, open)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}

private const val EXPANDED_ROTATION = 180f

@Composable
internal fun FolderButtonLabel(
    label: String,
    icon: Int,
    expanded: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp).rotate(if (expanded) EXPANDED_ROTATION else 0f),
        )
    }
}
