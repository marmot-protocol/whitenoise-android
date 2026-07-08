package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

@Composable
internal fun CopyableValueRow(
    label: String,
    value: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    appState: WhiteNoiseAppState,
    displayValue: String = value,
) {
    val copyLabel = stringResource(R.string.copy)
    // Identifier rows (npub, group id, public key) keep the value and trailing
    // copy icon on one line. The text may tail-ellipsize when space is tight,
    // but it must never wrap a stray character onto a second row (#799). Callers
    // may provide a pre-shortened displayValue when they need middle ellipsis.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = copyLabel,
                role = Role.Button,
            ) {
                clipboard.setText(AnnotatedString(value))
                appState.presentText(AppText.Resource(R.string.toast_copied_value, listOf(label)))
            }.semantics(mergeDescendants = true) {
                contentDescription = "$label, $value"
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                displayValue,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
