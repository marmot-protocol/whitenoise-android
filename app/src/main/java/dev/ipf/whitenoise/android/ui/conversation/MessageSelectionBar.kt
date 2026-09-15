package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.ipf.whitenoise.android.R

/** Prototype selection title retains the real selected-count accessibility state and native close callback. */
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
                stringResource(R.string.conversation_select_messages),
                modifier = Modifier.semantics { stateDescription = selectedCountDescription },
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
            }
        },
    )
}
