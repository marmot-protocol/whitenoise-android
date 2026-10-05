package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** A partial account projection cannot prove that a selected folder has no eligible destinations. */
@Composable
@Suppress("FunctionNaming")
internal fun DestinationFilterIncomplete(onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm)) {
        Text(stringResource(R.string.destination_filter_incomplete), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
