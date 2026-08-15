@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

@Composable
internal fun ShareChatPickerCloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
    }
}

@Composable
internal fun ShareChatPickerPreview(
    previewText: String,
    attachmentCount: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier,
    ) {
        Text(
            text = sharePickerPreviewText(previewText, attachmentCount),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (compact) 1 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun sharePickerPreviewText(
    previewText: String,
    attachmentCount: Int,
): String =
    when {
        previewText.isNotEmpty() && attachmentCount > 0 ->
            stringResource(R.string.share_preview_text_and_attachments, previewText, attachmentCount)
        previewText.isNotEmpty() -> previewText
        attachmentCount > 0 ->
            pluralStringResource(
                R.plurals.share_preview_attachments_count,
                attachmentCount,
                attachmentCount,
            )
        else -> ""
    }

@Composable
internal fun ShareChatPickerAccountIdentity(
    appState: WhiteNoiseAppState,
    account: AccountSummaryFfi,
    accountTitle: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (compact) {
            Text(
                text = stringResource(R.string.share_sending_as_value, accountTitle),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(R.string.share_sending_as),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                accountTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                appState.shortNpub(account.accountIdHex),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
