package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.outlined.Casino

/** A draft-only pseudonym action beside a kind-zero display-name field. */
@Composable
@Suppress("FunctionNaming") // Composable component follows the framework naming convention.
internal fun RandomProfileNameButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(Icons.Outlined.Casino, contentDescription = stringResource(R.string.profile_suggest_name))
    }
}
