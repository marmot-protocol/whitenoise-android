package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageSelectionBar(
    count: Int,
    canCopy: Boolean,
    canForward: Boolean,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(count.toString()) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        },
        actions = {
            IconButton(onClick = onCopy, enabled = canCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
            }
            IconButton(onClick = onForward, enabled = canForward) {
                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.forward))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        },
    )
}
