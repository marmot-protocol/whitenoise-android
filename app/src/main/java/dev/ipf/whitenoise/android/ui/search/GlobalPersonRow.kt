package dev.ipf.whitenoise.android.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults

/** A resolved public identifier opens the existing profile owner; no unavailable metadata is invented. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun GlobalPersonRow(
    npub: String,
    onOpen: (String) -> Unit,
) {
    ListItem(
        onClick = { onOpen(npub) },
        shapes = WhiteNoiseListItemDefaults.shapes(),
        modifier = Modifier.testTag("global.person." + npub),
        leadingContent = { Avatar(IdentityFormatter.short(npub), npub, 40.dp, pictureUrl = null) },
    ) {
        Column {
            Text(stringResource(R.string.chat_list_search_open_profile))
            Text(
                IdentityFormatter.short(npub, prefix = 12, suffix = 8),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
