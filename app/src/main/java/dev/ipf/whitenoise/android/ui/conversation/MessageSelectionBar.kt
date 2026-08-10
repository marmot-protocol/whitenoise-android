package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageSelectionBar(
    count: Int,
    onClose: () -> Unit,
) {
    val selectedCountDescription = pluralStringResource(R.plurals.message_selected_count, count, count)
    TopAppBar(
        title = {
            Text(
                count.toString(),
                modifier = Modifier.semantics { contentDescription = selectedCountDescription },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        },
    )
}
