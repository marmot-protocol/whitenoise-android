package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AnimatedAlertDialog

/** Shared confirmation preserves the Markdown destination display and authority emphasis. */
@Composable
@Suppress("FunctionNaming")
internal fun WebLinkConfirmation(
    url: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    containerColor: Color = AlertDialogDefaults.containerColor,
    titleColor: Color = AlertDialogDefaults.titleContentColor,
    textColor: Color = AlertDialogDefaults.textContentColor,
) {
    val parsedLink = remember(url) { parsedOpenableMarkdownLink(url) }
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    AnimatedAlertDialog(
        ownerKey = url,
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        titleContentColor = titleColor,
        textContentColor = textColor,
        title = { Text(stringResource(R.string.link_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                parsedLink?.effectiveAuthority?.let { authority ->
                    Text(authority, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    markdownSafeDisplayText(parsedLink?.destination ?: url),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { close { currentOnConfirm() } }) {
                Text(stringResource(R.string.link_confirm_open))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
